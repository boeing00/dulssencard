package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.ui.ManualForm
import com.msyim.dulssencard.ui.ManualKind
import com.msyim.dulssencard.ui.component.DsChip
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.PrimaryButton
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.component.UnderlinedField
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 거래 직접 입력.
 *
 *  - **결제 추가** — 현금성 결제(상품권·간편결제 충전 등), 알림이 오지 않은 결제
 *  - **취소 추가** — 취소 알림이 오지 않았을 때
 *  - **금액 보정** — 이유를 특정할 수 없는 차이를 맞출 때(증액·감액)
 *
 * 카드사 앱 총액과 전체가 어긋났다면 이 화면보다 **초기 사용액 다시 맞추기**가 빠르다 — 안내문으로 알린다.
 * 직접 넣은 거래는 확인 없이 바로 합계에 반영된다(사용자가 방금 확인한 값이므로).
 */
@Composable
fun ManualEntryScreen(
    form: ManualForm,
    cards: List<Card>,
    onChange: ((ManualForm) -> ManualForm) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    /** 뒤로가기가 실제로 갈 화면 이름. */
    backLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 18.dp, bottom = 30.dp),
    ) {
        Text("← $backLabel", style = DsType.backLink, modifier = Modifier.clickable(onClick = onBack))
        Spacer(Modifier.height(14.dp))
        Text("거래 직접 입력", style = DsType.h2)
        Spacer(Modifier.height(18.dp))

        SectionLabel("종류")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ManualKind.entries.forEach { kind ->
                DsChip(kind.label, form.kind == kind, onClick = { onChange { it.copy(kind = kind) } })
            }
        }
        Spacer(Modifier.height(8.dp))
        Footnote(
            when (form.kind) {
                ManualKind.PAYMENT -> "알림이 오지 않은 결제나 현금성 결제를 더합니다."
                ManualKind.CANCEL -> "취소 알림이 오지 않았을 때 그만큼 뺍니다."
                ManualKind.ADJUST -> "원인을 특정할 수 없는 차이를 맞춥니다. 카드사 앱 총액 전체가 달라졌다면 " +
                    "카드 상세의 '초기 사용액 다시 맞추기'가 더 정확합니다."
            },
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel("카드")
        Spacer(Modifier.height(10.dp))
        if (cards.isEmpty()) {
            Text("등록한 카드가 없습니다. 카드 탭에서 먼저 추가하세요.", style = DsType.listSecondary)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                cards.forEach { card ->
                    DsChip(
                        label = card.nickname,
                        selected = form.cardId == card.id,
                        onClick = { onChange { it.copy(cardId = card.id) } },
                        pill = false,
                        horizontalPadding = 13.dp,
                        verticalPadding = 11.dp,
                    )
                }
            }
        }

        if (form.kind == ManualKind.ADJUST) {
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DsChip("+ 증액", !form.negative, onClick = { onChange { it.copy(negative = false) } })
                DsChip("− 감액", form.negative, onClick = { onChange { it.copy(negative = true) } })
            }
        }

        Spacer(Modifier.height(22.dp))
        UnderlinedField(
            label = "금액 · 필수",
            value = form.amount,
            placeholder = "12,000",
            textStyle = DsType.inputAmount,
            keyboardType = KeyboardType.Number,
            suffix = "원",
            onValueChange = { v -> onChange { it.copy(amount = Money.reformatInput(v)) } },
        )

        Spacer(Modifier.height(22.dp))
        SectionLabel("날짜")
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "오늘", 1 to "어제", 2 to "2일 전", 3 to "3일 전").forEach { (days, label) ->
                DsChip(label, form.daysAgo == days, onClick = { onChange { it.copy(daysAgo = days) } })
            }
        }

        Spacer(Modifier.height(22.dp))
        UnderlinedField(
            label = "가맹점·메모 · 선택",
            value = form.merchant,
            placeholder = "예: 전통시장",
            textStyle = DsType.inputText,
            underlineColor = Ds.line,
            onValueChange = { v -> onChange { it.copy(merchant = v.take(40)) } },
        )

        Spacer(Modifier.height(26.dp))
        PrimaryButton(
            label = "저장",
            onClick = onSave,
            enabled = form.cardId != null && (Money.parseAmount(form.amount) ?: 0L) > 0L,
        )
        Spacer(Modifier.height(10.dp))
        Footnote("직접 넣은 거래는 확인 없이 바로 합계에 반영되고, 거래 상세의 변경 기록에 '직접 입력'으로 남습니다. 저장 직후 되돌릴 수 있습니다.")
    }
}
