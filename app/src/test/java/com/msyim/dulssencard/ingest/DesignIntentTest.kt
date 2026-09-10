package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * **의도적으로 이렇게 만든 것들**을 잠그는 테스트.
 *
 * 아래 셋은 전부 한 번씩 "버그"로 오인돼 되돌려진 적이 있다(2026-09-10). 겉보기에는
 * 개선처럼 보이지만 실제로는 이 앱이 지키려는 성질을 깨뜨린다. 고치기 전에 여기 주석을
 * 읽고, 그래도 고쳐야겠다면 **이 테스트부터 바꿔라.** 테스트가 조용히 빨개지는 편이
 * 사용자 돈이 조용히 틀어지는 것보다 낫다.
 */
class DesignIntentTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    // ------------------------------------------------------- ① 주기 경계는 반개방 구간

    /**
     * 제기된 주장: *"주기 시작 시각에 설정된 초기값이 양쪽 주기에 중복 집계된다."*
     *
     * 사실이 아니다. [Cycle.Window.contains] 는 `start <= t < end` 인 **반개방 구간**이라
     * 연속한 두 주기 `[s1,e1)`, `[e1,e2)` 에서 한 시각은 **정확히 한쪽에만** 속한다.
     * 중복될 수 없다.
     *
     * 그런데 "고친" 코드는 `t > start` 로 바꿔 시작 시각을 어느 주기에도 넣지 않았다.
     * 주기 시작 정각에 초기값을 넣은 사용자는 그 금액이 **조용히 사라진다.**
     */
    @Test
    fun `주기 시작 정각에 넣은 초기값도 이번 주기에 센다`() {
        val startDay = 1
        val window = Cycle.windowFor(
            startDay,
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 15, 12, 0), Cycle.ZONE).toInstant(),
        )
        val card = Card(
            id = "c1",
            nickname = "테스트",
            trackingTarget = 1_000_000,
            cycleStartDay = startDay,
            matchKeywords = listOf("테스트"),
            excludeKeywords = emptyList(),
            defaultCountsTowardTarget = true,
            defaultCountsTowardPurchaseLimit = true,
            initialAmount = 320_000L,
            // 주기가 시작하는 바로 그 밀리초.
            initialAmountAt = window.startMillis,
        )
        val progress = Aggregator.cardProgress(
            card = card,
            txns = emptyList(),
            now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 15, 12, 0), Cycle.ZONE).toInstant(),
        )
        assertEquals("주기 시작 정각의 초기값이 사라졌다", 320_000L, progress.spent)
    }

    @Test
    fun `한 시각은 두 주기에 동시에 속하지 않는다`() {
        // 위 주장이 왜 성립하지 않는지 직접 보인다.
        val sep = Cycle.windowFor(
            1,
            ZonedDateTime.of(LocalDateTime.of(2026, 9, 15, 12, 0), Cycle.ZONE).toInstant(),
        )
        val aug = Cycle.windowFor(
            1,
            ZonedDateTime.of(LocalDateTime.of(2026, 8, 15, 12, 0), Cycle.ZONE).toInstant(),
        )
        assertEquals("8월 주기의 끝이 9월 주기의 시작이어야 한다", aug.endMillis, sep.startMillis)
        assertTrue(sep.contains(sep.startMillis))
        assertFalse("경계 시각이 지난 주기에도 잡힌다 — 이러면 진짜 중복이다", aug.contains(sep.startMillis))
    }

    // ------------------------------------------------------- ② 가맹점 비교는 접두사 규칙

    /**
     * 제기된 주장: *"접두사 매칭만으로는 `스타벅스 강남점` 과 `스타벅스강남점` 을 구분 못 한다."*
     *
     * 사실이 아니다. [Fingerprint] 의 정규화가 글자·숫자만 남기므로 공백은 이미 사라진다.
     * 두 표기는 정규화 후 **완전히 같아져** 접두사 규칙으로 잘 처리된다.
     *
     * 그 위에 얹힌 "개선"은 Levenshtein 유사도 85% 이상을 같은 가게로 봤다. 방향이 위험하다 —
     * 이 함수가 false 를 돌려주면 [Ingestor] 는 그 결제를 **버린다.** 이름이 비슷한 다른 가게
     * (인접 지점, 같은 프랜차이즈)에서 같은 분에 같은 금액을 긁으면 한 건이 조용히 사라진다.
     *
     * 이 앱의 규칙은 반대다 — **확신이 없으면 버리지 말고 사용자에게 넘긴다.**
     * 결제를 잃는 것이 확인 한 번 더 하는 것보다 나쁘다.
     */
    @Test
    fun `표기만 다른 같은 가게는 충돌이 아니다`() {
        assertFalse(Fingerprint.merchantsConflict("스타벅스 강남점", "스타벅스강남점"))
        assertFalse(Fingerprint.merchantsConflict("이마트 성수", "이마트성수점"))
        assertFalse(Fingerprint.merchantsConflict("스타벅스", "스타벅스 역삼"))
    }

    @Test
    fun `이름이 비슷해도 다른 가게면 충돌로 본다`() {
        // 편집거리로 재면 매우 가깝다. 그래도 다른 지점이므로 앱이 임의로 하나를 버리면 안 된다.
        assertTrue(Fingerprint.merchantsConflict("스타벅스 강남점", "스타벅스 강북점"))
        assertTrue(Fingerprint.merchantsConflict("CU 역삼1호점", "CU 역삼2호점"))
    }

    @Test
    fun `가맹점을 모르면 판단하지 않는다`() {
        // 푸시에는 가맹점이 없는 경우가 흔하다. 없다고 해서 다른 가게라고 단정하지 않는다.
        assertFalse(Fingerprint.merchantsConflict(null, "스타벅스"))
        assertFalse(Fingerprint.merchantsConflict("스타벅스", ""))
    }

    // ------------------------------------------------------- ③ 쉼표 없는 금액도 금액이다

    /**
     * `WON_AMOUNT` 에서 `|[0-9]+` 가지가 빠진 적이 있다. 그러면 **천 단위 쉼표가 있는 금액만**
     * 인식한다. 1,000원 미만 결제는 쉼표가 붙을 자리가 없어 통째로 안 읽힌다.
     *
     * 편의점·카페 소액 결제가 여기 걸린다. 테스트 코퍼스에 900원짜리가 없어서
     * 150개가 전부 통과하는데도 실사용에서만 깨지는 종류의 회귀였다.
     */
    @Test
    fun `천원 미만 결제도 읽는다`() {
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.SMS,
                senderKey = "com.samsung.android.messaging",
                title = null,
                body = """
                    신한카드(1234)승인 홍*동
                    900원 일시불
                    09/10 08:15
                    편의점
                """.trimIndent(),
                receivedAt = at(2026, 9, 10, 8, 16),
            ),
        )
        assertNotNull("900원이 안 읽힌다", parsed)
        assertEquals(900L, parsed!!.amount)
    }

    @Test
    fun `쉼표 없이 쓴 네 자리 금액도 읽는다`() {
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.SMS,
                senderKey = "com.samsung.android.messaging",
                title = null,
                body = """
                    신한카드(1234)승인 홍*동
                    5000원 일시불
                    09/10 08:15
                    편의점
                """.trimIndent(),
                receivedAt = at(2026, 9, 10, 8, 16),
            ),
        )
        assertNotNull("5000원이 안 읽힌다", parsed)
        assertEquals(5_000L, parsed!!.amount)
    }

    @Test
    fun `쉼표 있는 금액은 그대로 읽는다`() {
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.SMS,
                senderKey = "com.samsung.android.messaging",
                title = null,
                body = """
                    신한카드(1234)승인 홍*동
                    84,300원 일시불
                    09/10 08:15
                    편의점
                """.trimIndent(),
                receivedAt = at(2026, 9, 10, 8, 16),
            ),
        )
        assertEquals(84_300L, parsed?.amount)
    }
}
