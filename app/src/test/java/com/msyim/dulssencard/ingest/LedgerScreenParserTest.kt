package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 카드사 앱 **이용내역 목록 화면** 파서 회귀 테스트.
 *
 * 아래 텍스트는 사용자 기기에서 실제로 캡처한 화면을 OCR 했을 때 나오는 형태다.
 * 화면마다 순서가 달라서(우리WON피드는 시각→가맹점→금액, 실적 내역 조회는
 * 가맹점→금액→날짜) 두 형식을 모두 잠가 둔다.
 */
class LedgerScreenParserTest {

    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 8, 10, 30), Cycle.ZONE).toInstant()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    /** 우리WON피드 이용내역. 날짜가 구역 헤더에만 있고 헤더에 그 날 합계가 붙는다. */
    private val wooriFeed = """
        SKT 09:16
        우리WON피드
        9월 이용금액
        171,120원
        9월 7일 월요일
        142,620원
        21:18 | 본인 | 일시불
        푸른들컨트리클럽 주식
        16,000원
        19:32 | 본인 | 일시불
        너른마당
        85,000원
        12:43 | 본인 | 일시불
        늘푸른스물한세기약
        12,820원
        12:32 | 본인 | 일시불
        새봄의원
        5,600원
        10:46 | 본인 | 일시불
        한마음 홀세일클럽 중앙점
        5,000원
        10:44 | 본인 | 일시불
        바른이치과의원
        18,200원
    """.trimIndent()

    /** 실적 내역 조회. 가맹점 → 실적인정금액 → 승인금액 → 날짜 순서이고 시각이 없다. */
    private val performanceScreen = """
        SKT 10:30
        실적 내역 조회
        10월 혜택을 위한 실적
        실적기간 :2026.09.01 ~ 2026.09.30
        171,120원
        9월 실적대상금액
        171,120원
        한마음 홀세일클럽 중앙점
        실적인정금액
        5,000원
        승인금액
        5,000원
        2026. 09. 07 · 국내일시불 · 미확정
        푸른들컨트리클럽 주식
        실적인정금액
        16,000원
        승인금액
        16,000원
        2026. 09. 07 · 국내일시불 · 미확정
        너른마당
        실적인정금액
        85,000원
        승인금액
        85,000원
        2026. 09. 07 · 국내일시불 · 미확정
    """.trimIndent()

    // ---------------------------------------------------------------- 판별

    @Test
    fun `목록 화면을 알아본다`() {
        assertTrue(LedgerScreenParser.looksLikeLedger(wooriFeed))
        assertTrue(LedgerScreenParser.looksLikeLedger(performanceScreen))
    }

    @Test
    fun `통지 한 건짜리 캡처는 목록으로 보지 않는다`() {
        val alimtalk = """
            우리카드
            알림톡 도착
            [우리카드 이용 안내]
            우리(4321)승인
            홍*동님
            16,000원 일시불
            09/07 21:18
            푸른들컨트리클럽 주식
            누적1,234,567원
        """.trimIndent()
        assertFalse(LedgerScreenParser.looksLikeLedger(alimtalk))
    }

    // ---------------------------------------------------------------- 우리WON피드

    @Test
    fun `우리WON피드에서 여섯 건을 모두 읽는다`() {
        val rows = LedgerScreenParser.parse(wooriFeed, now)
        assertEquals(6, rows.size)

        assertEquals(16_000L, rows[0].amount)
        assertEquals("푸른들컨트리클럽 주식", rows[0].merchant)
        assertEquals(at(2026, 9, 7, 21, 18), rows[0].occurredAt)
        assertFalse(rows[0].occurredAtEstimated)

        assertEquals(85_000L, rows[1].amount)
        assertEquals("너른마당", rows[1].merchant)
        assertEquals(at(2026, 9, 7, 19, 32), rows[1].occurredAt)

        assertEquals(18_200L, rows[5].amount)
        assertEquals("바른이치과의원", rows[5].merchant)
        assertEquals(at(2026, 9, 7, 10, 44), rows[5].occurredAt)
    }

    @Test
    fun `월 이용금액과 일일 합계를 거래로 읽지 않는다`() {
        val rows = LedgerScreenParser.parse(wooriFeed, now)
        // 171,120원(9월 이용금액), 142,620원(9월 7일 합계)이 섞이면 하루치가 통째로 이중 집계된다.
        assertTrue("월 합계가 거래로 들어왔다", rows.none { it.amount == 171_120L })
        assertTrue("일일 합계가 거래로 들어왔다", rows.none { it.amount == 142_620L })
        assertEquals(142_620L, rows.sumOf { it.amount })
    }

    // ---------------------------------------------------------------- 실적 내역

    @Test
    fun `실적 내역 화면에서 세 건을 읽는다`() {
        val rows = LedgerScreenParser.parse(performanceScreen, now)
        assertEquals(3, rows.size)
        assertEquals(5_000L, rows[0].amount)
        assertEquals("한마음 홀세일클럽 중앙점", rows[0].merchant)
        assertEquals(16_000L, rows[1].amount)
        assertEquals("푸른들컨트리클럽 주식", rows[1].merchant)
        assertEquals(85_000L, rows[2].amount)
        assertEquals("너른마당", rows[2].merchant)
    }

    @Test
    fun `실적인정금액과 승인금액을 두 건으로 세지 않는다`() {
        val rows = LedgerScreenParser.parse(performanceScreen, now)
        // 같은 결제가 두 줄로 적혀 있다. 두 건으로 세면 실적이 두 배가 된다.
        assertEquals(106_000L, rows.sumOf { it.amount })
    }

    @Test
    fun `실적 화면에는 시각이 없어 추정으로 표시한다`() {
        val rows = LedgerScreenParser.parse(performanceScreen, now)
        assertTrue(rows.all { it.occurredAtEstimated })
        // 날짜는 살아 있어야 주기 집계가 맞는다.
        assertTrue(rows.all { it.occurredAt > at(2026, 9, 6, 0, 0) })
        assertTrue(rows.all { it.occurredAt < at(2026, 9, 8, 0, 0) })
    }

    @Test
    fun `실적기간 안내의 금액은 거래가 아니다`() {
        val rows = LedgerScreenParser.parse(performanceScreen, now)
        assertTrue(rows.none { it.amount == 171_120L })
    }
}
