package com.msyim.dulssencard.domain

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

class CycleTest {

    private fun instant(y: Int, mo: Int, d: Int, h: Int = 12, mi: Int = 0) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant()

    @Test
    fun `주기는 시작일 0시부터 다음 달 시작일 직전까지다`() {
        val window = Cycle.windowFor(15, instant(2026, 9, 7))
        assertEquals(instant(2026, 8, 15, 0, 0).toEpochMilli(), window.startMillis)
        assertEquals(instant(2026, 9, 15, 0, 0).toEpochMilli(), window.endMillis)
    }

    @Test
    fun `시작일을 지나면 이번 달 주기로 넘어간다`() {
        val window = Cycle.windowFor(15, instant(2026, 9, 20))
        assertEquals(instant(2026, 9, 15, 0, 0).toEpochMilli(), window.startMillis)
        assertEquals(instant(2026, 10, 15, 0, 0).toEpochMilli(), window.endMillis)
    }

    @Test
    fun `시작일 당일 0시는 새 주기에 들어간다`() {
        val window = Cycle.windowFor(15, instant(2026, 9, 15, 0, 0))
        assertEquals(instant(2026, 9, 15, 0, 0).toEpochMilli(), window.startMillis)
        assertTrue(window.contains(instant(2026, 9, 15, 0, 0).toEpochMilli()))
    }

    @Test
    fun `주기 끝 순간은 포함하지 않는다`() {
        val window = Cycle.windowFor(1, instant(2026, 9, 7))
        assertFalse(window.contains(window.endMillis))
        assertTrue(window.contains(window.endMillis - 1))
    }

    @Test
    fun `2월을 지나도 시작일이 유지된다`() {
        val window = Cycle.windowFor(28, instant(2026, 3, 5))
        assertEquals(instant(2026, 2, 28, 0, 0).toEpochMilli(), window.startMillis)
        assertEquals(instant(2026, 3, 28, 0, 0).toEpochMilli(), window.endMillis)
    }

    @Test
    fun `시작일은 1에서 28 사이로 강제된다`() {
        assertEquals(28, Cycle.normalizeStartDay(31))
        assertEquals(1, Cycle.normalizeStartDay(0))
    }

    @Test
    fun `남은 일수는 주기 종료일까지의 날짜 차이다`() {
        val window = Cycle.windowFor(1, instant(2026, 9, 7))
        // 9/1 ~ 10/1, 오늘이 9/7 이면 남은 24일
        assertEquals(24L, window.daysRemaining(instant(2026, 9, 7)))
    }
}

class AggregatorTest {

    private fun instant(y: Int, mo: Int, d: Int, h: Int = 12) =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, 0), Cycle.ZONE).toInstant()

    private val now = instant(2026, 9, 7)

    private val card = Card(
        id = "c1",
        nickname = "신한 딥드림",
        trackingTarget = 500_000,
        cycleStartDay = 1,
        matchKeywords = listOf("신한"),
        excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true,
        defaultCountsTowardPurchaseLimit = true,
    )

    private var seq = 0

    private fun txn(
        amount: Long,
        cardId: String? = "c1",
        status: TxStatus = TxStatus.AUTO,
        direction: TxDirection = TxDirection.APPROVAL,
        target: Boolean = true,
        limit: Boolean = true,
        occurredAt: Long = instant(2026, 9, 5).toEpochMilli(),
    ) = Txn(
        id = "t${seq++}",
        cardId = cardId,
        occurredAt = occurredAt,
        receivedAt = occurredAt,
        amount = amount,
        currency = "KRW",
        direction = direction,
        status = status,
        source = TxSource.SMS,
        merchant = "가맹점",
        countsTowardTarget = target,
        countsTowardPurchaseLimit = limit,
        parserVersion = "test",
        confidence = 1.0,
        messageFingerprint = "fp${seq}",
        relatedTransactionId = null,
        pendingReason = null,
        issuerKey = "SHINHAN",
        installment = false,
        overseas = false,
    )

    @Test
    fun `자동 반영된 거래만 카드 누적에 들어간다`() {
        val txns = listOf(
            txn(100_000),
            txn(50_000, status = TxStatus.PENDING),
            txn(30_000, status = TxStatus.EXCLUDED),
        )
        assertEquals(100_000L, Aggregator.cardProgress(card, txns, now).spent)
    }

    @Test
    fun `취소는 카드 누적에서 차감된다`() {
        val txns = listOf(txn(100_000), txn(30_000, direction = TxDirection.CANCEL))
        assertEquals(70_000L, Aggregator.cardProgress(card, txns, now).spent)
    }

    @Test
    fun `목표 추적 제외 거래는 카드 누적에 들어가지 않는다`() {
        val txns = listOf(txn(100_000, target = false))
        assertEquals(0L, Aggregator.cardProgress(card, txns, now).spent)
    }

    @Test
    fun `지난 주기 거래는 이번 주기 누적에 들어가지 않는다`() {
        val txns = listOf(txn(100_000, occurredAt = instant(2026, 8, 20).toEpochMilli()))
        assertEquals(0L, Aggregator.cardProgress(card, txns, now).spent)
    }

    @Test
    fun `목표를 넘기면 달성으로 표시하고 진행률을 100퍼센트로 자른다`() {
        val progress = Aggregator.cardProgress(card, listOf(txn(600_000)), now)
        assertTrue(progress.complete)
        assertEquals(1f, progress.ratio, 0.001f)
        assertEquals(-100_000L, progress.remaining)
    }

    @Test
    fun `한도는 카드와 무관하게 합산되고 카드 주기와 독립적이다`() {
        val txns = listOf(
            txn(100_000, cardId = "c1"),
            txn(200_000, cardId = "c2"),
            txn(50_000, cardId = null, limit = false),
        )
        // 한도 주기 시작일 15 → 8/15 ~ 9/15. 위 거래는 모두 9/5 라 포함된다.
        val progress = Aggregator.limitProgress(1_000_000, 15, txns, now)
        assertEquals(300_000L, progress.spent)
        assertEquals(700_000L, progress.remaining)
        assertEquals(30, progress.percentUsed)
    }

    @Test
    fun `한도가 0이면 진행률은 0이고 나누기 오류가 나지 않는다`() {
        val progress = Aggregator.limitProgress(0, 1, listOf(txn(10_000)), now)
        assertEquals(0f, progress.ratio, 0.001f)
    }

    @Test
    fun `완료된 카드는 정렬에서 항상 하단으로 내려간다`() {
        val done = Aggregator.cardProgress(card, listOf(txn(600_000)), now)
        val active = Aggregator.cardProgress(
            card.copy(id = "c2", nickname = "가나다"),
            emptyList(),
            now,
        )
        val sorted = Aggregator.sortForHome(listOf(done, active), byName = true)
        assertEquals("c2", sorted.first().card.id)
        assertEquals("c1", sorted.last().card.id)
    }
}
