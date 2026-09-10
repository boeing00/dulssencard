package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 사용자 제보 두 건에 대한 회귀 테스트.
 *
 *  1. 은행 앱 캡처의 환전·송금·이체가 카드 결제로 잡혀 실적이 부풀었다.
 *  2. USD 로 쓴 건은 원화와 섞지 말고 따로 세야 한다.
 */
class BankAndForeignTest {

    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 8, 10, 30), Cycle.ZONE).toInstant()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    // ------------------------------------------------------- 은행 거래 제외

    /** 은행 앱 거래내역 캡처. 카드 결제와 환전·송금이 섞여 있다. */
    private val bankScreen = """
        SKT 09:16
        전체 거래내역
        9월 7일 월요일
        21:18
        푸른들컨트리클럽 주식
        16,000원
        18:40
        외화 환전
        1,350,000원
        15:02
        김철수 송금
        500,000원
        12:30
        계좌이체 출금
        200,000원
        10:46
        한마음 홀세일클럽 중앙점
        5,000원
    """.trimIndent()

    @Test
    fun `환전 송금 이체는 카드 결제로 잡지 않는다`() {
        val rows = LedgerScreenParser.parse(bankScreen, now)
        val amounts = rows.map { it.amount }

        assertTrue("환전 1,350,000원이 들어왔다: $amounts", 1_350_000L !in amounts)
        assertTrue("송금 500,000원이 들어왔다: $amounts", 500_000L !in amounts)
        assertTrue("계좌이체 200,000원이 들어왔다: $amounts", 200_000L !in amounts)
    }

    @Test
    fun `같은 화면의 카드 결제는 그대로 읽는다`() {
        val rows = LedgerScreenParser.parse(bankScreen, now)
        val amounts = rows.map { it.amount }
        assertTrue("카드 결제 16,000원이 빠졌다: $amounts", 16_000L in amounts)
        assertTrue("카드 결제 5,000원이 빠졌다: $amounts", 5_000L in amounts)
        assertEquals(21_000L, rows.sumOf { it.amount })
    }

    @Test
    fun `체크카드출금은 은행 출금이 아니라 카드 결제다`() {
        val screen = """
            9월 7일 월요일
            12:33
            햇살할인마트
            체크카드출금
            4,350원
            13:10
            계좌이체 출금
            100,000원
        """.trimIndent()
        val rows = LedgerScreenParser.parse(screen, now)
        val amounts = rows.map { it.amount }
        assertTrue("체크카드출금이 빠졌다: $amounts", 4_350L in amounts)
        assertTrue("계좌이체가 들어왔다: $amounts", 100_000L !in amounts)
    }

    @Test
    fun `환전은 카드사 이름이 있어도 결제가 아니다`() {
        // 회귀: 실기기에서 은행 앱 캡처의 `환전주머니 5,000,000원` 이
        // 우리카드 결제로 **자동 반영**됐다. 같은 필터가 목록 파서에만 있었기 때문이다.
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.IMAGE,
                senderKey = "image-ocr",
                title = null,
                body = """
                    우리카드
                    환전주머니 자동
                    5,000,000원
                    09/08 11:00
                """.trimIndent(),
                receivedAt = at(2026, 9, 8, 11, 1),
            ),
        )
        assertTrue("환전이 결제로 잡혔다", parsed == null)
    }

    @Test
    fun `송금과 이체도 결제가 아니다`() {
        listOf("김철수 송금", "계좌이체", "자동이체 출금").forEach { label ->
            val parsed = PaymentParser.parse(
                RawMessage(
                    source = TxSource.SMS,
                    senderKey = "com.samsung.android.messaging",
                    title = null,
                    body = """
                        우리카드 승인
                        $label
                        500,000원
                        09/08 15:02
                    """.trimIndent(),
                    receivedAt = at(2026, 9, 8, 15, 3),
                ),
            )
            assertTrue("$label 이 결제로 잡혔다", parsed == null)
        }
    }

    @Test
    fun `체크카드출금은 여전히 결제다`() {
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.SMS,
                senderKey = "com.samsung.android.messaging",
                title = null,
                body = """
                    [KB]09/08 14:27
                    123456**789
                    주식회사한울
                    체크카드출금
                    13,000
                """.trimIndent(),
                receivedAt = at(2026, 9, 8, 14, 28),
            ),
        )
        assertNotNull("체크카드출금이 막혔다", parsed)
        assertEquals(13_000L, parsed!!.amount)
    }

    // ------------------------------------------------------- 이미지 출처 안전장치

    @Test
    fun `이미지에서 온 거래는 자동 반영하지 않는다`() = kotlinx.coroutines.test.runTest {
        // 캡처는 무엇이든 담을 수 있다. 실기기에서 무관한 문서의 숫자가
        // 1억원짜리 거래로 들어온 적이 있어, 사람이 확인하기 전에는 합계에 넣지 않는다.
        val card = com.msyim.dulssencard.data.model.Card(
            id = "c1",
            nickname = "우리카드",
            trackingTarget = 500_000,
            cycleStartDay = 1,
            matchKeywords = listOf("우리카드"),
            excludeKeywords = emptyList(),
            defaultCountsTowardTarget = true,
            defaultCountsTowardPurchaseLimit = true,
        )
        val outcome = Ingestor.ingest(
            raw = RawMessage(
                source = TxSource.IMAGE,
                senderKey = "image-ocr",
                title = null,
                body = """
                    우리카드 승인
                    16,000원 일시불
                    09/08 21:18
                    푸른들컨트리클럽
                """.trimIndent(),
                receivedAt = at(2026, 9, 8, 21, 19),
            ),
            cards = listOf(card),
            existingByFingerprint = { null },
            cancelOriginFinder = { _, _, _ -> null },
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertEquals(TxStatus.PENDING, txn.status)
        assertEquals("c1", txn.cardId)
    }

    // ------------------------------------------------------- 해외 사용 분리

    @Test
    fun `해외 승인의 외화 금액을 숫자로 읽는다`() {
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.KAKAO,
                senderKey = IssuerRegistry.KAKAO_PACKAGE,
                title = "신한카드",
                body = """
                    신한카드(1234)해외승인
                    USD 42.50
                    09/07 03:20
                    AMAZON MKTPLACE
                """.trimIndent(),
                receivedAt = at(2026, 9, 7, 3, 21),
            ),
        )
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertTrue(parsed.overseas)
        assertEquals("USD", parsed.currency)
        assertEquals(42.50, parsed.foreignAmount!!, 0.001)
        // 원화 금액은 만들어 내지 않는다 — 환율을 모른다.
        assertEquals(0L, parsed.amount)
    }

    private fun foreignTxn(
        currency: String,
        value: Double,
        direction: TxDirection = TxDirection.APPROVAL,
        status: TxStatus = TxStatus.PENDING,
        occurredAt: Long = at(2026, 9, 7, 12, 0),
    ) = Txn(
        id = java.util.UUID.randomUUID().toString(),
        cardId = null,
        occurredAt = occurredAt,
        receivedAt = occurredAt,
        amount = 0L,
        currency = currency,
        foreignAmount = value,
        direction = direction,
        status = status,
        source = TxSource.KAKAO,
        merchant = "해외 가맹점",
        countsTowardTarget = true,
        countsTowardPurchaseLimit = true,
        parserVersion = "test",
        confidence = 0.7,
        messageFingerprint = java.util.UUID.randomUUID().toString(),
        relatedTransactionId = null,
        pendingReason = null,
        issuerKey = "SHINHAN",
        installment = false,
        overseas = true,
    )

    @Test
    fun `해외 사용을 통화별로 따로 센다`() {
        val txns = listOf(
            foreignTxn("USD", 42.50),
            foreignTxn("USD", 17.50),
            foreignTxn("JPY", 3_000.0),
        )
        val spend = Aggregator.foreignSpend(txns, limitCycleStartDay = 1, now = now)
        assertEquals(2, spend.size)
        assertEquals("JPY", spend[0].currency)
        assertEquals(3_000.0, spend[0].total, 0.001)
        assertEquals("USD", spend[1].currency)
        assertEquals(60.0, spend[1].total, 0.001)
        assertEquals(2, spend[1].count)
    }

    @Test
    fun `해외 사용은 원화 한도 합계에 섞이지 않는다`() {
        val txns = listOf(foreignTxn("USD", 100.0))
        val limit = Aggregator.limitProgress(1_000_000, 1, txns, now)
        assertEquals(0L, limit.spent)
    }

    @Test
    fun `해외 취소는 차감한다`() {
        val txns = listOf(
            foreignTxn("USD", 50.0),
            foreignTxn("USD", 20.0, direction = TxDirection.CANCEL),
        )
        val spend = Aggregator.foreignSpend(txns, limitCycleStartDay = 1, now = now)
        assertEquals(30.0, spend.single().total, 0.001)
    }

    @Test
    fun `제외 처리한 해외 거래는 세지 않는다`() {
        val txns = listOf(
            foreignTxn("USD", 50.0),
            foreignTxn("USD", 90.0, status = TxStatus.EXCLUDED),
        )
        val spend = Aggregator.foreignSpend(txns, limitCycleStartDay = 1, now = now)
        assertEquals(50.0, spend.single().total, 0.001)
    }

    @Test
    fun `지난 주기의 해외 거래는 이번 주기에 세지 않는다`() {
        val txns = listOf(foreignTxn("USD", 50.0, occurredAt = at(2026, 8, 20, 12, 0)))
        assertTrue(Aggregator.foreignSpend(txns, limitCycleStartDay = 1, now = now).isEmpty())
    }
}
