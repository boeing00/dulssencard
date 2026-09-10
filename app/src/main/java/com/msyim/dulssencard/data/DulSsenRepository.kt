package com.msyim.dulssencard.data

import android.content.Context
import com.msyim.dulssencard.data.crypto.DbPassphrase
import com.msyim.dulssencard.data.db.AppDatabase
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.Setting
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.ingest.Ingestor
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ingest.RawMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * 로컬 데이터 하나뿐인 저장소. 네트워크 경로는 존재하지 않는다.
 *
 * SMS 리시버·알림 리스너·화면이 모두 이 클래스를 통해 같은 암호화 DB 를 본다.
 */
class DulSsenRepository private constructor(private val context: Context) {

    private val db get() = AppDatabase.get(context)

    val cards: Flow<List<Card>> get() = db.cardDao().observeAll()
    val txns: Flow<List<Txn>> get() = db.txnDao().observeAll()
    val sourceApps: Flow<List<SourceApp>> get() = db.sourceAppDao().observeAll()
    val adjustments: Flow<List<Adjustment>> get() = db.adjustmentDao().observeAll()

    val settings: Flow<Map<String, String>>
        get() = db.settingDao().observeAll().map { rows -> rows.associate { it.key to it.value } }

    // ---------------------------------------------------------------- 수집

    /**
     * 메시지 한 건을 집계에 반영한다.
     *
     * 결제 통지가 아니거나 이미 있는 결제면 아무것도 쓰지 않는다.
     * 원문([RawMessage])은 이 함수 밖으로 나가지 않고, 파싱 결과만 저장된다.
     */
    suspend fun ingest(raw: RawMessage): Ingestor.Outcome {
        val cards = db.cardDao().all()
        val outcome = Ingestor.ingest(
            raw = raw,
            cards = cards,
            existingByFingerprint = { fingerprint -> db.txnDao().byFingerprint(fingerprint) },
            cancelOriginFinder = { amount, issuerKey, before ->
                db.txnDao().findCancelOrigin(
                    amount = amount,
                    issuerKey = issuerKey,
                    before = before,
                    notBefore = before - CANCEL_LOOKBACK_MILLIS,
                )
            },
        )
        if (outcome is Ingestor.Outcome.Insert) {
            // 지문에 유니크 인덱스가 걸려 있으므로, 두 소스가 동시에 들어와도
            // 경합에서 진 쪽은 조용히 무시된다(IGNORE). 이중 집계의 마지막 방어선이다.
            db.txnDao().insertIgnoringDuplicates(outcome.txn)
        }
        return outcome
    }

    /**
     * 카드사 앱 **이용내역 목록 화면**에서 읽은 행들을 거래로 넣는다.
     *
     * 통지와 달리 카드사·승인문구가 각 행에 없으므로, 화면 전체에서 판정한 카드사를
     * 모든 행에 공통으로 적용한다. 화면에서 카드사를 못 찾으면 미분류로 들어간다.
     *
     * 목록 화면은 과거 내역을 몰아 넣는 용도라 **자동 반영하지 않고 전부 확인 필요**로 둔다.
     * 사용자가 눈으로 훑고 확정하게 하려는 것이다 — 화면 부스러기가 섞일 여지가 통지보다 크다.
     */
    suspend fun importLedgerRows(
        rows: List<com.msyim.dulssencard.ingest.LedgerScreenParser.Row>,
        issuerKey: String?,
        receivedAt: Long,
        matchText: String,
    ): ImportResult {
        // 통지 경로와 똑같이 **사용자가 등록한 카드의 인식 키워드**로 맞춰 본다.
        // 이걸 빠뜨려서 목록으로 불러온 거래가 전부 '미분류'로 쌓였다.
        // 목록 화면에는 카드사 이름이 제목에 한 번만 나오므로 화면 전체 텍스트로 맞춘다.
        val allCards = db.cardDao().all()
        val matched = allCards.filter { card ->
            card.active && card.matchKeywords.any { keyword ->
                keyword.isNotBlank() && matchText.contains(keyword.trim(), ignoreCase = true)
            }
        }
        val card = matched.singleOrNull()
        val reason = when {
            matched.size > 1 -> com.msyim.dulssencard.data.model.PendingReason.MULTIPLE_CARD_MATCH
            card == null -> com.msyim.dulssencard.data.model.PendingReason.NO_CARD_MATCH
            else -> com.msyim.dulssencard.data.model.PendingReason.IMAGE_IMPORT
        }

        var inserted = 0
        var duplicates = 0
        rows.forEach { row ->
            val fingerprint = com.msyim.dulssencard.ingest.Fingerprint.of(
                issuerKey = issuerKey,
                occurredAt = row.occurredAt,
                amount = row.amount,
                direction = com.msyim.dulssencard.data.model.TxDirection.APPROVAL,
            )
            if (db.txnDao().byFingerprint(fingerprint) != null) {
                duplicates++
                return@forEach
            }

            db.txnDao().insertIgnoringDuplicates(
                Txn(
                    id = UUID.randomUUID().toString(),
                    cardId = card?.id,
                    occurredAt = row.occurredAt,
                    occurredAtEstimated = row.occurredAtEstimated,
                    receivedAt = receivedAt,
                    amount = row.amount,
                    currency = "KRW",
                    foreignAmount = null,
                    direction = com.msyim.dulssencard.data.model.TxDirection.APPROVAL,
                    status = com.msyim.dulssencard.data.model.TxStatus.PENDING,
                    source = com.msyim.dulssencard.data.model.TxSource.IMAGE,
                    merchant = row.merchant,
                    countsTowardTarget = card?.defaultCountsTowardTarget ?: true,
                    countsTowardPurchaseLimit = card?.defaultCountsTowardPurchaseLimit ?: true,
                    parserVersion = com.msyim.dulssencard.ingest.PaymentParser.VERSION + " · LEDGER",
                    confidence = 0.5,
                    messageFingerprint = fingerprint,
                    relatedTransactionId = null,
                    pendingReason = reason,
                    issuerKey = issuerKey,
                    installment = false,
                    overseas = false,
                ),
            )
            inserted++
        }
        return ImportResult(inserted = inserted, duplicates = duplicates)
    }

    /**
     * 불러오기 결과.
     *
     * 중복 건수를 따로 돌려주는 이유: 화면에 6건이 보이는데 5건만 들어오면
     * 사용자는 파서가 한 건을 놓친 것으로 읽는다. "이미 있어서 건너뛴 것"과
     * "못 읽은 것"은 전혀 다른 상황이라 반드시 구분해서 알려야 한다.
     */
    data class ImportResult(val inserted: Int, val duplicates: Int)

    // ---------------------------------------------------------------- 카드

    suspend fun upsertCard(card: Card) = db.cardDao().upsert(card)

    /**
     * 카드를 지운다. 그 카드에 붙어 있던 거래는 지우지 않고 미분류·확인 필요로 되돌린다.
     * 카드를 지웠다고 이미 쓴 돈이 사라지면 안 된다.
     */
    suspend fun deleteCard(card: Card) {
        db.txnDao().detachFromCard(card.id)
        db.cardDao().delete(card)
    }

    suspend fun card(id: String): Card? = db.cardDao().byId(id)

    // ---------------------------------------------------------------- 거래 보정

    suspend fun txn(id: String): Txn? = db.txnDao().byId(id)

    /**
     * 거래를 바꾸고 변경 기록을 남긴다.
     * PRD §6.3: 변경 시 원값·변경값·시간·사유를 기록한다.
     */
    suspend fun applyChange(
        before: Txn,
        after: Txn,
        changeType: ChangeType,
        reason: String? = null,
    ) {
        db.txnDao().update(after)
        db.adjustmentDao().insert(
            Adjustment(
                id = UUID.randomUUID().toString(),
                transactionId = before.id,
                changeType = changeType,
                beforeState = describe(before),
                afterState = describe(after),
                reason = reason,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    fun adjustmentsFor(txnId: String): Flow<List<Adjustment>> =
        db.adjustmentDao().observeForTxn(txnId)

    /**
     * 거래 상태 요약. 변경 기록에 남는 값이라 **금액과 상태만** 담고 가맹점·원문은 넣지 않는다.
     * PRD §10: 진단 로그에 거래 데이터를 기록하지 않는다.
     */
    private fun describe(txn: Txn): String = buildString {
        append("card=").append(txn.cardId ?: "-")
        append(" status=").append(txn.status.name)
        append(" target=").append(if (txn.countsTowardTarget) "1" else "0")
        append(" limit=").append(if (txn.countsTowardPurchaseLimit) "1" else "0")
    }

    // ---------------------------------------------------------------- 되돌리기

    /** 되돌리기용 스냅샷. 마지막 1건만 되돌릴 수 있으므로 통째로 떠 둔다. */
    data class Snapshot(val cards: List<Card>, val txns: List<Txn>, val settings: Map<String, String>)

    suspend fun snapshot(): Snapshot = Snapshot(
        cards = db.cardDao().all(),
        txns = db.txnDao().all(),
        settings = db.settingDao().let { dao ->
            Settings.ALL_KEYS.mapNotNull { key -> dao.get(key)?.let { key to it } }.toMap()
        },
    )

    suspend fun restore(snapshot: Snapshot) {
        db.cardDao().clear()
        db.txnDao().clear()
        snapshot.cards.forEach { db.cardDao().upsert(it) }
        snapshot.txns.forEach { db.txnDao().upsert(it) }
        snapshot.settings.forEach { (key, value) -> db.settingDao().put(Setting(key, value)) }
    }

    // ---------------------------------------------------------------- 설정

    suspend fun putSetting(key: String, value: String) = db.settingDao().put(Setting(key, value))

    suspend fun getSetting(key: String): String? = db.settingDao().get(key)

    // ---------------------------------------------------------------- 알림 소스

    /**
     * 알림을 띄운 적 있는 패키지를 기록한다. **알림 내용은 넘어오지 않는다** — 패키지명과 라벨뿐이다.
     * 사용자가 설정에서 켜기 전까지 이 앱의 알림 내용은 읽지도 저장하지도 않는다.
     */
    suspend fun noteSourceAppSeen(packageName: String, label: String) {
        val issuer = IssuerRegistry.byPackage(packageName)
        db.sourceAppDao().touch(packageName, label, issuer?.key, System.currentTimeMillis())
    }

    /**
     * 결제 통지를 띄우는 앱을 목록에 미리 채운다.
     *
     * 이게 없으면 알림 접근을 켜도 목록이 비어 있어(그 앱이 알림을 한 번 띄우기 전까지)
     * 사용자가 켤 대상이 없다. 알림 접근이 유일한 수집 경로이므로, 목록이 비면 앱이 통째로 죽는다.
     *
     * 기본값
     *  - **카드사 앱: 켬.** 알림이 결제 통지가 전부라 사용자가 기대하는 동작이다.
     *  - **기본 문자 앱: 켬.** 결제 문자를 읽는 유일한 통로다. SMS 권한을 선언하지 않는 대신
     *    여기서 읽으므로, 꺼 두면 결제 문자가 통째로 누락된다.
     *  - **카카오톡·기본이 아닌 문자 앱: 끔.** 카카오톡은 일상 대화가 오가는 메신저다.
     *    알림톡 결제 통지를 읽으려면 대화 알림도 같은 통로로 지나가므로 명시적으로 켜게 둔다.
     *
     * 어느 쪽이든 결제 통지가 아닌 알림은 파서가 즉시 버리고 아무것도 저장하지 않는다.
     */
    suspend fun seedKnownSourceApps(defaultSmsPackage: String?, label: (String) -> String?) {
        val candidates = buildList {
            addAll(IssuerRegistry.SUGGESTED_PACKAGES)
            addAll(IssuerRegistry.MESSAGING_PACKAGES)
            defaultSmsPackage?.let { add(it) }
        }.distinct()

        candidates.forEach { pkg ->
            if (db.sourceAppDao().byPackage(pkg) != null) return@forEach
            val name = label(pkg) ?: return@forEach
            val issuer = IssuerRegistry.byPackage(pkg)
            db.sourceAppDao().upsert(
                SourceApp(
                    packageName = pkg,
                    label = name,
                    issuerKey = issuer?.key,
                    enabled = issuer != null || pkg == defaultSmsPackage,
                    lastSeenAt = 0L,
                ),
            )
        }
    }

    /** 현재 등록된 알림 소스 전체. 진단용. */
    suspend fun sourceAppsSnapshot(): List<SourceApp> = db.sourceAppDao().all()

    suspend fun setSourceAppEnabled(packageName: String, label: String, enabled: Boolean) {
        val existing = db.sourceAppDao().byPackage(packageName)
        val issuer = IssuerRegistry.byPackage(packageName)
        db.sourceAppDao().upsert(
            SourceApp(
                packageName = packageName,
                label = existing?.label ?: label,
                issuerKey = issuer?.key,
                enabled = enabled,
                lastSeenAt = existing?.lastSeenAt ?: System.currentTimeMillis(),
            ),
        )
    }

    suspend fun isSourceAppEnabled(packageName: String): Boolean =
        db.sourceAppDao().byPackage(packageName)?.enabled == true

    // ---------------------------------------------------------------- 전체 삭제

    /**
     * 로컬 데이터 전체 삭제. 테이블을 비운 뒤 DB 파일과 암호를 함께 버린다.
     * 암호를 지우는 이유: 파일 시스템에 남은 조각이 나중에라도 복호화되지 않게 하려는 것이다.
     */
    suspend fun wipeAll() {
        db.txnDao().clear()
        db.cardDao().clear()
        db.adjustmentDao().clear()
        db.sourceAppDao().clear()
        db.cycleSnapshotDao().clear()
        db.settingDao().clear()
    }

    /** 앱을 완전히 초기화한다. 호출 뒤 프로세스를 종료해야 안전하다. */
    fun destroyDatabaseFile() {
        AppDatabase.closeAndForget()
        context.deleteDatabase(AppDatabase.DB_NAME)
        DbPassphrase.destroy(context)
    }

    // ---------------------------------------------------------------- 내보내기·불러오기

    /**
     * 전체 데이터를 JSON으로 직렬화한다.
     * 암호화는 호출자(UI)에서 처리한다.
     */
    suspend fun exportData(): BackupData = BackupData(
        version = 1,
        exportedAt = System.currentTimeMillis(),
        cards = db.cardDao().all(),
        txns = db.txnDao().all(),
        adjustments = db.adjustmentDao().all(),
        settings = db.settingDao().let { dao ->
            Settings.ALL_KEYS.mapNotNull { key -> dao.get(key)?.let { Setting(key, it) } }
        },
    )

    /**
     * 백업 데이터를 복원한다. 기존 데이터와 병합하며, 중복은 무시한다.
     */
    suspend fun importData(data: BackupData) {
        data.cards.forEach { db.cardDao().upsert(it) }
        data.txns.forEach { db.txnDao().upsert(it) }
        data.adjustments.forEach { db.adjustmentDao().upsert(it) }
        data.settings.forEach { db.settingDao().put(it) }
    }

    @Serializable
    data class BackupData(
        val version: Int,
        val exportedAt: Long,
        val cards: List<Card>,
        val txns: List<Txn>,
        val adjustments: List<Adjustment>,
        val settings: List<Setting>,
    )

    companion object {
        /**
         * 취소를 원 승인 거래에 연결할 때 거슬러 올라가는 최대 기간.
         * 카드사 취소는 결제 후 몇 달 안에 일어나므로 90일이면 충분하고,
         * 이보다 넓히면 "같은 금액 다른 결제"에 잘못 물릴 확률만 커진다.
         */
        const val CANCEL_LOOKBACK_MILLIS = 90L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: DulSsenRepository? = null

        fun get(context: Context): DulSsenRepository =
            instance ?: synchronized(this) {
                instance ?: DulSsenRepository(context.applicationContext).also { instance = it }
            }
    }
}

/** 설정 키. 값은 전부 문자열로 저장하고 읽을 때 변환한다. */
object Settings {
    const val LIMIT_AMOUNT = "limit_amount"
    const val LIMIT_CYCLE_START_DAY = "limit_cycle_start_day"
    const val AUTO_COLLECT_ENABLED = "auto_collect_enabled"
    const val ONBOARDING_DONE = "onboarding_done"
    const val HOME_SORT_BY_NAME = "home_sort_by_name"

    val ALL_KEYS = listOf(
        LIMIT_AMOUNT,
        LIMIT_CYCLE_START_DAY,
        AUTO_COLLECT_ENABLED,
        ONBOARDING_DONE,
        HOME_SORT_BY_NAME,
    )

    /** 홈 한도 카드의 제안값. PRD §7: 제안값일 뿐이며 사용자가 언제든 바꿀 수 있다. */
    const val DEFAULT_LIMIT_AMOUNT = 1_000_000L
    const val DEFAULT_LIMIT_CYCLE_START_DAY = 1
}
