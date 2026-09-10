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

    /** 입력 중 천 단위 쉼표 자동 삽입. 빈 값이면 빈 문자열을 돌려준다. */
    fun reformatInput(input: String): String {
        val value = parseAmount(input) ?: return ""
        return grouped(value)
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
