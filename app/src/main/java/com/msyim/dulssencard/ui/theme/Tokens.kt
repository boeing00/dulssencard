package com.msyim.dulssencard.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.R

/**
 * 디자인 토큰. 핸드오프 README 의 값을 그대로 옮겼다.
 *
 * 접근성 게이트: 모든 텍스트 대비 ≥ 4.5:1.
 * [Ds.text3](#5A5344)보다 밝은 회색을 텍스트에 쓰지 말 것. 더 밝은 값이 필요하면
 * [Ds.monoFill] 처럼 비텍스트 전용 토큰을 쓴다.
 */
object Ds {

    // ----------------------------------------------------------------- 색

    val ink = Color(0xFF17150F)
    val ink2 = Color(0xFF2C281D)
    val paper = Color(0xFFF4F1E8)
    val paper2 = Color(0xFFEDE9DC)
    val line = Color(0xFFDCD6C6)
    val text2 = Color(0xFF4A4437)
    val text3 = Color(0xFF5A5344)
    val textBody = Color(0xFF3D382C)

    /** 리스트 보조 텍스트에 자주 쓰이는 중간 회색. README 의 `#6B6455`. */
    val textSubtle = Color(0xFF6B6455)

    val accent = Color(0xFFB23A12)
    val accentPress = Color(0xFF8E2C0B)
    val accentTint = Color(0xFFF9E9E0)
    val accentTint2 = Color(0xFFF6E4DA)
    val accentTintHover = Color(0xFFF4DFD3)

    val green = Color(0xFF0B6B5B)

    val brownInk = Color(0xFF2E2415)
    val brownLabel = Color(0xFF6B5535)
    val brownSub = Color(0xFF5A4728)
    val brownBarA = Color(0xFF5A4426)
    val brownBarB = Color(0xFF3A2B15)
    val brownLink = Color(0xFF4A3A22)

    /** 비텍스트 전용(헤어라인·도형 채움). 텍스트에 쓰면 대비 게이트를 못 넘는다. */
    val monoFill = Color(0xFF8A8375)
    val mastheadMid = Color(0xFF8A6238)

    /** 스낵바 '되돌리기' 액션. */
    val undoLime = Color(0xFFD9F24A)

    /** 한도 카드 표면 그라디언트와 그 뒤에 까는 단색 틴트. */
    val limitSurfaceStops = listOf(Color(0xFFF6EFE1), Color(0xFFEADFCB), Color(0xFFDCCDB2))
    val limitSurfaceTint = Color(0xFFE9DECB)

    // ----------------------------------------------------------------- 치수

    val screenPadding = 22.dp
    val hairline = 1.dp

    /** 카드/버튼 기본 라운드. 이 디자인은 각진 편집물 톤을 노린다. */
    val radius = 2.dp
    val radiusCard = 6.dp
    val radiusBanner = 3.dp
    val radiusPill = 999.dp

    /** 터치 타깃 최소 높이. 리스트 행·토글·칩 모두 이 값을 넘겨야 한다. */
    val minTouchTarget = 44.dp
}

// --------------------------------------------------------------------- 서체

private val PlexSans = FontFamily(
    Font(R.font.plex_sans_kr_regular, FontWeight.Normal),
    Font(R.font.plex_sans_kr_medium, FontWeight.Medium),
    Font(R.font.plex_sans_kr_semibold, FontWeight.SemiBold),
)

private val PlexMono = FontFamily(
    Font(R.font.plex_mono_regular, FontWeight.Normal),
    Font(R.font.plex_mono_medium, FontWeight.Medium),
    Font(R.font.plex_mono_semibold, FontWeight.SemiBold),
)

/**
 * 서체 스케일.
 *
 * **모든 금액은 [DsType.PlexMono] 고정폭이다.** 금액을 세로로 정렬해 읽히게 하는 것이
 * 이 디자인의 의도라, 금액에 Sans 를 쓰면 안 된다.
 */
object DsType {

    val Sans = PlexSans
    val PlexMono = com.msyim.dulssencard.ui.theme.PlexMono

    /** 마스트헤드 '덜쎈카드'. */
    val masthead = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.035).em,
    )

    /** 마스트헤드 위 작은 대문자 라벨. */
    val kicker = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 9.5.sp,
        letterSpacing = 0.24.em,
        color = Ds.text3,
    )

    /** 화면 제목. */
    val h2 = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.02).em,
        color = Ds.ink,
    )

    /** 온보딩 제목(3줄 개행 고정). */
    val onboardTitle = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 31.sp,
        lineHeight = 38.44.sp,
        letterSpacing = (-0.02).em,
        color = Ds.ink,
    )

    /** 섹션 라벨(대문자 소형). */
    val sectionLabel = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.18.em,
        color = Ds.text3,
    )

    /** 입력 필드 라벨. */
    val fieldLabel = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        letterSpacing = 0.16.em,
        color = Ds.text3,
    )

    val limitSpent = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.2.sp,
        letterSpacing = (-0.02).em,
        color = Ds.brownInk,
    )

    val limitCaption = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = Ds.brownLabel,
    )

    val cardRemaining = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        color = Ds.ink,
    )

    val cardNickname = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.5.sp,
        color = Ds.ink,
    )

    val txAmount = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
    )

    val detailAmount = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 37.8.sp,
        letterSpacing = (-0.03).em,
    )

    val listPrimary = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.5.sp,
        color = Ds.ink,
    )

    val listSecondary = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        color = Ds.textSubtle,
    )

    /** 상태 배지. 대문자로 그린다. */
    val badge = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.5.sp,
        letterSpacing = 0.06.em,
    )

    val navLabel = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
    )

    val body = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.5.sp,
        lineHeight = 24.65.sp,
        color = Ds.text2,
    )

    /** 화면 하단 고지·푸터. */
    val footnote = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        color = Ds.text3,
    )

    val snackbar = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = Ds.paper,
    )

    val undoAction = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.5.sp,
        color = Ds.undoLime,
    )

    val primaryButton = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
    )

    val textButton = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        color = Ds.textSubtle,
    )

    val backLink = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        color = Ds.textSubtle,
    )

    val inputText = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        color = Ds.ink,
    )

    val inputAmount = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        color = Ds.ink,
    )

    val inputLimitAmount = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        color = Ds.ink,
    )

    val monoSmall = TextStyle(
        fontFamily = PlexMono,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = Ds.textSubtle,
    )

    val link = TextStyle(
        fontFamily = PlexSans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        textDecoration = TextDecoration.Underline,
    )
}
