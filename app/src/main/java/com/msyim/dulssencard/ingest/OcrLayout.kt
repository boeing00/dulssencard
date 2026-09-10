package com.msyim.dulssencard.ingest

/**
 * OCR 조각들을 **화면에 보이던 행**으로 다시 묶는다.
 *
 * ML Kit 의 평문 출력은 읽기 순서를 자기 나름대로 정하기 때문에, 2단 레이아웃에서
 * 오른쪽 열(금액)이 왼쪽 열(가맹점)과 완전히 분리돼 문자열 끝으로 밀린다.
 * 카드사 이용내역 화면이 정확히 그 구조라, 평문을 쓰면 가맹점과 금액의 짝이 어긋난다.
 *
 * 좌표는 남아 있으므로, 세로 위치가 겹치는 조각들을 한 행으로 보고 가로 순서대로 이어 붙인다.
 * 순수 함수라 기기 없이 테스트할 수 있다.
 */
object OcrLayout {

    /** OCR 이 돌려준 한 줄과 그 위치. */
    data class Fragment(
        val text: String,
        val left: Int,
        val top: Int,
        val bottom: Int,
    ) {
        val centerY: Int get() = (top + bottom) / 2
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    /**
     * 세로로 겹치는 조각들을 한 행으로 묶어 문자열로 되돌린다.
     *
     * 같은 행 판정은 **글자 높이에 비례**시킨다. 고정 픽셀 값으로 하면 해상도나 글꼴 크기가
     * 달라질 때 통째로 어긋난다.
     */
    fun rebuildRows(fragments: List<Fragment>, tolerance: Double = 0.6): String {
        if (fragments.isEmpty()) return ""

        val sorted = fragments.sortedBy { it.centerY }
        val rows = mutableListOf<MutableList<Fragment>>()

        sorted.forEach { fragment ->
            val row = rows.lastOrNull()
            val anchor = row?.firstOrNull()
            val sameRow = anchor != null &&
                kotlin.math.abs(fragment.centerY - anchor.centerY) <=
                (maxOf(fragment.height, anchor.height) * tolerance)

            if (sameRow) row.add(fragment) else rows.add(mutableListOf(fragment))
        }

        return rows.joinToString("\n") { row ->
            row.sortedBy { it.left }.joinToString(" ") { it.text.trim() }.trim()
        }
    }
}
