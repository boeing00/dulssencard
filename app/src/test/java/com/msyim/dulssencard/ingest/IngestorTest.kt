package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Cycle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 분류·중복 판정 회귀 테스트.
 *
 * 이 파일의 핵심은 다중 소스 중복 제거다. 같은 결제가 문자와 푸시로 두 번 들어와도
 * 합계에 한 번만 잡혀야 한다(PRD §11 수용 기준 5, 베타 품질 게이트 '중복 반영 0건').
 */
class IngestorTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private val shinhan = Card(
        id = "c1",
        nickname = "신한 딥드림",
        trackingTarget = 500_000,
        cycleStartDay = 1,
        matchKeywords = listOf("신한", "신한카드"),
        excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true,
        defaultCountsTowardPurchaseLimit = true,
    )

    private val hyundai = shinhan.copy(
        id = "c3",
        nickname = "현대 M 에디션",
        matchKeywords = listOf("현대카드"),
        defaultCountsTowardPurchaseLimit = false,
    )

    /**
     * 결제 문자. 이 앱은 SMS 권한을 쓰지 않으므로 실제로는 기본 문자 앱이 띄운 알림으로 들어온다
     * (senderKey 가 전화번호가 아니라 문자 앱 패키지명인 이유).
     */
    private fun smsApproval(receivedAt: Long = at(2026, 9, 6, 19, 43)) = RawMessage(
        source = TxSource.SMS,
        senderKey = "com.google.android.apps.messaging",
        title = null,
        body = """
            [Web발신]
            신한카드(1234)승인 홍*동
            84,300원 일시불
            09/06 19:42
            이마트 성수
            누적1,234,567원
        """.trimIndent(),
        receivedAt = receivedAt,
    )

    /** 같은 결제의 카드사 앱 푸시. 가맹점 표기가 다르고 카드 뒷자리가 없다 — 현실이 이렇다. */
    private fun pushApproval(receivedAt: Long = at(2026, 9, 6, 19, 43)) = RawMessage(
        source = TxSource.PUSH,
        senderKey = "com.shcard.smartpay",
        title = "신한카드",
        body = "승인 84,300원 09/06 19:42 이마트성수점",
        receivedAt = receivedAt,
    )

    private suspend fun ingest(
        raw: RawMessage,
        cards: List<Card> = listOf(shinhan),
        existing: List<Txn> = emptyList(),
    ): Ingestor.Outcome = Ingestor.ingest(
        raw = raw,
        cards = cards,
        existingByFingerprint = { fp -> existing.firstOrNull { it.messageFingerprint == fp } },
        cancelOriginFinder = { amount, issuer, before ->
            existing.firstOrNull {
                it.amount == amount &&
                    it.issuerKey == issuer &&
                    (it.occurredAt ?: it.receivedAt) <= before &&
                    it.direction == com.msyim.dulssencard.data.model.TxDirection.APPROVAL
            }
        },
    )

    private fun inserted(outcome: Ingestor.Outcome): Txn {
        assertTrue("expected Insert but was $outcome", outcome is Ingestor.Outcome.Insert)
        return (outcome as Ingestor.Outcome.Insert).txn
    }

    // ------------------------------------------------------------ 자동 반영

    @Test
    fun `명확한 승인은 자동 반영된다`() = runTest {
        val txn = inserted(ingest(smsApproval()))
        assertEquals(TxStatus.AUTO, txn.status)
        assertEquals("c1", txn.cardId)
        assertNull(txn.pendingReason)
        assertEquals(84_300L, txn.signedAmount)
    }

    @Test
    fun `카드 기본값이 새 거래에 적용된다`() = runTest {
        val raw = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                현대카드 승인
                26,900원 일시불
                09/06 19:42
                배달의민족
            """.trimIndent(),
            receivedAt = at(2026, 9, 6, 19, 43),
        )
        val txn = inserted(ingest(raw, cards = listOf(hyundai)))
        assertTrue(txn.countsTowardTarget)
        // 현대 카드는 구매 추적 한도 기본 포함이 꺼져 있다.
        assertTrue(!txn.countsTowardPurchaseLimit)
    }

    // ------------------------------------------------------------ 중복 제거 (핵심)

    @Test
    fun `같은 문자를 두 번 받아도 한 번만 반영된다`() = runTest {
        val first = inserted(ingest(smsApproval()))
        val second = ingest(smsApproval(at(2026, 9, 6, 19, 44)), existing = listOf(first))
        assertTrue(second is Ingestor.Outcome.Duplicate)
    }

    @Test
    fun `같은 결제가 문자와 카드사 앱 푸시로 오면 한 번만 반영된다`() = runTest {
        val fromSms = inserted(ingest(smsApproval()))
        val fromPush = ingest(pushApproval(at(2026, 9, 6, 19, 43)), existing = listOf(fromSms))
        assertTrue(
            "문자와 푸시가 각각 집계되면 이중 집계다. outcome=$fromPush",
            fromPush is Ingestor.Outcome.Duplicate,
        )
    }

    @Test
    fun `푸시가 먼저 오고 문자가 나중에 와도 한 번만 반영된다`() = runTest {
        val fromPush = inserted(ingest(pushApproval()))
        val fromSms = ingest(smsApproval(at(2026, 9, 6, 19, 45)), existing = listOf(fromPush))
        assertTrue(fromSms is Ingestor.Outcome.Duplicate)
    }

    @Test
    fun `같은 결제가 카카오 알림톡으로도 오면 한 번만 반영된다`() = runTest {
        val fromSms = inserted(ingest(smsApproval()))
        val kakao = RawMessage(
            source = TxSource.KAKAO,
            senderKey = IssuerRegistry.KAKAO_PACKAGE,
            title = "신한카드",
            body = "승인 84,300원 09/06 19:42 이마트",
            receivedAt = at(2026, 9, 6, 19, 44),
        )
        assertTrue(ingest(kakao, existing = listOf(fromSms)) is Ingestor.Outcome.Duplicate)
    }

    @Test
    fun `같은 분 같은 금액이라도 가맹점이 다르면 별개 결제로 남긴다`() = runTest {
        val first = inserted(ingest(smsApproval()))
        val otherShop = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                신한카드(1234)승인 홍*동
                84,300원 일시불
                09/06 19:42
                롯데백화점 잠실
            """.trimIndent(),
            receivedAt = at(2026, 9, 6, 19, 43),
        )
        val second = inserted(ingest(otherShop, existing = listOf(first)))
        assertNotEquals(first.messageFingerprint, second.messageFingerprint)
        // 확신할 수 없으므로 합계에 자동으로 넣지 않고 사용자에게 넘긴다.
        assertEquals(TxStatus.PENDING, second.status)
        assertEquals(PendingReason.DUPLICATE_SUSPECTED, second.pendingReason)
    }

    @Test
    fun `지문이 같고 수신 시각이 멀면 중복 의심으로 남긴다`() = runTest {
        val first = inserted(ingest(smsApproval()))
        val muchLater = ingest(smsApproval(at(2026, 9, 6, 23, 50)), existing = listOf(first))
        val txn = inserted(muchLater)
        assertEquals(TxStatus.PENDING, txn.status)
        assertEquals(PendingReason.DUPLICATE_SUSPECTED, txn.pendingReason)
    }

    @Test
    fun `같은 금액을 다른 시각에 연속 결제하면 둘 다 반영된다`() = runTest {
        val first = inserted(ingest(smsApproval()))
        val laterPurchase = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                신한카드(1234)승인 홍*동
                84,300원 일시불
                09/06 20:15
                이마트 성수
            """.trimIndent(),
            receivedAt = at(2026, 9, 6, 20, 16),
        )
        val second = inserted(ingest(laterPurchase, existing = listOf(first)))
        assertEquals(TxStatus.AUTO, second.status)
        assertNotEquals(first.messageFingerprint, second.messageFingerprint)
    }

    // ------------------------------------------------------------ 확인 필요 분기

    @Test
    fun `키워드가 두 카드와 겹치면 자동 반영하지 않는다`() = runTest {
        val overlapping = shinhan.copy(id = "c2", nickname = "신한 체크", matchKeywords = listOf("신한카드"))
        val txn = inserted(ingest(smsApproval(), cards = listOf(shinhan, overlapping)))
        assertEquals(TxStatus.PENDING, txn.status)
        assertEquals(PendingReason.MULTIPLE_CARD_MATCH, txn.pendingReason)
        assertNull(txn.cardId)
    }

    @Test
    fun `어느 카드와도 안 맞으면 미분류 확인 필요다`() = runTest {
        val txn = inserted(ingest(smsApproval(), cards = listOf(hyundai)))
        assertEquals(TxStatus.PENDING, txn.status)
        assertEquals(PendingReason.NO_CARD_MATCH, txn.pendingReason)
    }

    @Test
    fun `제외 키워드에 걸리면 제외됨 상태로 들어온다`() = runTest {
        val withExclude = shinhan.copy(excludeKeywords = listOf("이마트"))
        val txn = inserted(ingest(smsApproval(), cards = listOf(withExclude)))
        assertEquals(TxStatus.EXCLUDED, txn.status)
        assertEquals(PendingReason.EXCLUDE_KEYWORD, txn.pendingReason)
    }

    @Test
    fun `해외 승인은 자동 반영하지 않는다`() = runTest {
        val raw = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                신한카드(1234)해외승인
                USD 12.00
                09/01 03:20
                AMAZON MKTPLACE
            """.trimIndent(),
            receivedAt = at(2026, 9, 1, 3, 21),
        )
        val txn = inserted(ingest(raw))
        assertEquals(TxStatus.PENDING, txn.status)
        assertEquals(PendingReason.FOREIGN_CURRENCY, txn.pendingReason)
    }

    @Test
    fun `할부는 자동 반영하지 않는다`() = runTest {
        val raw = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                신한카드(1234)승인
                1,200,000원 12개월할부
                09/06 19:42
                하이마트 강남
            """.trimIndent(),
            receivedAt = at(2026, 9, 6, 19, 43),
        )
        val txn = inserted(ingest(raw))
        assertEquals(PendingReason.INSTALLMENT, txn.pendingReason)
    }

    // ------------------------------------------------------------ 취소

    @Test
    fun `원 승인 거래를 찾으면 취소가 자동 반영되고 차감된다`() = runTest {
        val approval = inserted(ingest(smsApproval()))
        val cancel = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                신한카드(1234)취소 홍*동
                84,300원
                09/06 20:10
                이마트 성수
            """.trimIndent(),
            receivedAt = at(2026, 9, 6, 20, 11),
        )
        val txn = inserted(ingest(cancel, existing = listOf(approval)))
        assertEquals(TxStatus.AUTO, txn.status)
        assertEquals(approval.id, txn.relatedTransactionId)
        assertEquals(-84_300L, txn.signedAmount)
    }

    @Test
    fun `원 승인 거래가 없는 취소는 확인 필요다`() = runTest {
        val cancel = RawMessage(
            source = TxSource.SMS,
            senderKey = "com.google.android.apps.messaging",
            title = null,
            body = """
                [Web발신]
                신한카드(1234)취소 홍*동
                50,000원
                09/06 20:10
                모르는가게
            """.trimIndent(),
            receivedAt = at(2026, 9, 6, 20, 11),
        )
        val txn = inserted(ingest(cancel))
        assertEquals(TxStatus.PENDING, txn.status)
        assertEquals(PendingReason.UNLINKED_CANCEL, txn.pendingReason)
    }

    // ------------------------------------------------------------ 결제가 아닌 메시지

    @Test
    fun `결제 통지가 아니면 아무것도 남기지 않는다`() = runTest {
        val raw = RawMessage(
            source = TxSource.KAKAO,
            senderKey = IssuerRegistry.KAKAO_PACKAGE,
            title = "친구",
            body = "오늘 저녁 뭐 먹을까?",
            receivedAt = at(2026, 9, 6, 19, 43),
        )
        assertEquals(Ingestor.Outcome.NotAPayment, ingest(raw))
    }

    // ------------------------------------------------------------ 취소가 물려받는 것

    /** 같은 결제의 취소 문자. 금액과 가맹점을 바꿔 가며 쓴다. */
    private fun cancelSms(
        amount: String = "84,300",
        merchant: String = "이마트 성수",
        receivedAt: Long = at(2026, 9, 6, 20, 11),
    ) = RawMessage(
        source = TxSource.SMS,
        senderKey = "com.google.android.apps.messaging",
        title = null,
        body = """
            [Web발신]
            신한카드(1234)취소 홍*동
            ${amount}원
            09/06 20:10
            $merchant
        """.trimIndent(),
        receivedAt = receivedAt,
    )

    @Test
    fun `취소는 원 승인 거래의 포함 설정을 물려받는다`() = runTest {
        // 사용자가 원 승인을 '목표 추적 제외'로 꺼 뒀다. 그 취소가 카드 기본값(포함)으로
        // 들어오면 **더한 적 없는 돈을 빼서** 카드 누적이 실제보다 작아진다.
        val approval = inserted(ingest(smsApproval())).copy(
            countsTowardTarget = false,
            countsTowardPurchaseLimit = false,
        )

        val txn = inserted(ingest(cancelSms(), existing = listOf(approval)))

        assertEquals(approval.id, txn.relatedTransactionId)
        assertEquals(false, txn.countsTowardTarget)
        assertEquals(false, txn.countsTowardPurchaseLimit)
    }

    @Test
    fun `원 승인이 포함이면 취소도 포함으로 들어온다`() = runTest {
        val approval = inserted(ingest(smsApproval()))

        val txn = inserted(ingest(cancelSms(), existing = listOf(approval)))

        assertEquals(true, txn.countsTowardTarget)
        assertEquals(true, txn.countsTowardPurchaseLimit)
    }

    @Test
    fun `취소 문구가 어느 카드와도 안 맞으면 원 승인 거래의 카드로 붙인다`() = runTest {
        // 카드를 못 찾으면 취소가 확인 필요로 쌓이고, 그동안 누적은 부풀어 있는 채로 남는다.
        // 원 승인 거래를 이미 찾았다면 카드는 그 거래가 알고 있다.
        val approval = inserted(ingest(smsApproval()))
        assertEquals(shinhan.id, approval.cardId)

        val txn = inserted(ingest(cancelSms(), cards = listOf(hyundai), existing = listOf(approval)))

        assertEquals(shinhan.id, txn.cardId)
        assertEquals(TxStatus.AUTO, txn.status)
        assertNull(txn.pendingReason)
    }

    @Test
    fun `카드가 맞은 취소는 그 카드를 그대로 쓴다`() = runTest {
        val approval = inserted(ingest(smsApproval()))

        val txn = inserted(ingest(cancelSms(), existing = listOf(approval)))

        assertEquals(shinhan.id, txn.cardId)
    }

    // ------------------------------------------------------------ 캡처 재반입

    /** 캡처 이미지 한 덩어리. 수신 시각은 '결제 시각'이 아니라 '불러온 시각'이다. */
    private fun imageBlock(receivedAt: Long) = RawMessage(
        source = TxSource.IMAGE,
        senderKey = "image-ocr",
        title = null,
        body = """
            신한카드(1234)승인 홍*동
            84,300원 일시불
            09/06 19:42
            이마트 성수
        """.trimIndent(),
        receivedAt = receivedAt,
    )

    @Test
    fun `같은 캡처를 하루 뒤에 다시 불러와도 중복으로 판정한다`() = runTest {
        // 이미지의 수신 시각은 결제 시각과 무관하다. 시각 창(15분)을 그대로 적용하면
        // 같은 결제가 매번 새 행으로 들어오고, 사용자가 확정하는 순간 이중 집계가 된다.
        val first = inserted(ingest(imageBlock(at(2026, 9, 6, 21, 0))))

        val again = ingest(imageBlock(at(2026, 9, 7, 21, 0)), existing = listOf(first))

        assertTrue("expected Duplicate but was $again", again is Ingestor.Outcome.Duplicate)
    }

    @Test
    fun `캡처라도 가맹점이 다르면 별개 결제로 남긴다`() = runTest {
        val first = inserted(ingest(imageBlock(at(2026, 9, 6, 21, 0))))
        val otherShop = imageBlock(at(2026, 9, 6, 21, 0)).copy(
            body = imageBlock(0L).body.replace("이마트 성수", "롯데백화점 잠실"),
        )

        val second = ingest(otherShop, existing = listOf(first))

        assertTrue("expected Insert but was $second", second is Ingestor.Outcome.Insert)
    }
}
