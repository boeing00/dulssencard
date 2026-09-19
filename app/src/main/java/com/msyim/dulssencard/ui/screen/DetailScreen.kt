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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ui.component.AmountEditDialog
import com.msyim.dulssencard.ui.component.DestructiveButton
import com.msyim.dulssencard.ui.component.DsConfirmDialog
import com.msyim.dulssencard.ui.component.DsTextButton
import com.msyim.dulssencard.ui.component.InlineLink
import com.msyim.dulssencard.ui.component.OutlineButton
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
    allTxns: List<Txn>,
    cancelCandidates: List<Txn>?,
    onCorrectAmount: (Long) -> Unit,
    onLoadCancelCandidates: () -> Unit,
    onPickOrigin: (Txn) -> Unit,
    onDismissCandidates: () -> Unit,
    onOpenTxn: (Txn) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val meta = statusMeta(txn)
    var correcting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val origin = txn.relatedTransactionId?.let { id -> allTxns.firstOrNull { it.id == id } }
    // 이 결제를 취소한 거래. 승인 쪽에서도 "취소됐는지"를 보여 줘야 합계가 왜 줄었는지 안다.
    val cancelledBy = if (txn.direction == TxDirection.APPROVAL) {
        allTxns.firstOrNull { it.relatedTransactionId == txn.id && it.direction == TxDirection.CANCEL }
    } else {
        null
    }
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
                Spacer(Modifier.height(6.dp))
                InlineLink("금액 정정", { correcting = true }, Modifier.offset(x = (-6).dp))
            }
            Hairline()
        }

        if (txn.direction == TxDirection.CANCEL) {
            item {
                LinkedTxnSection(
                    title = "원 승인 거래",
                    linked = origin,
                    emptyText = "연결된 원 승인 거래가 없습니다. 같은 결제의 승인 거래를 골라 이어 주세요 - " +
                        "연결하면 원 거래의 카드로 반영됩니다.",
                    actionLabel = if (origin == null) "원 거래 선택" else "다른 거래로 바꾸기",
                    onAction = onLoadCancelCandidates,
                    onOpen = onOpenTxn,
                )
            }
        } else if (cancelledBy != null) {
            item {
                LinkedTxnSection(
                    title = "이 결제의 취소",
                    linked = cancelledBy,
                    emptyText = "",
                    actionLabel = null,
                    onAction = {},
                    onOpen = onOpenTxn,
                )
            }
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
                    DestructiveButton("영구 삭제", { confirmDelete = true })
                } else {
                    PrimaryButton("집계 확정", onConfirm)
                    DestructiveButton("실적 제외", onExclude)
                    // 자동 반영 거래는 바로 지우지 않는다 — 합계가 사용자 모르게 줄어든다. 제외한 뒤에 지운다.
                    if (txn.status == TxStatus.PENDING) {
                        DsTextButton("삭제", { confirmDelete = true })
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DsConfirmDialog(
            title = deleteTitle(listOf(txn)),
            text = DELETE_NOTE,
            confirmLabel = "삭제",
            destructive = true,
            onConfirm = onDelete,
            onDismiss = { confirmDelete = false },
        )
    }

    if (correcting) {
        AmountEditDialog(
            title = "금액 정정",
            message = "알림에서 금액을 잘못 읽었거나 부분 취소가 반영되지 않았을 때 고칩니다. 변경 기록에 남습니다.",
            initial = txn.amount,
            confirmLabel = "정정",
            onDismiss = { correcting = false },
            onConfirm = onCorrectAmount,
        )
    }

    cancelCandidates?.let { candidates ->
        OriginPickerDialog(
            cancel = txn,
            candidates = candidates,
            cards = cards,
            onDismiss = onDismissCandidates,
            onPick = onPickOrigin,
        )
    }
}

@Composable
private fun LinkedTxnSection(
    title: String,
    linked: Txn?,
    emptyText: String,
    actionLabel: String?,
    onAction: () -> Unit,
    onOpen: (Txn) -> Unit,
) {
    Column(Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 20.dp, bottom = 16.dp)) {
        SectionLabel(title)
        Spacer(Modifier.height(10.dp))
        if (linked == null) {
            Text(emptyText, style = DsType.listSecondary.copy(color = Ds.accentPress))
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Ds.radiusBanner))
                    .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radiusBanner))
                    .clickable { onOpen(linked) }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(linked.merchant ?: "미확인 가맹점", style = DsType.listPrimary.copy(fontSize = 14.sp))
                    Text(Times.listStamp(linked.occurredAt), style = DsType.listSecondary)
                }
                Text(Money.won(linked.amount), style = DsType.txAmount)
            }
        }
        if (actionLabel != null) {
            Spacer(Modifier.height(10.dp))
            OutlineButton(actionLabel, onAction)
        }
    }
    Hairline()
}

/**
 * 원 승인 거래 고르기. 후보는 같은 금액을 먼저, 최근 순으로 보인다([DulSsenRepository.cancelCandidates]).
 * 부분 취소를 위해 금액이 더 큰 승인도 보여 준다.
 */
@Composable
private fun OriginPickerDialog(
    cancel: Txn,
    candidates: List<Txn>,
    cards: List<Card>,
    onDismiss: () -> Unit,
    onPick: (Txn) -> Unit,
) {
    val names = cards.associate { it.id to it.nickname }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.paper,
        title = { Text("원 승인 거래 선택", style = DsType.listPrimary.copy(fontSize = 16.sp)) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "취소 ${Money.won(cancel.amount)} · ${Times.listStamp(cancel.occurredAt)} 이전의 승인 거래입니다.",
                    style = DsType.listSecondary,
                )
                Spacer(Modifier.height(8.dp))
                if (candidates.isEmpty()) {
                    Text("고를 수 있는 승인 거래가 없습니다.", style = DsType.listSecondary)
                }
                candidates.forEach { candidate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(candidate) }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(candidate.merchant ?: "미확인 가맹점", style = DsType.listPrimary.copy(fontSize = 14.sp), maxLines = 1)
                            Text(
                                "${names[candidate.cardId] ?: "미분류"} · ${Times.listStamp(candidate.occurredAt)}",
                                style = DsType.listSecondary,
                            )
                        }
                        Text(
                            Money.won(candidate.amount),
                            style = DsType.txAmount.copy(color = if (candidate.amount == cancel.amount) Ds.ink else Ds.text3),
                        )
                    }
                    Hairline()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("닫기", style = DsType.listPrimary.copy(color = Ds.textSubtle))
            }
        },
    )
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
    com.msyim.dulssencard.data.model.ChangeType.LINK_CANCEL -> "원 승인 거래에 연결"
    com.msyim.dulssencard.data.model.ChangeType.MANUAL_ENTRY -> "직접 입력"
    com.msyim.dulssencard.data.model.ChangeType.INITIAL_AMOUNT -> "초기 사용액 변경"
    com.msyim.dulssencard.data.model.ChangeType.IMPORT_BACKUP -> "백업에서 가져옴"
}
