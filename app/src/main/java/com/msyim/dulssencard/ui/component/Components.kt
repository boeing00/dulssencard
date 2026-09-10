package com.msyim.dulssencard.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/** 진행 막대 전환. README: `width .45s cubic-bezier(.2,.8,.2,1)`. */
private val ProgressEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
private const val PROGRESS_DURATION_MS = 450
private const val TOGGLE_DURATION_MS = 200

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = DsType.sectionLabel, modifier = modifier)
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Ds.line) {
    Box(modifier.fillMaxWidth().height(Ds.hairline).background(color))
}

/**
 * 진행 막대. 0~100% 로 클램프한다.
 * 목표를 넘겨도 막대가 넘치지 않아야 "달성"이 한눈에 읽힌다.
 */
@Composable
fun ProgressBar(
    ratio: Float,
    height: Dp,
    trackColor: Color,
    fillBrush: Brush,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = tween(PROGRESS_DURATION_MS, easing = ProgressEasing),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(1.dp))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(animated)
                .height(height)
                .clip(RoundedCornerShape(1.dp))
                .background(fillBrush),
        )
    }
}

@Composable
fun ProgressBar(
    ratio: Float,
    height: Dp,
    trackColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
) = ProgressBar(ratio, height, trackColor, Brush.verticalGradient(listOf(fillColor, fillColor)), modifier)

/**
 * 토글 스위치. 트랙 52×30, 노브 24×24.
 * 터치 타깃을 44dp 로 벌리기 위해 바깥에 여백을 준다.
 */
@Composable
fun DsToggle(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val knobOffset by animateFloatAsState(
        targetValue = if (checked) 25f else 3f,
        animationSpec = tween(TOGGLE_DURATION_MS, easing = ProgressEasing),
        label = "knob",
    )
    Box(
        modifier
            .size(width = 52.dp, height = Ds.minTouchTarget)
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(Ds.radiusPill))
                .background(if (checked) Ds.ink else Ds.line),
        ) {
            Box(
                Modifier
                    .padding(start = knobOffset.dp, top = 3.dp)
                    .size(24.dp)
                    .clip(RoundedCornerShape(Ds.radiusPill))
                    .background(Ds.paper),
            )
        }
    }
}

/** 칩. 탭은 라운드 999, 카드 선택·일자는 라운드 2. */
@Composable
fun DsChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    pill: Boolean = true,
    horizontalPadding: Dp = 12.dp,
    verticalPadding: Dp = 9.dp,
) {
    val shape = RoundedCornerShape(if (pill) Ds.radiusPill else Ds.radius)
    Box(
        modifier
            .clip(shape)
            .background(if (selected) Ds.ink else Color.Transparent)
            .border(Ds.hairline, if (selected) Ds.ink else Ds.line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding, vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = DsType.listPrimary.copy(
                fontSize = 13.sp,
                color = if (selected) Ds.paper else Ds.textBody,
            ),
            maxLines = 1,
        )
    }
}

/** 일자 선택용 정사각 칩(38×38). */
@Composable
fun DaySquareChip(day: Int, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Ds.radius)
    Box(
        Modifier
            .size(38.dp)
            .clip(shape)
            .background(if (selected) Ds.ink else Color.Transparent)
            .border(Ds.hairline, if (selected) Ds.ink else Ds.line, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            day.toString(),
            style = DsType.monoSmall.copy(color = if (selected) Ds.paper else Ds.textBody),
        )
    }
}

@Composable
fun PrimaryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.radius))
            .background(if (enabled) Ds.ink else Ds.line)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsType.primaryButton.copy(color = Ds.paper))
    }
}

/** 보조(파괴적) 버튼 — 투명 배경, accent 테두리·텍스트. */
@Composable
fun DestructiveButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Ds.radius))
            .border(Ds.hairline, Ds.accent, RoundedCornerShape(Ds.radius))
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsType.primaryButton.copy(color = Ds.accent))
    }
}

@Composable
fun DsTextButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsType.textButton)
    }
}

/** 아웃라인 소형 버튼(+ 카드 추가). */
@Composable
fun OutlineButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(Ds.radius))
            .border(Ds.hairline, Ds.ink, RoundedCornerShape(Ds.radius))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, style = DsType.listPrimary.copy(fontSize = 13.sp))
    }
}

/** `P1` 같은 작은 아웃라인 배지. */
@Composable
fun OutlineBadge(label: String, color: Color = Ds.accent, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(Ds.radius))
            .border(Ds.hairline, color, RoundedCornerShape(Ds.radius))
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        Text(
            label,
            style = DsType.kicker.copy(fontSize = 9.5.sp, letterSpacing = 0.1.em, color = color),
        )
    }
}

/** 채워진 카운트 배지(확인 필요 건수). */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(
        modifier
            .clip(RoundedCornerShape(Ds.radiusPill))
            .background(Ds.accent)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    ) {
        Text(count.toString(), style = DsType.kicker.copy(color = Ds.paper))
    }
}

/** 리스트 한 행. 좌우 여백과 하단 구분선을 한곳에서 관리한다. */
@Composable
fun ListRow(
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = Ds.screenPadding, vertical = 16.dp),
    showDivider: Boolean = true,
    content: @Composable ColumnScopeAlias.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = Ds.minTouchTarget)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(padding),
            content = content,
        )
        if (showDivider) Hairline()
    }
}

typealias ColumnScopeAlias = androidx.compose.foundation.layout.ColumnScope

/**
 * 홈 하단 광고 지면 (320×50).
 *
 * BuildConfig.ADS_ENABLED 가 false 인 동안은 그려지지 않는다.
 * 광고를 켤 때도 **거래·SMS 파생 데이터를 광고 SDK 에 절대 넘기지 않는다** —
 * 이 컴포저블은 어떤 거래 값도 인자로 받지 않도록 만들어 두었다.
 */
@Composable
fun AdSlot(modifier: Modifier = Modifier) {
    if (!com.msyim.dulssencard.BuildConfig.ADS_ENABLED) return
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Ds.screenPadding, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 320.dp, height = 50.dp)
                .clip(RoundedCornerShape(Ds.radius))
                .background(Ds.paper2)
                .border(Ds.hairline, Ds.line, RoundedCornerShape(Ds.radius)),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(Ds.radius))
                        .background(Ds.line),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "광고 자리",
                        style = DsType.listPrimary.copy(fontSize = 12.sp, color = Ds.textBody),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "비타게팅 배너",
                        style = DsType.listSecondary.copy(fontSize = 10.5.sp, color = Ds.text2),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .background(Ds.text2, RoundedCornerShape(bottomStart = Ds.radius))
                    .padding(horizontal = 4.dp, vertical = 3.dp),
            ) {
                Text(
                    "AD",
                    style = DsType.kicker.copy(
                        fontSize = 8.5.sp,
                        letterSpacing = 0.1.em,
                        color = Ds.paper,
                    ),
                )
            }
        }
    }
}

/** 스낵바. 4.2초 자동 소멸은 ViewModel 이 관리한다. */
@Composable
fun DsSnackbar(
    message: String,
    undoable: Boolean,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(Ds.radiusBanner))
            .background(Ds.ink)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = DsType.snackbar, modifier = Modifier.weight(1f))
        if (undoable) {
            Spacer(Modifier.width(12.dp))
            Text(
                "되돌리기",
                style = DsType.undoAction,
                modifier = Modifier.clickable(onClick = onUndo),
            )
        }
    }
}

/** 화면 하단 고지·푸터 문단. */
@Composable
fun Footnote(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(text, style = DsType.footnote, modifier = modifier, textAlign = textAlign)
}
