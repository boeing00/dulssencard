package com.msyim.dulssencard.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.msyim.dulssencard.domain.FreeTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 한 번 결제로 카드 등록 제한을 푸는 Play 인앱 결제.
 *
 * ## 인터넷 권한 없이 동작한다
 *
 * 결제 자체는 **Play 스토어 앱**이 처리하고 이 앱과는 기기 안 IPC(`InAppBillingService.BIND`)로만 주고받는다.
 * 결제 라이브러리(billing 9.1.0)가 의존하는 사용 기록 전송 라이브러리(`transport-backend-cct`)가
 * `INTERNET` · `ACCESS_NETWORK_STATE` 를 끌고 오지만, 매니페스트의 `tools:node="remove"` 가 둘 다 뺀다.
 * 빠지는 건 Google 로 가는 라이브러리 사용 기록뿐이고, 권한이 없으니 OS 가 전송을 막는다.
 * 이 앱이 추가로 선언하는 권한은 `com.android.vending.BILLING`(위험 권한 아님) 하나다.
 *
 * ## 구매 상태
 *
 * 앱을 켤 때마다 Play 에 다시 묻는다(Play 스토어 앱이 기기 안에 캐시해 두므로 오프라인에서도 답한다).
 * 답을 받기 전에 화면이 잠깐 무료로 보이지 않게 마지막 결과를 [prefs] 에 둔다.
 * **DB(settings 표)에 두지 않는 이유**: 설정 표는 백업 파일에 들어가 다른 기기·다른 계정으로 옮겨진다.
 * 구매는 Play 계정에 묶인 것이라 백업을 따라가면 안 된다. 이 prefs 는 `allowBackup=false` 로 기기 밖에 안 나간다.
 *
 * 환불되면 다음 조회에서 구매가 사라져 무료로 돌아간다. 그래도 이미 등록한 카드는 그대로 쓴다([FreeTier]).
 */
class ProUnlock(context: Context) {

    data class State(
        /** 결제를 마쳤다(또는 지난번에 마친 것으로 기억한다). */
        val owned: Boolean = false,
        /** 결제가 진행 중이다(편의점 결제 등 나중에 확정되는 수단). 아직 풀지 않는다. */
        val pending: Boolean = false,
        /** Play Console 의 가격을 기기 통화로 표시한 문자열. 상품 정보를 못 받았으면 null. */
        val price: String? = null,
        /** 지금 결제 창을 띄울 수 있는가. Play 스토어가 없거나 상품을 못 받았으면 false. */
        val available: Boolean = false,
    )

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(State(owned = prefs.getBoolean(KEY_OWNED, false)))
    val state: StateFlow<State> = _state.asStateFlow()

    private var product: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
                handle(purchases)
            } else if (result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                refreshPurchases()
            }
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    /** 연결하고 상품 · 구매 상태를 읽는다. 여러 번 불러도 된다. */
    fun start() {
        if (client.isReady) {
            refreshPurchases()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode != BillingClient.BillingResponseCode.OK) return
                loadProduct()
                refreshPurchases()
            }

            override fun onBillingServiceDisconnected() {
                // enableAutoServiceReconnection 이 다음 호출 때 다시 붙는다.
            }
        })
    }

    /** 결제 창을 띄운다. 띄우지 못하면 false. 결과는 [state] 로 온다. */
    fun launch(activity: Activity): Boolean {
        val details = product ?: return false
        val params = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .apply { details.oneTimePurchaseOfferDetails?.offerToken?.let { setOfferToken(it) } }
            .build()
        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()
        return client.launchBillingFlow(activity, flow).responseCode == BillingClient.BillingResponseCode.OK
    }

    /** "구매 복원". 다른 기기에서 산 경우나 재설치 뒤에 쓴다 — 같은 Play 계정이면 다시 풀린다. */
    fun refreshPurchases() {
        if (!client.isReady) {
            start()
            return
        }
        val params = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) handle(purchases, authoritative = true)
        }
    }

    private fun loadProduct() {
        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(FreeTier.PRO_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(query) { result, details ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            val found = details.productDetailsList.firstOrNull { it.productId == FreeTier.PRO_PRODUCT_ID }
            product = found
            _state.value = _state.value.copy(
                price = found?.oneTimePurchaseOfferDetails?.formattedPrice,
                available = found != null,
            )
        }
    }

    /**
     * 구매 목록 반영. [authoritative] 이면 전체 목록이라 "없음"도 사실이다(환불 · 다른 계정) —
     * 결제 직후 콜백은 방금 산 것만 오므로 없는 것을 '미보유'로 읽으면 안 된다.
     */
    private fun handle(purchases: List<Purchase>, authoritative: Boolean = false) {
        val mine = purchases.filter { FreeTier.PRO_PRODUCT_ID in it.products }
        val purchased = mine.filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = mine.any { it.purchaseState == Purchase.PurchaseState.PENDING }
        // 확인(acknowledge)하지 않으면 Play 가 3일 뒤 자동 환불한다.
        purchased.filterNot { it.isAcknowledged }.forEach { purchase ->
            val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            client.acknowledgePurchase(ack) { }
        }
        val owned = if (authoritative) purchased.isNotEmpty() else (_state.value.owned || purchased.isNotEmpty())
        prefs.edit().putBoolean(KEY_OWNED, owned).apply()
        _state.value = _state.value.copy(owned = owned, pending = pending && !owned)
    }

    fun close() = client.endConnection()

    private companion object {
        const val PREFS = "pro_unlock"
        const val KEY_OWNED = "owned"
    }
}
