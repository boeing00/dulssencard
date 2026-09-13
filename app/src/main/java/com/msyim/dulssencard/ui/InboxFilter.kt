package com.msyim.dulssencard.ui

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Cycle
import java.time.Instant

/**
 * 결과함 필터. 카드를 여러 장 쓰면 전체 목록에서 확인할 거래를 찾기 어렵다.
 *
 *  - **탭** — 확인 필요만 / 전체 / 제외됨
 *  - **카드** — 전체 / 특정 카드 / 미분류(카드가 안 붙은 거래)
 *  - **이번 주기만** — 카드를 골랐으면 **그 카드의 주기**, 아니면 개인 구매 한도 주기
 *
 * 카드마다 주기 시작일이 달라서 "이번 주기"는 고른 카드에 따라 달라진다. 한 가지 날짜로 자르면
 * 15일 시작 카드의 이번 주기 거래가 1일 기준으로 잘려 나간다.
 */
object InboxFilter {

    /** 카드 필터에서 "미분류"를 뜻하는 값. 카드 id 와 겹치지 않는다. */
    const val UNASSIGNED = "__unassigned__"

    data class Criteria(
        val tab: InboxTab,
        /** null = 모든 카드, [UNASSIGNED] = 미분류, 그 밖 = 카드 id. */
        val cardId: String? = null,
        val thisCycleOnly: Boolean = false,
    )

    fun apply(
        txns: List<Txn>,
        cards: List<Card>,
        criteria: Criteria,
        limitCycleStartDay: Int,
        now: Instant = Instant.now(),
    ): List<Txn> {
        val byTab = when (criteria.tab) {
            InboxTab.PENDING -> txns.filter { it.status == TxStatus.PENDING }
            InboxTab.ALL -> txns
            InboxTab.EXCLUDED -> txns.filter { it.status == TxStatus.EXCLUDED }
        }
        val byCard = when (criteria.cardId) {
            null -> byTab
            UNASSIGNED -> byTab.filter { it.cardId == null }
            else -> byTab.filter { it.cardId == criteria.cardId }
        }
        if (!criteria.thisCycleOnly) return byCard

        val startDay = cards.firstOrNull { it.id == criteria.cardId }?.cycleStartDay ?: limitCycleStartDay
        val window = Cycle.windowFor(startDay, now)
        return byCard.filter { window.contains(Aggregator.effectiveTime(it)) }
    }
}
