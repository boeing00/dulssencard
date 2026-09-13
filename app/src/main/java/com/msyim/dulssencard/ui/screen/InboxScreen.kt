package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ui.InboxFilter
import com.msyim.dulssencard.ui.InboxTab
import com.msyim.dulssencard.ui.component.CardPickerDialog
import com.msyim.dulssencard.ui.component.DsTextButton
import com.msyim.dulssencard.ui.component.DsChip
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/** 상태별 색과 라벨. 결과함과 상세가 같은 값을 쓴다. */
data class StatusMeta(val label: String, val color: Color)

fun statusMeta(txn: Txn): StatusMeta = when {
    txn.status == TxStatus.PENDING -> StatusMeta("확인 필요", Ds.accent)
    txn.status == TxStatus.EXCLUDED -> StatusMeta("제외됨", Ds.text3)
    txn.direction == TxDirection.CANCEL -> StatusMeta("취소 반영", Ds.green)
    else -> StatusMeta("자동 반영", Ds.ink)
}

/**
 * 거래 결과함.
 *
 * 확인 필요 거래를 처리하고 전체 거래를 열람한다.
 * 어떤 탭에서도 SMS·알림 본문은 보여 주지 않는다 — 저장하지 않기 때문이다.
 */
@Composable
fun InboxScreen(
    txns: List<Txn>,
    cards: List<Card>,
    tab: InboxTab,
    cardFilter: String?,
    thisCycleOnly: Boolean,
    limitCycleStartDay: Int,
    onSelectTab: (InboxTab) -> Unit,
    onSelectCard: (String?) -> Unit,
    onToggleThisCycle: () -> Unit,
    onOpenTxn: (Txn) -> Unit,
    onConfirm: (Txn) -> Unit,
    onExclude: (Txn) -> Unit,
    onAssignCard: (Txn, Card) -> Unit,
    onAddManual: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 탭 숫자도 카드·주기 필터를 따른다. 필터를 걸었는데 숫자만 전체 건수면 사용자가 헷갈린다.
    fun count(t: InboxTab) =
        InboxFilter.apply(txns, cards, InboxFilter.Criteria(t, cardFilter, thisCycleOnly), limitCycleStartDay).size
    val visible = remember(txns, cards, tab, cardFilter, thisCycleOnly, limitCycleStartDay) {
        InboxFilter.apply(txns, cards, InboxFilter.Criteria(tab, cardFilter, thisCycleOnly), limitCycleStartDay)
    }
    val cardNames = cards.associate { it.id to it.nickname }
    var assigning by remember { mutableStateOf<Txn?>(null) }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 22.dp,
                    bottom = 6.dp,
                ),
            ) {
                SectionLabel("거래 결과함")
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when (tab) {
                            InboxTab.PENDING -> "확인 필요"
                            InboxTab.ALL -> "모든 거래"
                            InboxTab.EXCLUDED -> "제외된 거래"
                        },
                        style = DsType.h2,
                        modifier = Modifier.weight(1f),
                    )
                    DsTextButton("+ 직접 입력", onAddManual)
                }
            }
        }

        item {
            Row(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 6.dp,
                    bottom = 14.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DsChip(
                    label = "확인 필요만 ${count(InboxTab.PENDING)}",
                    selected = tab == InboxTab.PENDING,
                    onClick = { onSelectTab(InboxTab.PENDING) },
                )
                DsChip("전체 ${count(InboxTab.ALL)}", tab == InboxTab.ALL, onClick = { onSelectTab(InboxTab.ALL) })
                DsChip("제외됨", tab == InboxTab.EXCLUDED, onClick = { onSelectTab(InboxTab.EXCLUDED) })
            }
        }

        // 카드 필터 + 이번 주기만. 카드를 여러 장 쓰면 전체 목록에서 확인할 거래를 찾기 어렵다.
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = Ds.screenPadding, end = Ds.screenPadding, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DsChip("이번 주기만", thisCycleOnly, onClick = onToggleThisCycle, pill = false)
                Box(Modifier.width(1.dp).height(24.dp).background(Ds.line))
                DsChip("모든 카드", cardFilter == null, onClick = { onSelectCard(null) }, pill = false)
                cards.forEach { card ->
                    DsChip(card.nickname, cardFilter == card.id, onClick = { onSelectCard(card.id) }, pill = false)
                }
                DsChip(
                    "미분류",
                    cardFilter == InboxFilter.UNASSIGNED,
                    onClick = { onSelectCard(InboxFilter.UNASSIGNED) },
                    pill = false,
                )
            }
        }

        if (visible.isEmpty()) {
            item { EmptyInbox() }
        } else {
            items(visible, key = { it.id }) { txn ->
                TxnRow(
                    txn = txn,
                    cardName = cardNames[txn.cardId],
                    onClick = { onOpenTxn(txn) },
                    onConfirm = { onConfirm(txn) },
                    onExclude = { onExclude(txn) },
                    onAssign = { assigning = txn },
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    assigning?.let { txn ->
        CardPickerDialog(
            cards = cards,
            selectedId = txn.cardId,
            onDismiss = { assigning = null },
            onPick = { card -> onAssignCard(txn, card) },
        )
    }
}

@Composable
private fun TxnRow(
    txn: Txn,
    cardName: String?,
    onClick: () -> Unit,
    onConfirm: () -> Unit,
    onExclude: () -> Unit,
    onAssign: () -> Unit,
) {
    val meta = statusMeta(txn)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(onClick = onClick)
                .padding(horizontal = Ds.screenPadding, vertical = 15.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 좌측 상태 컬러 바.
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(Ds.radius))
                    .background(meta.color),
            )

            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        txn.merchant ?: "미확인 가맹점",
                        style = DsType.listPrimary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        amountLabel(txn),
                        style = DsType.txAmount.copy(color = amountColor(txn)),
                        maxLines = 1,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${cardName ?: "미분류"} · ${Times.listStamp(txn.occurredAt)}",
                        style = DsType.listSecondary,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(meta.label.uppercase(), style = DsType.badge.copy(color = meta.color))
                }

                txn.pendingReason?.let { reason ->
                    if (txn.status != TxStatus.AUTO) {
                        Spacer(Modifier.height(7.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Ds.radius))
                                .background(Ds.accentTint)
                                .padding(horizontal = 9.dp, vertical = 7.dp),
                        ) {
                            Text(
                                reason.message,
                                style = DsType.listSecondary.copy(color = Ds.accentPress),
                            )
                        }
                    }
                }

                // 확인 필요 거래는 목록에서 바로 처리한다. 상세까지 들어가는 한 단계가 쌓이면 확인 대상이 방치된다.
                // 카드가 없으면 반영 버튼을 숨긴다 - 어느 카드 합계에도 안 들어가 반영해도 의미가 없다.
                if (txn.status == TxStatus.PENDING) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (txn.cardId != null) QuickAction("반영", Ds.ink, onConfirm)
                        QuickAction(if (txn.cardId == null) "카드 지정" else "카드 변경", Ds.ink, onAssign)
                        QuickAction("제외", Ds.accent, onExclude)
                    }
                }
            }
        }
        Hairline()
    }
}

/** 취소는 앞에 `−` 를 붙여 차감임을 드러낸다. */
private fun amountLabel(txn: Txn): String = when {
    txn.amount <= 0L && txn.currency != "KRW" && txn.currency.isNotBlank() ->
        "${txn.currency} 확인 필요"
    txn.direction == TxDirection.CANCEL -> "−${Money.won(txn.amount)}"
    else -> Money.won(txn.amount)
}

private fun amountColor(txn: Txn): Color = when {
    txn.direction == TxDirection.CANCEL -> Ds.green
    txn.status != TxStatus.AUTO -> Ds.text3
    else -> Ds.ink
}

@Composable
private fun EmptyInbox() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("해당하는 거래가 없습니다", style = DsType.listPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "새 결제 문자나 카드사 앱 알림이 도착하면 여기에 정리됩니다.",
            style = DsType.listSecondary.copy(fontSize = 12.5.sp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QuickAction(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(Ds.radius))
            .border(Ds.hairline, color, RoundedCornerShape(Ds.radius))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(label, style = DsType.listPrimary.copy(fontSize = 13.sp, color = color))
    }
}
