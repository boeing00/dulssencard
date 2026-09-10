package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.domain.Cycle
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 카드사 앱의 **이용내역 목록 화면**을 읽는다.
 *
 * ## 왜 통지 파서로는 안 되는가
 *
 * [PaymentParser] 는 "한 덩어리 = 결제 한 건"을 전제한다. 목록 화면은 그 전제가 깨진다:
 *
 * - 한 화면에 수십 건이 있다.
 * - 날짜가 각 행이 아니라 **구역 헤더**에만 있다 (`9월 7일 월요일`).
 * - 헤더에 그 날의 **합계 금액**이 같이 붙어 있다 (`9월 7일 월요일   142,620원`).
 *   이걸 결제로 읽으면 하루 치가 통째로 이중 집계된다.
 * - 화면마다 순서가 다르다.
 *   우리WON피드: `시각 → 가맹점 → 금액`
 *   실적 내역 조회: `가맹점 → 실적인정금액 → 승인금액 → 날짜`
 *
 * ## 접근
 *
 * 줄을 종류별 토큰(날짜/시각/금액/텍스트)으로 훑으면서, **금액을 만날 때마다 거래 하나**를
 * 만든다. 날짜·시각은 가장 최근에 본 값을 쓰고, 가맹점은 금액 주변에서 가장 가까운
 * 쓸 만한 텍스트를 고른다. 순서가 다른 두 화면을 하나의 규칙으로 흡수하기 위함이다.
 */
object LedgerScreenParser {

    data class Row(
        val occurredAt: Long,
        val occurredAtEstimated: Boolean,
        val amount: Long,
        val merchant: String?,
    )

    /** `9월 7일 월요일`, `2026년 9월 5일 토요일` */
    private val KO_DATE = Regex("""(?:(\d{4})년\s*)?(\d{1,2})월\s*(\d{1,2})일""")

    /** `2026. 09. 07`, `2026.09.07` */
    private val DOTTED_DATE = Regex("""(\d{4})\s*[.]\s*(\d{1,2})\s*[.]\s*(\d{1,2})""")

    /** 줄이 시각으로 시작하는 경우. `21:18 | 본인 | 일시불` */
    private val LEADING_TIME = Regex("""^\s*(\d{1,2}):(\d{2})\b""")

    /**
     * 목록 화면의 금액.
     *
     * **`원` 을 요구하지 않는다.** 실기기 OCR 이 `원` 을 제대로 못 읽는다:
     * `16,000원` → `16,000`, `12,820원` → `12,820l`, `5,600원` → `5,6002!`.
     * `원` 을 필수로 두면 화면 전체에서 금액을 **한 건도** 못 잡는다(실제로 그랬다).
     *
     * 대신 **천 단위 쉼표**를 필수로 둔다. 목록 화면의 금액은 예외 없이 쉼표가 있고,
     * 시각(`21:18`)·카드번호·연도에는 쉼표가 없어 이것만으로 충분히 갈린다.
     * 뒤에 붙는 `원` 이나 그 오인식 찌꺼기는 함께 먹어서 가맹점 이름에 남지 않게 한다.
     */
    private val AMOUNT = Regex("""([0-9]{1,3}(?:,\s?[0-9]{3})+)\s*(?:원|[0-9a-zA-Z!|]{1,2})?""")

    /**
     * 결제 금액이 아닌 금액이 붙는 줄. 합계·누적·잔여는 거래가 아니다.
     * `실적인정금액` 은 `승인금액` 과 같은 값이 한 번 더 나오는 것이라 중복을 만든다.
     */
    private val AMOUNT_NOISE = listOf(
        "이용금액", "합계", "총", "누적", "잔액", "한도", "실적대상", "실적기간",
        "결제금액", "실적인정금액", "포인트", "적립", "할인", "혜택", "이자", "수수료",
        // '승인금액' 은 뺀다 — 그 라벨 다음 줄의 금액이 곧 결제 금액이다.
    )

    /**
     * **카드 결제가 아닌** 은행 거래. 은행 앱 화면을 캡처하면 이런 행이 섞여 들어온다.
     *
     * 환전·송금·이체는 카드로 쓴 돈이 아니므로 실적에도 구매 한도에도 들어가면 안 된다.
     * 금액만 보고 판단하면 이걸 못 걸러서, 한 달 카드 실적이 계좌 이체액만큼 부풀어 오른다.
     *
     * `출금` 은 단독으로는 은행 출금이라 제외하지만, `체크카드출금` 은 카드 결제라서
     * [isCardPaymentRow] 에서 되살린다.
     */
    private val NON_CARD_ROW = listOf(
        "환전", "송금", "이체", "입금", "출금", "예금", "적금", "대출", "상환",
        "ATM", "현금서비스", "카드론", "충전", "환급", "급여", "배당", "이자지급",
        "보험료", "관리비", "자동납부", "펌뱅킹", "가상계좌", "잔액조회",
    )

    /** 카드 문구에 정상적으로 나오는 말. 은행 거래 판정에서 제외한다. */
    private val SAFE_COMPOUNDS = listOf("누적금액", "누적", "실적", "결제금액", "승인금액")

    /** [NON_CARD_ROW] 에 걸렸어도 카드 결제인 것들. */
    private val CARD_PAYMENT_OVERRIDE = listOf("체크카드출금", "카드출금", "신용카드", "체크카드")

    /** 가맹점이 될 수 없는 줄. 화면 부스러기와 정형 라벨. */
    private val TEXT_NOISE = listOf(
        "본인", "일시불", "할부", "개월", "국내", "해외", "미확정", "확정",
        "승인금액", "실적인정금액", "이용금액", "이용내역", "조회", "실적", "혜택",
        "결제일", "결제금액", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일",
        "바로가기", "메시지", "채널", "카드의정석", "맞춤 카드", "보지 않기", "설정",
    )

    /**
     * 목록 화면으로 볼 만한가. 시각 또는 날짜 표시가 여러 번 나오고 금액도 여럿이면 목록이다.
     * 통지 한 건짜리 캡처를 목록으로 잘못 처리하지 않도록 하는 관문이다.
     */
    fun looksLikeLedger(text: String): Boolean {
        val lines = text.lines()
        val amounts = lines.count { line ->
            AMOUNT.containsMatchIn(line) && AMOUNT_NOISE.none { line.contains(it) }
        }
        val times = lines.count { LEADING_TIME.containsMatchIn(it) }
        val dates = lines.count { KO_DATE.containsMatchIn(it) || DOTTED_DATE.containsMatchIn(it) }

        // 금액이 여러 건이고, 날짜 구역 헤더가 있거나 행마다 시각이 붙어 있으면 목록이다.
        //
        // 예전에는 시각·날짜 표시를 **합쳐서 2개 이상**으로 요구했는데, 우리카드 앱의
        // `이용내역` 화면은 날짜 헤더가 `9월 7일` 하나뿐이고 행에는 시각이 아예 없다.
        // 그래서 목록으로 인식되지 못하고 통지 파서로 흘러가 통째로 버려졌다.
        return amounts >= 2 && (dates >= 1 || times >= 2)
    }

    /**
     * @param now 연도를 못 읽은 날짜의 기준. 화면에 연도가 없는 형식(`9월 7일`)이 많다.
     */
    fun parse(text: String, now: Instant = Instant.now()): List<Row> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val today = now.atZone(Cycle.ZONE).toLocalDate()

        // 날짜·시각 위치를 먼저 훑는다. 화면마다 순서가 달라 순차 처리로는 안 된다 —
        // 우리WON피드는 날짜가 거래 **앞**에, 실적 내역 조회는 거래 **뒤**에 온다.
        val dates = lines.indices.mapNotNull { i -> readDate(lines[i], today)?.let { i to it } }
        val times = lines.indices.mapNotNull { i -> readTime(lines[i])?.let { i to it } }

        val rows = mutableListOf<Row>()
        lines.forEachIndexed { index, line ->
            if (isTotalAmount(lines, index, today)) return@forEachIndexed
            if (!isCardPaymentRow(lines, index)) return@forEachIndexed

            val value = AMOUNT.find(line)?.groupValues?.get(1)
                ?.replace(",", "")?.replace(" ", "")?.toLongOrNull()
                ?.takeIf { it > 0L } ?: return@forEachIndexed

            // 가장 가까운 날짜. 앞뒤 어느 쪽이든 본다.
            val day = dates.minByOrNull { kotlin.math.abs(it.first - index) }?.second
                ?: return@forEachIndexed

            // 시각은 **앞쪽 가까이**에 있을 때만 쓴다. 뒤쪽 시각은 다음 거래 것이다.
            val time = times.filter { it.first < index && index - it.first <= 3 }
                .maxByOrNull { it.first }?.second

            rows += Row(
                occurredAt = LocalDateTime.of(day, time ?: LocalTime.NOON)
                    .atZone(Cycle.ZONE).toInstant().toEpochMilli(),
                occurredAtEstimated = time == null,
                amount = value,
                merchant = findMerchant(lines, index, line),
            )
        }
        return dedupeAdjacent(rows)
    }

    /**
     * 이 금액이 거래가 아니라 합계인가.
     *
     * 화면에서는 라벨과 금액이 같은 줄에 보여도 OCR 은 좌우 열을 **다른 줄**로 뱉는다.
     * (`9월 7일 월요일` / `142,620원`, `실적인정금액` / `5,000원`)
     * 그래서 금액 줄만 보면 못 걸러 내고, 바로 앞 줄까지 함께 봐야 한다.
     * 이걸 놓치면 하루치 합계가 거래로 들어와 통째로 이중 집계된다.
     */
    private fun isTotalAmount(lines: List<String>, index: Int, today: LocalDate): Boolean {
        val line = lines[index]
        if (AMOUNT_NOISE.any { line.contains(it) }) return true
        if (readDate(line, today) != null) return true

        val previous = lines.getOrNull(index - 1) ?: return false
        if (AMOUNT_NOISE.any { previous.contains(it) }) return true

        // 날짜 헤더 바로 뒤에 붙은 **금액만 있는 줄**은 그 날의 합계다.
        // (OCR 이 `9월 7일 월요일   142,620원` 의 좌우 열을 두 줄로 쪼개 놓는 경우)
        //
        // 금액 옆에 가맹점 이름이 같이 있으면 그건 합계가 아니라 그 날의 첫 거래다.
        // 이 단서가 없으면 `9월 7일` / `푸른들컨트리클럽 주식 16,000` 배치에서
        // 첫 거래를 통째로 잃는다.
        val amountOnly = AMOUNT.replace(line, " ").none { it.isLetter() }
        if (readDate(previous, today) != null && !AMOUNT.containsMatchIn(previous) && amountOnly) {
            return true
        }
        return false
    }

    /**
     * 이 행이 카드 결제인가. 은행 앱 캡처에 섞인 환전·송금·이체 행을 걸러 낸다.
     *
     * 라벨이 금액과 다른 줄에 있을 수 있어 앞뒤 두 줄까지 함께 본다.
     */
    private fun isCardPaymentRow(lines: List<String>, index: Int): Boolean {
        // **앞쪽만** 본다. 목록 화면에서 행 라벨은 금액 위에 오고, 금액 아래는 이미 다음 행이다.
        // 뒤까지 보면 다음 행의 `외화 환전` 이 바로 위 행의 멀쩡한 카드 결제까지 지워 버린다.
        val window = (index - 2..index)
            .mapNotNull { lines.getOrNull(it) }
            .joinToString(" ")
        if (CARD_PAYMENT_OVERRIDE.any { window.contains(it) }) return true
        // `누적금액` 안의 `적금` 처럼 부분문자열이 겹치는 말을 먼저 걷어낸다.
        val cleaned = SAFE_COMPOUNDS.fold(window) { acc, word -> acc.replace(word, " ") }
        return NON_CARD_ROW.none { cleaned.contains(it) }
    }

    private fun readTime(line: String): LocalTime? {
        val m = LEADING_TIME.find(line) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val mi = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || mi !in 0..59) return null
        return LocalTime.of(h, mi)
    }

    private fun readDate(line: String, today: LocalDate): LocalDate? {
        DOTTED_DATE.find(line)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull()
        }
        KO_DATE.find(line)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: today.year
            val month = m.groupValues[2].toIntOrNull() ?: return null
            val day = m.groupValues[3].toIntOrNull() ?: return null
            val parsed = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
            // 연도가 없으면 미래로 튀지 않게 한 해 물린다(연말연시).
            return if (m.groupValues[1].isEmpty() && parsed.isAfter(today.plusDays(1))) {
                parsed.minusYears(1)
            } else {
                parsed
            }
        }
        return null
    }

    /**
     * 금액 줄 주변에서 가맹점을 찾는다.
     *
     * 같은 줄에 있으면(`푸른들컨트리클럽 주식   16,000원`) 금액을 떼고 남은 부분을 쓰고,
     * 아니면 바로 앞뒤 몇 줄에서 가장 가까운 쓸 만한 텍스트를 고른다.
     * 우리WON피드는 금액 앞에, 실적 내역 조회는 금액보다 위쪽에 가맹점이 있다.
     */
    private fun findMerchant(lines: List<String>, index: Int, amountLine: String): String? {
        val sameLine = cleanMerchant(AMOUNT.replace(amountLine, " "))
        if (isUsableMerchant(sameLine)) return sameLine

        // 두 화면 모두 가맹점이 금액보다 **위**에 있다. 위쪽을 끝까지 본 다음에야 아래를 본다.
        // 실적 내역 조회는 사이에 `실적인정금액 / 5,000원 / 승인금액` 세 줄이 끼어 있어
        // 창을 좁게 잡으면 아래쪽 다음 거래의 가맹점을 잘못 집어 온다.
        for (offset in 1..6) {
            lines.getOrNull(index - offset)?.let { if (isUsableMerchant(it)) return it }
        }
        for (offset in 1..3) {
            lines.getOrNull(index + offset)?.let { if (isUsableMerchant(it)) return it }
        }
        return null
    }

    /**
     * 금액을 걷어낸 자리에 남는 부스러기를 턴다.
     * OCR 이 `원` 을 `l`·`2!` 로 읽어 놓으면 `늘푸른스물한세기약 l` 같은 꼴이 된다.
     */
    private fun cleanMerchant(text: String): String =
        text.trim()
            .trimEnd('|', '·', '-', ' ', 'l', 'I', '!', '2', '.', '%', ',', '>', ')')
            .trim()
            .trimStart('|', '·', '-', ' ')
            .trim()

    private fun isUsableMerchant(line: String): Boolean {
        if (line.length !in 2..40) return false
        if (AMOUNT.containsMatchIn(line)) return false
        if (LEADING_TIME.containsMatchIn(line)) return false
        if (KO_DATE.containsMatchIn(line) || DOTTED_DATE.containsMatchIn(line)) return false
        if (TEXT_NOISE.any { line.contains(it) }) return false
        if (line.contains('*')) return false
        if (line.none { it.isLetter() }) return false
        return true
    }

    /**
     * 같은 값이 연달아 두 번 나오는 경우를 하나로 줄인다.
     * `실적인정금액 5,000원` / `승인금액 5,000원` 처럼 한 결제를 두 줄로 적는 화면이 있다.
     */
    private fun dedupeAdjacent(rows: List<Row>): List<Row> =
        rows.filterIndexed { i, row ->
            val prev = rows.getOrNull(i - 1) ?: return@filterIndexed true
            !(prev.amount == row.amount &&
                prev.occurredAt == row.occurredAt &&
                prev.merchant == row.merchant)
        }
}
