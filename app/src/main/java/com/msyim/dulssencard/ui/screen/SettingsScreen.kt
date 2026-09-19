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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.domain.Cycle
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ui.component.DaySquareChip
import com.msyim.dulssencard.ui.component.DsToggle
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.OutlineBadge
import com.msyim.dulssencard.ui.component.SectionLabel
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/** 설정 · 내 데이터. */
@Composable
fun SettingsScreen(
    limitInput: String,
    limitCycleStartDay: Int,
    autoCollectEnabled: Boolean,
    hasNotificationAccess: Boolean,
    smsAppEnabled: Boolean,
    hasDefaultSmsApp: Boolean,
    enabledSourceCount: Int,
    onLimitInputChange: (String) -> Unit,
    onLimitCommit: () -> Unit,
    onLimitCycleStartDayChange: (Int) -> Unit,
    onToggleAutoCollect: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenSourceApps: () -> Unit,
    onOpenBackup: () -> Unit,
    /** 한 번 결제 상태. */
    pro: com.msyim.dulssencard.billing.ProUnlock.State,
    onBuyPro: () -> Unit,
    onRestorePro: () -> Unit,
    onImportFromImage: () -> Unit,
    onWipe: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmWipe by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 22.dp,
                    bottom = 18.dp,
                ),
            ) {
                SectionLabel("설정")
                Spacer(Modifier.height(8.dp))
                Text("한도와 내 데이터", style = DsType.h2)
            }
        }

        item {
            LimitBox(
                limitInput = limitInput,
                cycleStartDay = limitCycleStartDay,
                onChange = onLimitInputChange,
                onCommit = onLimitCommit,
                onCycleStartDayChange = onLimitCycleStartDayChange,
            )
        }

        item { Spacer(Modifier.height(22.dp)); Hairline() }

        // ---------------------------------------------------------- 수집 경로

        item { SettingsGroupHeader("결제 모으기") }

        item {
            SettingRow(
                title = "자동 집계",
                subtitle = if (autoCollectEnabled) {
                    "새 결제를 기기 안에서 자동 반영합니다"
                } else {
                    "제한 모드 — 열람·수동 보정만 가능합니다"
                },
                trailing = { DsToggle(autoCollectEnabled, onToggle = { onToggleAutoCollect(!autoCollectEnabled) }) },
            )
        }

        item {
            SettingRow(
                title = "알림 접근",
                subtitle = if (hasNotificationAccess) {
                    "허용됨 — 켠 앱 ${enabledSourceCount}개의 알림만 읽습니다"
                } else {
                    "이 앱의 유일한 수집 경로입니다 — 눌러서 시스템 설정에서 켜세요"
                },
                onClick = onOpenNotificationAccess,
                trailing = { StatusPill(granted = hasNotificationAccess) },
            )
        }

        item {
            SettingRow(
                title = "결제 문자 수집",
                subtitle = when {
                    !hasDefaultSmsApp -> "이 기기에는 기본 문자 앱이 없습니다"
                    smsAppEnabled -> "문자 앱 알림에서 결제 문자를 읽습니다"
                    else -> "문자 앱이 꺼져 있습니다 — 알림 소스에서 켜세요"
                },
                onClick = onOpenSourceApps,
                trailing = { StatusPill(granted = smsAppEnabled, disabled = !hasDefaultSmsApp) },
            )
        }

        item {
            SettingRow(
                title = "알림 소스 관리",
                subtitle = "어떤 앱의 알림을 읽을지 고릅니다",
                onClick = onOpenSourceApps,
                trailing = { Text("→", style = DsType.listPrimary.copy(color = Ds.textSubtle)) },
            )
        }

        // ---------------------------------------------------------- P1 · 내 데이터

        item { SettingsGroupHeader("카드 무제한") }

        item {
            SettingRow(
                title = when {
                    pro.owned -> "카드 무제한 사용 중"
                    pro.pending -> "결제 확인을 기다리는 중"
                    else -> "카드 무제한 풀기" + (pro.price?.let { " · $it" } ?: "")
                },
                subtitle = when {
                    pro.owned -> "고맙습니다. 카드를 원하는 만큼 등록할 수 있습니다."
                    pro.pending -> "결제가 확정되면 자동으로 풀립니다."
                    else -> "무료로는 카드 ${com.msyim.dulssencard.domain.FreeTier.FREE_CARD_LIMIT}장까지 등록합니다. " +
                        "한 번 결제하면 제한이 없어집니다. 수집·집계·백업은 무료 그대로입니다."
                },
                onClick = if (pro.owned || pro.pending) null else onBuyPro,
            )
        }

        if (!pro.owned) {
            item {
                SettingRow(
                    title = "구매 복원",
                    subtitle = "재설치했거나 같은 Google 계정의 다른 기기에서 샀다면 눌러 주세요.",
                    onClick = onRestorePro,
                )
            }
        }

        item { SettingsGroupHeader("내 데이터") }

        item {
            SettingRow(
                title = "백업 · 복원",
                subtitle = "비밀번호를 건 파일로 내보내고, 다른 기기에서 그 파일로 되살립니다.",
                onClick = onOpenBackup,
                trailing = { Text("→", style = DsType.listPrimary.copy(color = Ds.textSubtle)) },
            )
        }

        item {
            SettingRow(
                title = "이미지에서 불러오기",
                subtitle = "카드앱·문자·카카오톡 캡처 화면에서 지난 결제를 읽어옵니다. " +
                    "문자 권한 대신 쓰는 방식이라 권한을 요구하지 않습니다.",
                onClick = onImportFromImage,
            )
        }

        item {
            SettingRow(
                title = "로컬 데이터 전체 삭제",
                subtitle = "카드·거래·보정 기록이 기기에서 지워집니다.",
                titleColor = Ds.accent,
                onClick = { confirmWipe = true },
            )
        }

        item {
            Footnote(
                "이 앱은 어떤 권한도 매니페스트에 선언하지 않습니다. 결제 문자·카드사 앱 푸시·카카오 결제 알림을 " +
                    "모두 알림 접근으로 읽고, 켠 앱의 알림만 봅니다. " +
                    "로컬 DB 는 Android Keystore 기반 키로 암호화되며, 문자·알림 본문, 주민등록번호, " +
                    "카드번호, CVC 는 저장하지 않습니다. 네트워크 요청도 하지 않습니다.",
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 18.dp,
                    bottom = 28.dp,
                ),
            )
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            containerColor = Ds.paper,
            title = { Text("로컬 데이터를 모두 지울까요?", style = DsType.listPrimary.copy(fontSize = 16.sp)) },
            text = {
                Text(
                    "카드 설정, 거래, 보정 기록이 이 기기에서 지워집니다. " +
                        "삭제 직후 4.2초 안에는 되돌릴 수 있습니다.",
                    style = DsType.listSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmWipe = false; onWipe() }) {
                    Text("삭제", style = DsType.listPrimary.copy(color = Ds.accent))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmWipe = false }) {
                    Text("취소", style = DsType.listPrimary.copy(color = Ds.textSubtle))
                }
            },
        )
    }
}

@Composable
private fun LimitBox(
    limitInput: String,
    cycleStartDay: Int,
    onChange: (String) -> Unit,
    onCommit: () -> Unit,
    onCycleStartDayChange: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Ds.screenPadding)
            .clip(RoundedCornerShape(Ds.radiusBanner))
            .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radiusBanner))
            .padding(16.dp),
    ) {
        Text("개인 구매 추적 한도".uppercase(), style = DsType.fieldLabel)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (limitInput.isEmpty()) {
                    Text("1,000,000", style = DsType.inputLimitAmount.copy(color = Ds.text3))
                }
                BasicTextField(
                    value = limitInput,
                    onValueChange = onChange,
                    textStyle = DsType.inputLimitAmount,
                    singleLine = true,
                    cursorBrush = SolidColor(Ds.ink),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text("원", style = DsType.listPrimary.copy(fontSize = 15.sp))
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(Ds.hairline).background(Ds.ink))
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(Ds.radius))
                .background(Ds.ink)
                .clickable(onClick = onCommit)
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text("한도 저장", style = DsType.listPrimary.copy(fontSize = 13.sp, color = Ds.paper))
        }

        // 일자 칩 28개를 펼쳐 두면 4줄을 먹어서, 아래 '암호화 내보내기'·'최근 14일 내역
        // 불러오기'·'로컬 데이터 전체 삭제'가 첫 화면 밖으로 밀려 없는 것처럼 보인다.
        // 평소엔 현재 값만 한 줄로 보여 주고 누를 때만 펼친다.
        var dayPickerOpen by rememberSaveable { mutableStateOf(false) }

        Spacer(Modifier.height(16.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { dayPickerOpen = !dayPickerOpen },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("한도 주기 시작일".uppercase(), style = DsType.fieldLabel, modifier = Modifier.weight(1f))
            Text(
                "매월 ${Cycle.normalizeStartDay(cycleStartDay)}일",
                style = DsType.monoSmall.copy(fontSize = 12.sp, color = Ds.ink),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (dayPickerOpen) "▲" else "▼",
                style = DsType.listSecondary.copy(color = Ds.text3),
            )
        }

        if (dayPickerOpen) {
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                (1..28).forEach { day ->
                    DaySquareChip(day, Cycle.normalizeStartDay(cycleStartDay) == day) {
                        onCycleStartDayChange(day)
                        dayPickerOpen = false
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Footnote(
            "기본값 1,000,000원은 제안값일 뿐이며 언제든 바꿀 수 있습니다. " +
                "카드 주기와 독립적으로 계산됩니다.",
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    titleColor: androidx.compose.ui.graphics.Color = Ds.ink,
    badge: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = Ds.screenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = DsType.listPrimary.copy(fontSize = 15.sp, color = titleColor))
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        OutlineBadge(badge)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = DsType.listSecondary)
            }
            if (trailing != null) {
                Spacer(Modifier.width(12.dp))
                trailing()
            }
        }
        Hairline()
    }
}

@Composable
private fun StatusPill(
    granted: Boolean,
    disabled: Boolean = false,
    onLabel: String = "허용됨",
    offLabel: String = "필요함",
) {
    val (label, color) = when {
        disabled -> "사용 불가" to Ds.text3
        granted -> onLabel to Ds.green
        else -> offLabel to Ds.accent
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(Ds.radiusPill))
            .border(Ds.hairline, color, RoundedCornerShape(Ds.radiusPill))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, style = DsType.badge.copy(color = color))
    }
}

/**
 * 알림 소스 관리 · 수집 진단.
 *
 * 자동 집계가 왜 안 되는지 **사용자가 스스로 알아낼 수 있게** 한다. 위에서부터 차례로 보면 원인이 나온다:
 * 알림 접근이 켜져 있나 → 자동 집계가 켜져 있나 → 그 앱이 켜져 있나 → 알림이 오고 있나 →
 * 결제로 읽히고 있나(인식/실패 수).
 *
 * 여기 뜨는 것은 **앱 이름과 시각·개수뿐**이다. 알림 내용은 저장하지 않으며,
 * 사용자가 켠 앱에 한해서만 이후 알림의 내용을 읽는다.
 */
@Composable
fun SourceAppsScreen(
    apps: List<SourceApp>,
    hasNotificationAccess: Boolean,
    autoCollectEnabled: Boolean,
    defaultSmsPackage: String?,
    onOpenNotificationAccess: () -> Unit,
    onBack: () -> Unit,
    /** 뒤로가기가 실제로 갈 화면 이름. */
    backLabel: String,
    onToggle: (SourceApp, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Column(
                Modifier.padding(
                    start = Ds.screenPadding,
                    end = Ds.screenPadding,
                    top = 18.dp,
                    bottom = 18.dp,
                ),
            ) {
                Text("← $backLabel", style = DsType.backLink, modifier = Modifier.clickable(onClick = onBack))
                Spacer(Modifier.height(14.dp))
                Text("알림 소스 · 수집 진단", style = DsType.h2)
                Spacer(Modifier.height(10.dp))
                Footnote(
                    "켠 앱의 결제 알림만 읽습니다. 끈 앱의 알림은 내용을 보지도 저장하지도 않습니다. " +
                        "여기 보이는 것은 시각과 개수뿐입니다.",
                )
            }
            Hairline()
        }

        // ---- 권한 상태
        item {
            SettingRow(
                title = "알림 접근",
                subtitle = if (hasNotificationAccess) "허용됨 - 수집할 수 있습니다" else "꺼져 있어 아무 알림도 읽지 못합니다. 눌러서 켜세요",
                onClick = onOpenNotificationAccess,
                trailing = { StatusPill(hasNotificationAccess) },
            )
        }
        item {
            SettingRow(
                title = "자동 집계",
                subtitle = if (autoCollectEnabled) "새 결제를 자동으로 반영합니다" else "꺼짐 - 설정에서 켜야 새 결제가 반영됩니다",
                trailing = { StatusPill(autoCollectEnabled, onLabel = "켜짐", offLabel = "꺼짐") },
            )
        }

        if (apps.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 30.dp, vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("아직 감지된 앱이 없습니다", style = DsType.listPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "알림 접근을 켜면 알림을 띄운 앱이 여기에 나타납니다.",
                        style = DsType.listSecondary,
                    )
                }
            }
        } else {
            item {
                SectionLabel(
                    "읽을 앱",
                    Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 20.dp, bottom = 4.dp),
                )
            }
            items(apps, key = { it.packageName }) { app ->
                SourceDiagnosticRow(
                    app = app,
                    isDefaultSms = app.packageName == defaultSmsPackage,
                    onToggle = { onToggle(app, !app.enabled) },
                )
            }
        }

        item {
            Footnote(
                "실패는 결제 알림처럼 보이는데 금액·시각을 읽지 못한 경우만 셉니다(대화·광고는 세지 않습니다). " +
                    "실패가 계속 늘면 카드사가 문구 형식을 바꾼 것일 수 있습니다. 카운터는 일주일마다 새로 셉니다.",
                Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 16.dp, bottom = 28.dp),
            )
        }
    }
}

@Composable
private fun SourceDiagnosticRow(app: SourceApp, isDefaultSms: Boolean, onToggle: () -> Unit) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ds.screenPadding, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.label, style = DsType.listPrimary.copy(fontSize = 15.sp))
                    if (isDefaultSms) {
                        Spacer(Modifier.width(8.dp))
                        OutlineBadge("기본 문자 앱", color = Ds.green)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append("마지막 알림 ")
                        append(if (app.lastSeenAt > 0) Times.ago(app.lastSeenAt) else "없음")
                        append(" · 결제 인식 ")
                        append(if (app.lastPaymentAt > 0) Times.ago(app.lastPaymentAt) else "없음")
                    },
                    style = DsType.listSecondary,
                )
                if (app.enabled && app.countsSince > 0) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${Times.ago(app.countsSince)}부터 인식 ${app.recognizedCount} · 실패 ${app.failedCount}",
                        style = DsType.listSecondary.copy(color = if (app.failedCount > 0) Ds.accent else Ds.textSubtle),
                    )
                } else if (!app.enabled) {
                    Spacer(Modifier.height(2.dp))
                    Text("꺼져 있어 내용을 읽지 않습니다", style = DsType.listSecondary.copy(color = Ds.text3))
                }
            }
            Spacer(Modifier.width(12.dp))
            DsToggle(app.enabled, onToggle = onToggle)
        }
        Hairline()
    }
}

/** 설정 묶음 제목. 항목이 평면으로 늘어서 있으면 무엇이 어디 있는지 훑어보기 어렵다. */
@Composable
private fun SettingsGroupHeader(title: String) {
    SectionLabel(
        title,
        Modifier.padding(start = Ds.screenPadding, end = Ds.screenPadding, top = 22.dp, bottom = 4.dp),
    )
}
