package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import java.util.UUID
import kotlin.math.abs

/**
 * 수집한 메시지 한 건을 거래로 만든다. 순수 함수로 두어 기기 없이 테스트할 수 있게 했다.
 *
 * 저장은 [com.msyim.dulssencard.data.DulSsenRepository] 가 한다. 여기서는
 * "이 메시지를 어떻게 처리할 것인가"만 정한다.
 */
object Ingestor {

    /** 같은 결제의 두 경로(문자 + 푸시)가 도착하는 시간 차. 이 안이면 확신하고 합친다. */
    const val CROSS_SOURCE_WINDOW_MILLIS = 15L * 60 * 1000

    sealed interface Outcome {
        /** 결제 통지가 아니다. 아무것도 남기지 않는다. */
        data object NotAPayment : Outcome

        /** 이미 있는 거래와 같은 결제다. 버린다. */
        data class Duplicate(val existingId: String) : Outcome

        /** 새 거래. */
        data class Insert(val txn: Txn) : Outcome
    }

    /**
     * @param existingByFingerprint 같은 지문을 가진 기존 거래(있으면).
     * @param cancelOriginFinder    취소 거래의 원 승인 거래를 찾는 함수.
     */
    suspend fun ingest(
        raw: RawMessage,
        cards: List<Card>,
        existingByFingerprint: suspend (String) -> Txn?,
        cancelOriginFinder: suspend (amount: Long, issuerKey: String?, before: Long) -> Txn?,
    ): Outcome {
        val parsed = PaymentParser.parse(raw) ?: return Outcome.NotAPayment

        val baseFingerprint = Fingerprint.of(
            issuerKey = parsed.issuerKey,
            occurredAt = parsed.occurredAt,
            amount = parsed.amount,
            direction = parsed.direction,
        )

        val existing = existingByFingerprint(baseFingerprint)
        var fingerprint = baseFingerprint
        var duplicateSuspected = false

        if (existing != null) {
            // 지문이 같아도 가맹점이 명백히 다르면 별개 결제다.
            // (같은 카드사에서 같은 분에 같은 금액을 다른 가게에서 긁은 경우)
            val differentShop = Fingerprint.merchantsConflict(existing.merchant, parsed.merchant)
            val closeInTime = abs(existing.receivedAt - raw.receivedAt) <= CROSS_SOURCE_WINDOW_MILLIS

            if (!differentShop && closeInTime) {
                // 같은 결제가 다른 경로로(문자 + 푸시) 또는 같은 경로로 재전달되어 또 온 것이다.
                // 이게 다중 소스 수집의 정상 동작이며, 여기서 걸러야 이중 집계가 안 난다.
                return Outcome.Duplicate(existing.id)
            }
            // 같은 결제인지 별개 결제인지 앱이 확신할 수 없다.
            // 조용히 버리면 결제를 잃고, 그냥 넣으면 이중 집계가 된다. 사용자에게 넘긴다.
            duplicateSuspected = true
            fingerprint = Fingerprint.disambiguate(baseFingerprint, raw.receivedAt)
        }

        val matches = narrowBySuffix(
            cards.filter { card ->
                card.active && matches(card, parsed.matchText, parsed.issuerKey)
            },
            parsed.cardSuffix,
        )
        val excluded = matches.singleOrNull()?.let { card ->
            card.excludeKeywords.any { keyword ->
                keyword.isNotBlank() && parsed.matchText.contains(keyword, ignoreCase = true)
            }
        } ?: false

        val cardId = matches.singleOrNull()?.id
        val defaultsCard = matches.singleOrNull()

        var relatedTransactionId: String? = null
        if (parsed.direction == TxDirection.CANCEL) {
            val before = parsed.occurredAt ?: raw.receivedAt
            relatedTransactionId =
                cancelOriginFinder(parsed.amount, parsed.issuerKey, before)?.id
        }

        val reason = firstBlockingReason(
            parsed = parsed,
            matchCount = matches.size,
            excluded = excluded,
            duplicateSuspected = duplicateSuspected,
            cancelUnlinked = parsed.direction == TxDirection.CANCEL && relatedTransactionId == null,
        )

        val status = when {
            reason == PendingReason.EXCLUDE_KEYWORD -> TxStatus.EXCLUDED
            reason != null -> TxStatus.PENDING
            // 캡처 이미지는 무엇이든 담을 수 있다(다른 앱 화면, 문서, 은행 거래).
            // 실기기에서 무관한 문서의 숫자가 1억원짜리 거래로 들어온 적이 있어,
            // 이미지 출처는 사람이 눈으로 확인하기 전까지 합계에 넣지 않는다.
            raw.source == com.msyim.dulssencard.data.model.TxSource.IMAGE -> TxStatus.PENDING
            else -> TxStatus.AUTO
        }

        return Outcome.Insert(
            Txn(
                id = UUID.randomUUID().toString(),
                cardId = cardId,
                occurredAt = parsed.occurredAt,
                occurredAtEstimated = parsed.occurredAtEstimated,
                receivedAt = raw.receivedAt,
                amount = parsed.amount,
                currency = parsed.currency.ifEmpty { "KRW" },
                foreignAmountMinor = parsed.foreignAmountMinor,
                updatedAt = raw.receivedAt,
                direction = parsed.direction,
                status = status,
                source = raw.source,
                merchant = parsed.merchant,
                countsTowardTarget = defaultsCard?.defaultCountsTowardTarget ?: true,
                countsTowardPurchaseLimit = defaultsCard?.defaultCountsTowardPurchaseLimit ?: true,
                parserVersion = PaymentParser.VERSION,
                confidence = parsed.confidence,
                messageFingerprint = fingerprint,
                relatedTransactionId = relatedTransactionId,
                pendingReason = reason,
                issuerKey = parsed.issuerKey,
                installment = parsed.installment,
                overseas = parsed.overseas,
            ),
        )
    }

    /**
     * 자동 반영을 막는 첫 번째 사유. 없으면 null 이고 그때만 자동 반영한다.
     *
     * 순서가 곧 사용자에게 보여줄 우선순위다. 가장 구체적인 이유를 앞에 둔다.
     */
    private fun firstBlockingReason(
        parsed: ParsedPayment,
        matchCount: Int,
        excluded: Boolean,
        duplicateSuspected: Boolean,
        cancelUnlinked: Boolean,
    ): PendingReason? = when {
        excluded -> PendingReason.EXCLUDE_KEYWORD
        duplicateSuspected -> PendingReason.DUPLICATE_SUSPECTED
        matchCount > 1 -> PendingReason.MULTIPLE_CARD_MATCH
        matchCount == 0 -> PendingReason.NO_CARD_MATCH
        // 해외·할부를 금액/시각 검사보다 먼저 본다. 해외 승인은 원화 금액이 없는 게 정상이라
        // "금액 0원"이라고 알리면 사용자가 원인을 오해한다.
        parsed.overseas -> PendingReason.FOREIGN_CURRENCY
        parsed.installment -> PendingReason.INSTALLMENT
        parsed.occurredAt == null -> PendingReason.UNKNOWN_TIME
        parsed.amount <= 0L -> PendingReason.ZERO_AMOUNT
        parsed.issuerKey == null -> PendingReason.PARSE_FAILED
        !parsed.hasRequiredFields -> PendingReason.PARSE_FAILED
        parsed.confidence < PaymentParser.AUTO_CONFIDENCE_THRESHOLD -> PendingReason.LOW_CONFIDENCE
        cancelUnlinked -> PendingReason.UNLINKED_CANCEL
        else -> null
    }

    /**
     * 카드의 인식 키워드 중 하나라도 걸리면 그 카드의 거래 후보다.
     *
     * 부분 문자열만으로는 부족하다. 카드사가 통지에서 자기 이름을 다르게 쓰기 때문이다 —
     * 사용자가 `우리카드` 로 등록해도 우리카드 앱 푸시는 자신을 **`우리WON카드`** 라고 부르고,
     * `"우리WON카드".contains("우리카드")` 는 **false** 다(우리 + WON + 카드로 갈라진다).
     * 그래서 우리카드만 미분류로 떨어지고 현대카드는 멀쩡한 증상이 나왔다.
     *
     * 그래서 키워드가 안 걸리면 **카드사로 한 번 더 본다.** 사용자가 카드사 이름으로
     * 등록해 두었고 통지도 같은 카드사면, 표기가 달라도 같은 카드사다.
     */
    private fun matches(card: Card, text: String, issuerKey: String?): Boolean {
        val byKeyword = card.matchKeywords.any { keyword ->
            keyword.isNotBlank() && text.contains(keyword.trim(), ignoreCase = true)
        }
        return byKeyword || (issuerKey != null && issuerOf(card) == issuerKey)
    }

    /**
     * 카드의 인식 키워드가 가리키는 카드사. 자유 문자열만 넣어 두었으면 null 이다.
     *
     * 키워드를 이어 붙여 [IssuerRegistry.detect] 에 넘긴다 — 길이·위치 우선 규칙을 그대로 쓰기
     * 위해서다. 키워드 하나씩 보면 `우리` 와 `BC` 가 동점일 때 판정이 갈린다.
     */
    private fun issuerOf(card: Card): String? =
        IssuerRegistry.detect(card.matchKeywords.joinToString(" "))?.key

    /**
     * 같은 카드사 카드를 여러 장 등록했을 때 뒷 4자리로 좁힌다.
     *
     * 좁혀지지 않으면 후보를 그대로 돌려준다 — 찍어서 맞히면 합계가 조용히 틀어지므로,
     * 그때는 `확인 필요`로 사용자에게 넘기는 편이 낫다.
     */
    private fun narrowBySuffix(candidates: List<Card>, suffix: String?): List<Card> {
        if (candidates.size <= 1 || suffix.isNullOrBlank()) return candidates
        val bySuffix = candidates.filter { card ->
            card.matchKeywords.any { it.trim() == suffix }
        }
        return if (bySuffix.size == 1) bySuffix else candidates
    }
}
