package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ui.component.AmountEditDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.ui.CollectionGap
import com.msyim.dulssencard.ui.component.AdSlot
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.ProgressBar
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 홈 대시보드.
 *
 * 정보 우선순위(README): **개인 구매 추적 한도 → 카드별 남은 목표 → 확인 필요 거래**.
 */
@Composable
fun HomeScreen(
    limit: Aggregator.LimitProgress,
    rows: List<Aggregator.CardProgress>,
    pendingCount: Int,
    sortByName: Boolean,
    foreignSpend: List<Aggregator.ForeignSpend>,
    collectionGap: CollectionGap,
    onOpenLimitSettings: () -> Unit,
    onOpenPending: () -> Unit,
    onToggleSort: () -> Unit,
    onCardClick: (Card) -> Unit,
    onFixCollectionGap: (CollectionGap) -> Unit,
    recentCollection: List<RecentCollection>,
    lastCollectedByCard: Map<String, Long>,
    onSetInitialAmount: (Card, Long) -> Unit,
    onOpenSources: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingInitial by remember { mutableStateOf<Aggregator.CardProgress?>(null) }

    LazyColumn(modifier.fillMaxWidth()) {
        item { Masthead(daysRemaining = limit.daysRemaining) }
        item { LimitCard(limit, onOpenLimitSettings) }
        item {
            Footnote(
                "사용자 설정과 결제 알림 분류에 따른 추적값이며 카드사 공식 실적과 다를 수 있습니다.",
                Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 9.dp),
            )
        }

        if (foreignSpend.isNotEmpty()) {
            item { ForeignSpendRow(foreignSpend) }
        }

        if (collectionGap != CollectionGap.NONE) {
            item { CollectionGapBanner(collectionGap) { onFixCollectionGap(collectionGap) } }
        } else {
            // 수집 경로가 다 열려 있어도 **실제로 알림이 오고 있는지**는 따로다. 공백을 늦게 알아채지 않게 보여 준다.
            item { RecentCollectionRow(recentCollection, onOpenSources) }
        }

        if (pendingCount > 0) {
            item { PendingBanner(pendingCount, onOpenPending) }
        }

        item { CardSectionHeader(sortByName, onToggleSort) }

        if (rows.isEmpty()) {
            item { EmptyCards() }
        } else {
            items(rows, key = { it.card.id }) { row ->
                CardRow(
                    row = row,
                    lastCollectedAt = lastCollectedByCard[row.card.id],
                    onClick = { onCardClick(row.card) },
                    onEditInitial = { editingInitial = row },
                )
            }
        }

        item { AdSlot() }

        item {
            Footnote(
                "완료된 카드는 하단으로 이동합니다. " +
                    "카드사 공식 인정 여부는 앱이 판정하지 않습니다.",
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 18.dp,
                    bottom = 28.dp,
                ),
            )
        }
    }

    editingInitial?.let { row ->
        AmountEditDialog(
            title = "${row.card.nickname} 초기 사용액 다시 맞추기",
            message = "카드사 앱에서 확인한 이번 달 이용금액을 넣으세요. 이 시각 이전 결제는 이 금액에 들어 있다고 봅니다. " +
                "비워 두고 저장하면 초기 사용액을 끄고 알림만 셉니다.",
            initial = row.initialApplied,
            confirmLabel = "저장",
            allowZero = true,
            onDismiss = { editingInitial = null },
            onConfirm = { amount -> onSetInitialAmount(row.card, amount) },
        )
    }
}

/** 홈 '최근 수집' 한 줄의 항목. 알림 소스 이름과 마지막으로 결제를 인식한 시각. */
data class RecentCollection(val label: String, val lastPaymentAt: Long)

@Composable
private fun RecentCollectionRow(items: List<RecentCollection>, onOpenSources: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSources)
            .padding(horizontal = Ds.screenPadding, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("최근 수집".uppercase(), style = DsType.fieldLabel)
        Spacer(Modifier.width(10.dp))
        Text(
            if (items.isEmpty()) {
                "아직 결제 알림을 받지 못했습니다"
            } else {
                items.take(3).joinToString(" · ") { "${it.label} ${Times.ago(it.lastPaymentAt)}" }
            },
            style = DsType.listSecondary.copy(color = if (items.isEmpty()) Ds.accent else Ds.textBody),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        Text("진단 →", style = DsType.listSecondary.copy(color = Ds.textSubtle))
    }
}

/** 마스트헤드 — 계단형 바 + 카플라벨 + 워드마크, 우측에 주기 남은 일수 필. */
@Composable
private fun Masthead(daysRemaining: Long) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 22.dp, bottom = 16.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            Modifier.padding(bottom = 6.dp, end = 10.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            StairBar(13.dp, Ds.line)
            StairBar(22.dp, Ds.mastheadMid)
            StairBar(32.dp, Ds.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("SMS · 푸시 → 추적", style = DsType.kicker)
            Spacer(Modifier.height(4.dp))
            Text(
                buildWordmark(),
                style = DsType.masthead,
                maxLines = 1,
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(Ds.radiusPill))
                .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radiusPill))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(
                "D-$daysRemaining",
                style = DsType.monoSmall.copy(fontSize = 11.sp, color = Ds.text2),
            )
        }
    }
}

private fun buildWordmark() = buildAnnotatedString {
    append("덜쎈")
    withStyle(SpanStyle(color = Ds.accent)) { append("카드") }
}

@Composable
private fun StairBar(height: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) {
    Box(
        Modifier
            .size(width = 6.dp, height = height)
            .clip(RoundedCornerShape(1.dp))
            .background(color),
    )
}

/** 한도 카드 — 입체감 있는 흐린 브라운 표면. */
@Composable
private fun LimitCard(limit: Aggregator.LimitProgress, onOpenSettings: () -> Unit) {
    val shape = RoundedCornerShape(Ds.radiusCard)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ds.screenPadding)
            .clip(shape)
            .background(Ds.limitSurfaceTint)
            .background(Brush.linearGradient(Ds.limitSurfaceStops))
            .border(Ds.hairline, androidx.compose.ui.graphics.Color(0xBFFFFFFF), shape)
            .padding(16.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "개인 구매 추적 한도".uppercase(),
                    style = DsType.sectionLabel.copy(color = Ds.brownLabel),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "한도 설정",
                    style = DsType.link.copy(color = Ds.brownLink, textDecoration = TextDecoration.Underline),
                    modifier = Modifier.clickable(onClick = onOpenSettings),
                )
            }

            Spacer(Modifier.height(14.dp))
            // 사용액과 한도는 각자 한 줄을 쓴다. 5,000,000원까지 잘리지 않아야 한다.
            Text(Money.won(limit.spent), style = DsType.limitSpent, maxLines = 1)
            Text("한도 ${Money.won(limit.limit)}", style = DsType.limitCaption, maxLines = 1)

            Spacer(Modifier.height(12.dp))
            ProgressBar(
                ratio = limit.ratio,
                height = 10.dp,
                trackColor = androidx.compose.ui.graphics.Color(0x335A4226),
                fillBrush = Brush.verticalGradient(listOf(Ds.brownBarA, Ds.brownBarB)),
            )

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) {
                    Text("남은 금액 ", style = DsType.limitCaption, maxLines = 1)
                    Text(
                        Money.won(limit.remaining),
                        style = DsType.limitCaption.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                            color = Ds.brownInk,
                        ),
                        maxLines = 1,
                    )
                }
                Text("${limit.percentUsed}% 사용", style = DsType.limitCaption, maxLines = 1)
            }
        }
    }
}

@Composable
private fun PendingBanner(count: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Ds.radiusBanner)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ds.screenPadding, vertical = 14.dp)
            .clip(shape)
            .background(Ds.accentTint)
            .border(Ds.hairline, Ds.accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "확인 필요 ${count}건 — 합계에 반영되지 않음",
            style = DsType.listPrimary.copy(fontSize = 13.5.sp, color = Ds.accentPress),
            modifier = Modifier.weight(1f),
        )
        Text("→", style = DsType.listPrimary.copy(color = Ds.accentPress))
    }
}

/**
 * 해외 사용. 원화 합계와 **분리해서** 보여 준다.
 *
 * 이 앱은 네트워크를 쓰지 않아 환율을 모른다. 임의 환율로 원화에 더하면 한도·실적 숫자가
 * 거짓이 되므로, 통화별 원금액 그대로 따로 적는다.
 */
@Composable
private fun ForeignSpendRow(items: List<Aggregator.ForeignSpend>) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 14.dp),
    ) {
        SectionLabel("해외 사용")
        Spacer(Modifier.height(8.dp))
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    "${item.currency} ${com.msyim.dulssencard.domain.ForeignMoney.format(item.totalMinor, item.currency)}",
                    style = DsType.monoSmall.copy(fontSize = 13.sp, color = Ds.ink),
                    modifier = Modifier.weight(1f),
                )
                Text("${item.count}건", style = DsType.listSecondary)
            }
        }
        Spacer(Modifier.height(6.dp))
        Footnote("환율을 알 수 없어 원화 합계에는 넣지 않습니다.")
    }
}

/**
 * 수집 경로가 반쪽만 열렸을 때. 사용자가 "왜 어떤 결제는 안 잡히지?"를 겪기 전에 알려 준다.
 */
@Composable
private fun CollectionGapBanner(gap: CollectionGap, onFix: () -> Unit) {
    val shape = RoundedCornerShape(Ds.radiusBanner)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ds.screenPadding, vertical = 14.dp)
            .clip(shape)
            .background(Ds.paper2)
            .border(Ds.hairline, Ds.line, shape)
            .clickable(onClick = onFix)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(gap.title, style = DsType.listPrimary)
            Spacer(Modifier.height(4.dp))
            Text(gap.subtitle, style = DsType.listSecondary)
        }
        Text("→", style = DsType.listPrimary.copy(color = Ds.textSubtle))
    }
}

@Composable
private fun CardSectionHeader(sortByName: Boolean, onToggleSort: () -> Unit) {
    Column {
        Hairline()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 26.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("카드별 남은 목표", Modifier.weight(1f))
            Text(
                if (sortByName) "이름순" else "남은 금액순",
                style = DsType.listSecondary.copy(color = Ds.text2),
                modifier = Modifier.clickable(onClick = onToggleSort),
            )
        }
    }
}

@Composable
private fun CardRow(
    row: Aggregator.CardProgress,
    lastCollectedAt: Long?,
    onClick: () -> Unit,
    onEditInitial: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // 완료된 카드는 하단으로 내리고 진행 바·배지 색으로만 구분한다. 행 전체를 흐리면
            // 카드명·금액까지 대비 기준 아래로 떨어져 읽기 어렵다.
            .padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 16.dp, bottom = 17.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.card.nickname, style = DsType.cardNickname, modifier = Modifier.weight(1f))
            Text(
                if (row.complete) "목표 달성" else Money.won(row.remaining),
                style = DsType.cardRemaining.copy(color = if (row.complete) Ds.green else Ds.ink),
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${Money.won(row.spent)} / ${Money.won(row.card.trackingTarget)}",
                style = DsType.monoSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                if (row.complete) "COMPLETE" else "TRACKING",
                style = DsType.badge.copy(color = if (row.complete) Ds.green else Ds.text3),
            )
        }

        Spacer(Modifier.height(10.dp))
        ProgressBar(
            ratio = row.ratio,
            height = 5.dp,
            trackColor = Ds.line,
            fillColor = if (row.complete) Ds.green else Ds.ink,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                buildString {
                    append("남은 ${row.daysRemaining}일")
                    // 합계가 어디서 왔는지 밝힌다. 숫자의 출처를 알아야 사용자가 다시 맞출 수 있다.
                    append(" · ")
                    append(
                        if (row.initialApplied != 0L) {
                            "초기 사용액 ${Money.won(row.initialApplied)} + 알림 ${row.countedCount}건"
                        } else {
                            "알림 ${row.countedCount}건"
                        },
                    )
                    append(" · ")
                    append(lastCollectedAt?.let { "수집 ${Times.ago(it)}" } ?: "수집 기록 없음")
                },
                style = DsType.footnote,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            // 카드사 앱 총액이 달라졌을 때 카드 편집까지 들어가지 않고 여기서 바로 맞춘다.
            Text(
                "초기 사용액 수정",
                style = DsType.link.copy(fontSize = 12.sp),
                modifier = Modifier
                    .heightIn(min = Ds.minTouchTarget)
                    .clickable(onClick = onEditInitial)
                    .padding(start = 8.dp)
                    .wrapContentHeight(),
            )
        }
    }
    Hairline()
}

@Composable
private fun EmptyCards() {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("아직 등록한 카드가 없습니다", style = DsType.listPrimary)
        Spacer(Modifier.height(6.dp))
        Text("카드 탭에서 첫 카드를 추가하면 결제가 분류됩니다.", style = DsType.listSecondary)
    }
}
