package com.msyim.dulssencard.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * 집계 주기 계산.
 *
 * PRD §8: 카드별 주기는 매월 `시작일 00:00` 부터 다음 달 `시작일 직전`까지다.
 * 카드 주기와 개인 구매 추적 한도 주기는 서로 독립적으로 계산한다.
 * 기준 시간대는 Asia/Seoul 로 고정한다 — 기기 시간대를 바꿔도 주기 경계가 흔들리면 안 된다.
 */
object Cycle {

    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    /** 시작일은 1~28 만 허용한다. 29~31 은 없는 달이 생겨 주기가 건너뛴다. */
    fun normalizeStartDay(day: Int): Int = day.coerceIn(1, 28)

    /**
     * [at] 이 속한 주기의 창.
     *
     * 예) 시작일 15, at = 9/7 → [8/15 00:00, 9/15 00:00)
     *     시작일 15, at = 9/20 → [9/15 00:00, 10/15 00:00)
     */
    fun windowFor(startDay: Int, at: Instant = Instant.now()): Window {
        val day = normalizeStartDay(startDay)
        val now = at.atZone(ZONE)
        var start = now.toLocalDate().withDayOfMonth(day).atStartOfDay(ZONE)
        if (start.isAfter(now)) start = start.minusMonths(1)
        return Window(start, start.plusMonths(1))
    }

    data class Window(val start: ZonedDateTime, val endExclusive: ZonedDateTime) {

        val startMillis: Long get() = start.toInstant().toEpochMilli()
        val endMillis: Long get() = endExclusive.toInstant().toEpochMilli()

        /** 스냅샷 키. 시작일과 주기 시작 날짜로 한 주기를 유일하게 가리킨다. */
        fun key(prefix: String): String = "$prefix:${start.toLocalDate()}"

        fun contains(epochMillis: Long): Boolean =
            epochMillis >= startMillis && epochMillis < endMillis

        /**
         * 주기가 끝나기까지 남은 일수. 홈 헤더의 `D-N` 과 카드 행의 `남은 N일`에 쓴다.
         * 주기 마지막 날이면 1, 주기가 끝난 순간(다음 주기 첫날)이면 다음 주기 기준으로 다시 센다.
         */
        fun daysRemaining(at: Instant = Instant.now()): Long {
            val today: LocalDate = at.atZone(ZONE).toLocalDate()
            return ChronoUnit.DAYS.between(today, endExclusive.toLocalDate()).coerceAtLeast(0)
        }
    }
}
