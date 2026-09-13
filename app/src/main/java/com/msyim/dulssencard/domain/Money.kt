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

    /**
     * "2시간 전" 같은 상대 시각. 수집 공백을 한눈에 알아채게 하는 데 쓴다.
     *
     * 일주일이 넘으면 날짜로 바꾼다 — "43일 전"은 계산을 강요하지만 "08/01"은 바로 읽힌다.
     * 미래 시각(기기 시계가 틀린 경우)은 "방금"으로 뭉갠다.
     */
    fun ago(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
        val minutes = (now - epochMillis) / 60_000L
        return when {
            minutes < 1 -> "방금"
            minutes < 60 -> "${minutes}분 전"
            minutes < 24 * 60 -> "${minutes / 60}시간 전"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)}일 전"
            else -> DAY_FORMAT.format(Instant.ofEpochMilli(epochMillis))
        }
    }

    private val DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd").withZone(Cycle.ZONE)
}
