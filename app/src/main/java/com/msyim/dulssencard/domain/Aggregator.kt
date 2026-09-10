package com.msyim.dulssencard.domain

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import java.time.Instant

/**
 * 집계 규칙 (핸드오프 README '집계 규칙 (구현 필수)' + PRD §8).
 *
 * - 카드 누적 = 그 카드의 `status == AUTO && countsTowardTarget` 거래 합. 취소는 차감.
 * - 한도 사용액 = `status == AUTO && countsTowardPurchaseLimit` 거래 합(카드 무관). 취소는 차감.
 * - `PENDING`(확인 필요)과 `EXCLUDED`(제외)는 어떤 합계에도 반영하지 않는다.
 * - 진행률은 0~100% 로 클램프한다.
 * - 카드 주기와 한도 주기는 독립 계산한다.
 *
 * 파생 계산만 하고 캐시를 두지 않는다. 어떤 보정이든 다음 렌더에 바로 반영되게 하려는 것이다.
 */
object Aggregator {

    data class CardProgress(
        val card: Card,
        val spent: Long,
        /**
         * [spent] 중 사용자가 직접 입력한 몫. 이번 주기 값이 아니면 0이다.
         * 화면에서 "이 숫자가 어디서 왔는지"를 밝히는 데 쓴다 — 그래야 사용자가 다시 맞출 수 있다.
         */
        val initialApplied: Long,
        val remaining: Long,
        val ratio: Float,
        val complete: Boolean,
        val daysRemaining: Long,
        val cycleStartMillis: Long,
        val cycleEndMillis: Long,
    )

    /** 통화별 해외 사용 합계. 원화 환산은 하지 않는다. */
    data class ForeignSpend(val currency: String, val total: Double, val count: Int)

    data class LimitProgress(
        val spent: Long,
        val limit: Long,
        val remaining: Long,
        val ratio: Float,
        val percentUsed: Int,
        val daysRemaining: Long,
    )

    /**
     * 거래 시각. 시각 미상이면 수신 시각으로 대신한다.
     *
     * 시각 미상 거래는 어차피 자동 반영되지 않아(PENDING) 합계에 안 들어가지만,
     * 사용자가 확인 결과함에서 카드로 옮겨 AUTO 로 승격시키면 합계에 들어온다.
     * 그때 주기 판정 기준이 없으면 거래가 어느 주기에도 안 잡히므로 수신 시각으로 떨어뜨린다.
     */
    fun effectiveTime(txn: Txn): Long = txn.occurredAt ?: txn.receivedAt

    /**
     * 이번 주기에 반영할 초기 사용액.
     *
     * **초기값은 사용자가 입력한 그 주기에만 유효하다.** 주기가 넘어가면 0이다.
     * 이걸 빼먹으면 지난달 사용액이 이번 달에 영원히 얹히는데, 숫자가 그럴듯하게 크기만 해서
     * 사용자가 알아채기 어렵다. 다음 주기는 통지만으로 온전히 집계된다.
     *
     * 값을 지우지 않고 읽을 때 걸러 내는 이유는, 사용자가 언제 얼마를 입력했는지
     * 기록으로 남겨 두기 위해서다.
     */
    private fun initialAmountIn(card: Card, window: Cycle.Window): Long =
        if (card.initialAmount != 0L && 
            card.initialAmountAt > window.start && 
            card.initialAmountAt <= window.endInclusive) {
            card.initialAmount
        } else {
            0L
        }

    fun cardProgress(
        card: Card,
        txns: List<Txn>,
        now: Instant = Instant.now(),
    ): CardProgress {
        val window = Cycle.windowFor(card.cycleStartDay, now)
        val initial = initialAmountIn(card, window)
        val spent = initial + txns.sumOf { txn ->
            if (
                txn.cardId == card.id &&
                txn.status == TxStatus.AUTO &&
                txn.countsTowardTarget &&
                window.contains(effectiveTime(txn)) &&
                effectiveTime(txn) > card.initialAmountAt
            ) {
                txn.signedAmount
            } else {
                0L
            }
        }
        val remaining = card.trackingTarget - spent
        return CardProgress(
            card = card,
            spent = spent,
            initialApplied = initial,
            remaining = remaining,
            ratio = ratio(spent, card.trackingTarget),
            complete = remaining <= 0L && card.trackingTarget > 0L,
            daysRemaining = window.daysRemaining(now),
            cycleStartMillis = window.startMillis,
            cycleEndMillis = window.endMillis,
        )
    }

    fun limitProgress(
        limitAmount: Long,
        limitCycleStartDay: Int,
        txns: List<Txn>,
        now: Instant = Instant.now(),
    ): LimitProgress {
        val window = Cycle.windowFor(limitCycleStartDay, now)
        val spent = txns.sumOf { txn ->
            if (
                txn.status == TxStatus.AUTO &&
                txn.countsTowardPurchaseLimit &&
                window.contains(effectiveTime(txn))
            ) {
                txn.signedAmount
            } else {
                0L
            }
        }
        val r = ratio(spent, limitAmount)
        return LimitProgress(
            spent = spent,
            limit = limitAmount,
            remaining = limitAmount - spent,
            ratio = r,
            percentUsed = (r * 100f).toInt(),
            daysRemaining = window.daysRemaining(now),
        )
    }

    /**
     * 해외 사용 집계.
     *
     * 원화 합계와 **섞지 않는다.** 이 앱은 네트워크를 쓰지 않아 환율을 모르므로
     * 원화로 환산할 방법이 없고, 임의 환율로 더하면 한도·실적 숫자가 거짓이 된다.
     * 그래서 통화별로 따로 세어 홈에 별도 줄로 보여 준다.
     *
     * 해외 승인은 자동 반영되지 않으므로(확인 필요), 사용자가 확정한 것만 셀지
     * 전부 셀지가 문제인데 — 여기서는 **제외 처리하지 않은 것 전부**를 센다.
     * 참고용 숫자이고, 빠뜨리면 "해외에서 쓴 게 어디 갔지"가 되기 때문이다.
     */
    fun foreignSpend(
        txns: List<Txn>,
        limitCycleStartDay: Int,
        now: Instant = Instant.now(),
    ): List<ForeignSpend> {
        val window = Cycle.windowFor(limitCycleStartDay, now)
        return txns
            .filter { txn ->
                txn.status != TxStatus.EXCLUDED &&
                    txn.foreignAmount != null &&
                    txn.currency.isNotBlank() &&
                    txn.currency != "KRW" &&
                    window.contains(effectiveTime(txn))
            }
            .groupBy { it.currency }
            .map { (currency, rows) ->
                ForeignSpend(
                    currency = currency,
                    total = rows.sumOf { row ->
                        val value = row.foreignAmount ?: 0.0
                        if (row.direction == com.msyim.dulssencard.data.model.TxDirection.CANCEL) {
                            -value
                        } else {
                            value
                        }
                    },
                    count = rows.size,
                )
            }
            .filter { it.total > 0.0 }
            .sortedByDescending { it.total }
    }

    /**
     * 홈 카드 리스트 정렬.
     * 완료된 카드는 언제나 하단으로 내린다(README: 완료 카드는 하단 이동 + 저대비).
     */
    fun sortForHome(rows: List<CardProgress>, byName: Boolean): List<CardProgress> =
        rows.sortedWith(
            compareBy<CardProgress> { it.complete }
                .thenBy { if (byName) 0L else it.remaining }
                .thenBy { it.card.nickname },
        )

    private fun ratio(spent: Long, total: Long): Float {
        if (total <= 0L) return 0f
        return (spent.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }
}
