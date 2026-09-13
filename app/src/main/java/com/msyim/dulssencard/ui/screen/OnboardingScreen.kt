package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ui.CardForm
import com.msyim.dulssencard.ui.component.AmountEditDialog
import com.msyim.dulssencard.ui.component.DsChip
import com.msyim.dulssencard.ui.component.DsTextButton
import com.msyim.dulssencard.ui.component.DsToggle
import com.msyim.dulssencard.ui.component.Footnote
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.OutlineButton
import com.msyim.dulssencard.ui.component.PrimaryButton
import com.msyim.dulssencard.ui.component.UnderlinedField
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 온보딩 네 단계: **알림 접근 허용 → 읽을 앱 선택 → 카드 등록 → 초기 사용액 입력.**
 *
 * 한 화면에서 전부 설명하면 사용자는 무엇을 해야 끝나는지 모른다. 단계마다 할 일이 하나이고,
 * 끝나면 첫 결제 알림이 오기 전에도 홈에 의미 있는 숫자가 떠 있다(초기 사용액 덕분에).
 *
 * PRD §6.1: 자동 집계의 가치와 데이터 처리를 먼저 설명하고, **동의한 경우에만** 권한 설정으로 보낸다.
 * 알림 접근은 앱이 요청할 수 없어 시스템 설정으로 보내고, 돌아오면 상태를 다시 읽는다(MainActivity.onResume).
 */
@Composable
fun OnboardingScreen(
    step: Int,
    hasNotificationAccess: Boolean,
    sourceApps: List<SourceApp>,
    defaultSmsPackage: String?,
    cards: List<Card>,
    cardRows: List<Aggregator.CardProgress>,
    cardForm: CardForm,
    onOpenNotificationAccess: () -> Unit,
    onToggleSource: (SourceApp, Boolean) -> Unit,
    onCardFormChange: ((CardForm) -> CardForm) -> Unit,
    onSaveCard: () -> Unit,
    onSetInitialAmount: (Card, Long) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onFinish: (enableCollection: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 26.dp, end = 26.dp, top = 30.dp, bottom = 26.dp),
    ) {
        StepHeader(step)
        Spacer(Modifier.height(18.dp))
        when (step) {
            0 -> AccessStep(hasNotificationAccess, onOpenNotificationAccess, onNext, onLimited = { onFinish(false) })
            1 -> SourcesStep(sourceApps, defaultSmsPackage, onToggleSource, onNext, onBack)
            2 -> CardStep(sourceApps, cards, cardForm, onCardFormChange, onSaveCard, onNext, onBack)
            else -> InitialAmountStep(cardRows, onSetInitialAmount, onFinish = { onFinish(true) }, onBack)
        }
    }
}

private val STEP_TITLES = listOf("알림 접근", "읽을 앱", "카드 등록", "초기 사용액")

@Composable
private fun StepHeader(step: Int) {
    Text(
        "STEP ${step + 1} / ${STEP_TITLES.size} · ${STEP_TITLES[step.coerceIn(0, STEP_TITLES.lastIndex)]}",
        style = DsType.kicker.copy(fontSize = 10.sp, letterSpacing = 0.22.em, color = Ds.accent),
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        STEP_TITLES.indices.forEach { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(if (index <= step) Ds.ink else Ds.line),
            )
        }
    }
}

// ------------------------------------------------------------------ 1. 알림 접근

@Composable
private fun AccessStep(
    hasAccess: Boolean,
    onOpenSettings: () -> Unit,
    onNext: () -> Unit,
    onLimited: () -> Unit,
) {
    Text("결제 알림을\n카드 목표로\n자동 정리할게요", style = DsType.onboardTitle)
    Spacer(Modifier.height(16.dp))
    Text(
        buildAnnotatedString {
            append("덜쎈카드는 새로 도착하는 결제 문자, 카드사 앱 푸시, 카카오톡 결제 알림에서 카드사·금액·승인/취소·거래 시각을 ")
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("기기 안에서만") }
            append(" 추출해 개인 추적값을 계산합니다.")
        },
        style = DsType.body,
    )
    Spacer(Modifier.height(18.dp))
    NoticeList()
    Spacer(Modifier.height(20.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("알림 접근", style = DsType.listPrimary.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text(
            if (hasAccess) "허용됨" else "아직 꺼져 있음",
            style = DsType.badge.copy(color = if (hasAccess) Ds.green else Ds.accent),
        )
    }
    Spacer(Modifier.height(14.dp))
    if (hasAccess) {
        PrimaryButton("다음", onNext)
    } else {
        PrimaryButton("알림 접근 켜러 가기", onOpenSettings)
        Spacer(Modifier.height(6.dp))
        Footnote("시스템 설정에서 '덜쎈카드'를 켠 뒤 뒤로 오면 다음 단계로 넘어갈 수 있습니다.")
    }
    Spacer(Modifier.height(4.dp))
    DsTextButton("나중에 하기 (제한 모드로 시작)", onLimited)
}

@Composable
private fun NoticeList() {
    val notices = listOf(
        "✓" to "문자·알림 원문과 주민등록번호는 저장·전송하지 않습니다",
        "✓" to "광고·마케팅·분석 목적으로 사용하지 않습니다",
        "✓" to "문자 권한을 요구하지 않습니다. 알림 접근만 쓰며, 사용자가 켠 앱의 알림만 읽습니다",
        "!" to "집계값은 사용자가 설정한 규칙에 따른 개인 추적값이며 카드사 공식 실적이 아닙니다",
    )
    Column(Modifier.fillMaxWidth()) {
        notices.forEachIndexed { index, (glyph, text) ->
            val warn = glyph == "!"
            Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(glyph, style = DsType.listPrimary.copy(color = if (warn) Ds.text3 else Ds.accent), modifier = Modifier.width(14.dp))
                Text(
                    text,
                    style = DsType.listPrimary.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp,
                        color = if (warn) Ds.textSubtle else Ds.text2,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            if (index != notices.lastIndex) Hairline()
        }
    }
}

// ------------------------------------------------------------------ 2. 읽을 앱

@Composable
private fun SourcesStep(
    apps: List<SourceApp>,
    defaultSmsPackage: String?,
    onToggle: (SourceApp, Boolean) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    Text("어느 앱의\n알림을 읽을까요?", style = DsType.onboardTitle)
    Spacer(Modifier.height(14.dp))
    Text(
        "켠 앱의 결제 알림만 읽고, 끈 앱의 알림은 내용을 보지 않습니다. 설치된 카드사 앱과 기본 문자 앱은 미리 켜 두었습니다. " +
            "카드 결제를 카카오톡 알림톡으로 받는다면 카카오톡을 켜세요.",
        style = DsType.body,
    )
    Spacer(Modifier.height(16.dp))
    if (apps.isEmpty()) {
        Text("설치된 카드사 앱을 찾지 못했습니다. 문자로 결제 알림을 받는다면 그대로 다음으로 가세요.", style = DsType.listSecondary)
    }
    apps.sortedWith(compareByDescending<SourceApp> { it.enabled }.thenBy { it.label }).forEach { app ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(app.label, style = DsType.listPrimary.copy(fontSize = 15.sp))
                Text(
                    when {
                        app.packageName == defaultSmsPackage -> "기본 문자 앱 - 결제 문자를 여기서 읽습니다"
                        app.packageName == IssuerRegistry.KAKAO_PACKAGE -> "대화 알림도 같은 통로로 지나갑니다(내용은 결제만 읽음)"
                        app.issuerKey != null -> "카드사 앱"
                        else -> app.packageName
                    },
                    style = DsType.listSecondary,
                )
            }
            DsToggle(app.enabled, onToggle = { onToggle(app, !app.enabled) })
        }
        Hairline()
    }
    Spacer(Modifier.height(20.dp))
    PrimaryButton("다음", onNext, enabled = apps.isEmpty() || apps.any { it.enabled })
    DsTextButton("이전", onBack)
}

// ------------------------------------------------------------------ 3. 카드 등록

@Composable
private fun CardStep(
    apps: List<SourceApp>,
    cards: List<Card>,
    form: CardForm,
    onChange: ((CardForm) -> CardForm) -> Unit,
    onSave: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    Text("실적을 챙길\n카드를 등록하세요", style = DsType.onboardTitle)
    Spacer(Modifier.height(14.dp))
    Text("카드마다 월간 목표와 주기 시작일이 다를 수 있습니다. 나중에 카드 탭에서 더 추가할 수 있습니다.", style = DsType.body)

    if (cards.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("등록한 카드: " + cards.joinToString(", ") { it.nickname }, style = DsType.listPrimary.copy(color = Ds.green, fontSize = 14.sp))
    }

    // 켜 둔 카드사 앱으로 이름·키워드를 제안한다. 한 번 눌러 채우면 오타로 매칭이 안 되는 일이 줄어든다.
    val suggestions = apps.filter { it.enabled }.mapNotNull { IssuerRegistry.byKey(it.issuerKey) }.distinctBy { it.key }
    if (suggestions.isNotEmpty() && form.nickname.isBlank()) {
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            suggestions.forEach { issuer ->
                DsChip("+ ${issuer.displayName}", false, onClick = {
                    onChange { it.copy(nickname = issuer.displayName, keywords = issuer.displayName) }
                })
            }
        }
    }

    // 입력 문자열을 따로 든다. 숫자로만 들면 지운 순간 빈칸을 표현할 수 없어 '1'을 지우고 '3'을 치면
    // '13'이나 '31'이 되고, 범위 밖 값은 조용히 28로 잘렸다(에뮬레이터 검증에서 발견).
    var dayText by remember(form.startDay) { mutableStateOf(form.startDay.toString()) }
    val dayValid = dayText.toIntOrNull()?.let { it in 1..28 } == true
    Spacer(Modifier.height(18.dp))
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        UnderlinedField("별명", form.nickname, "예: 우리 카드의정석", DsType.inputText, { v -> onChange { it.copy(nickname = v) } })
        UnderlinedField(
            "월간 추적 목표", form.target, "300,000", DsType.inputAmount,
            { v -> onChange { it.copy(target = Money.reformatInput(v)) } },
            keyboardType = KeyboardType.Number, suffix = "원",
        )
        UnderlinedField(
            "인식 키워드", form.keywords, "우리카드, 4321", DsType.inputText,
            { v -> onChange { it.copy(keywords = v) } },
            helper = "쉼표로 구분. 카드사 이름이나 카드 뒷 4자리를 넣으세요.",
        )
        UnderlinedField(
            "주기 시작일 (1~28)", dayText, "1", DsType.inputText,
            { v ->
                dayText = v.filter(Char::isDigit).take(2)
                dayText.toIntOrNull()?.takeIf { it in 1..28 }?.let { day -> onChange { it.copy(startDay = day) } }
            },
            keyboardType = KeyboardType.Number, suffix = "일",
            helper = if (dayValid) null else "1부터 28 사이로 넣으세요. 29~31일 시작 카드는 28로 두면 됩니다.",
        )
    }
    Spacer(Modifier.height(18.dp))
    OutlineButton("이 카드 저장", { if (dayValid) onSave() }, Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    PrimaryButton("다음", onNext, enabled = cards.isNotEmpty())
    DsTextButton(if (cards.isEmpty()) "카드는 나중에 등록" else "이전", if (cards.isEmpty()) onNext else onBack)
}

// ------------------------------------------------------------------ 4. 초기 사용액

@Composable
private fun InitialAmountStep(
    rows: List<Aggregator.CardProgress>,
    onSet: (Card, Long) -> Unit,
    onFinish: () -> Unit,
    onBack: () -> Unit,
) {
    var editing by remember { mutableStateOf<Aggregator.CardProgress?>(null) }

    Text("이번 달에\n이미 쓴 금액을\n알려 주세요", style = DsType.onboardTitle)
    Spacer(Modifier.height(14.dp))
    Text(
        "지난 결제는 알림으로 되찾을 수 없습니다. 카드사 앱의 '이번 달 이용금액'을 옮겨 적으면 " +
            "앞으로 오는 결제 알림이 거기에 더해집니다. 모르면 비워 두고 지금부터 세도 됩니다.",
        style = DsType.body,
    )
    Spacer(Modifier.height(16.dp))
    if (rows.isEmpty()) {
        Text("등록한 카드가 없습니다. 카드 탭에서 추가한 뒤 카드 상세에서 입력할 수 있습니다.", style = DsType.listSecondary)
    }
    rows.forEach { row ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { editing = row }
                .padding(vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(row.card.nickname, style = DsType.listPrimary.copy(fontSize = 15.sp))
                Text("매월 ${row.card.cycleStartDay}일부터", style = DsType.listSecondary)
            }
            Text(
                if (row.initialApplied != 0L) Money.won(row.initialApplied) else "입력",
                style = DsType.link,
            )
        }
        Hairline()
    }
    Spacer(Modifier.height(22.dp))
    PrimaryButton("시작하기", onFinish)
    DsTextButton("이전", onBack)

    editing?.let { row ->
        AmountEditDialog(
            title = "${row.card.nickname} 이번 달 이용금액",
            message = "매월 ${row.card.cycleStartDay}일부터 오늘까지 쓴 금액을 카드사 앱에서 확인해 넣으세요.",
            initial = row.initialApplied,
            confirmLabel = "저장",
            allowZero = true,
            onDismiss = { editing = null },
            onConfirm = { amount -> onSet(row.card, amount) },
        )
    }
}
