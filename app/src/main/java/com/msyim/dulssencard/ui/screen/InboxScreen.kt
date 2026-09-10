package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
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
import com.msyim.dulssencard.ui.InboxTab
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
    cardFilterId: String?,
    onSelectTab: (InboxTab) -> Unit,
    onClearCardFilter: () -> Unit,
    onOpenTxn: (Txn) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 홈에서 카드를 눌러 들어오면 그 카드의 거래만 본다. 카드가 여러 장일 때
    // 홈의 숫자가 어디서 나왔는지 확인할 수 있는 유일한 길이다.
    val filterCard = cards.firstOrNull { it.id == cardFilterId }
    val scoped = if (filterCard == null) txns else txns.filter { it.cardId == filterCard.id }

    val pending = scoped.filter { it.status == TxStatus.PENDING }
    val excluded = scoped.filter { it.status == TxStatus.EXCLUDED }
    val visible = when (tab) {
        InboxTab.PENDING -> pending
        InboxTab.ALL -> scoped
        InboxTab.EXCLUDED -> excluded
    }
    val cardNames = cards.associate { it.id to it.nickname }

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
                Text(
                    when (tab) {
                        InboxTab.PENDING -> "확인 필요"
                        InboxTab.ALL -> "모든 거래"
                        InboxTab.EXCLUDED -> "제외된 거래"
                    },
                    style = DsType.h2,
                )
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
                    label = "확인 필요 ${pending.size}",
                    selected = tab == InboxTab.PENDING,
                    onClick = { onSelectTab(InboxTab.PENDING) },
                )
                DsChip("전체 ${scoped.size}", tab == InboxTab.ALL, onClick = { onSelectTab(InboxTab.ALL) })
                DsChip("제외됨", tab == InboxTab.EXCLUDED, onClick = { onSelectTab(InboxTab.EXCLUDED) })
            }
        }

        if (filterCard != null) {
            item { CardFilterBanner(filterCard.nickname, onClearCardFilter) }
        }

        if (visible.isEmpty()) {
            item { EmptyInbox() }
        } else {
            items(visible, key = { it.id }) { txn ->
                TxnRow(txn, cardNames[txn.cardId]) { onOpenTxn(txn) }
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TxnRow(txn: Txn, cardName: String?, onClick: () -> Unit) {
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
            }
        }
        Hairline()
    }
}

/**
 * 취소는 앞에 `−` 를 붙여 차감임을 드러낸다.
 *
 * 해외 승인은 원화 금액이 없는 게 정상이라 그냥 `Money.won` 을 쓰면 **`0원`** 으로 보인다.
 * 상세 화면이 실제로 그랬다. 외화 금액이 있으면 그 값을 통화와 함께 적는다 —
 * 환율을 알 방법이 없어 원화로는 환산하지 않는다.
 */
internal fun amountLabel(txn: Txn): String {
    val foreign = txn.foreignAmount
    val isForeign = txn.amount <= 0L && txn.currency.isNotBlank() && txn.currency != "KRW"
    val sign = if (txn.direction == TxDirection.CANCEL) "−" else ""
    return when {
        isForeign && foreign != null -> "$sign${txn.currency} ${Money.foreign(foreign)}"
        isForeign -> "${txn.currency} 확인 필요"
        else -> sign + Money.won(txn.amount)
    }
}

private fun amountColor(txn: Txn): Color = when {
    txn.direction == TxDirection.CANCEL -> Ds.green
    txn.status != TxStatus.AUTO -> Ds.text3
    else -> Ds.ink
}

/** 어떤 카드로 좁혀 보고 있는지 밝히고, 한 번에 풀 수 있게 한다. */
@Composable
private fun CardFilterBanner(nickname: String, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ds.screenPadding)
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(Ds.radiusBanner))
            .background(Ds.paper2)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$nickname 거래만 보는 중",
            style = DsType.listSecondary.copy(color = Ds.textBody),
            modifier = Modifier.weight(1f),
        )
        Text(
            "전체 보기",
            style = DsType.listSecondary.copy(color = Ds.accent),
            modifier = Modifier.clickable(onClick = onClear),
        )
    }
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
