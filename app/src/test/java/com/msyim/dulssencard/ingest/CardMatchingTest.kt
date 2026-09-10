package com.msyim.dulssencard.ingest

import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.domain.Cycle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 카드 매칭 회귀 테스트.
 *
 * ## 왜 이 파일이 생겼나
 *
 * 사용자 제보: **"현대카드는 자동으로 잘 읽는데, 우리카드는 자동으로 잘 인식하지 못 해."**
 *
 * 원인은 한국어 부분 문자열이었다. 카드를 `우리카드` 로 등록해도 우리카드 앱 푸시는
 * 자신을 **`우리WON카드`** 라고 부른다. `"우리WON카드".contains("우리카드")` 는 **false** 다 —
 * 우리 + WON + 카드로 갈라져 있기 때문이다. 그래서 매칭이 0건이 되고 `미분류`로 떨어졌다.
 *
 * 현대카드는 통지에도 그냥 `현대카드` 라고 나와서 부분 문자열로 걸렸다. 그래서 한쪽만 됐다.
 *
 * 파서는 멀쩡했다(`PaymentParserTest` 가 실제 문구로 잠가 두었다). 깨진 건 그 다음 단계인
 * **사용자 키워드 ↔ 통지 문구** 대조였다.
 */
class CardMatchingTest {

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    private fun card(
        id: String,
        nickname: String,
        keywords: List<String>,
    ) = Card(
        id = id,
        nickname = nickname,
        trackingTarget = 500_000,
        cycleStartDay = 1,
        matchKeywords = keywords,
        excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true,
        defaultCountsTowardPurchaseLimit = true,
    )

    private val woori = card("w1", "우리카드", listOf("우리카드"))
    private val hyundai = card("h1", "현대 M", listOf("현대카드"))

    private fun push(pkg: String, title: String, body: String) = RawMessage(
        source = TxSource.PUSH,
        senderKey = pkg,
        title = title,
        body = body.trimIndent(),
        receivedAt = at(2026, 9, 10, 12, 1),
    )

    private suspend fun ingest(raw: RawMessage, cards: List<Card>) = Ingestor.ingest(
        raw = raw,
        cards = cards,
        existingByFingerprint = { null },
        cancelOriginFinder = { _, _, _ -> null },
    )

    // ------------------------------------------------------- 제보한 증상

    @Test
    fun `우리WON카드 푸시를 우리카드로 등록한 카드에 붙인다`() = runTest {
        // 회귀: `"우리WON카드".contains("우리카드")` 가 false 라 미분류로 떨어졌다.
        val outcome = ingest(
            push(
                pkg = "com.wooricard.smartapp",
                title = "우리WON카드",
                body = """
                    우리WON카드 승인
                    16,000원 일시불
                    09/10 12:00
                    푸른들컨트리클럽
                """,
            ),
            listOf(woori, hyundai),
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertEquals("w1", txn.cardId)
        assertEquals(TxStatus.AUTO, txn.status)
        assertNull(txn.pendingReason)
    }

    @Test
    fun `현대카드는 지금까지처럼 그대로 붙는다`() = runTest {
        val outcome = ingest(
            push(
                pkg = "com.hyundaicard.appcard",
                title = "현대카드",
                body = """
                    현대카드 승인
                    23,000원 일시불
                    09/10 12:00
                    스타벅스
                """,
            ),
            listOf(woori, hyundai),
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertEquals("h1", txn.cardId)
        assertEquals(TxStatus.AUTO, txn.status)
    }

    @Test
    fun `우리WON페이 표기도 우리카드로 붙인다`() = runTest {
        val outcome = ingest(
            push(
                pkg = "com.wooricard.smartapp",
                title = "우리WON페이",
                body = """
                    우리WON페이 승인
                    5,000원 일시불
                    09/10 12:00
                    편의점
                """,
            ),
            listOf(woori),
        )
        assertEquals("w1", (outcome as Ingestor.Outcome.Insert).txn.cardId)
    }

    @Test
    fun `카카오 알림톡 우리카드도 그대로 붙는다`() = runTest {
        // 알림톡은 본문에 `우리카드` 가 그대로 있어 원래도 됐다. 깨지지 않았는지만 확인한다.
        val outcome = ingest(
            RawMessage(
                source = TxSource.KAKAO,
                senderKey = IssuerRegistry.KAKAO_PACKAGE,
                title = "우리카드",
                body = """
                    [우리카드 이용 안내]
                    우리(4321)승인
                    16,000원 일시불
                    09/10 12:00
                    푸른들컨트리클럽
                """.trimIndent(),
                receivedAt = at(2026, 9, 10, 12, 1),
            ),
            listOf(woori),
        )
        assertEquals("w1", (outcome as Ingestor.Outcome.Insert).txn.cardId)
    }

    // ------------------------------------------------------- 남의 카드로 새면 안 된다

    @Test
    fun `다른 카드사 통지가 우리카드로 새지 않는다`() = runTest {
        val outcome = ingest(
            push(
                pkg = "com.shcard.smartpay",
                title = "신한카드",
                body = """
                    신한카드 승인
                    9,000원 일시불
                    09/10 12:00
                    스타벅스
                """,
            ),
            listOf(woori, hyundai),
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertNull("등록 안 한 카드사가 붙었다", txn.cardId)
        assertEquals(PendingReason.NO_CARD_MATCH, txn.pendingReason)
    }

    @Test
    fun `가맹점 이름의 우리는 카드사로 치지 않는다`() = runTest {
        // 회귀 위험: `우리사랑동물병원` 같은 가맹점이 우리카드 거래로 둔갑하면 안 된다.
        // 카드사 판정은 이미 위치 우선 규칙으로 KB 를 고른다(IssuerRegistry.detect).
        val outcome = ingest(
            push(
                pkg = "com.kbcard.cxh.appcard",
                title = "KB국민카드",
                body = """
                    KB국민카드 승인
                    30,000원 일시불
                    09/10 12:00
                    우리사랑동물병원
                """,
            ),
            listOf(woori),
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertNull("가맹점의 '우리'가 우리카드로 붙었다", txn.cardId)
    }

    // ------------------------------------------------------- 같은 카드사 여러 장

    @Test
    fun `같은 카드사 카드가 둘이면 뒷자리로 가른다`() = runTest {
        // 카드사만으로는 어느 장인지 모른다. 사용자가 키워드에 넣어 둔 뒷 4자리로 좁힌다.
        val a = card("w1", "우리 카드의정석", listOf("우리카드", "4321"))
        val b = card("w2", "우리 트래블", listOf("우리카드", "8821"))
        val outcome = ingest(
            push(
                pkg = "com.wooricard.smartapp",
                title = "우리WON카드",
                body = """
                    우리WON카드(8821)승인
                    12,000원 일시불
                    09/10 12:00
                    카페
                """,
            ),
            listOf(a, b),
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertEquals("w2", txn.cardId)
        assertEquals(TxStatus.AUTO, txn.status)
    }

    @Test
    fun `같은 카드사 카드가 둘인데 뒷자리를 모르면 사용자에게 넘긴다`() = runTest {
        // 찍어서 맞히면 합계가 조용히 틀어진다. 확인 필요로 남기는 편이 낫다.
        val a = card("w1", "우리 카드의정석", listOf("우리카드"))
        val b = card("w2", "우리 트래블", listOf("우리카드"))
        val outcome = ingest(
            push(
                pkg = "com.wooricard.smartapp",
                title = "우리WON카드",
                body = """
                    우리WON카드 승인
                    12,000원 일시불
                    09/10 12:00
                    카페
                """,
            ),
            listOf(a, b),
        )
        val txn = (outcome as Ingestor.Outcome.Insert).txn
        assertNull(txn.cardId)
        assertEquals(PendingReason.MULTIPLE_CARD_MATCH, txn.pendingReason)
        assertEquals(TxStatus.PENDING, txn.status)
    }

    // ------------------------------------------------------- 자유 키워드는 그대로

    @Test
    fun `카드사와 무관한 자유 키워드도 여전히 통한다`() = runTest {
        // 별명처럼 임의 문자열을 넣어 쓰는 사용법을 막지 않는다.
        val custom = card("x1", "회사 법인", listOf("법인구매"))
        val outcome = ingest(
            push(
                pkg = "com.shcard.smartpay",
                title = "신한카드",
                body = """
                    신한카드 승인 법인구매
                    9,000원 일시불
                    09/10 12:00
                    주유소
                """,
            ),
            listOf(custom),
        )
        assertEquals("x1", (outcome as Ingestor.Outcome.Insert).txn.cardId)
    }
}
