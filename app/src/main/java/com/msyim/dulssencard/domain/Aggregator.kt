package com.msyim.dulssencard.domain

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxSource
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
    data class ForeignSpend(
        val currency: String,
        /** 최소 통화 단위 합계. 표시는 [ForeignMoney.format]. */
        val totalMinor: Long,
        val count: Int,
    )

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
     * 초기 사용액 기준 시각 **이후** 거래인가.
     *
     * 결제 알림의 시각은 **분 단위**(`09/13 01:54`)인데 기준 시각은 저장한 순간의 밀리초다. 그대로 비교하면
     * 기준을 넣은 **바로 그 분에 한 결제가 "기준 이전"으로 판정돼 빠진다** — 온보딩을 마치고 곧바로 결제한
     * 사용자가 "방금 긁었는데 왜 안 늘지?"를 겪는다(에뮬레이터 검증에서 발견). 그래서 분 단위 시각은
     * **그 분의 끝**으로 보고 비교한다. 같은 분의 결제는 사용자가 카드사 앱 금액을 읽은 뒤에 일어났을
     * 가능성이 크므로 더하는 쪽이 맞다. 수신 시각으로 대신한(추정) 시각은 이미 정밀하므로 그대로 쓴다.
     */
    fun isAfterInitialBaseline(txn: Txn, card: Card): Boolean {
        val time = effectiveTime(txn)
        val latestPossible = if (txn.occurredAt != null && !txn.occurredAtEstimated) {
            time - Math.floorMod(time, MINUTE_MILLIS) + MINUTE_MILLIS - 1
        } else {
            time
        }
        return latestPossible > card.initialAmountAt
    }

    private const val MINUTE_MILLIS = 60_000L

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
        if (card.initialAmount != 0L && window.contains(card.initialAmountAt)) {
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
                isAfterInitialBaseline(txn, card)
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
                    txn.foreignAmountMinor != null &&
                    txn.currency.isNotBlank() &&
                    txn.currency != "KRW" &&
                    window.contains(effectiveTime(txn))
            }
            .groupBy { it.currency }
            .map { (currency, rows) ->
                ForeignSpend(
                    currency = currency,
                    totalMinor = rows.sumOf { row ->
                        val value = row.foreignAmountMinor ?: 0L
                        if (row.direction == com.msyim.dulssencard.data.model.TxDirection.CANCEL) {
                            -value
                        } else {
                            value
                        }
                    },
                    count = rows.size,
                )
            }
            .filter { it.totalMinor > 0L }
            // 통화 코드순. 금액순은 의미가 없다 — 서로 다른 통화의 크기를 비교할 수 없고,
            // 최소 단위로 세면 USD 60(6000센트)이 JPY 3,000 보다 "크게" 나오는 엉뚱한 순서가 된다.
            .sortedBy { it.currency }
    }

    /**
     * 카드 상세 화면용 분해. "이 숫자가 어떻게 나왔는지"를 사용자가 따라갈 수 있게 나눈다.
     *
     * [counted] 의 부호 있는 합 + [CardProgress.initialApplied] 가 정확히 [CardProgress.spent] 다.
     * 이 등식이 깨지면 화면에 보이는 목록과 합계가 달라 사용자가 숫자를 믿지 못한다(테스트로 잠근다).
     */
    data class CardBreakdown(
        val progress: CardProgress,
        /** 이번 주기 합계에 들어간 거래. */
        val counted: List<Txn>,
        /** 이번 주기에 반영됐지만 초기 사용액 기준 시각 **이전**이라 초기 사용액에 들어 있다고 보는 거래. */
        val coveredByInitial: List<Txn>,
        /** 이번 주기 자동 반영이지만 사용자가 '목표 추적 포함'을 끈 거래. */
        val notCountedTowardTarget: List<Txn>,
        /** 이 카드에 붙은 확인 필요 거래. 주기와 무관하게 처리가 필요하므로 전부 보여 준다. */
        val pending: List<Txn>,
        /** 이번 주기에 제외 처리한 거래. */
        val excluded: List<Txn>,
        /** 이 카드 거래를 알림에서 마지막으로 받은 시각. 직접 입력·캡처는 수집이 아니므로 뺀다. */
        val lastCollectedAt: Long?,
    )

    fun cardBreakdown(card: Card, txns: List<Txn>, now: Instant = Instant.now()): CardBreakdown {
        val progress = cardProgress(card, txns, now)
        val window = Cycle.windowFor(card.cycleStartDay, now)
        val mine = txns.filter { it.cardId == card.id }
        val inCycle = mine.filter { window.contains(effectiveTime(it)) }
        val auto = inCycle.filter { it.status == TxStatus.AUTO }
        return CardBreakdown(
            progress = progress,
            counted = auto.filter { it.countsTowardTarget && isAfterInitialBaseline(it, card) },
            coveredByInitial = auto.filter {
                progress.initialApplied != 0L && it.countsTowardTarget && !isAfterInitialBaseline(it, card)
            },
            notCountedTowardTarget = auto.filter { !it.countsTowardTarget },
            pending = mine.filter { it.status == TxStatus.PENDING },
            excluded = inCycle.filter { it.status == TxStatus.EXCLUDED },
            lastCollectedAt = mine
                .filter { it.source == TxSource.SMS || it.source == TxSource.PUSH || it.source == TxSource.KAKAO }
                .maxOfOrNull { it.receivedAt },
        )
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
