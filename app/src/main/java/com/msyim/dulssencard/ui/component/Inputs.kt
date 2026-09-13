package com.msyim.dulssencard.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 밑줄 한 줄짜리 입력칸.
 *
 * Material 의 TextField 를 쓰지 않는 이유: 디자인이 밑줄 1px 과 정확한 baseline 정렬을
 * 요구하는데, Material 컨테이너의 기본 패딩·인디케이터를 걷어내는 비용이 직접 그리는 것보다 크다.
 */
@Composable
fun UnderlinedField(
    label: String,
    value: String,
    placeholder: String,
    textStyle: TextStyle,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    suffix: String? = null,
    helper: String? = null,
    underlineColor: Color = Ds.ink,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    Column(modifier.fillMaxWidth()) {
        Text(label.uppercase(), style = DsType.fieldLabel)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = textStyle.copy(color = Ds.text3))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = textStyle,
                    singleLine = true,
                    cursorBrush = SolidColor(Ds.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                    visualTransformation = visualTransformation,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (suffix != null) {
                Spacer(Modifier.width(6.dp))
                Text(suffix, style = DsType.listPrimary.copy(fontWeight = FontWeight.Normal, fontSize = 14.sp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(Ds.hairline).background(underlineColor))
        if (helper != null) {
            Spacer(Modifier.height(8.dp))
            Footnote(helper)
        }
    }
}

/**
 * 비밀번호 입력칸. **화면에 가린다.** 붙여넣기는 막지 않는다 — 비밀번호 관리자에서 긴 비밀번호를
 * 붙여 넣는 사용자를 막으면 오히려 짧은 비밀번호를 쓰게 만든다.
 */
@Composable
fun PasswordField(label: String, value: String, onValueChange: (String) -> Unit, helper: String? = null) {
    UnderlinedField(
        label = label,
        value = value,
        placeholder = "8자 이상",
        textStyle = DsType.inputText,
        onValueChange = onValueChange,
        keyboardType = KeyboardType.Password,
        helper = helper,
        visualTransformation = PasswordVisualTransformation(),
    )
}

/** 확인 대화상자. 되돌릴 수 없는 동작 앞에 둔다. */
@Composable
fun DsConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.paper,
        title = { Text(title, style = DsType.listPrimary.copy(fontSize = 16.sp)) },
        text = { Text(text, style = DsType.listSecondary) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, style = DsType.listPrimary.copy(color = if (destructive) Ds.accent else Ds.ink))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", style = DsType.listPrimary.copy(color = Ds.textSubtle))
            }
        },
    )
}

/**
 * 금액 하나를 고치는 대화상자. 홈의 초기 사용액 빠른 수정, 거래 금액 정정에 쓴다.
 * [allowZero] 가 참이면 0 을 받는다(초기 사용액 0 = 끄기).
 */
@Composable
fun AmountEditDialog(
    title: String,
    message: String,
    initial: Long?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (Long) -> Unit,
    allowZero: Boolean = false,
) {
    var input by remember { mutableStateOf(initial?.takeIf { it != 0L }?.let(Money::grouped) ?: "") }
    val parsed = if (input.isBlank()) (if (allowZero) 0L else null) else Money.parseAmount(input)
    val valid = parsed != null && (parsed > 0L || (allowZero && parsed == 0L))

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.paper,
        title = { Text(title, style = DsType.listPrimary.copy(fontSize = 16.sp)) },
        text = {
            Column {
                Text(message, style = DsType.listSecondary)
                Spacer(Modifier.height(16.dp))
                UnderlinedField(
                    label = "금액",
                    value = input,
                    placeholder = "0",
                    textStyle = DsType.inputAmount,
                    onValueChange = { input = Money.reformatInput(it) },
                    keyboardType = KeyboardType.Number,
                    suffix = "원",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (valid) { onDismiss(); onConfirm(parsed!!) } }, enabled = valid) {
                Text(confirmLabel, style = DsType.listPrimary.copy(color = if (valid) Ds.ink else Ds.text3))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", style = DsType.listPrimary.copy(color = Ds.textSubtle))
            }
        },
    )
}

/** 카드 고르기. 결과함 빠른 처리의 '카드 지정'에 쓴다. */
@Composable
fun CardPickerDialog(
    cards: List<Card>,
    selectedId: String?,
    onDismiss: () -> Unit,
    onPick: (Card) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.paper,
        title = { Text("카드 지정", style = DsType.listPrimary.copy(fontSize = 16.sp)) },
        text = {
            if (cards.isEmpty()) {
                Text("등록한 카드가 없습니다. 카드 탭에서 먼저 추가하세요.", style = DsType.listSecondary)
            } else {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    cards.forEach { card ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onDismiss(); onPick(card) }
                                .padding(vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                card.nickname,
                                style = DsType.listPrimary.copy(fontSize = 15.sp),
                                modifier = Modifier.weight(1f),
                            )
                            if (card.id == selectedId) Text("현재", style = DsType.badge.copy(color = Ds.green))
                        }
                        Hairline()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", style = DsType.listPrimary.copy(color = Ds.textSubtle))
            }
        },
    )
}
