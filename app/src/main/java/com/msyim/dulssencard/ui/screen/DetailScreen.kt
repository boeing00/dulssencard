package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ui.component.DestructiveButton
import com.msyim.dulssencard.ui.component.DsChip
import com.msyim.dulssencard.ui.component.DsToggle
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.PrimaryButton
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 거래 상세·보정. 오인식·취소·실적 제외를 3동작 이내로 처리한다.
 *
 * 이 화면은 카드, 시각, 금액, 상태, 매칭 근거, 변경 기록만 보여 준다.
 * **문자·알림 본문은 저장하지 않으므로 보여 줄 수도 없다.**
 */
@Composable
fun DetailScreen(
    txn: Txn,
    cards: List<Card>,
    adjustments: List<Adjustment>,
    onBack: () -> Unit,
    onMoveToCard: (Card) -> Unit,
    onToggleTarget: () -> Unit,
    onToggleLimit: () -> Unit,
    onConfirm: () -> Unit,
    onExclude: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = statusMeta(txn)
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Text(
                "← 결과함",
                style = DsType.backLink,
                modifier = Modifier
                    .padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 18.dp)
                    .clickable(onClick = onBack),
            )
        }

        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 18.dp,
                    bottom = 20.dp,
                ),
            ) {
                Text(meta.label.uppercase(), style = DsType.badge.copy(color = meta.color))
                Spacer(Modifier.height(10.dp))
                Text(
                    if (txn.direction == TxDirection.CANCEL) {
                        "−${Money.won(txn.amount)}"
                    } else {
                        Money.won(txn.amount)
                    },
                    style = DsType.detailAmount.copy(
                        color = if (txn.direction == TxDirection.CANCEL) Ds.green else Ds.ink,
                    ),
                    maxLines = 1,
                )
                Spacer(Modifier.height(8.dp))
                Text(txn.merchant ?: "미확인 가맹점", style = DsType.cardNickname)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${Times.listStamp(txn.occurredAt)} · " +
                        if (txn.direction == TxDirection.CANCEL) "취소" else "승인",
                    style = DsType.monoSmall.copy(fontSize = 12.5.sp),
                )
                if (txn.occurredAtEstimated) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "알림에 거래 시각이 없어 수신 시각으로 표시합니다",
                        style = DsType.footnote,
                    )
                }
            }
            Hairline()
        }

        item { CardPicker(cards, txn.cardId, onMoveToCard) }

        item {
            ToggleRow(
                title = "목표 추적 포함",
                subtitle = "카드 월간 추적 목표에 합산",
                checked = txn.countsTowardTarget,
                onToggle = onToggleTarget,
            )
        }
        item {
            ToggleRow(
                title = "구매 추적 한도 포함",
                subtitle = "개인 구매 추적 한도에 합산",
                checked = txn.countsTowardPurchaseLimit,
                onToggle = onToggleLimit,
            )
        }

        item { MatchEvidence(txn, cards) }
        item { ChangeLog(adjustments) }

        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 22.dp,
                    bottom = 30.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (txn.status == TxStatus.EXCLUDED) {
                    PrimaryButton("제외 복원", onRestore)
                } else {
                    PrimaryButton("집계 확정", onConfirm)
                    DestructiveButton("실적 제외", onExclude)
                }
            }
        }
    }
}

@Composable
private fun CardPicker(cards: List<Card>, selectedId: String?, onPick: (Card) -> Unit) {
    Column(
        Modifier.padding(
            start = Ds.screenPadding,
            end = Ds.screenPadding,
            top = 20.dp,
            bottom = 4.dp,
        ),
    ) {
        SectionLabel("카드")
        Spacer(Modifier.height(10.dp))
        if (cards.isEmpty()) {
            Text("등록한 카드가 없습니다. 카드 탭에서 먼저 추가하세요.", style = DsType.listSecondary)
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                cards.forEach { card ->
                    DsChip(
                        label = card.nickname,
                        selected = card.id == selectedId,
                        onClick = { onPick(card) },
                        pill = false,
                        horizontalPadding = 13.dp,
                        verticalPadding = 11.dp,
                    )
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    Hairline()
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ds.screenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = DsType.listPrimary.copy(fontSize = 15.sp))
                Spacer(Modifier.height(3.dp))
                Text(subtitle, style = DsType.listSecondary)
            }
            Spacer(Modifier.width(12.dp))
            DsToggle(checked, onToggle)
        }
        Hairline()
    }
}

/**
 * 매칭 근거. 왜 이 카드로, 왜 이 상태로 분류됐는지 사용자가 확인할 수 있어야 한다.
 * fingerprint 는 축약해 보여 준다(전체는 의미 없는 해시라 화면만 어지럽힌다).
 */
@Composable
private fun MatchEvidence(txn: Txn, cards: List<Card>) {
    val card = cards.firstOrNull { it.id == txn.cardId }
    val rows = listOf(
        "수집 경로" to sourceLabel(txn.source),
        "카드사" to (IssuerRegistry.displayName(txn.issuerKey) ?: "판정하지 못함"),
        "매칭 키워드" to (card?.matchKeywords?.joinToString(", ") ?: "없음"),
        "파싱 신뢰도" to String.format(java.util.Locale.US, "%.2f", txn.confidence),
        "파서 버전" to txn.parserVersion,
        "지문" to txn.messageFingerprint.take(12),
    )

    Column(
        Modifier.padding(
            start = Ds.screenPadding,
            end = Ds.screenPadding,
            top = 22.dp,
        ),
    ) {
        SectionLabel("매칭 근거")
        Spacer(Modifier.height(10.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Ds.radiusBanner))
                .background(Ds.paper2)
                .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radiusBanner))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            rows.forEach { (key, value) ->
                Row(Modifier.fillMaxWidth()) {
                    Text(key, style = DsType.listSecondary, modifier = Modifier.width(84.dp))
                    Text(
                        value,
                        style = DsType.monoSmall.copy(fontSize = 11.5.sp, color = Ds.textBody),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "문자·알림 본문은 저장·표시하지 않습니다.",
                style = DsType.footnote,
            )
        }
    }
}

private fun sourceLabel(source: TxSource): String = when (source) {
    TxSource.SMS -> "결제 문자"
    TxSource.PUSH -> "카드사 앱 알림"
    TxSource.KAKAO -> "카카오톡 알림"
    TxSource.IMAGE -> "캡처 이미지"
    TxSource.MANUAL -> "직접 입력"
}

@Composable
private fun ChangeLog(adjustments: List<Adjustment>) {
    Column(
        Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 22.dp),
    ) {
        SectionLabel("변경 기록")
        Spacer(Modifier.height(6.dp))
        if (adjustments.isEmpty()) {
            Text(
                "아직 보정 기록이 없습니다.",
                style = DsType.listSecondary,
                modifier = Modifier.padding(vertical = 9.dp),
            )
        } else {
            adjustments.forEach { adjustment ->
                Column {
                    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                        Text(
                            Times.logStamp(adjustment.createdAt),
                            style = DsType.monoSmall.copy(fontSize = 11.sp),
                            modifier = Modifier.width(90.dp),
                        )
                        Text(
                            changeLabel(adjustment),
                            style = DsType.listSecondary.copy(color = Ds.textBody),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Hairline()
                }
            }
        }
    }
}

private fun changeLabel(adjustment: Adjustment): String = when (adjustment.changeType) {
    com.msyim.dulssencard.data.model.ChangeType.MOVE_CARD -> "다른 카드로 이동"
    com.msyim.dulssencard.data.model.ChangeType.TOGGLE_TARGET -> "목표 추적 포함 여부 변경"
    com.msyim.dulssencard.data.model.ChangeType.TOGGLE_LIMIT -> "구매 추적 한도 포함 여부 변경"
    com.msyim.dulssencard.data.model.ChangeType.CONFIRM -> "집계 확정"
    com.msyim.dulssencard.data.model.ChangeType.EXCLUDE -> "실적 제외"
    com.msyim.dulssencard.data.model.ChangeType.RESTORE -> "제외 복원"
    com.msyim.dulssencard.data.model.ChangeType.AMOUNT_MANUAL -> "금액 수동 보정"
    com.msyim.dulssencard.data.model.ChangeType.WIPE -> "데이터 삭제"
}
