package com.msyim.dulssencard.ingest

/**
 * 캡처 이미지에서 뽑아낸 OCR 텍스트를 결제 통지 단위로 자른다.
 *
 * ## 왜 필요한가
 *
 * 카드 통지는 카드사·금액·시각·가맹점이 **각기 다른 줄**에 있다. OCR 결과를 줄 단위로
 * 파서에 넣으면 어느 줄도 필수 항목을 채우지 못해 전부 버려진다. 반대로 화면에 통지가
 * 여러 건 찍혀 있는데 통째로 넣으면, 1번 결제의 금액과 2번 결제의 시각이 섞인 유령 거래가 나온다.
 *
 * 그래서 "카드사 이름과 승인/취소가 같이 있는 줄"을 통지의 시작으로 보고 그 지점에서 자른다.
 * 카드사 문구는 예외 없이 카드사 이름을 맨 앞에 두기 때문에 이 단서가 안정적이다.
 */
object OcrText {

    private val DIRECTION_HINT = Regex("""승인|취소|환불|출금|결제""")

    /**
     * 통지 단위로 자른 덩어리들. 시작점을 하나도 못 찾으면 전체를 한 덩어리로 돌려준다.
     *
     * 앞부분(상태바·앱 헤더 등)은 첫 시작점 앞이라 버려지는데, 거기에는 결제 정보가 없다.
     */
    fun blocks(text: String): List<String> {
        val lines = text.lines()
        val starts = lines.indices.filter { isNoticeStart(lines[it]) }
        if (starts.size <= 1) return listOf(text)

        return starts.mapIndexed { index, start ->
            val end = if (index + 1 < starts.size) starts[index + 1] else lines.size
            lines.subList(start, end).joinToString("\n")
        }
    }

    private fun isNoticeStart(line: String): Boolean =
        IssuerRegistry.detect(line) != null && DIRECTION_HINT.containsMatchIn(line)
}
