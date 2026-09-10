package com.msyim.dulssencard.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.msyim.dulssencard.ui.component.DsTextButton
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.component.PrimaryButton
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

/**
 * 온보딩 — 권한 고지.
 *
 * PRD §6.1: 자동 집계의 가치와 데이터 처리를 설명하고, **동의한 경우에만** 권한을 요청한다.
 *
 * 이 앱은 SMS 권한을 쓰지 않고 알림 접근 하나로 결제 문자·앱 푸시·알림톡을 모두 읽는다.
 * 알림 접근은 앱이 요청할 수 없으므로, 동의하면 시스템 설정 화면으로 보낸다.
 */
@Composable
fun OnboardingScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 26.dp, end = 26.dp, top = 34.dp, bottom = 26.dp),
    ) {
        Text(
            "STEP 1 / 3 · 권한 고지",
            style = DsType.kicker.copy(fontSize = 10.sp, letterSpacing = 0.22.em, color = Ds.accent),
        )
        Spacer(Modifier.height(18.dp))

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

        Spacer(Modifier.height(22.dp))

        NoticeList()

        Spacer(Modifier.height(28.dp))

        PrimaryButton("알림 접근 켜러 가기", onAccept)
        Spacer(Modifier.height(4.dp))
        DsTextButton("나중에 하기", onDecline)
    }
}

@Composable
private fun NoticeList() {
    val notices = listOf(
        Notice("✓", "문자·알림 원문과 주민등록번호는 저장·전송하지 않습니다", Ds.accent, Ds.text2),
        Notice("✓", "광고·마케팅·분석 목적으로 사용하지 않습니다", Ds.accent, Ds.text2),
        Notice(
            "✓",
            "문자 권한을 요구하지 않습니다. 알림 접근만 쓰며, 사용자가 켠 앱의 알림만 읽습니다",
            Ds.accent,
            Ds.text2,
        ),
        Notice(
            "!",
            "집계값은 사용자가 설정한 규칙에 따른 개인 추적값이며 카드사 공식 실적이 아닙니다",
            Ds.text3,
            Ds.textSubtle,
        ),
    )
    Column(Modifier.fillMaxWidth()) {
        notices.forEachIndexed { index, notice ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    notice.glyph,
                    style = DsType.listPrimary.copy(color = notice.glyphColor),
                    modifier = Modifier.width(14.dp),
                )
                Text(
                    notice.text,
                    style = DsType.listPrimary.copy(
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.5.sp,
                        lineHeight = 21.sp,
                        color = notice.textColor,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            if (index != notices.lastIndex) Hairline()
        }
    }
}

private data class Notice(
    val glyph: String,
    val text: String,
    val glyphColor: androidx.compose.ui.graphics.Color,
    val textColor: androidx.compose.ui.graphics.Color,
)
