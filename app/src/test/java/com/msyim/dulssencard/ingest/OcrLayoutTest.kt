package com.msyim.dulssencard.ingest

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * OCR 행 복원 회귀 테스트.
 *
 * 실기기에서 ML Kit 평문 출력을 떠 보니 2단 레이아웃의 오른쪽 열이 통째로 문자열 끝으로
 * 밀려 있었다. 카드사 이용내역 화면이 그 구조라, 평문을 쓰면 가맹점과 금액의 짝이 어긋난다.
 * 아래 좌표는 그때 확인한 배치를 본뜬 것이다.
 */
class OcrLayoutTest {

    private fun frag(text: String, left: Int, top: Int, height: Int = 40) =
        OcrLayout.Fragment(text = text, left = left, top = top, bottom = top + height)

    @Test
    fun `세로로 겹치는 조각을 한 행으로 묶는다`() {
        // 우리WON피드: 왼쪽 가맹점 / 오른쪽 금액이 같은 시각적 행에 있다.
        val fragments = listOf(
            frag("푸른들컨트리클럽 주식", left = 60, top = 500),
            frag("16,000원", left = 820, top = 504),
            frag("너른마당", left = 60, top = 620),
            frag("85,000원", left = 820, top = 618),
        )
        assertEquals(
            "푸른들컨트리클럽 주식 16,000원\n너른마당 85,000원",
            OcrLayout.rebuildRows(fragments),
        )
    }

    @Test
    fun `입력 순서가 뒤죽박죽이어도 좌표로 복원한다`() {
        // ML Kit 은 오른쪽 열을 뒤로 몰아 준다. 그 순서 그대로 넣어도 결과가 같아야 한다.
        val fragments = listOf(
            frag("푸른들컨트리클럽 주식", left = 60, top = 500),
            frag("너른마당", left = 60, top = 620),
            frag("16,000원", left = 820, top = 504),
            frag("85,000원", left = 820, top = 618),
        )
        assertEquals(
            "푸른들컨트리클럽 주식 16,000원\n너른마당 85,000원",
            OcrLayout.rebuildRows(fragments),
        )
    }

    @Test
    fun `행 간격이 좁아도 다른 행으로 나눈다`() {
        val fragments = listOf(
            frag("21:18 | 본인 | 일시불", left = 60, top = 440, height = 30),
            frag("푸른들컨트리클럽 주식", left = 60, top = 480, height = 30),
            frag("16,000원", left = 820, top = 482, height = 30),
        )
        assertEquals(
            "21:18 | 본인 | 일시불\n푸른들컨트리클럽 주식 16,000원",
            OcrLayout.rebuildRows(fragments),
        )
    }

    @Test
    fun `복원한 행을 목록 파서가 그대로 읽는다`() {
        val fragments = listOf(
            frag("9월 7일 월요일", left = 60, top = 300),
            frag("142,620원", left = 800, top = 302),
            frag("21:18 | 본인 | 일시불", left = 60, top = 400),
            frag("푸른들컨트리클럽 주식", left = 60, top = 460),
            frag("16,000원", left = 820, top = 462),
            frag("19:32 | 본인 | 일시불", left = 60, top = 560),
            frag("너른마당", left = 60, top = 620),
            frag("85,000원", left = 820, top = 622),
        )
        val text = OcrLayout.rebuildRows(fragments)
        val rows = LedgerScreenParser.parse(text)

        assertEquals(2, rows.size)
        assertEquals(16_000L, rows[0].amount)
        assertEquals("푸른들컨트리클럽 주식", rows[0].merchant)
        assertEquals(85_000L, rows[1].amount)
        assertEquals("너른마당", rows[1].merchant)
        // 일일 합계 142,620원이 거래로 새면 하루치가 이중 집계된다.
        assertEquals(101_000L, rows.sumOf { it.amount })
    }

    @Test
    fun `OCR 이 쉼표 뒤에 공백을 넣어도 금액을 읽는다`() {
        // 실기기 확인: ML Kit 이 `16,000` 을 `16, 000` 으로 뱉는다.
        val fragments = listOf(
            frag("9월 7일 월요일", left = 60, top = 300),
            frag("21:18 | 본인 | 일시불", left = 60, top = 400),
            frag("푸른들컨트리클럽 주식", left = 60, top = 460),
            frag("16, 000원", left = 820, top = 462),
        )
        val rows = LedgerScreenParser.parse(OcrLayout.rebuildRows(fragments))
        assertEquals(1, rows.size)
        assertEquals(16_000L, rows[0].amount)
        assertEquals("푸른들컨트리클럽 주식", rows[0].merchant)
    }

    @Test
    fun `빈 입력은 빈 문자열`() {
        assertEquals("", OcrLayout.rebuildRows(emptyList()))
    }
}
