package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.domain.Cycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * **실기기에서 실제로 나온 OCR 출력**으로 만든 회귀 테스트.
 *
 * 아래 문자열은 갤럭시 S24+ 에서 우리WON피드 화면을 캡처해 ML Kit 으로 읽은 결과다.
 * 합성 샘플로는 절대 나오지 않는 실패 양상이 들어 있다.
 *
 * **가맹점 이름·카드 뒷자리·이름은 합성값으로 바꿔 두었다**(저장소를 공개하면서).
 * 바꾼 것은 그 문자열들뿐이고, 글자 수·공백·괄호처럼 파서를 깨뜨렸던 성질은 그대로다 —
 * 그래서 이 파일이 여전히 회귀 테스트로 기능한다. 금액과 OCR 오인식 패턴은 실측 그대로다.
 *
 * ## 여기서 드러난 것
 *
 * `원` 글자를 제대로 못 읽는다:
 * `16,000원` → `16,000`, `12,820원` → `12,820l`, `5,600원` → `5,6002!`
 *
 * 금액 정규식이 `원` 을 필수로 요구하고 있었기 때문에, 화면 전체에서 금액을
 * **한 건도** 잡지 못했다. 사용자에게는 "이 파일은 내용을 하나도 못 읽어"로 나타났다.
 */
class DeviceOcrTest {

    private val now = ZonedDateTime.of(LocalDateTime.of(2026, 9, 8, 13, 40), Cycle.ZONE).toInstant()

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    /** 갤럭시 S24+ 실측 OCR 출력. 오타처럼 보이는 부분이 실제 출력이다. */
    private val wooriFeedFromDevice = """
        SKT 09:16 V6 TALK l 100
        < 우리WON피드
        9월 이용금액 171,1202!
        9월 7일 월요일 142,620l
        21:18 본인 일시불
        푸른들컨트리클럽 주식 16,000
        19:32 본인 일시불
        너른마당 85,000
        12:43| 본인 일시불
        늘푸른스물한세기약 12,820l
        12:32 | 본인 일시불
        새봄의원 5,6002!
        10:46 본인 일시불
        한마음 홀세일클럽 중앙점 5,000
        10:44 본인 일시불
        바른이치과의원 18,200l
        대형마트친화 홍길동님 맞춤 카드 X
        카드의정석2 7CORE
        일주일간 보지 않기
    """.trimIndent()

    /**
     * 우리카드 앱 `이용내역` 화면. 갤럭시 S24+ 실측 OCR 출력.
     *
     * 앞의 우리WON피드와 구조가 다르다 — 날짜 헤더가 `9월 7일` 하나뿐이고
     * 행마다 시각이 없다. 대신 카드 정보(`VISA 본인 4321 신용 일시불`)가 한 줄씩 붙는다.
     */
    private val wooriUsageFromDevice = """
        SKT 14:13 pay l81
        이용내역
        9월 7일
        푸른들컨트리클럽 주식 16,000
        VISA 본인 4321 신용 일시불
        너른마당 85,000
        VISA 본인 4321 신용 일시불
        늘푸른스물한세기약 12,8202
        VISA 본인 4321 신용 일시불
        새봄의원 5,600%
        VISA 본인 4321 신용 일시불
        한마음 홀세일클럽 중앙점 5,000
        VISA 본인 4321 신용 일시불
        분할납부 >
        바른이치과의원 18,200
        VISA 본인 4321 신용 일시불
    """.trimIndent()

    @Test
    fun `행별 시각이 없는 이용내역 화면도 목록으로 인식한다`() {
        // 회귀: 시각·날짜 표시를 합쳐 2개 이상 요구했더니, 날짜 헤더 하나뿐인
        // 이 화면이 목록으로 인식되지 못하고 통지 파서로 흘러가 통째로 버려졌다.
        assertTrue(LedgerScreenParser.looksLikeLedger(wooriUsageFromDevice))
    }

    @Test
    fun `이용내역 화면에서 여섯 건을 읽는다`() {
        val rows = LedgerScreenParser.parse(wooriUsageFromDevice, now)
        assertEquals("$rows", 6, rows.size)
        assertEquals(
            listOf(16_000L, 85_000L, 12_820L, 5_600L, 5_000L, 18_200L),
            rows.map { it.amount },
        )
        assertEquals(
            listOf(
                "푸른들컨트리클럽 주식",
                "너른마당",
                "늘푸른스물한세기약",
                "새봄의원",
                "한마음 홀세일클럽 중앙점",
                "바른이치과의원",
            ),
            rows.map { it.merchant },
        )
    }

    @Test
    fun `행별 시각이 없으면 추정으로 표시한다`() {
        val rows = LedgerScreenParser.parse(wooriUsageFromDevice, now)
        assertTrue(rows.all { it.occurredAtEstimated })
        // 날짜는 헤더에서 살아 있어야 주기 집계가 맞는다.
        assertTrue(rows.all { it.occurredAt > at(2026, 9, 6, 23, 59) })
        assertTrue(rows.all { it.occurredAt < at(2026, 9, 8, 0, 0) })
    }

    @Test
    fun `이용내역 화면은 카드 뒷자리로만 카드를 알 수 있다`() {
        // 이 화면에는 카드사 이름이 없다. 카드 인식 키워드에 뒷 4자리를 넣어야 맞는다.
        assertEquals(null, IssuerRegistry.detect(wooriUsageFromDevice))
        assertTrue(wooriUsageFromDevice.contains("4321"))
    }

    @Test
    fun `목록 화면으로 인식한다`() {
        assertTrue(LedgerScreenParser.looksLikeLedger(wooriFeedFromDevice))
    }

    @Test
    fun `원 글자를 못 읽어도 여섯 건을 모두 읽는다`() {
        val rows = LedgerScreenParser.parse(wooriFeedFromDevice, now)
        assertEquals("실기기 OCR 에서 6건이 나와야 한다: $rows", 6, rows.size)
        assertEquals(
            listOf(16_000L, 85_000L, 12_820L, 5_600L, 5_000L, 18_200L),
            rows.map { it.amount },
        )
    }

    @Test
    fun `가맹점 이름에 금액 찌꺼기가 남지 않는다`() {
        val rows = LedgerScreenParser.parse(wooriFeedFromDevice, now)
        assertEquals(
            listOf(
                "푸른들컨트리클럽 주식",
                "너른마당",
                "늘푸른스물한세기약",
                "새봄의원",
                "한마음 홀세일클럽 중앙점",
                "바른이치과의원",
            ),
            rows.map { it.merchant },
        )
    }

    @Test
    fun `시각을 행마다 제대로 붙인다`() {
        val rows = LedgerScreenParser.parse(wooriFeedFromDevice, now)
        assertEquals(at(2026, 9, 7, 21, 18), rows[0].occurredAt)
        assertEquals(at(2026, 9, 7, 19, 32), rows[1].occurredAt)
        assertEquals(at(2026, 9, 7, 10, 44), rows[5].occurredAt)
        assertTrue(rows.none { it.occurredAtEstimated })
    }

    @Test
    fun `월 합계와 일일 합계는 거래가 아니다`() {
        val rows = LedgerScreenParser.parse(wooriFeedFromDevice, now)
        val amounts = rows.map { it.amount }
        assertTrue("월 이용금액 171,120이 들어왔다", 171_120L !in amounts)
        assertTrue("일일 합계 142,620이 들어왔다", 142_620L !in amounts)
        // 화면의 일일 합계와 개별 거래의 합이 맞아야 한다.
        assertEquals(142_620L, rows.sumOf { it.amount })
    }

    @Test
    fun `화면 하단 광고 문구를 거래로 읽지 않는다`() {
        val rows = LedgerScreenParser.parse(wooriFeedFromDevice, now)
        assertTrue(rows.none { it.merchant?.contains("카드의정석") == true })
        assertTrue(rows.none { it.merchant?.contains("맞춤 카드") == true })
    }

    @Test
    fun `등록한 카드의 인식 키워드가 화면 제목과 맞는다`() {
        // 회귀: 목록 불러오기가 카드 매칭을 아예 안 해서 전부 '미분류'로 쌓였다.
        // 화면 제목 `우리WON피드` 에 "우리" 가 있으므로 우리카드로 등록한 카드와 맞아야 한다.
        val keywords = listOf("우리카드", "우리")
        assertTrue(
            "화면 텍스트에서 카드 키워드를 못 찾는다",
            keywords.any { wooriFeedFromDevice.contains(it, ignoreCase = true) },
        )
    }

    @Test
    fun `화면에서 카드사를 우리카드로 판정한다`() {
        assertEquals("WOORI", IssuerRegistry.detect(wooriFeedFromDevice)?.key)
    }
}
