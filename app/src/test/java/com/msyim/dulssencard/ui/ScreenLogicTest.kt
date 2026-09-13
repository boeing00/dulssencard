package com.msyim.dulssencard.ui

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Cycle
import com.msyim.dulssencard.domain.Times
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 화면이 쓰는 순수 로직: 카드 상세 분해, 결과함 필터, 상대 시각.
 * 화면 자체는 테스트하지 않지만, 화면에 보이는 숫자와 목록은 전부 여기서 나온다.
 */
class ScreenLogicTest {

    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 12, 15, 0), Cycle.ZONE).toInstant()

    private fun at(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private val woori = Card(
        id = "w", nickname = "우리", trackingTarget = 500_000, cycleStartDay = 1,
        matchKeywords = listOf("우리카드"), excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true, defaultCountsTowardPurchaseLimit = true,
        initialAmount = 200_000, initialAmountAt = at(2026, 9, 8),
    )
    private val samsung = woori.copy(id = "s", nickname = "삼성", cycleStartDay = 15, initialAmount = 0, initialAmountAt = 0)

    private var seq = 0
    private fun txn(
        cardId: String?,
        amount: Long,
        at: Long,
        status: TxStatus = TxStatus.AUTO,
        direction: TxDirection = TxDirection.APPROVAL,
        target: Boolean = true,
        source: TxSource = TxSource.PUSH,
    ) = Txn(
        id = "t${seq++}", cardId = cardId, occurredAt = at, receivedAt = at, amount = amount, currency = "KRW",
        direction = direction, status = status, source = source, merchant = "m", countsTowardTarget = target,
        countsTowardPurchaseLimit = true, parserVersion = "t", confidence = 1.0, messageFingerprint = "fp$seq",
        relatedTransactionId = null, pendingReason = null, issuerKey = null, installment = false, overseas = false,
    )

    // ------------------------------------------------------- 카드 상세 분해

    @Test
    fun `상세 목록의 합과 초기 사용액을 더하면 홈 합계와 정확히 같다`() {
        // 화면에 보이는 목록과 위의 합계가 어긋나면 사용자는 숫자를 믿지 않는다.
        val txns = listOf(
            txn("w", 30_000, at(2026, 9, 5)),                     // 기준 이전 → 초기값에 포함
            txn("w", 16_000, at(2026, 9, 9)),                     // 반영
            txn("w", 4_000, at(2026, 9, 10), direction = TxDirection.CANCEL), // 반영(차감)
            txn("w", 9_000, at(2026, 9, 10), target = false),     // 목표 추적 제외
            txn("w", 7_000, at(2026, 9, 11), status = TxStatus.EXCLUDED),
            txn("w", 5_000, at(2026, 9, 11), status = TxStatus.PENDING),
            txn("w", 99_000, at(2026, 8, 20)),                    // 지난 주기
            txn("s", 50_000, at(2026, 9, 11)),                    // 다른 카드
        )
        val b = Aggregator.cardBreakdown(woori, txns, now)
        assertEquals(b.progress.spent, b.progress.initialApplied + b.counted.sumOf { it.signedAmount })
        assertEquals(212_000L, b.progress.spent)
        assertEquals(listOf(30_000L), b.coveredByInitial.map { it.amount })
        assertEquals(listOf(9_000L), b.notCountedTowardTarget.map { it.amount })
        assertEquals(listOf(7_000L), b.excluded.map { it.amount })
        assertEquals(listOf(5_000L), b.pending.map { it.amount })
    }

    @Test
    fun `초기 사용액이 없는 카드는 기준 이전 목록이 비어 있다`() {
        val b = Aggregator.cardBreakdown(samsung, listOf(txn("s", 10_000, at(2026, 9, 11))), now)
        assertTrue(b.coveredByInitial.isEmpty())
        assertEquals(listOf(10_000L), b.counted.map { it.amount })
    }

    @Test
    fun `마지막 수집 시각은 알림에서 온 거래만 본다`() {
        val txns = listOf(
            txn("w", 1_000, at(2026, 9, 10), source = TxSource.SMS),
            txn("w", 1_000, at(2026, 9, 12), source = TxSource.MANUAL), // 직접 입력은 수집이 아니다
        )
        assertEquals(at(2026, 9, 10), Aggregator.cardBreakdown(woori, txns, now).lastCollectedAt)
    }

    // ------------------------------------------------------- 결과함 필터

    private val inbox = listOf(
        txn("w", 1_000, at(2026, 9, 10), status = TxStatus.PENDING),
        txn("w", 2_000, at(2026, 9, 10)),
        txn("s", 3_000, at(2026, 9, 13), status = TxStatus.PENDING), // 삼성 주기(9/15 시작) 기준 이번 주기는 8/15~9/14
        txn("s", 4_000, at(2026, 8, 10), status = TxStatus.PENDING), // 삼성 지난 주기
        txn(null, 5_000, at(2026, 9, 11), status = TxStatus.PENDING),
        txn("w", 6_000, at(2026, 9, 11), status = TxStatus.EXCLUDED),
    )

    private fun filter(c: InboxFilter.Criteria) =
        InboxFilter.apply(inbox, listOf(woori, samsung), c, limitCycleStartDay = 1, now = now).map { it.amount }

    @Test
    fun `확인 필요만 본다`() {
        assertEquals(listOf(1_000L, 3_000L, 4_000L, 5_000L), filter(InboxFilter.Criteria(InboxTab.PENDING)))
    }

    @Test
    fun `이 카드만 본다`() {
        assertEquals(listOf(1_000L, 2_000L, 6_000L), filter(InboxFilter.Criteria(InboxTab.ALL, cardId = "w")))
    }

    @Test
    fun `미분류만 본다`() {
        assertEquals(listOf(5_000L), filter(InboxFilter.Criteria(InboxTab.PENDING, cardId = InboxFilter.UNASSIGNED)))
    }

    @Test
    fun `이번 주기는 고른 카드의 주기 시작일로 자른다`() {
        // 삼성은 15일 시작이라 9/13 은 이번 주기(8/15~9/14), 8/10 은 지난 주기다.
        assertEquals(
            listOf(3_000L),
            filter(InboxFilter.Criteria(InboxTab.PENDING, cardId = "s", thisCycleOnly = true)),
        )
    }

    @Test
    fun `카드를 안 고르면 이번 주기는 한도 주기로 자른다`() {
        // 한도는 1일 시작 → 9/1~9/30. 8/10 만 빠진다.
        assertEquals(
            listOf(1_000L, 3_000L, 5_000L),
            filter(InboxFilter.Criteria(InboxTab.PENDING, thisCycleOnly = true)),
        )
    }

    // ------------------------------------------------------- 상대 시각

    @Test
    fun `상대 시각을 사람이 읽는 말로 바꾼다`() {
        val base = at(2026, 9, 12, 15, 0)
        assertEquals("방금", Times.ago(base - 20_000, base))
        assertEquals("5분 전", Times.ago(base - 5 * 60_000, base))
        assertEquals("2시간 전", Times.ago(base - 2 * 3_600_000, base))
        assertEquals("3일 전", Times.ago(base - 3 * 86_400_000L, base))
        assertEquals("일주일이 넘으면 날짜로", "08/01", Times.ago(at(2026, 8, 1), base))
        assertEquals("기기 시계가 틀려 미래면", "방금", Times.ago(base + 60_000, base))
    }
}
