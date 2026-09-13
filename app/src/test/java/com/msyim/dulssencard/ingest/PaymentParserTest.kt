package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 파서 회귀 테스트 (PRD §9 테스트 코퍼스).
 *
 * 여기 들어 있는 문구는 실제 문자를 옮긴 것이 아니라 형식만 본뜬 합성 샘플이다.
 * 카드사 형식이 바뀌면 여기에 케이스를 먼저 추가하고 파서를 고친다.
 */
class PaymentParserTest {

    private val received = at(2026, 9, 6, 19, 43)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private fun sms(body: String, receivedAt: Long = received) = RawMessage(
        source = TxSource.SMS,
        senderKey = "15771000",
        title = null,
        body = body.trimIndent(),
        receivedAt = receivedAt,
    )

    private fun push(pkg: String, title: String?, body: String, receivedAt: Long = received) =
        RawMessage(
            source = TxSource.PUSH,
            senderKey = pkg,
            title = title,
            body = body.trimIndent(),
            receivedAt = receivedAt,
        )

    // ------------------------------------------------------------------ 승인

    @Test
    fun `신한 승인 SMS 에서 금액 시각 가맹점을 뽑는다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)승인 홍*동
                84,300원 일시불
                09/06 19:42
                이마트 성수
                누적1,234,567원
                """,
            ),
        )
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals("SHINHAN", parsed.issuerKey)
        assertEquals(TxDirection.APPROVAL, parsed.direction)
        assertEquals(84_300L, parsed.amount)
        assertEquals("KRW", parsed.currency)
        assertEquals(at(2026, 9, 6, 19, 42), parsed.occurredAt)
        assertEquals("이마트 성수", parsed.merchant)
        assertEquals("1234", parsed.cardSuffix)
        assertFalse(parsed.installment)
        assertFalse(parsed.overseas)
        assertTrue(parsed.hasRequiredFields)
        assertTrue(parsed.confidence >= PaymentParser.AUTO_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `누적 실적 금액을 결제 금액으로 읽지 않는다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                KB국민 승인
                12,000원
                09/06 19:42
                스타벅스 서초
                누적 1,234,567원
                """,
            ),
        )
        assertEquals(12_000L, parsed?.amount)
    }

    @Test
    fun `줄바꿈 없는 한 줄 푸시도 결제 금액만 읽는다`() {
        val parsed = PaymentParser.parse(
            push(
                pkg = "com.hyundaicard.appcard",
                title = "현대카드",
                body = "현대카드 승인 26,900원 09/06 19:42 배달의민족 누적 980,000원",
            ),
        )
        assertEquals(26_900L, parsed?.amount)
        assertEquals("HYUNDAI", parsed?.issuerKey)
    }

    // ------------------------------------------------------------------ 취소

    @Test
    fun `취소 문자는 CANCEL 로 읽는다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)취소 홍*동
                84,300원
                09/06 20:10
                이마트 성수
                """,
            ),
        )
        assertEquals(TxDirection.CANCEL, parsed?.direction)
        assertEquals(84_300L, parsed?.amount)
    }

    @Test
    fun `승인취소 처럼 두 단어가 붙어 있어도 취소로 읽는다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                롯데카드 승인취소
                33,000원
                09/06 20:10
                올리브영 홍대
                """,
            ),
        )
        assertEquals(TxDirection.CANCEL, parsed?.direction)
    }

    // ------------------------------------------------------------------ 할부 · 해외

    @Test
    fun `할부 거래를 표시한다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                현대카드 승인
                홍*동
                1,200,000원 12개월할부
                09/06 19:42
                하이마트 강남
                """,
            ),
        )
        assertTrue(parsed!!.installment)
        assertEquals(1_200_000L, parsed.amount)
    }

    @Test
    fun `일시불은 할부가 아니다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)승인
                5,000원 일시불
                09/06 19:42
                편의점
                """,
            ),
        )
        assertFalse(parsed!!.installment)
    }

    @Test
    fun `해외 승인은 통화를 잡고 원화 금액을 만들지 않는다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)해외승인
                USD 12.00
                09/01 03:20
                AMAZON MKTPLACE
                """,
            ),
        )
        assertNotNull(parsed)
        assertTrue(parsed!!.overseas)
        assertEquals(0L, parsed.amount)
        assertEquals("USD", parsed.currency)
        assertFalse(parsed.hasRequiredFields)
    }

    // ------------------------------------------------------------------ 누락 케이스

    @Test
    fun `시각이 없으면 수신 시각으로 대신하고 추정임을 남긴다`() {
        // 현대카드처럼 날짜·시각이 아예 없는 형식이 실제로 있다(RealCorpusTest 참고).
        // 시각 미상으로 두면 그 카드사 거래가 전부 확인 필요로 쌓이므로,
        // 결제 직후 도착한다는 성질을 이용해 수신 시각으로 근사한다.
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드 승인
                12,000원
                스타벅스
                """,
            ),
        )
        assertNotNull(parsed)
        requireNotNull(parsed)
        assertEquals(received, parsed.occurredAt)
        assertTrue(parsed.occurredAtEstimated)
        assertTrue(parsed.hasRequiredFields)
    }

    @Test
    fun `금액이 없으면 0 이고 자동 반영 대상이 아니다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드 승인
                09/06 19:42
                스타벅스
                """,
            ),
        )
        assertEquals(0L, parsed!!.amount)
        assertFalse(parsed.hasRequiredFields)
    }

    // ------------------------------------------------------------------ 결제가 아닌 문자

    @Test
    fun `인증번호 문자는 결제로 보지 않는다`() {
        assertNull(PaymentParser.parse(sms("[Web발신] 인증번호 [482910] 를 입력해 주세요")))
    }

    @Test
    fun `광고 문자는 결제로 보지 않는다`() {
        assertNull(
            PaymentParser.parse(
                sms("[Web발신] (광고) 신한카드 여름 이벤트! 무료수신거부 080-000-0000"),
            ),
        )
    }

    @Test
    fun `결제일 안내처럼 약한 단어만 있는 문구는 버린다`() {
        assertNull(
            PaymentParser.parse(sms("[Web발신] 신한카드 결제일 안내입니다. 자세한 내용은 앱에서 확인하세요.")),
        )
    }

    // ------------------------------------------------------------------ 카카오 알림톡

    @Test
    fun `카카오 알림톡은 제목에서 카드사를 찾는다`() {
        val parsed = PaymentParser.parse(
            RawMessage(
                source = TxSource.KAKAO,
                senderKey = IssuerRegistry.KAKAO_PACKAGE,
                title = "삼성카드",
                body = """
                    승인 홍*동
                    45,000원 일시불
                    09/06 19:42
                    무신사
                """.trimIndent(),
                receivedAt = received,
            ),
        )
        assertEquals("SAMSUNG", parsed?.issuerKey)
        assertEquals(45_000L, parsed?.amount)
        assertEquals("무신사", parsed?.merchant)
    }

    // -------------------------------------------------- 실제 알림톡 (기기에서 채집)

    private fun kakao(title: String, body: String, receivedAt: Long = received) = RawMessage(
        source = TxSource.KAKAO,
        senderKey = IssuerRegistry.KAKAO_PACKAGE,
        title = title,
        body = body.trimIndent(),
        receivedAt = receivedAt,
    )

    @Test
    fun `우리카드 알림톡을 읽는다`() {
        // 2026-09-07 사용자 기기에서 채집한 실제 문구.
        // 함정 셋: 전각 공백이 낀 가맹점, 누적 실적, 하단 마케팅 문구(가맹점보다 길다).
        val parsed = PaymentParser.parse(
            kakao(
                title = "우리카드",
                body = """
                    [우리카드 이용 안내]
                    우리(4321)승인
                    홍*동님
                    16,000원 일시불
                    09/07 21:18
                    푸른들컨트리클럽　주식
                    누적1,234,567원
                    채널 추가하고 이 채널의 마케팅 메시지 등을 카카오톡으로 받기
                """,
                receivedAt = at(2026, 9, 7, 21, 19),
            ),
        )
        requireNotNull(parsed)
        assertEquals("WOORI", parsed.issuerKey)
        assertEquals(TxDirection.APPROVAL, parsed.direction)
        assertEquals(16_000L, parsed.amount)
        assertEquals(at(2026, 9, 7, 21, 18), parsed.occurredAt)
        assertEquals("4321", parsed.cardSuffix)
        assertEquals("푸른들컨트리클럽　주식", parsed.merchant)
        assertFalse(parsed.installment)
        assertTrue(parsed.hasRequiredFields)
        assertTrue(parsed.confidence >= PaymentParser.AUTO_CONFIDENCE_THRESHOLD)
    }

    @Test
    fun `삼성카드 알림톡을 읽는다`() {
        // 함정: 괄호 없는 카드 뒷자리(삼성가족2468승인), (주) 로 시작하는 상호.
        val parsed = PaymentParser.parse(
            kakao(
                title = "삼성카드",
                body = """
                    삼성가족2468승인 홍*동
                    54,000원 일시불
                    09/07 11:04
                    (주)한빛상사
                """,
                receivedAt = at(2026, 9, 7, 11, 5),
            ),
        )
        requireNotNull(parsed)
        assertEquals("SAMSUNG", parsed.issuerKey)
        assertEquals(54_000L, parsed.amount)
        assertEquals(at(2026, 9, 7, 11, 4), parsed.occurredAt)
        assertEquals("2468", parsed.cardSuffix)
        // 회귀: trim('(' , ')') 로 앞 괄호만 벗겨 "주)한빛상사" 가 되던 버그.
        assertEquals("(주)한빛상사", parsed.merchant)
    }

    @Test
    fun `알림톡 하단 마케팅 문구를 가맹점으로 잡지 않는다`() {
        val parsed = PaymentParser.parse(
            kakao(
                title = "우리카드",
                body = """
                    우리(4321)승인
                    9,000원 일시불
                    09/07 21:18
                    스타벅스
                    채널 추가하고 이 채널의 마케팅 메시지 등을 카카오톡으로 받기
                """,
            ),
        )
        assertEquals("스타벅스", parsed?.merchant)
    }

    @Test
    fun `한 줄 통지에서도 상호의 주 접두를 지키다`() {
        // 회귀: 에뮬레이터 실측에서 발견. 머리표 제거 정규식이 `(주)` 까지 지워
        // `(주)한빛상사` 가 `한빛상사` 로 잘렸다.
        val parsed = PaymentParser.parse(
            kakao(
                title = "삼성카드",
                body = "삼성가족2468승인 홍*동 54,000원 일시불 09/07 11:04 (주)한빛상사",
            ),
        )
        assertEquals("(주)한빛상사", parsed?.merchant)
    }

    @Test
    fun `한 줄 통지에서 Web발신 머리표는 지운다`() {
        val parsed = PaymentParser.parse(
            sms("[Web발신] 우리(4321)승인 홍*동님 16,000원 일시불 09/07 21:18 푸른들컨트리클럽 누적1,234,567원"),
        )
        assertEquals("푸른들컨트리클럽", parsed?.merchant)
        assertEquals(16_000L, parsed?.amount)
    }

    // ------------------------------------------------------------------ 연말연시 함정

    @Test
    fun `12월 31일 결제가 1월 1일에 처리돼도 연도를 넘기지 않는다`() {
        val receivedNewYear = at(2027, 1, 1, 0, 5)
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)승인
                9,000원 일시불
                12/31 23:58
                편의점
                """,
                receivedAt = receivedNewYear,
            ),
        )
        assertEquals(at(2026, 12, 31, 23, 58), parsed?.occurredAt)
    }

    // ------------------------------------------------------------------ 가맹점 추출

    @Test
    fun `한 줄로 오는 통지에서도 가맹점을 건진다`() {
        // 회귀: 에뮬레이터 실측에서 발견. 카드사 앱 푸시와 알림톡은 대개 한 줄이라
        // 줄 단위 소거법이 통째로 실패해 가맹점이 항상 미확인으로 나왔다.
        val parsed = PaymentParser.parse(
            sms("[Web발신] 신한카드(1234)승인 홍*동 84,300원 일시불 09/06 19:25 이마트 성수 누적1,234,567원"),
        )
        assertEquals("이마트 성수", parsed?.merchant)
        assertEquals(84_300L, parsed?.amount)
    }

    @Test
    fun `한 줄 푸시의 가맹점도 건진다`() {
        val parsed = PaymentParser.parse(
            push(
                pkg = "com.hyundaicard.appcard",
                title = "현대카드",
                body = "현대카드 승인 26,900원 09/06 19:42 배달의민족 누적 980,000원",
            ),
        )
        assertEquals("배달의민족", parsed?.merchant)
    }


    @Test
    fun `카드사 이름이 들어간 가맹점도 가맹점으로 읽는다`() {
        // 회귀: "롯데"가 롯데카드 키워드라는 이유로 가맹점 줄을 통째로 버리던 버그.
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)승인 홍*동
                84,300원 일시불
                09/06 19:42
                롯데백화점 잠실
                """,
            ),
        )
        assertEquals("롯데백화점 잠실", parsed?.merchant)
    }

    @Test
    fun `하나로마트처럼 카드사 이름으로 시작하는 가맹점도 살린다`() {
        val parsed = PaymentParser.parse(
            sms(
                """
                [Web발신]
                신한카드(1234)승인
                23,100원 일시불
                09/06 19:42
                하나로마트 성수점
                """,
            ),
        )
        assertEquals("하나로마트 성수점", parsed?.merchant)
    }

    // ------------------------------------------------------------------ 카드사 판정

    @Test
    fun `두 카드사 이름이 같이 나오면 더 구체적인 쪽을 고른다`() {
        assertEquals("SHINHAN", IssuerRegistry.detect("신한카드 승인 / 현대 제휴 가맹점")?.key)
    }

    @Test
    fun `카드사 이름이 둘 나오면 먼저 나온 쪽을 택한다`() {
        // 위치 우선 규칙. 실제 문구에서 카드사 이름은 맨 앞에 오고 가맹점은 뒤에 오므로
        // 위치가 곧 신뢰도다(농협BC, KB*카드 + 우리사랑동물병원 사례 — RealCorpusTest 참고).
        assertEquals("SHINHAN", IssuerRegistry.detect("신한카드 및 현대카드 공동 이벤트 안내")?.key)
    }

    @Test
    fun `카드사 이름이 둘 나오는 홍보 문구는 결제로 보지 않는다`() {
        // 위 규칙이 느슨해 보여도, 결제 여부 판정은 금액·시각·방향어가 따로 막는다.
        assertNull(PaymentParser.parse(sms("[Web발신] 신한카드 및 현대카드 공동 이벤트 안내입니다")))
    }
}
