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
    onExportEncrypted: () -> Unit,
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

        item {
            SettingRow(
                title = "암호화 내보내기",
                subtitle = "비밀번호를 잃어버리면 복구할 수 없습니다.",
                badge = "P1",
                onClick = onExportEncrypted,
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
private fun StatusPill(granted: Boolean, disabled: Boolean = false) {
    val (label, color) = when {
        disabled -> "사용 불가" to Ds.text3
        granted -> "허용됨" to Ds.green
        else -> "필요함" to Ds.accent
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
 * 알림 소스 관리.
 *
 * 여기 뜨는 목록은 **알림을 띄운 적 있는 앱의 이름**뿐이다. 알림 내용은 저장하지 않으며,
 * 사용자가 켠 앱에 한해서만 이후 알림의 내용을 읽는다.
 */
@Composable
fun SourceAppsScreen(
    apps: List<SourceApp>,
    onBack: () -> Unit,
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
                Text("← 설정", style = DsType.backLink, modifier = Modifier.clickable(onClick = onBack))
                Spacer(Modifier.height(14.dp))
                Text("알림 소스", style = DsType.h2)
                Spacer(Modifier.height(10.dp))
                Footnote(
                    "켠 앱의 결제 알림만 읽습니다. 끈 앱의 알림은 내용을 보지도 저장하지도 않습니다. " +
                        "결제 문자를 읽으려면 문자 앱을 켜 두어야 합니다. " +
                        "카드사 앱이 목록에 없으면, 그 앱에서 알림이 한 번 온 뒤 다시 확인하세요.",
                )
            }
            Hairline()
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
            items(apps, key = { it.packageName }) { app ->
                SettingRow(
                    title = app.label,
                    subtitle = app.packageName,
                    trailing = { DsToggle(app.enabled, onToggle = { onToggle(app, !app.enabled) }) },
                )
            }
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}
