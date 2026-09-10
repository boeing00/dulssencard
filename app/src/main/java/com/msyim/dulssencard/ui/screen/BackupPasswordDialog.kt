package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.crypto.BackupCrypto
import com.msyim.dulssencard.ui.BackupPrompt
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 백업 비밀번호 입력.
 *
 * 백업 파일은 **이 비밀번호로만** 열린다. DB 암호(Android Keystore 봉인)를 쓸 수 없기
 * 때문이다 — 그 키는 기기 밖으로 나갈 수 없어서, 기기를 바꾸면 백업이 벽돌이 된다.
 * 대신 잊으면 복구 수단이 없으므로 그 사실을 화면에서 분명히 말한다.
 */
@Composable
fun BackupPasswordDialog(
    prompt: BackupPrompt,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }

    val exporting = prompt == BackupPrompt.EXPORT
    val tooShort = password.length < BackupCrypto.MIN_PASSWORD_LENGTH
    val mismatch = exporting && repeat != password
    val canConfirm = !tooShort && !mismatch

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Ds.paper,
        title = {
            Text(
                if (exporting) "백업 비밀번호를 정하세요" else "백업 비밀번호를 넣으세요",
                style = DsType.listPrimary.copy(fontSize = 16.sp),
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PasswordField(
                    value = password,
                    placeholder = "${BackupCrypto.MIN_PASSWORD_LENGTH}자 이상",
                    onValueChange = { password = it },
                )
                if (exporting) {
                    Spacer(Modifier.height(16.dp))
                    PasswordField(
                        value = repeat,
                        placeholder = "한 번 더",
                        onValueChange = { repeat = it },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Footnote(
                    when {
                        exporting && mismatch && repeat.isNotEmpty() -> "두 번 넣은 값이 다릅니다."
                        exporting ->
                            "이 앱은 네트워크를 쓰지 않아 비밀번호 사본이 어디에도 없습니다. " +
                                "잊으면 백업 파일을 영영 열 수 없습니다."
                        else -> "내보낼 때 정한 비밀번호입니다."
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (canConfirm) onConfirm(password) },
                enabled = canConfirm,
            ) {
                Text(
                    if (exporting) "내보내기" else "불러오기",
                    style = DsType.listPrimary.copy(
                        color = if (canConfirm) Ds.accent else Ds.text3,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", style = DsType.listPrimary.copy(color = Ds.textSubtle))
            }
        },
    )
}

@Composable
private fun PasswordField(value: String, placeholder: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth()) {
            if (value.isEmpty()) {
                Text(placeholder, style = DsType.listSecondary.copy(color = Ds.text3))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = DsType.listPrimary,
                singleLine = true,
                cursorBrush = SolidColor(Ds.ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(Ds.hairline).background(Ds.ink))
    }
}
