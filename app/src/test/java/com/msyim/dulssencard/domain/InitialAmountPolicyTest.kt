package com.msyim.dulssencard.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 초기 사용액을 **저장할 때 기준 시각을 어떻게 찍는가**의 회귀 테스트.
 *
 * [InitialAmountTest] 는 저장된 값을 읽어 합계를 내는 쪽을 잠그고, 이 파일은 그 값을
 * 만들어 내는 쪽을 잠근다. 둘 다 필요하다 — 읽기가 아무리 정확해도 저장할 때 기준 시각을
 * 지난 주기에 남겨 두면 사용자가 입력한 금액이 통째로 0으로 읽힌다.
 *
 * 실제로 그런 버그가 있었다. 기준 시각을 "금액이 바뀌었는가"로만 갱신해서,
 * 10월에 9월과 **같은 금액**을 옮겨 적으면 기준 시각이 9월에 머물렀다.
 */
class InitialAmountPolicyTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    /** 주기 시작일 1일. 9월 주기는 9/1~9/30, 10월 주기는 10/1~10/31 이다. */
    private val startDay = 1

    private val sep08 = at(2026, 9, 8, 15, 0)
    private val sep20 = at(2026, 9, 20, 11, 0)
    private val oct05 = at(2026, 10, 5, 9, 30)

    private fun resolve(
        existingAmount: Long,
        existingAt: Long,
        input: Long?,
        now: Long,
    ) = InitialAmountPolicy.resolve(
        existingAmount = existingAmount,
        existingAt = existingAt,
        input = input,
        cycleStartDay = startDay,
        now = now,
    )

    // ---------------------------------------------------- 주기 전환 (가장 위험)

    @Test
    fun `주기가 바뀐 뒤 같은 금액을 다시 넣으면 기준 시각을 지금으로 다시 찍는다`() {
        // 9월에 30만원을 넣었고, 10월에 카드앱을 보니 또 30만원이라 그대로 옮겨 적었다.
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = 300_000L, now = oct05)

        assertEquals(300_000L, result.amount)
        // 여기서 sep08 을 유지하면 Aggregator 가 "이번 주기 값이 아니다"라며 0 으로 읽는다.
        assertEquals(oct05, result.at)
    }

    @Test
    fun `주기가 바뀐 뒤 다른 금액을 넣어도 지금으로 찍는다`() {
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = 420_000L, now = oct05)

        assertEquals(420_000L, result.amount)
        assertEquals(oct05, result.at)
    }

    @Test
    fun `지난 주기 값이 있어도 새 주기 입력은 그 주기 안에서 유효하다`() {
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = 300_000L, now = oct05)
        val window = Cycle.windowFor(startDay, java.time.Instant.ofEpochMilli(oct05))

        assertEquals(true, window.contains(result.at))
    }

    // ---------------------------------------------------- 같은 주기 안에서

    @Test
    fun `같은 주기에서 금액을 고치면 기준 시각도 지금으로 옮긴다`() {
        // 통지를 놓쳐 합계가 어긋났을 때 카드앱 숫자를 다시 넣는 복구 경로다.
        // 그 사이 들어온 거래는 이미 새 금액에 포함돼 있으므로 기준 시각을 옮겨야 한다.
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = 350_000L, now = sep20)

        assertEquals(350_000L, result.amount)
        assertEquals(sep20, result.at)
    }

    @Test
    fun `같은 주기 같은 금액이면 기준 시각을 유지한다`() {
        // 별명만 고치고 저장을 눌렀다고 해서, 그 사이 도착한 거래가 합계에서 사라지면 안 된다.
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = 300_000L, now = sep20)

        assertEquals(300_000L, result.amount)
        assertEquals(sep08, result.at)
    }

    // ---------------------------------------------------- 새 카드

    @Test
    fun `초기값이 없던 카드에 금액을 넣으면 지금이 기준이다`() {
        val result = resolve(existingAmount = 0L, existingAt = 0L, input = 300_000L, now = sep08)

        assertEquals(300_000L, result.amount)
        assertEquals(sep08, result.at)
    }

    @Test
    fun `초기값을 안 넣은 새 카드는 0 그대로다`() {
        val result = resolve(existingAmount = 0L, existingAt = 0L, input = null, now = sep08)

        assertEquals(0L, result.amount)
        assertEquals(0L, result.at)
    }

    // ---------------------------------------------------- 입력칸을 비웠을 때

    @Test
    fun `입력칸이 비어 있어도 지난 주기 기록은 지우지 않는다`() {
        // 화면은 지난 주기 초기값을 비워서 보여 준다(이번 주기에 유효하지 않으므로).
        // 그 상태에서 별명만 고치고 저장한 것을 '지우겠다'로 읽으면 입력 기록이 날아간다.
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = null, now = oct05)

        assertEquals(300_000L, result.amount)
        assertEquals(sep08, result.at)
    }

    @Test
    fun `이번 주기 값을 비우면 초기값을 쓰지 않는 카드가 된다`() {
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = null, now = sep20)

        assertEquals(0L, result.amount)
        assertEquals(0L, result.at)
    }

    @Test
    fun `0 을 직접 넣으면 초기값을 쓰지 않는다`() {
        val result = resolve(existingAmount = 300_000L, existingAt = sep08, input = 0L, now = sep20)

        assertEquals(0L, result.amount)
        assertEquals(0L, result.at)
    }

    // ---------------------------------------------------- 주기 시작일이 1일이 아닐 때

    @Test
    fun `시작일이 15일인 카드도 주기 경계로 판단한다`() {
        val sep10 = at(2026, 9, 10, 12, 0) // 8/15~9/15 주기
        val sep20same = at(2026, 9, 20, 12, 0) // 9/15~10/15 주기 — 경계를 넘었다
        val result = InitialAmountPolicy.resolve(
            existingAmount = 200_000L,
            existingAt = sep10,
            input = 200_000L,
            cycleStartDay = 15,
            now = sep20same,
        )

        assertEquals(sep20same, result.at)
    }
}
