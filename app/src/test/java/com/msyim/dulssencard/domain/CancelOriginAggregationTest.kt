package com.msyim.dulssencard.domain

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 취소는 **원 거래를 따라간다.**
 *
 * 취소 통지는 원 거래와 연결되면 자동 반영(AUTO)으로 들어온다. 그런데 원 거래가 합계에 들어가 있지 않으면
 * (제외 · 확인 필요 · 목표 추적 포함 끔) 취소만 빠지는 셈이 되어 합계가 **음수**가 된다 —
 * 5만원 결제를 '제외'했더니 취소 문자가 와서 카드 합계가 −5만원이 되는 식이다.
 *
 * 원 거래 상태는 나중에 바뀔 수 있으므로(제외했다가 되살리기) 연결 시점에 굳히지 않고
 * 집계할 때마다 원 거래를 본다. 원 거래가 기준 시각 이전이거나 지난 주기여도 합계에 들어가 있는
 * 거래라면 취소는 그대로 차감한다(CLAUDE.md §4 규칙 ③).
 */
class CancelOriginAggregationTest {

    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 12, 15, 0), Cycle.ZONE).toInstant()

    private fun at(mo: Int, d: Int, h: Int = 12): Long =
        ZonedDateTime.of(LocalDateTime.of(2026, mo, d, h, 0), Cycle.ZONE).toInstant().toEpochMilli()

    private val card = Card(
        id = "c1", nickname = "우리", trackingTarget = 500_000, cycleStartDay = 1,
        matchKeywords = listOf("우리카드"), excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true, defaultCountsTowardPurchaseLimit = true,
    )

    private fun txn(
        id: String,
        amount: Long,
        at: Long,
        direction: TxDirection = TxDirection.APPROVAL,
        status: TxStatus = TxStatus.AUTO,
        related: String? = null,
        target: Boolean = true,
        limit: Boolean = true,
        currency: String = "KRW",
        foreignMinor: Long? = null,
    ) = Txn(
        id = id, cardId = "c1", occurredAt = at, receivedAt = at, amount = amount,
        currency = currency, foreignAmountMinor = foreignMinor, direction = direction, status = status,
        source = TxSource.SMS, merchant = "가맹점", countsTowardTarget = target,
        countsTowardPurchaseLimit = limit, parserVersion = "test", confidence = 0.9,
        messageFingerprint = "fp-$id", relatedTransactionId = related, pendingReason = null,
        issuerKey = "WOORI", installment = false, overseas = false,
    )

    private fun cancelOf(origin: Txn, at: Long = at(9, 10)) =
        txn("cancel-${origin.id}", origin.amount, at, TxDirection.CANCEL, related = origin.id,
            currency = origin.currency, foreignMinor = origin.foreignAmountMinor)

    @Test
    fun `제외한 거래의 취소는 합계를 음수로 만들지 않는다`() {
        val origin = txn("o", 50_000, at(9, 5), status = TxStatus.EXCLUDED)
        val p = Aggregator.cardProgress(card, listOf(origin, cancelOf(origin)), now)
        assertEquals(0L, p.spent)
    }

    @Test
    fun `확인 필요 거래의 취소도 원 거래가 확정되기 전에는 빼지 않는다`() {
        val origin = txn("o", 50_000, at(9, 5), status = TxStatus.PENDING)
        val cancel = cancelOf(origin)
        assertEquals(0L, Aggregator.cardProgress(card, listOf(origin, cancel), now).spent)

        // 원 거래를 확정하면 결제와 취소가 함께 들어가 0 이 된다.
        val confirmed = origin.copy(status = TxStatus.AUTO)
        assertEquals(0L, Aggregator.cardProgress(card, listOf(confirmed, cancel), now).spent)
    }

    @Test
    fun `목표 추적에서 뺀 거래의 취소는 목표 합계에서 빼지 않는다`() {
        val other = txn("x", 100_000, at(9, 3))
        val origin = txn("o", 50_000, at(9, 5), target = false)
        val p = Aggregator.cardProgress(card, listOf(other, origin, cancelOf(origin)), now)
        assertEquals(100_000L, p.spent)
    }

    @Test
    fun `한도에서 뺀 거래의 취소는 한도 사용액에서 빼지 않는다`() {
        val other = txn("x", 100_000, at(9, 3))
        val origin = txn("o", 50_000, at(9, 5), limit = false)
        val l = Aggregator.limitProgress(1_000_000, 1, listOf(other, origin, cancelOf(origin)), now)
        assertEquals(100_000L, l.spent)
    }

    @Test
    fun `지난 주기에 반영된 거래의 취소는 이번 주기에서 그대로 차감한다`() {
        val other = txn("x", 100_000, at(9, 3))
        val origin = txn("o", 50_000, at(8, 25))
        val p = Aggregator.cardProgress(card, listOf(other, origin, cancelOf(origin)), now)
        assertEquals(50_000L, p.spent)
    }

    @Test
    fun `원 거래가 합계에 있으면 취소 자신의 설정이 꺼져 있어도 차감한다`() {
        // 카드 기본값이 '목표 추적 제외'인데 사용자가 원 결제만 켰다. 취소는 기본값(꺼짐)으로 들어온다.
        // 취소 자기 설정을 보면 결제만 남고 차감이 안 된다.
        val origin = txn("o", 50_000, at(9, 5))
        val cancel = cancelOf(origin).copy(countsTowardTarget = false, countsTowardPurchaseLimit = false)
        assertEquals(0L, Aggregator.cardProgress(card, listOf(origin, cancel), now).spent)
        assertEquals(0L, Aggregator.limitProgress(1_000_000, 1, listOf(origin, cancel), now).spent)
        val b = Aggregator.cardBreakdown(card, listOf(origin, cancel), now)
        assertEquals(b.progress.spent, b.progress.initialApplied + b.counted.sumOf { it.signedAmount })
        assertTrue("원 거래를 따라 들어간 취소가 '목표 추적에서 뺀 거래'로 보인다", b.notCountedTowardTarget.isEmpty())
    }

    @Test
    fun `원 거래를 찾을 수 없는 연결된 취소는 지금처럼 차감한다`() {
        val other = txn("x", 100_000, at(9, 3))
        val orphan = txn("c", 30_000, at(9, 10), TxDirection.CANCEL, related = "missing")
        assertEquals(70_000L, Aggregator.cardProgress(card, listOf(other, orphan), now).spent)
    }

    @Test
    fun `원 거래가 빠진 취소는 상세 분해에 따로 보이고 등식이 유지된다`() {
        val other = txn("x", 100_000, at(9, 3))
        val origin = txn("o", 50_000, at(9, 5), status = TxStatus.EXCLUDED)
        val cancel = cancelOf(origin)
        val b = Aggregator.cardBreakdown(card, listOf(other, origin, cancel), now)
        assertEquals(b.progress.spent, b.progress.initialApplied + b.counted.sumOf { it.signedAmount })
        assertEquals(listOf(cancel.id), b.cancelOfUncountedOrigin.map { it.id })
        assertTrue(b.counted.none { it.id == cancel.id })
    }

    @Test
    fun `제외한 해외 결제의 취소는 해외 합계에서 빼지 않는다`() {
        val usd = txn("u", 0, at(9, 4), currency = "USD", foreignMinor = 6_000)
        val origin = txn("o", 0, at(9, 5), status = TxStatus.EXCLUDED, currency = "USD", foreignMinor = 2_000)
        val spend = Aggregator.foreignSpend(listOf(usd, origin, cancelOf(origin)), 1, now)
        assertEquals(6_000L, spend.single().totalMinor)
    }
}
