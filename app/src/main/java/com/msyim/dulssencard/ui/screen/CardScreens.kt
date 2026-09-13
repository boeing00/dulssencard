package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.ui.CardForm
import com.msyim.dulssencard.ui.component.DaySquareChip
import com.msyim.dulssencard.ui.component.DestructiveButton
import com.msyim.dulssencard.ui.component.DsToggle
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.OutlineButton
import com.msyim.dulssencard.ui.component.PrimaryButton
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.component.UnderlinedField
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/** 카드 설정 목록. */
@Composable
fun CardListScreen(
    cards: List<Card>,
    onAdd: () -> Unit,
    onEdit: (Card) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = Ds.screenPadding,
                        end = Ds.screenPadding,
                        top = 22.dp,
                        bottom = 18.dp,
                    ),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    SectionLabel("카드 설정")
                    Spacer(Modifier.height(8.dp))
                    Text("${cards.size}장 추적 중", style = DsType.h2)
                }
                OutlineButton("+ 카드 추가", onAdd)
            }
            Hairline()
        }

        items(cards, key = { it.id }) { card ->
            Column {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onEdit(card) }
                        .padding(horizontal = Ds.screenPadding, vertical = 16.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            card.nickname,
                            style = DsType.listPrimary.copy(fontSize = 15.sp),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "목표 ${Money.won(card.trackingTarget)}",
                            style = DsType.monoSmall.copy(fontSize = 11.5.sp),
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "인식 키워드 ${card.matchKeywords.joinToString(", ")} · " +
                            "매월 ${card.cycleStartDay}일 시작",
                        style = DsType.footnote,
                    )
                }
                Hairline()
            }
        }

        item {
            Footnote(
                "동일 키워드를 여러 카드에 저장하면 해당 거래는 자동 반영하지 않고 " +
                    "확인 결과함으로 이동합니다.",
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 18.dp,
                    bottom = 28.dp,
                ),
            )
        }
    }
}

/**
 * 카드 등록·편집.
 *
 * 검증: 별명·목표·인식 키워드가 비면 저장하지 않는다(ViewModel.saveCard 에서 처리).
 * 목표는 입력 중 천 단위 쉼표를 자동 삽입한다.
 */
@Composable
fun CardEditScreen(
    form: CardForm,
    isNew: Boolean,
    existingCard: Card?,
    onBack: () -> Unit,
    onChange: ((CardForm) -> CardForm) -> Unit,
    onSave: () -> Unit,
    onDelete: (Card) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 18.dp,
                ),
            ) {
                Text(
                    "← 카드",
                    style = DsType.backLink,
                    modifier = Modifier.clickable(onClick = onBack),
                )
                Spacer(Modifier.height(14.dp))
                Text(if (isNew) "카드 추가" else "카드 편집", style = DsType.h2)
            }
        }

        item {
            Column(
                Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                UnderlinedField(
                    label = "별명 · 필수",
                    value = form.nickname,
                    placeholder = "예: 신한 딥드림",
                    textStyle = DsType.inputText,
                    onValueChange = { v -> onChange { it.copy(nickname = v) } },
                )

                UnderlinedField(
                    label = "월간 추적 목표 · 필수",
                    value = form.target,
                    placeholder = "300,000",
                    textStyle = DsType.inputAmount,
                    keyboardType = KeyboardType.Number,
                    suffix = "원",
                    // 입력 중 천 단위 쉼표 자동 삽입.
                    onValueChange = { v -> onChange { it.copy(target = Money.reformatInput(v)) } },
                )

                UnderlinedField(
                    label = "인식 키워드 · 1개 이상",
                    value = form.keywords,
                    placeholder = "신한, 신한카드",
                    textStyle = DsType.inputText.copy(fontSize = 15.sp),
                    helper = "쉼표로 구분. 결제 문자·앱 알림에서 이 키워드를 찾습니다.",
                    onValueChange = { v -> onChange { it.copy(keywords = v) } },
                )

                UnderlinedField(
                    label = "제외 키워드 · 선택",
                    value = form.exclude,
                    placeholder = "해외, 할부",
                    textStyle = DsType.inputText.copy(fontSize = 15.sp),
                    underlineColor = Ds.line,
                    onValueChange = { v -> onChange { it.copy(exclude = v) } },
                )

                Column {
                    Text("주기 시작일 · 1~28".uppercase(), style = DsType.fieldLabel)
                    Spacer(Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (1..28).forEach { day ->
                            DaySquareChip(day, form.startDay == day) {
                                onChange { it.copy(startDay = day) }
                            }
                        }
                    }
                }

                // 과거 내역은 통지로 복원할 수 없다. 카드사 앱이 이미 계산해 둔 숫자를
                // 옮겨 적게 하고, 그 뒤로 오는 통지를 여기에 더해 나간다.
                UnderlinedField(
                    label = "지금까지 쓴 금액 · 선택",
                    value = form.initialAmount,
                    placeholder = "352,000",
                    textStyle = DsType.inputAmount,
                    keyboardType = KeyboardType.Number,
                    suffix = "원",
                    underlineColor = Ds.line,
                    helper = "이번 달 ${form.startDay}일부터 오늘까지 쓴 금액을 카드사 앱에서 " +
                        "확인해 입력하세요. 앞으로 도착하는 결제 알림은 여기에 자동으로 더해집니다. " +
                        "비워 두면 지금부터 쌓이는 금액만 셉니다.",
                    onValueChange = { v ->
                        onChange { it.copy(initialAmount = Money.reformatInput(v)) }
                    },
                )
            }
        }

        item {
            Column(Modifier.padding(top = 22.dp)) {
                Hairline()
                DefaultToggleRow(
                    title = "기본값: 목표 추적 포함",
                    checked = form.defaultTarget,
                    onToggle = { onChange { it.copy(defaultTarget = !it.defaultTarget) } },
                )
                DefaultToggleRow(
                    title = "기본값: 구매 추적 한도 포함",
                    checked = form.defaultLimit,
                    onToggle = { onChange { it.copy(defaultLimit = !it.defaultLimit) } },
                )
                Footnote(
                    "카드 기본값은 새 거래에만 적용되고 이미 저장된 거래는 바꾸지 않습니다.",
                    Modifier.padding(
                        start = Ds.screenPadding,
                        end = Ds.screenPadding,
                        top = 12.dp,
                    ),
                )
            }
        }

        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 24.dp,
                    bottom = 30.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(if (isNew) "카드 저장" else "변경 저장", onSave)
                if (existingCard != null) {
                    DestructiveButton("카드 삭제", onClick = { onDelete(existingCard) })
                }
            }
        }
    }
}

@Composable
private fun DefaultToggleRow(title: String, checked: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ds.screenPadding, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = DsType.listPrimary.copy(fontSize = 15.sp),
                modifier = Modifier.weight(1f),
            )
            DsToggle(checked, onToggle)
        }
        Hairline()
    }
}

