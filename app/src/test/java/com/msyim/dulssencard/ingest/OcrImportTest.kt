package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Cycle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 캡처 이미지 OCR 경로 회귀 테스트.
 *
 * 실기기에서 "사진에서 거의 못 읽어온다"는 제보로 드러난 버그를 잠근다.
 * 원인은 OCR 텍스트를 **줄 단위로** 파서에 넣은 것이었다. 카드 통지는 카드사·금액·시각·
 * 가맹점이 각기 다른 줄에 있어서 어느 한 줄도 필수 항목을 못 채우고 전부 버려졌다.
 *
 * 아래 문자열은 사용자 기기의 카카오 알림톡 화면을 OCR 했을 때 나오는 형태를 본뜬 것이다 —
 * 상태바·채널 헤더·버튼 라벨 같은 화면 부스러기가 함께 섞여 들어온다.
 */
class OcrImportTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private val now = at(2026, 9, 8, 6, 40)

    private suspend fun ingest(
        body: String,
        cards: List<Card> = emptyList(),
        existing: List<Txn> = emptyList(),
    ) = Ingestor.ingest(
        raw = RawMessage(
            source = TxSource.IMAGE,
            senderKey = "image-ocr",
            title = null,
            body = body,
            receivedAt = now,
        ),
        cards = cards,
        existingByFingerprint = { fp -> existing.firstOrNull { it.messageFingerprint == fp } },
        cancelOriginFinder = { _, _, _, _ -> null },
    )

    private fun inserted(outcome: Ingestor.Outcome): Txn {
        assertTrue("expected Insert but was $outcome", outcome is Ingestor.Outcome.Insert)
        return (outcome as Ingestor.Outcome.Insert).txn
    }

    /** 우리카드 알림톡 화면 캡처를 OCR 한 결과. */
    private val wooriScreen = """
        SKT 06:38
        우리카드
        1588-9955
        채널 추가
        알림톡 차단
        우리카드
        알림톡 도착
        카드승인금액
        16,000원
        [우리카드 이용 안내]
        우리(4321)승인
        홍*동님
        16,000원 일시불
        09/07 21:18
        푸른들컨트리클럽 주식
        누적1,234,567원
        채널 추가하고 이 채널의
        마케팅 메시지 등을
        카카오톡으로 받기
        채널 추가
        이용내역 확인하기
        포인트 조회하기
        21:18
        우리카드바로가기
    """.trimIndent()

    /** 삼성카드 알림톡 화면 캡처를 OCR 한 결과. */
    private val samsungScreen = """
        SKT 06:38
        삼성카드
        챗봇 채팅중
        2026년 9월 7일 월요일
        삼성카드
        알림톡 도착
        삼성가족2468승인 홍*동
        54,000원 일시불
        09/07 11:04
        (주)한빛상사
        11:05
        삼성카드바로가기
        챗봇에게 메시지 입력
    """.trimIndent()

    // ------------------------------------------------------------ 핵심 회귀

    @Test
    fun `줄 단위로 넣으면 올바른 거래가 안 나온다`() = runTest {
        // 고쳐지기 전 동작. 줄 하나에는 금액과 시각이 같이 있을 수 없어서,
        // 나오는 건 전부 금액 0원짜리 '확인 필요' 쓰레기다.
        // 게다가 그 쓰레기가 지문을 선점하면 뒤이은 올바른 파싱이 '중복'으로 막힌다.
        val results = wooriScreen.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { (ingest(it) as? Ingestor.Outcome.Insert)?.txn }

        assertTrue(
            "줄 단위로는 금액 16,000원 거래가 나올 수 없다: ${results.map { it.amount }}",
            results.none { it.amount == 16_000L },
        )
    }

    @Test
    fun `화면 전체를 한 덩어리로 넣으면 우리카드 결제를 읽는다`() = runTest {
        val txn = inserted(ingest(wooriScreen))
        assertEquals("WOORI", txn.issuerKey)
        assertEquals(16_000L, txn.amount)
        assertEquals(at(2026, 9, 7, 21, 18), txn.occurredAt)
        assertEquals("푸른들컨트리클럽 주식", txn.merchant)
        // 누적 1,234,567 원을 결제 금액으로 읽으면 안 된다.
        assertTrue(txn.amount < 100_000L)
    }

    @Test
    fun `삼성카드 화면도 읽는다`() = runTest {
        val txn = inserted(ingest(samsungScreen))
        assertEquals("SAMSUNG", txn.issuerKey)
        assertEquals(54_000L, txn.amount)
        assertEquals(at(2026, 9, 7, 11, 4), txn.occurredAt)
        assertEquals("(주)한빛상사", txn.merchant)
    }

    // ------------------------------------------------------------ 통지 분할

    @Test
    fun `통지가 하나면 자르지 않고 통째로 넘긴다`() {
        assertEquals(listOf(wooriScreen), OcrText.blocks(wooriScreen))
    }

    @Test
    fun `한 화면에 통지가 둘이면 각각 잘라 낸다`() = runTest {
        val twoNotices = """
            신한카드 알림
            신한카드(1234)승인 홍*동
            12,000원 일시불
            09/07 10:00
            스타벅스 서초
            누적100,000원
            신한카드(1234)승인 홍*동
            34,000원 일시불
            09/07 18:20
            이마트 성수
            누적134,000원
        """.trimIndent()

        val blocks = OcrText.blocks(twoNotices)
        assertEquals(2, blocks.size)

        val first = inserted(ingest(blocks[0]))
        val second = inserted(ingest(blocks[1], existing = listOf(first)))
        assertEquals(12_000L, first.amount)
        assertEquals(34_000L, second.amount)
        assertEquals("스타벅스 서초", first.merchant)
        assertEquals("이마트 성수", second.merchant)
    }

    @Test
    fun `삼성 알림톡 두 건이 한 화면에 있으면 각각 잡는다`() = runTest {
        // 사용자 기기의 실제 캡처 형태. 한 대화방에 알림톡 두 건이 이어져 있다.
        val twoAlimtalk = """
            SKT 10:15
            삼성카드
            챗봇 채팅중
            삼성카드
            알림톡 도착
            삼성5678승인 홍*동
            208,000원 일시불
            09/03 06:43
            가을숲스포츠
            누적1,876,500원
            이번달 이용내역 조회
            06:43
            2026년 9월 5일 토요일
            삼성카드
            알림톡 도착
            삼성5678승인 홍*동
            7,890원 일시불
            09/05 10:00
            한빛몰(프리미엄)
            누적1,884,390원
            이번달 이용내역 조회
            10:00
        """.trimIndent()

        val blocks = OcrText.blocks(twoAlimtalk)
        assertEquals(2, blocks.size)

        val first = inserted(ingest(blocks[0]))
        val second = inserted(ingest(blocks[1], existing = listOf(first)))
        assertEquals(208_000L, first.amount)
        assertEquals("가을숲스포츠", first.merchant)
        assertEquals(7_890L, second.amount)
        assertEquals("한빛몰(프리미엄)", second.merchant)
    }

    // ------------------------------------------------------------ 엉뚱한 캡처

    @Test
    fun `앱 설정 화면 캡처를 결제로 읽지 않는다`() = runTest {
        // 회귀: 실기기에서 실제로 터졌다. 한도 입력칸의 `1,000,000원` 을 결제로 읽어
        // 100만원짜리 거래가 만들어졌다. `결제 문자 수집` 라벨의 '결제'가 약한 신호로 잡혔다.
        val settingsCapture = """
            설정
            한도와 내 데이터
            개인 구매 추적 한도
            1,000,000원
            한도 저장
            자동 집계
            새 결제를 기기 안에서 자동 반영합니다
            알림 접근
            결제 문자 수집
            문자 앱 알림에서 결제 문자를 읽습니다
            암호화 내보내기
            비밀번호를 잃어버리면 복구할 수 없습니다.
        """.trimIndent()
        assertEquals(Ingestor.Outcome.NotAPayment, ingest(settingsCapture))
    }

    @Test
    fun `카드사를 못 찾은 이미지는 승인이 적혀 있어도 받지 않는다`() = runTest {
        val unknown = """
            영수증
            승인 12,000원
            09/07 10:00
            어느가게
        """.trimIndent()
        assertEquals(Ingestor.Outcome.NotAPayment, ingest(unknown))
    }

    // ------------------------------------------------------------ 화면 부스러기

    @Test
    fun `화면 부스러기만 있는 캡처는 결제로 보지 않는다`() = runTest {
        val junk = """
            SKT 06:38
            카카오톡
            친구
            채팅
            더보기
            오늘 저녁 뭐 먹을까?
        """.trimIndent()
        assertEquals(Ingestor.Outcome.NotAPayment, ingest(junk))
    }
}
