package com.msyim.dulssencard.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 금액 표기·입력 회귀 테스트.
 *
 * [Money.group] 과 [Money.groupedOffset] 은 금액 입력칸의 커서 위치 계산에 쓰인다.
 * 둘이 어긋나면 커서가 엉뚱한 데로 가거나 인덱스 범위를 벗어난다.
 */
class MoneyTest {

    @Test
    fun `천 단위마다 쉼표를 넣는다`() {
        assertEquals("", Money.group(""))
        assertEquals("1", Money.group("1"))
        assertEquals("123", Money.group("123"))
        assertEquals("1,234", Money.group("1234"))
        assertEquals("12,345", Money.group("12345"))
        assertEquals("123,456", Money.group("123456"))
        assertEquals("1,234,567", Money.group("1234567"))
    }

    @Test
    fun `커서를 맨 앞과 맨 뒤에 두면 그대로 앞과 뒤다`() {
        assertEquals(0, Money.groupedOffset(7, 0))
        assertEquals("1,234,567".length, Money.groupedOffset(7, 7))
        assertEquals(0, Money.groupedOffset(0, 0))
    }

    @Test
    fun `커서 위치가 표시 문자열 안에서 자릿수와 맞는다`() {
        // "1,234,567" 에서 숫자 k 개 뒤의 위치는 k + (그 앞에 낀 쉼표 수)다.
        val digits = "1234567"
        val formatted = Money.group(digits)
        for (k in 0..digits.length) {
            val offset = Money.groupedOffset(digits.length, k)
            assertEquals(
                "숫자 ${k}개 뒤의 커서 위치가 어긋났다",
                k,
                formatted.take(offset).count { it.isDigit() },
            )
        }
    }

    @Test
    fun `모든 자릿수에서 커서 위치가 범위를 벗어나지 않는다`() {
        for (n in 0..Money.MAX_INPUT_DIGITS) {
            val length = Money.group("1".repeat(n)).length
            for (k in 0..n) {
                val offset = Money.groupedOffset(n, k)
                assertEquals(true, offset in 0..length)
            }
        }
    }

    @Test
    fun `범위 밖 커서도 잘라서 돌려준다`() {
        assertEquals(Money.groupedOffset(4, 4), Money.groupedOffset(4, 99))
        assertEquals(Money.groupedOffset(4, 0), Money.groupedOffset(4, -3))
    }

    @Test
    fun `숫자만 남기고 앞자리 0 을 턴다`() {
        assertEquals("1234", Money.onlyDigits("1,234"))
        assertEquals("1234", Money.onlyDigits("1,234원"))
        assertEquals("7", Money.onlyDigits("007"))
        assertEquals("", Money.onlyDigits("0"))
        assertEquals("", Money.onlyDigits("가나다"))
    }

    @Test
    fun `자릿수가 넘치면 잘라서 입력칸이 비워지지 않게 한다`() {
        // 예전에는 Long 범위를 넘기면 parseAmount 가 null 을 돌려주고,
        // 그걸 받은 입력칸이 통째로 비워졌다.
        val tooLong = "9".repeat(30)
        assertEquals(Money.MAX_INPUT_DIGITS, Money.onlyDigits(tooLong).length)
        assertEquals(true, Money.parseAmount(tooLong) != null)
    }

    @Test
    fun `숫자가 없으면 null 이다`() {
        assertNull(Money.parseAmount(""))
        assertNull(Money.parseAmount("원"))
    }

    @Test
    fun `외화는 로케일과 무관하게 소수 두 자리로 적는다`() {
        assertEquals("1,234.56", Money.foreign(1234.56))
        assertEquals("0.99", Money.foreign(0.99))
        assertEquals("-30.00", Money.foreign(-30.0))
    }

    @Test
    fun `원화는 음수에 마이너스를 붙인다`() {
        assertEquals("1,234,000원", Money.won(1_234_000))
        assertEquals("-84,300원", Money.won(-84_300))
        assertEquals("0원", Money.won(0))
    }
}
