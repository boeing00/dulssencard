package com.msyim.dulssencard.domain

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 초기 사용액(`initialAmount`) 회귀 테스트.
 *
 * 과거 내역을 캡처 OCR 로 복원하는 대신, 사용자가 카드앱에서 본 "이번 달 이용금액"을
 * 직접 입력받고 그 뒤로 오는 통지를 더해 나간다.
 *
 * ```
 * 현재 주기 합계 = initialAmount(이번 주기 것일 때만) + Σ(기준 시각 이후 거래)
 * ```
 *
 * **가장 위험한 것은 주기 전환이다.** 초기값이 다음 주기로 넘어가면 지난달 사용액이
 * 이번 달에 영원히 얹히는데, 숫자가 그럴듯하게 크기만 해서 사용자가 알아채기 어렵다.
 * 그래서 그 케이스를 맨 먼저 잠근다.
 */
class InitialAmountTest {

    /** 9월 8일 오후. 카드 주기 시작일이 1일이므로 9/1~9/30 주기 안이다. */
    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 8, 15, 0), Cycle.ZONE).toInstant()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private fun card(
        initialAmount: Long = 0L,
        initialAmountAt: Long = 0L,
        cycleStartDay: Int = 1,
    ) = Card(
        id = "c1",
        nickname = "우리카드",
        trackingTarget = 1_000_000L,
        cycleStartDay = cycleStartDay,
        matchKeywords = listOf("우리카드"),
        excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true,
        defaultCountsTowardPurchaseLimit = true,
        initialAmount = initialAmount,
        initialAmountAt = initialAmountAt,
    )

    private fun txn(
        amount: Long,
        occurredAt: Long,
        direction: TxDirection = TxDirection.APPROVAL,
        status: TxStatus = TxStatus.AUTO,
        cardId: String? = "c1",
    ) = Txn(
        id = java.util.UUID.randomUUID().toString(),
        cardId = cardId,
        occurredAt = occurredAt,
        receivedAt = occurredAt,
        amount = amount,
        currency = "KRW",
        foreignAmountMinor = null,
        direction = direction,
        status = status,
        source = TxSource.SMS,
        merchant = "가맹점",
        countsTowardTarget = true,
        countsTowardPurchaseLimit = true,
        parserVersion = "test",
        confidence = 0.9,
        messageFingerprint = java.util.UUID.randomUUID().toString(),
        relatedTransactionId = null,
        pendingReason = null,
        issuerKey = "WOORI",
        installment = false,
        overseas = false,
    )

    // ------------------------------------------------------- 주기 전환 (가장 위험)

    @Test
    fun `주기가 넘어가면 초기값을 세지 않는다`() {
        // 8월 20일에 "이번 달 32만원 썼다"고 입력했다. 지금은 9월 주기다.
        // 그 값이 살아 있으면 지난달 사용액이 이번 달에 얹힌다.
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 8, 20, 15, 0))
        val progress = Aggregator.cardProgress(c, txns = emptyList(), now = now)
        assertEquals(0L, progress.spent)
    }

    @Test
    fun `지난 주기 초기값이 있어도 이번 주기 거래는 그대로 센다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 8, 20, 15, 0))
        val txns = listOf(txn(50_000L, at(2026, 9, 5, 12, 0)))
        assertEquals(50_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    @Test
    fun `주기 시작일이 15일인 카드도 주기 경계를 지킨다`() {
        // 9/8 기준으로 현재 주기는 8/15~9/14 다. 8월 20일 입력값은 **이번 주기 안**이라 살아 있다.
        val c = card(
            initialAmount = 320_000L,
            initialAmountAt = at(2026, 8, 20, 15, 0),
            cycleStartDay = 15,
        )
        assertEquals(320_000L, Aggregator.cardProgress(c, emptyList(), now).spent)
    }

    // ------------------------------------------------------- 기본 합산

    @Test
    fun `초기값과 이후 통지를 더해 합계를 낸다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val txns = listOf(
            txn(16_000L, at(2026, 9, 8, 16, 30)),
            txn(5_000L, at(2026, 9, 8, 18, 0)),
        )
        assertEquals(341_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    @Test
    fun `초기값만 있고 통지가 없으면 초기값이 곧 합계다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        assertEquals(320_000L, Aggregator.cardProgress(c, emptyList(), now).spent)
    }

    @Test
    fun `초기값을 입력하지 않으면 지금까지처럼 통지만 센다`() {
        // 회귀: 필드를 더하면서 기존 동작이 바뀌면 안 된다.
        val c = card()
        val txns = listOf(txn(16_000L, at(2026, 9, 5, 12, 0)))
        assertEquals(16_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    // ------------------------------------------------------- 기준 시각 경계

    @Test
    fun `기준 시각 이전 거래는 초기값에 더하지 않는다`() {
        // 9/3 결제는 이미 사용자가 입력한 32만원 안에 들어 있다. 또 더하면 이중 집계다.
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val txns = listOf(txn(16_000L, at(2026, 9, 3, 12, 0)))
        assertEquals(320_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    @Test
    fun `기준을 넣은 바로 그 분에 한 결제는 더한다`() {
        // 회귀(에뮬레이터 검증): 초기 사용액을 15:00:30 에 저장하고 곧바로 결제했더니 알림 시각이
        // 분 단위(15:00 = 15:00:00)라 '기준 이전'으로 판정돼 합계에 안 들어갔다.
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0) + 30_000L)
        assertEquals(336_000L, Aggregator.cardProgress(c, listOf(txn(16_000L, at(2026, 9, 8, 15, 0))), now).spent)
    }

    @Test
    fun `기준 직전 분의 결제는 초기 사용액에 들어 있다고 본다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0) + 30_000L)
        assertEquals(320_000L, Aggregator.cardProgress(c, listOf(txn(16_000L, at(2026, 9, 8, 14, 59))), now).spent)
    }

    @Test
    fun `초기값이 없으면 기준 시각 이전 거래도 그대로 센다`() {
        // 초기값을 안 쓰는 카드에서 기준 시각(0)이 필터로 작동해 거래를 잘라내면 안 된다.
        val c = card()
        val txns = listOf(txn(16_000L, at(2026, 9, 2, 12, 0)))
        assertEquals(16_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    // ------------------------------------------------------- 취소

    @Test
    fun `기준 이후 취소는 차감한다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val txns = listOf(
            txn(16_000L, at(2026, 9, 8, 16, 0)),
            txn(16_000L, at(2026, 9, 8, 17, 0), direction = TxDirection.CANCEL),
        )
        assertEquals(320_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    @Test
    fun `기준 이전 결제를 기준 이후에 취소하면 초기값에서 차감된다`() {
        // 원 거래는 초기값에 들어 있다. 카드사 누적액도 줄어들 것이므로 차감이 맞다.
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val txns = listOf(txn(20_000L, at(2026, 9, 8, 16, 0), direction = TxDirection.CANCEL))
        assertEquals(300_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    // ------------------------------------------------------- 다른 집계와의 격리

    @Test
    fun `확인 필요 거래는 초기값에 더해지지 않는다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val txns = listOf(txn(16_000L, at(2026, 9, 8, 16, 0), status = TxStatus.PENDING))
        assertEquals(320_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    @Test
    fun `다른 카드의 초기값이 섞이지 않는다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val txns = listOf(txn(99_000L, at(2026, 9, 8, 16, 0), cardId = "c2"))
        assertEquals(320_000L, Aggregator.cardProgress(c, txns, now).spent)
    }

    @Test
    fun `초기값은 개인 구매 한도 합계에 섞이지 않는다`() {
        // initialAmount 는 카드별 원화 총액이고, 구매 한도는 주기 시작일이 따로다.
        // 주기가 어긋난 값을 더하면 한도 숫자가 거짓이 된다. 카드 진행률에만 쓴다.
        val txns = listOf(txn(16_000L, at(2026, 9, 8, 16, 0)))
        assertEquals(16_000L, Aggregator.limitProgress(1_000_000L, 1, txns, now).spent)
    }

    @Test
    fun `잔여 금액과 달성률도 초기값을 반영한다`() {
        val c = card(initialAmount = 800_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        val progress = Aggregator.cardProgress(c, emptyList(), now)
        assertEquals(200_000L, progress.remaining)
        assertEquals(0.8f, progress.ratio, 0.001f)
    }

    @Test
    fun `초기값만으로 목표를 넘기면 완료로 본다`() {
        val c = card(initialAmount = 1_200_000L, initialAmountAt = at(2026, 9, 8, 15, 0))
        assertEquals(true, Aggregator.cardProgress(c, emptyList(), now).complete)
    }

    // ------------------------------------------------------- 카드 편집 저장 시 기준 시각

    @Test
    fun `값도 시작일도 그대로면 기준 시각을 유지한다`() {
        // 저장만 눌렀다고 기준이 지금으로 밀리면 그 사이 들어온 거래가 초기값 안으로 빨려 들어가 사라진다.
        val base = at(2026, 9, 8, 10, 0)
        val c = card(initialAmount = 320_000L, initialAmountAt = base)
        assertEquals(base, Aggregator.initialAmountAtOnSave(c, 320_000L, 1, now))
    }

    @Test
    fun `값을 바꾸면 기준 시각은 지금이다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 10, 0))
        assertEquals(now.toEpochMilli(), Aggregator.initialAmountAtOnSave(c, 350_000L, 1, now))
    }

    @Test
    fun `새 주기에 지난달과 같은 금액을 넣으면 기준 시각을 새로 찍는다`() {
        // 편집 화면은 지난 주기 초기값을 빈칸으로 보여 준다. 사용자가 이번 달 금액을 넣었는데
        // 그게 우연히 지난달과 같으면 "안 바뀜"으로 판정돼 기준 시각이 지난 주기에 남고,
        // 방금 넣은 초기값이 0 으로 읽힌다.
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 8, 20, 10, 0))
        val stamp = Aggregator.initialAmountAtOnSave(c, 320_000L, 1, now)
        assertEquals(now.toEpochMilli(), stamp)
        assertEquals(320_000L, Aggregator.cardProgress(c.copy(initialAmountAt = stamp), emptyList(), now).spent)
    }

    @Test
    fun `주기 시작일을 바꾸면 기준 시각을 새로 찍는다`() {
        // 시작일 1 → 5 로 바꾸면 9/3 에 넣은 기준이 새 주기 [9/5, 10/5) 밖으로 밀려
        // 초기값이 통째로 사라진다. 저장하는 지금을 기준으로 삼아 입력칸에 보이던 금액을 살린다.
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 3, 10, 0))
        val stamp = Aggregator.initialAmountAtOnSave(c, 320_000L, 5, now)
        assertEquals(now.toEpochMilli(), stamp)
        val saved = c.copy(cycleStartDay = 5, initialAmountAt = stamp)
        assertEquals(320_000L, Aggregator.cardProgress(saved, emptyList(), now).spent)
    }

    @Test
    fun `초기값을 비우면 기준 시각도 끈다`() {
        val c = card(initialAmount = 320_000L, initialAmountAt = at(2026, 9, 8, 10, 0))
        assertEquals(0L, Aggregator.initialAmountAtOnSave(c, 0L, 1, now))
    }

    @Test
    fun `새 카드는 지금을 기준으로 삼는다`() {
        assertEquals(now.toEpochMilli(), Aggregator.initialAmountAtOnSave(null, 100_000L, 1, now))
    }
}
