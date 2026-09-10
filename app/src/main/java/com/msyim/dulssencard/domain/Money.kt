package com.msyim.dulssencard.domain

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 금액·시각 표기. 모든 금액은 모노스페이스로 그려지므로 자리수 정렬이 유지된다. */
object Money {

    private val KO = Locale.KOREA

    /** `1,234,000원`. 음수는 앞에 `-`. */
    fun won(amount: Long): String {
        val sign = if (amount < 0) "-" else ""
        return sign + grouped(kotlin.math.abs(amount)) + "원"
    }

    /** 단위 없이 `1,234,000`. 입력 필드의 천 단위 쉼표에 쓴다. */
    fun grouped(amount: Long): String = String.format(KO, "%,d", amount)

    /**
     * 외화 금액 `1,234.56`. 원화로 환산하지 않으므로 통화 기호는 호출자가 붙인다.
     *
     * 로케일을 명시하는 이유: 기본 로케일에 맡기면 기기 설정에 따라 `1.234,56` 이 되어
     * 소수점과 자릿수 구분자가 뒤바뀐다.
     */
    fun foreign(amount: Double): String = String.format(KO, "%,.2f", amount)

    /**
     * 입력칸이 받아 줄 최대 자릿수. Long 을 넘겨 `toLongOrNull()` 이 null 을 돌려주면
     * 입력칸이 통째로 비워지므로, 넘치기 전에 자른다(1조원이면 어떤 카드 한도보다도 크다).
     */
    const val MAX_INPUT_DIGITS = 13

    /**
     * 입력 문자열에서 숫자만 뽑아 정수로. 비었거나 숫자가 없으면 null.
     * 카드 편집·한도 설정의 검증에서 쓴다(0 이면 저장 실패).
     */
    fun parseAmount(input: String): Long? {
        val digits = onlyDigits(input)
        if (digits.isEmpty()) return null
        return digits.toLongOrNull()
    }

    /**
     * 입력 문자열에서 숫자만 남기고 자릿수를 [MAX_INPUT_DIGITS] 로 자른다.
     * 앞자리 0 도 털어 낸다 — `007` 을 그대로 두면 표시가 `007` 로 남는다.
     */
    fun onlyDigits(input: String): String =
        input.filter { it.isDigit() }
            .trimStart('0')
            .take(MAX_INPUT_DIGITS)

    /**
     * 숫자 문자열에 천 단위 쉼표만 끼워 넣는다.
     *
     * [grouped] 와 달리 Long 으로 바꾸지 않는다. 그래서 자릿수가 아무리 길어도, 앞자리에
     * 0 이 있어도 **숫자 개수와 표시 글자 수의 관계가 어긋나지 않는다.**
     * 입력칸의 커서 위치 계산([groupedOffset])이 이 성질에 기댄다.
     */
    fun group(digits: String): String =
        digits.reversed().chunked(3).joinToString(",").reversed()

    /**
     * 숫자만 세어 구한 커서 위치를, 쉼표가 끼워진 표시 문자열에서의 위치로 옮긴다.
     *
     * 입력칸이 값을 매 글자마다 다시 포맷해 넣으면 커서가 끝으로 튀어 중간 수정이 불가능해진다.
     * 그래서 상태에는 숫자만 담고 쉼표는 표시할 때만 끼우는데, 그러려면 두 좌표계를
     * 오갈 방법이 필요하다.
     */
    fun groupedOffset(digitCount: Int, offset: Int): Int {
        if (digitCount <= 0) return 0
        val rest = digitCount - offset.coerceIn(0, digitCount)
        val commasAfterCursor = if (rest <= 0) 0 else (rest - 1) / 3
        val formattedLength = digitCount + (digitCount - 1) / 3
        return (formattedLength - rest - commasAfterCursor).coerceIn(0, formattedLength)
    }
}

/** 거래 시각 표기. 저장은 UTC epoch millis, 표시는 Asia/Seoul. */
object Times {

    private val LIST_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(Cycle.ZONE)

    private val LOG_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(Cycle.ZONE)

    /** `09/06 19:42`. 시각 미상이면 `--/-- --:--`. */
    fun listStamp(epochMillis: Long?): String =
        epochMillis?.let { LIST_FORMAT.format(Instant.ofEpochMilli(it)) } ?: "--/-- --:--"

    fun logStamp(epochMillis: Long): String = LOG_FORMAT.format(Instant.ofEpochMilli(epochMillis))
}
