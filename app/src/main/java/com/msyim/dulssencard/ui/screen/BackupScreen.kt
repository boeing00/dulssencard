package com.msyim.dulssencard.ui.screen

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.msyim.dulssencard.backup.AutoBackupStore
import com.msyim.dulssencard.backup.ImportPlanner
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ui.ImportStage
import com.msyim.dulssencard.ui.component.DsChip
import com.msyim.dulssencard.ui.component.DsConfirmDialog
import com.msyim.dulssencard.ui.component.DsTextButton
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.OutlineButton
import com.msyim.dulssencard.ui.component.PasswordField
import com.msyim.dulssencard.ui.component.PrimaryButton
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType
import java.time.LocalDate

/**
 * 암호화 백업·가져오기·자동 백업.
 *
 * 파일 위치는 **시스템 파일 선택기**가 정한다. 저장소 권한을 요청하지 않고, 사용자가 고른 한 곳에만 쓴다.
 * 비밀번호는 이 화면의 입력칸에만 있다가 저장·열기 순간에 넘겨지고, 앱 어디에도 저장되지 않는다.
 */
@Composable
fun BackupScreen(
    busy: Boolean,
    importStage: ImportStage,
    autoBackups: List<AutoBackupStore.Entry>,
    passwordProblem: (String, String) -> String?,
    onBack: () -> Unit,
    /** 뒤로가기가 실제로 갈 화면 이름. */
    backLabel: String,
    onExport: (Uri, String) -> Unit,
    onImportPicked: (Uri) -> Unit,
    onOpenImport: (String) -> Unit,
    onSelectMode: (ImportPlanner.Mode) -> Unit,
    onApplyImport: () -> Unit,
    onCancelImport: () -> Unit,
    onRestoreAuto: (AutoBackupStore.Entry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var confirmApply by remember { mutableStateOf(false) }
    var restoreTarget by remember { mutableStateOf<AutoBackupStore.Entry?>(null) }

    val problem = if (password.isEmpty() && confirm.isEmpty()) null else passwordProblem(password, confirm)

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        if (uri != null) {
            onExport(uri, password)
            password = ""
            confirm = ""
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importPassword = ""
            onImportPicked(uri)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 30.dp),
    ) {
        Column(Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 18.dp, bottom = 16.dp)) {
            Text("← $backLabel", style = DsType.backLink, modifier = Modifier.clickable(onClick = onBack))
            Spacer(Modifier.height(14.dp))
            Text("백업", style = DsType.h2)
            Spacer(Modifier.height(8.dp))
            Footnote(
                "백업 파일은 비밀번호로 암호화됩니다(Argon2id · AES-256-GCM). 파일이 변조되면 열리지 않습니다. " +
                    "파일은 기기 밖으로 자동 전송되지 않고, 저장 위치는 직접 고릅니다.",
            )
        }
        Hairline()

        // ---------------------------------------------------------------- 내보내기
        Column(Modifier.padding(horizontal = Ds.screenPadding, vertical = 20.dp)) {
            SectionLabel("암호화 백업 내보내기")
            Spacer(Modifier.height(14.dp))
            PasswordField("비밀번호", password, { password = it })
            Spacer(Modifier.height(16.dp))
            PasswordField("비밀번호 확인", confirm, { confirm = it }, helper = problem)
            Spacer(Modifier.height(14.dp))
            Warning("비밀번호를 잃으면 이 백업은 누구도 열 수 없습니다. 앱 개발자도 복구해 드릴 수 없습니다.")
            Spacer(Modifier.height(14.dp))
            PrimaryButton(
                label = if (busy) "암호화하는 중…" else "파일로 저장",
                onClick = { createDocument.launch("dulssencard-${LocalDate.now()}.dscb") },
                enabled = !busy && password.isNotEmpty() && problem == null,
            )
        }
        Hairline()

        // ---------------------------------------------------------------- 가져오기
        Column(Modifier.padding(horizontal = Ds.screenPadding, vertical = 20.dp)) {
            SectionLabel("백업 가져오기")
            Spacer(Modifier.height(12.dp))
            when (importStage) {
                ImportStage.Idle -> {
                    Footnote("가져오기 전에 지금 데이터가 이 기기에 자동으로 백업됩니다. 잘못 가져와도 아래 '자동 백업'에서 되돌릴 수 있습니다.")
                    Spacer(Modifier.height(12.dp))
                    OutlineButton("백업 파일 고르기", { openDocument.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                }
                ImportStage.Working -> Text("처리하는 중… (비밀번호 확인에 몇 초 걸립니다)", style = DsType.listSecondary)
                is ImportStage.NeedPassword -> {
                    PasswordField("백업 비밀번호", importPassword, { importPassword = it }, helper = importStage.error)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton("열기", { onOpenImport(importPassword); importPassword = "" }, enabled = importPassword.isNotEmpty())
                    DsTextButton("취소", onCancelImport)
                }
                is ImportStage.Preview -> {
                    ImportPreview(importStage, onSelectMode)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(
                        if (importStage.mode == ImportPlanner.Mode.REPLACE) "통째로 바꾸기" else "합치기",
                        { confirmApply = true },
                    )
                    DsTextButton("취소", onCancelImport)
                }
                is ImportStage.Failed -> {
                    Text(importStage.message, style = DsType.listPrimary.copy(color = Ds.accent, fontSize = 14.sp))
                    Spacer(Modifier.height(10.dp))
                    OutlineButton("다른 파일 고르기", { onCancelImport(); openDocument.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                }
            }
        }
        Hairline()

        // ---------------------------------------------------------------- 자동 백업
        Column(Modifier.padding(horizontal = Ds.screenPadding, vertical = 20.dp)) {
            SectionLabel("자동 백업 · 가져오기 직전 상태")
            Spacer(Modifier.height(8.dp))
            Footnote("가져오기·되돌리기를 할 때마다 직전 데이터를 이 기기 전용 키로 암호화해 최근 3개까지 둡니다. 다른 기기에서는 열리지 않고, '전체 삭제' 때 함께 지워집니다.")
            Spacer(Modifier.height(10.dp))
            if (autoBackups.isEmpty()) {
                Text("아직 자동 백업이 없습니다.", style = DsType.listSecondary)
            } else {
                autoBackups.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = importStage !is ImportStage.Working) { restoreTarget = entry }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(Times.logStamp(entry.createdAt), style = DsType.listPrimary.copy(fontSize = 14.sp))
                            Text("${Times.ago(entry.createdAt)} · ${"%,d".format(entry.sizeBytes / 1024 + 1)}KB", style = DsType.listSecondary)
                        }
                        Text("이 시점으로", style = DsType.link)
                    }
                    Hairline()
                }
            }
        }
    }

    if (confirmApply) {
        val preview = importStage as? ImportStage.Preview
        if (preview != null) {
            val replace = preview.mode == ImportPlanner.Mode.REPLACE
            DsConfirmDialog(
                title = if (replace) "지금 데이터를 백업으로 바꿀까요?" else "백업을 합칠까요?",
                text = if (replace) {
                    "이 기기의 카드 ${preview.summary.localCardsRemoved}장 · 거래 ${preview.summary.localTxnsRemoved}건이 백업 내용으로 바뀝니다. " +
                        "적용 직전 상태는 자동 백업으로 남습니다."
                } else {
                    "같은 결제는 한 건으로 합치고, 더 최근에 고친 쪽을 씁니다. 적용 직전 상태는 자동 백업으로 남습니다."
                },
                confirmLabel = if (replace) "바꾸기" else "합치기",
                destructive = replace,
                onConfirm = onApplyImport,
                onDismiss = { confirmApply = false },
            )
        } else {
            confirmApply = false
        }
    }

    restoreTarget?.let { entry ->
        DsConfirmDialog(
            title = "${Times.logStamp(entry.createdAt)} 시점으로 되돌릴까요?",
            text = "지금 데이터가 그 시점의 데이터로 바뀝니다. 되돌리기 직전 상태도 먼저 자동 백업됩니다.",
            confirmLabel = "되돌리기",
            destructive = true,
            onConfirm = { onRestoreAuto(entry) },
            onDismiss = { restoreTarget = null },
        )
    }
}

@Composable
private fun ImportPreview(preview: ImportStage.Preview, onSelectMode: (ImportPlanner.Mode) -> Unit) {
    val s = preview.summary
    val payload = preview.payload
    Text(
        "${Times.logStamp(payload.createdAt)}에 만든 백업 · 카드 ${payload.cards.size}장 · 거래 ${payload.txns.size}건",
        style = DsType.listPrimary.copy(fontSize = 14.sp),
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DsChip("합치기", preview.mode == ImportPlanner.Mode.MERGE, onClick = { onSelectMode(ImportPlanner.Mode.MERGE) })
        DsChip("통째로 바꾸기", preview.mode == ImportPlanner.Mode.REPLACE, onClick = { onSelectMode(ImportPlanner.Mode.REPLACE) })
    }
    Spacer(Modifier.height(12.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.radiusBanner))
            .background(Ds.paper2)
            .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radiusBanner))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (preview.mode == ImportPlanner.Mode.MERGE) {
            PreviewLine("카드", "추가 ${s.cardsAdded} · 갱신 ${s.cardsUpdated} · 유지 ${s.cardsKept}")
            PreviewLine("거래", "추가 ${s.txnsAdded} · 갱신 ${s.txnsUpdated} · 같은 결제라 유지 ${s.txnsKept}")
            PreviewLine("설정", "바뀌는 값 ${s.settingsChanged}")
            if (s.txnsUnassigned > 0) PreviewLine("미분류", "카드를 못 찾은 거래 ${s.txnsUnassigned}건은 확인 필요로")
            Footnote("같은 결제(지문 일치)는 한 건으로 합치고, 둘 다 있으면 더 최근에 고친 쪽을 씁니다. 수정 시각이 같으면 이 기기 값을 지킵니다. 알림 소스 설정은 이 기기 것을 유지합니다.")
        } else {
            PreviewLine("사라짐", "이 기기의 카드 ${s.localCardsRemoved}장 · 거래 ${s.localTxnsRemoved}건")
            PreviewLine("들어옴", "카드 ${s.cardsAdded}장 · 거래 ${s.txnsAdded}건")
            Footnote("새 기기로 옮길 때 씁니다. 알림 소스는 켬/끔만 가져오고 이 기기의 수집 기록은 유지합니다.")
        }
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = DsType.listSecondary, modifier = Modifier.padding(end = 10.dp))
        Text(value, style = DsType.listPrimary.copy(fontSize = 13.5.sp), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Warning(text: String) {
    Text(
        text,
        style = DsType.listSecondary.copy(color = Ds.accentPress),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.radius))
            .background(Ds.accentTint)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    )
}
