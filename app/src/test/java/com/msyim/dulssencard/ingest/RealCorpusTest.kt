package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 실제 카드사 문구 코퍼스.
 *
 * 여기 들어 있는 문구는 지어낸 것이 아니라 **실제 카드사가 보낸 통지**다. 출처는 둘:
 *  - 사용자 기기에서 직접 채집한 카카오 알림톡 (우리·삼성) → PaymentParserTest
 *  - 공개된 파서 프로젝트의 테스트 코퍼스 (github.com/kakao/credit-card-sms-parser, deprecated)
 *
 * 문구 자체는 카드사가 생성한 정형 템플릿이고, 파싱 로직은 전부 새로 썼다.
 *
 * 이 코퍼스를 붙이면서 파서에서 고친 것들이 아래 테스트다. 하나하나가 실제로 깨졌던 케이스이므로
 * 파서를 손볼 때 여기부터 통과시킨다.
 */
class RealCorpusTest {

    private val received = at(2026, 4, 6, 15, 30)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private fun msg(body: String, receivedAt: Long = received) = RawMessage(
        source = TxSource.SMS,
        senderKey = "com.google.android.apps.messaging",
        title = null,
        body = body.trimIndent(),
        receivedAt = receivedAt,
    )

    // ---------------------------------------------------------------- 현대카드

    @Test
    fun `현대카드 - 날짜 시각이 아예 없는 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                [현대카드]-승인
                김재*님
                1,500원(일시불)
                마노핀익스프레스신림
                누적:354,220원
                """,
            ),
        )
        requireNotNull(parsed)
        assertEquals("HYUNDAI", parsed.issuerKey)
        assertEquals(1_500L, parsed.amount)
        assertEquals("마노핀익스프레스신림", parsed.merchant)
        // 문구에 시각이 없다. 수신 시각으로 대신하되 추정임을 남긴다.
        assertTrue(parsed.occurredAtEstimated)
        assertEquals(received, parsed.occurredAt)
        // 시각을 추정으로라도 채웠으니 자동 반영 대상이 된다.
        assertTrue(parsed.hasRequiredFields)
    }

    @Test
    fun `현대카드 - 통화 기호가 든 가맹점`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                [현대카드]-승인
                김재*님
                699,200원(일시불)
                BSP대한항공(￦)
                누적:844,350원
                """,
            ),
        )
        assertEquals(699_200L, parsed?.amount)
        assertEquals("BSP대한항공(￦)", parsed?.merchant)
    }

    @Test
    fun `한 줄 통지에서 가맹점 앞뒤 부호를 털어 낸다`() {
        // 회귀: 에뮬레이터 실측. `1,500원(일시불) 마노핀...` 에서 조각을 지우고 나면
        // `) 마노핀익스프레스신림` 처럼 짝 잃은 부호가 남았다.
        val hyundai = PaymentParser.parse(
            msg("[현대카드]-승인 김재*님 1,500원(일시불) 마노핀익스프레스신림 누적:354,220원"),
        )
        assertEquals("마노핀익스프레스신림", hyundai?.merchant)

        // 마침표로 구분하는 농협BC 형식.
        val nh = PaymentParser.parse(
            msg("농협BC(4*8*)오*름님. 04/14 11:51. 일시불81,400원. 누적금액679,780원. 버거킹 판교유스페"),
        )
        assertEquals("NH", nh?.issuerKey)
        assertEquals(81_400L, nh?.amount)
        assertEquals("버거킹 판교유스페", nh?.merchant)
    }

    // ---------------------------------------------------------------- 하나 · KEB하나

    @Test
    fun `하나카드 - 슬래시 구분 한 줄 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                하나(6*8*)김*호님 04/06 15:26 씨유판교 일시불/3,500원/누적-4,645원
                """,
            ),
        )
        requireNotNull(parsed)
        assertEquals("HANA", parsed.issuerKey)
        // 누적이 음수(-4,645원)로 붙어 있어도 결제 금액을 잘못 읽으면 안 된다.
        assertEquals(3_500L, parsed.amount)
        assertEquals(at(2026, 4, 6, 15, 26), parsed.occurredAt)
        assertEquals("씨유판교", parsed.merchant)
    }

    @Test
    fun `KEB하나 - 가맹점과 시각이 한 줄에 있는 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                KEB하나 박솔*님 4*6*
                일시불 30,000원
                (주)이니 03/23 09:58
                누적 2,368,397원
                """,
                receivedAt = at(2026, 3, 23, 10, 0),
            ),
        )
        requireNotNull(parsed)
        assertEquals("HANA", parsed.issuerKey)
        assertEquals(30_000L, parsed.amount)
        assertEquals(at(2026, 3, 23, 9, 58), parsed.occurredAt)
        assertEquals("(주)이니", parsed.merchant)
    }

    @Test
    fun `KEB하나 - 가맹점에 다른 카드사 이름이 들어간 한 줄 형식`() {
        // 회귀: 한 줄 폴백이 전체 카드사 키워드를 지워 `롯데쇼핑(` 이 `쇼핑(` 으로 잘렸다.
        // 카드사 판정도 하나(3번째 글자) vs 롯데(뒤쪽)에서 위치로 갈린다.
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                KEB하나  박우*님 7*5* 일시불     10,000원 롯데쇼핑( 04/18 18:49
                """,
                receivedAt = at(2026, 4, 18, 18, 50),
            ),
        )
        requireNotNull(parsed)
        assertEquals("HANA", parsed.issuerKey)
        assertEquals(10_000L, parsed.amount)
        assertTrue(
            "가맹점에서 카드사 이름이 잘리면 안 된다: ${parsed.merchant}",
            parsed.merchant?.contains("롯데쇼핑") == true,
        )
    }

    // ---------------------------------------------------------------- KB

    @Test
    fun `KB국민카드 - 표준 다줄 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                KB국민카드 2*5*
                정*욱님
                03/25 09:30
                2,200원
                미니스톱판교점
                누적 97,440원
                """,
                receivedAt = at(2026, 3, 25, 9, 31),
            ),
        )
        requireNotNull(parsed)
        assertEquals("KB", parsed.issuerKey)
        assertEquals(2_200L, parsed.amount)
        assertEquals("미니스톱판교점", parsed.merchant)
    }

    @Test
    fun `KB 체크카드출금 - 승인이라는 말도 원이라는 단위도 없는 형식`() {
        // 회귀: 방향어가 없어 통째로 버려졌고, 금액에 '원'이 없어 0으로 읽혔다.
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                [KB]04/08 21:27
                123456**789
                주식회사한울
                체크카드출금
                13,000
                """,
                receivedAt = at(2026, 4, 8, 21, 28),
            ),
        )
        assertNotNull("체크카드출금도 결제다", parsed)
        requireNotNull(parsed)
        assertEquals("KB", parsed.issuerKey)
        assertEquals(TxDirection.APPROVAL, parsed.direction)
        assertEquals(13_000L, parsed.amount)
        assertEquals(at(2026, 4, 8, 21, 27), parsed.occurredAt)
        assertEquals("주식회사한울", parsed.merchant)
    }

    @Test
    fun `KB 체크카드출금 - 잔액이 붙은 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                [KB]05/17 12:33
                435002**739
                햇살할인마트
                체크카드출금
                4,350
                잔액3,172,018
                """,
                receivedAt = at(2026, 5, 17, 12, 34),
            ),
        )
        requireNotNull(parsed)
        // 잔액 3,172,018 을 결제 금액으로 읽으면 안 된다.
        assertEquals(4_350L, parsed.amount)
        assertEquals("햇살할인마트", parsed.merchant)
    }

    @Test
    fun `KB 취소 - 가맹점 이름에 다른 카드사가 들어간 경우`() {
        // 회귀: 가맹점 `우리사랑동물병원` 의 "우리" 가 카드사 판정을 이겨 우리카드 거래가 됐다.
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                KB*카드
                김재호님
                05/29 20:52
                320,000원
                우리사랑동물병원 취소
                누적 560,060원
                """,
                receivedAt = at(2026, 5, 29, 20, 53),
            ),
        )
        requireNotNull(parsed)
        assertEquals("카드사를 가맹점 이름으로 판정하면 안 된다", "KB", parsed.issuerKey)
        assertEquals(TxDirection.CANCEL, parsed.direction)
        assertEquals(320_000L, parsed.amount)
    }

    @Test
    fun `KB국민체크 - 사용으로 끝나는 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                KB국민체크(6*3*)
                ***님
                04/24 12:13
                20,000원
                효소원판교점 사용
                """,
                receivedAt = at(2026, 4, 24, 12, 14),
            ),
        )
        requireNotNull(parsed)
        assertEquals("KB", parsed.issuerKey)
        assertEquals(20_000L, parsed.amount)
        assertTrue(parsed.merchant?.startsWith("효소원판교점") == true)
    }

    @Test
    fun `KB 해외 승인은 자동 반영하지 않는다`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                KB국민카드
                김*호님
                10/16 04:39
                475.54(US$)
                미국   GOOGLE * 승인
                """,
                receivedAt = at(2026, 10, 16, 4, 40),
            ),
        )
        requireNotNull(parsed)
        // 원화 금액이 없으므로 필수 항목을 못 채운다 → 확인 필요로 간다.
        assertEquals(0L, parsed.amount)
        assertTrue(!parsed.hasRequiredFields)
    }

    @Test
    fun `KB 체크카드출금 - 한 줄로 뭉쳐 온 경우`() {
        // 회귀: 에뮬레이터 실측에서 발견. 다줄 형식은 통과했지만 한 줄로 오면 금액이 0 이었다.
        // 앱 푸시는 대개 한 줄이라 실사용에서 항상 터졌을 케이스다.
        val parsed = PaymentParser.parse(
            msg(
                "[KB]09/08 14:27 123456**789 주식회사한울 체크카드출금 13,000",
                receivedAt = at(2026, 9, 8, 14, 28),
            ),
        )
        requireNotNull(parsed)
        assertEquals("KB", parsed.issuerKey)
        // 카드번호 123456**789 나 시각 14:27 을 금액으로 읽으면 안 된다.
        assertEquals(13_000L, parsed.amount)
        assertEquals(at(2026, 9, 8, 14, 27), parsed.occurredAt)
    }

    // ---------------------------------------------------------------- 삼성

    @Test
    fun `삼성카드 - 표준 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                삼성가족카드승인9785
                03/24 18:45
                10,000원
                일시불
                소문난우동
                """,
                receivedAt = at(2026, 3, 24, 18, 46),
            ),
        )
        requireNotNull(parsed)
        assertEquals("SAMSUNG", parsed.issuerKey)
        assertEquals(10_000L, parsed.amount)
        assertEquals("소문난우동", parsed.merchant)
    }

    @Test
    fun `삼성법인 - 방향어가 아예 없는 형식`() {
        // 회귀: '승인'이 없어 결제 통지로 인식되지 않고 통째로 버려졌다.
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                김*호님
                삼성법인6265
                04/22 19:56
                찌개애감동
                16,000원
                일시불
                잔액
                4,968,160원
                """,
                receivedAt = at(2026, 4, 22, 19, 57),
            ),
        )
        assertNotNull("방향어가 없어도 카드사·금액·시각이 있으면 결제다", parsed)
        requireNotNull(parsed)
        assertEquals("SAMSUNG", parsed.issuerKey)
        assertEquals(TxDirection.APPROVAL, parsed.direction)
        // 잔액 4,968,160 을 결제 금액으로 읽으면 안 된다.
        assertEquals(16_000L, parsed.amount)
        assertEquals("찌개애감동", parsed.merchant)
    }

    // ---------------------------------------------------------------- BC 계열

    @Test
    fun `씨티BC - 금액이 맨 앞에 오는 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [체크.승인]
                22,000원
                씨티BC(1*9*)김*정님
                01/23 12:34
                매일식당
                """,
                receivedAt = at(2026, 1, 23, 12, 35),
            ),
        )
        requireNotNull(parsed)
        assertEquals("BC", parsed.issuerKey)
        assertEquals(22_000L, parsed.amount)
        assertEquals("매일식당", parsed.merchant)
    }

    @Test
    fun `농협BC - 카드사 이름이 둘 겹치는 형식`() {
        // 회귀: 농협(2) 과 BC(2) 가 동점이라 카드사 판정을 포기했다.
        // 먼저 나온 쪽(농협)을 택한다.
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                농협BC(4*8*)오*름님.
                04/14 11:51.
                일시불81,400원.
                누적금액679,780원.
                버거킹 판교유스페
                """,
                receivedAt = at(2026, 4, 14, 11, 52),
            ),
        )
        requireNotNull(parsed)
        assertEquals("NH", parsed.issuerKey)
        assertEquals(81_400L, parsed.amount)
        assertEquals("버거킹 판교유스페", parsed.merchant)
    }

    // ---------------------------------------------------------------- 신한

    @Test
    fun `신한카드 - 한 줄 형식`() {
        val parsed = PaymentParser.parse(
            msg(
                """
                [Web발신]
                신한카드승인 강*혜(9*0*) 04/27 21:31 (일시불)39,500원 (주)페어몬트 누적688,800원
                """,
                receivedAt = at(2026, 4, 27, 21, 32),
            ),
        )
        requireNotNull(parsed)
        assertEquals("SHINHAN", parsed.issuerKey)
        assertEquals(39_500L, parsed.amount)
        assertEquals(at(2026, 4, 27, 21, 31), parsed.occurredAt)
        assertEquals("(주)페어몬트", parsed.merchant)
    }
}
