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
     * 입력 문자열에서 숫자만 뽑아 정수로. 비었거나 숫자가 없으면 null.
     * 카드 편집·한도 설정의 검증에서 쓴다(0 이면 저장 실패).
     */
    fun parseAmount(input: String): Long? {
        val digits = input.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return digits.toLongOrNull()
    }

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
