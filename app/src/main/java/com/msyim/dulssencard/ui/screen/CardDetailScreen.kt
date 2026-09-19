package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ui.component.AmountEditDialog
import com.msyim.dulssencard.ui.component.InlineLink
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.OutlineButton
import com.msyim.dulssencard.ui.component.ProgressBar
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 카드 상세. 홈에서 카드를 누르면 온다.
 *
 * 이 화면의 목적은 **"이 합계가 어떻게 나왔는가"를 사용자가 따라갈 수 있게** 하는 것이다.
 * 위에서부터 합계 → 초기 사용액 → 이후 반영된 거래 → 초기 사용액에 포함된 거래 → 확인 필요 → 제외.
 * 목록의 합과 초기 사용액을 더하면 맨 위 합계와 정확히 같다([Aggregator.CardBreakdown]).
 */
@Composable
fun CardDetailScreen(
    breakdown: Aggregator.CardBreakdown,
    onBack: () -> Unit,
    /** 뒤로가기가 실제로 갈 화면 이름. */
    backLabel: String,
    onOpenTxn: (Txn) -> Unit,
    onSetInitialAmount: (Long) -> Unit,
    onAddManual: () -> Unit,
    onEditCard: () -> Unit,
    onOpenPending: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = breakdown.progress
    val card = progress.card
    var editingInitial by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 18.dp)) {
                Text("← $backLabel", style = DsType.backLink, modifier = Modifier.clickable(onClick = onBack))
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.nickname, style = DsType.h2, modifier = Modifier.weight(1f))
                    InlineLink("카드 설정", onEditCard)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Times.listStamp(progress.cycleStartMillis).take(5)} ~ " +
                        "${Times.listStamp(progress.cycleEndMillis - 1).take(5)} · 남은 ${progress.daysRemaining}일" +
                        (breakdown.lastCollectedAt?.let { " · 마지막 수집 ${Times.ago(it)}" } ?: " · 알림 수집 기록 없음"),
                    style = DsType.monoSmall.copy(fontSize = 12.sp),
                )
            }
        }

        // ---- 이번 주기 합계
        item {
            Column(Modifier.padding(horizontal = Ds.screenPadding, vertical = 20.dp)) {
                SectionLabel("이번 주기 합계")
                Spacer(Modifier.height(8.dp))
                Text(Money.won(progress.spent), style = DsType.detailAmount, maxLines = 1)
                Spacer(Modifier.height(10.dp))
                ProgressBar(
                    ratio = progress.ratio,
                    height = 6.dp,
                    trackColor = Ds.line,
                    fillColor = if (progress.complete) Ds.green else Ds.ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (progress.complete) {
                        "목표 ${Money.won(card.trackingTarget)} 달성"
                    } else {
                        "목표 ${Money.won(card.trackingTarget)} · ${Money.won(progress.remaining)} 남음"
                    },
                    style = DsType.listSecondary,
                )
            }
            Hairline()
        }

        // ---- 초기 사용액
        item {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Ds.screenPadding, vertical = 16.dp)
                    .clip(RoundedCornerShape(Ds.radiusBanner))
                    .background(Ds.paper2)
                    .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radiusBanner))
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("초기 사용액".uppercase(), style = DsType.fieldLabel)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (progress.initialApplied != 0L) Money.won(progress.initialApplied) else "입력 안 함",
                            style = DsType.cardNickname,
                        )
                    }
                    OutlineButton(if (progress.initialApplied != 0L) "다시 맞추기" else "입력", { editingInitial = true })
                }
                Spacer(Modifier.height(8.dp))
                Footnote(
                    if (progress.initialApplied != 0L) {
                        "기준 ${Times.logStamp(card.initialAmountAt)} · 이 시각 이전 결제는 이 금액에 들어 있다고 봅니다. " +
                            "카드사 앱 총액과 달라졌으면 '다시 맞추기'로 지금 금액을 넣으세요."
                    } else {
                        "카드사 앱의 이번 달 이용금액을 넣으면, 그 뒤로 오는 결제 알림이 여기에 더해집니다."
                    },
                )
            }
        }

        // ---- 확인 필요 (맨 위로 끌어올린다 — 합계에 안 들어간 돈이라 가장 먼저 봐야 한다)
        if (breakdown.pending.isNotEmpty()) {
            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPending)
                        .padding(horizontal = Ds.screenPadding, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "확인 필요 ${breakdown.pending.size}건 — 합계에 아직 안 들어갔습니다",
                        style = DsType.listPrimary.copy(color = Ds.accent, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                    )
                    Text("→", style = DsType.listPrimary.copy(color = Ds.accent))
                }
                Hairline()
            }
        }

        section(
            title = "이후 반영된 거래 ${breakdown.counted.size}건 · ${Money.won(breakdown.counted.sumOf { it.signedAmount })}",
            txns = breakdown.counted,
            empty = "초기 사용액 이후 반영된 결제가 없습니다.",
            onOpenTxn = onOpenTxn,
        )
        if (breakdown.coveredByInitial.isNotEmpty()) {
            section(
                title = "초기 사용액에 포함된 거래 ${breakdown.coveredByInitial.size}건",
                txns = breakdown.coveredByInitial,
                empty = "",
                onOpenTxn = onOpenTxn,
                note = "기준 시각 이전이라 합계에 다시 더하지 않았습니다.",
            )
        }
        if (breakdown.notCountedTowardTarget.isNotEmpty()) {
            section(
                title = "목표 추적에서 뺀 거래 ${breakdown.notCountedTowardTarget.size}건",
                txns = breakdown.notCountedTowardTarget,
                empty = "",
                onOpenTxn = onOpenTxn,
            )
        }
        if (breakdown.cancelOfUncountedOrigin.isNotEmpty()) {
            section(
                title = "원 거래를 따라 뺀 취소 ${breakdown.cancelOfUncountedOrigin.size}건",
                txns = breakdown.cancelOfUncountedOrigin,
                empty = "",
                onOpenTxn = onOpenTxn,
                note = "원 거래가 합계에 없어 취소도 차감하지 않았습니다.",
            )
        }
        if (breakdown.excluded.isNotEmpty()) {
            section(
                title = "제외한 거래 ${breakdown.excluded.size}건",
                txns = breakdown.excluded,
                empty = "",
                onOpenTxn = onOpenTxn,
            )
        }

        item {
            Column(Modifier.padding(horizontal = Ds.screenPadding, vertical = 22.dp)) {
                OutlineButton("+ 거래 직접 추가 (현금성 결제·누락된 알림·보정)", onAddManual, Modifier.fillMaxWidth())
            }
        }
    }

    if (editingInitial) {
        AmountEditDialog(
            title = "${card.nickname} 초기 사용액",
            message = "카드사 앱에서 확인한 이번 달 이용금액을 넣으세요. 기준 시각은 지금으로 바뀝니다. " +
                "비워 두고 저장하면 초기 사용액을 끄고 알림만 셉니다.",
            initial = progress.initialApplied,
            confirmLabel = "저장",
            allowZero = true,
            onDismiss = { editingInitial = false },
            onConfirm = onSetInitialAmount,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    txns: List<Txn>,
    empty: String,
    onOpenTxn: (Txn) -> Unit,
    note: String? = null,
) {
    item {
        Column(Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 20.dp, bottom = 6.dp)) {
            SectionLabel(title)
            if (note != null) {
                Spacer(Modifier.height(4.dp))
                Footnote(note)
            }
        }
    }
    if (txns.isEmpty()) {
        item {
            Text(
                empty,
                style = DsType.listSecondary,
                modifier = Modifier.padding(horizontal = Ds.screenPadding, vertical = 8.dp),
            )
        }
    } else {
        items(txns, key = { "${title.take(6)}-${it.id}" }) { txn -> CompactTxnRow(txn, onOpenTxn) }
    }
}

@Composable
private fun CompactTxnRow(txn: Txn, onOpen: (Txn) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(txn) }
            .padding(horizontal = Ds.screenPadding, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            Times.listStamp(txn.occurredAt),
            style = DsType.monoSmall.copy(fontSize = 11.5.sp),
            modifier = Modifier.width(82.dp),
        )
        Text(txn.merchant ?: "미확인 가맹점", style = DsType.listPrimary.copy(fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1)
        Text(
            when (txn.direction) {
                TxDirection.CANCEL -> "−${Money.won(txn.amount)}"
                else -> Money.won(txn.amount)
            },
            style = DsType.txAmount.copy(color = if (txn.direction == TxDirection.CANCEL) Ds.green else Ds.ink),
            maxLines = 1,
        )
    }
    Hairline(Modifier.padding(horizontal = Ds.screenPadding))
}

