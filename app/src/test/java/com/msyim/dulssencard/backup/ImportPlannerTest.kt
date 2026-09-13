package com.msyim.dulssencard.backup

import com.msyim.dulssencard.backup.ImportPlanner.Mode
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.Setting
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 백업 가져오기 **충돌 정책** 테스트. 순수 함수라 DB 없이 모든 경우를 돈다.
 * 규칙의 근거는 [ImportPlanner] 문서에 있다.
 */
class ImportPlannerTest {

    private fun card(id: String, updatedAt: Long = 0L, nickname: String = "카드$id") = Card(
        id = id, nickname = nickname, trackingTarget = 300_000, cycleStartDay = 1,
        matchKeywords = listOf("신한"), excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true, defaultCountsTowardPurchaseLimit = true, updatedAt = updatedAt,
    )

    private fun txn(
        id: String,
        fingerprint: String,
        cardId: String? = "c1",
        updatedAt: Long = 0L,
        status: TxStatus = TxStatus.AUTO,
        related: String? = null,
        direction: TxDirection = TxDirection.APPROVAL,
    ) = Txn(
        id = id, cardId = cardId, occurredAt = 1_000L, receivedAt = 1_000L, amount = 5_000, currency = "KRW",
        direction = direction, status = status, source = TxSource.SMS, merchant = "가게",
        countsTowardTarget = true, countsTowardPurchaseLimit = true, parserVersion = "t", confidence = 1.0,
        messageFingerprint = fingerprint, relatedTransactionId = related, pendingReason = null,
        issuerKey = "SHINHAN", installment = false, overseas = false, updatedAt = updatedAt,
    )

    private fun local(
        cards: List<Card> = emptyList(),
        txns: List<Txn> = emptyList(),
        adjustments: List<Adjustment> = emptyList(),
        settings: List<Setting> = emptyList(),
        sources: List<SourceApp> = emptyList(),
    ) = ImportPlanner.Local(cards, txns, adjustments, settings, emptyList(), sources)

    private fun backup(
        cards: List<Card> = emptyList(),
        txns: List<Txn> = emptyList(),
        adjustments: List<Adjustment> = emptyList(),
        settings: List<Setting> = emptyList(),
        sources: List<SourceApp> = emptyList(),
    ) = BackupPayload.of(cards, txns, adjustments, settings, emptyList(), sources, "test", 5, now = 9_999L)

    // ------------------------------------------------------- 병합: 같은 결제

    @Test
    fun `두 기기가 따로 수집한 같은 결제는 한 건으로 합친다`() {
        // 지문이 같으면 같은 결제다. id 는 기기마다 따로 생긴다.
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("local-1", "fp-A"))),
            backup(cards = listOf(card("c1")), txns = listOf(txn("phone-9", "fp-A"))),
            Mode.MERGE,
        )
        assertTrue("이중 집계: 같은 결제가 추가로 들어간다", plan.txns.isEmpty())
        assertEquals(1, plan.summary.txnsKept)
        assertEquals(0, plan.summary.txnsAdded)
    }

    @Test
    fun `같은 결제는 더 최근에 고친 쪽을 쓰되 로컬 id 를 유지한다`() {
        // 휴대폰에서 이 거래를 '제외'로 고쳤다(더 최근). 로컬은 아직 자동 반영 상태.
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("local-1", "fp-A", updatedAt = 100))),
            backup(
                cards = listOf(card("c1")),
                txns = listOf(txn("phone-9", "fp-A", updatedAt = 200, status = TxStatus.EXCLUDED)),
            ),
            Mode.MERGE,
        )
        val written = plan.txns.single()
        assertEquals("로컬 id 가 바뀌면 이 거래를 가리키던 기록이 끊긴다", "local-1", written.id)
        assertEquals(TxStatus.EXCLUDED, written.status)
        assertEquals(1, plan.summary.txnsUpdated)
    }

    @Test
    fun `수정 시각이 같으면 로컬을 유지한다`() {
        // v5 이전 데이터는 양쪽 다 0이다. 근거 없이 덮어쓰지 않는다.
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("l", "fp-A", status = TxStatus.AUTO))),
            backup(cards = listOf(card("c1")), txns = listOf(txn("b", "fp-A", status = TxStatus.EXCLUDED))),
            Mode.MERGE,
        )
        assertTrue(plan.txns.isEmpty())
    }

    @Test
    fun `로컬이 더 최근이면 백업이 덮어쓰지 않는다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("l", "fp-A", updatedAt = 500))),
            backup(cards = listOf(card("c1")), txns = listOf(txn("b", "fp-A", updatedAt = 100, status = TxStatus.EXCLUDED))),
            Mode.MERGE,
        )
        assertTrue(plan.txns.isEmpty())
        assertEquals(1, plan.summary.txnsKept)
    }

    @Test
    fun `백업에만 있는 결제는 추가한다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("l", "fp-A"))),
            backup(cards = listOf(card("c1")), txns = listOf(txn("b", "fp-B"))),
            Mode.MERGE,
        )
        assertEquals("b", plan.txns.single().id)
        assertEquals(1, plan.summary.txnsAdded)
    }

    @Test
    fun `취소 연결은 합쳐진 원 거래의 로컬 id 로 옮겨진다`() {
        // 휴대폰의 취소 거래가 휴대폰 id(phone-origin)를 가리킨다. 그 원 거래는 로컬에 local-origin 으로 있다.
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("local-origin", "fp-origin"))),
            backup(
                cards = listOf(card("c1")),
                txns = listOf(
                    txn("phone-origin", "fp-origin"),
                    txn("phone-cancel", "fp-cancel", related = "phone-origin", direction = TxDirection.CANCEL),
                ),
            ),
            Mode.MERGE,
        )
        val cancel = plan.txns.single { it.id == "phone-cancel" }
        assertEquals("취소가 없는 id 를 가리킨다 — 원 거래와 연결이 끊겼다", "local-origin", cancel.relatedTransactionId)
    }

    @Test
    fun `변경 기록도 합쳐진 거래의 로컬 id 로 옮겨진다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("local-1", "fp-A"))),
            backup(
                cards = listOf(card("c1")),
                txns = listOf(txn("phone-9", "fp-A")),
                adjustments = listOf(Adjustment("adj-1", "phone-9", ChangeType.EXCLUDE, "a", "b", null, 1L)),
            ),
            Mode.MERGE,
        )
        assertEquals("local-1", plan.adjustments.single().transactionId)
    }

    @Test
    fun `지문은 다른데 id 만 겹치면 덮어쓰지 않고 새 id 를 준다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1")), txns = listOf(txn("same-id", "fp-A"))),
            backup(cards = listOf(card("c1")), txns = listOf(txn("same-id", "fp-B"))),
            Mode.MERGE,
        )
        val written = plan.txns.single()
        assertTrue("다른 결제가 로컬 거래를 덮어쓴다", written.id != "same-id")
        assertEquals("fp-B", written.messageFingerprint)
    }

    // ------------------------------------------------------- 병합: 카드

    @Test
    fun `카드 id 가 같으면 더 최근 설정을 쓴다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1", updatedAt = 100, nickname = "옛 이름"))),
            backup(cards = listOf(card("c1", updatedAt = 200, nickname = "새 이름"))),
            Mode.MERGE,
        )
        assertEquals("새 이름", plan.cards.single().nickname)
        assertEquals(1, plan.summary.cardsUpdated)
    }

    @Test
    fun `카드 설정이 같은 시각이면 로컬을 유지한다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1", updatedAt = 100, nickname = "로컬"))),
            backup(cards = listOf(card("c1", updatedAt = 100, nickname = "백업"))),
            Mode.MERGE,
        )
        assertTrue(plan.cards.isEmpty())
        assertEquals(1, plan.summary.cardsKept)
    }

    @Test
    fun `어디에도 없는 카드를 가리키는 거래는 미분류 확인 필요로 넣는다`() {
        // 합산 대상 카드가 없으면 그 금액은 어느 합계에도 안 보이고 사라진다. 사용자에게 넘긴다.
        val orphan = BackupPayload.of(
            cards = emptyList(), txns = listOf(txn("b", "fp-B", cardId = "ghost")), adjustments = emptyList(),
            settings = emptyList(), cycleSnapshots = emptyList(), sourceApps = emptyList(),
            appVersion = "t", schemaVersion = 5, now = 1L,
        )
        val written = ImportPlanner.plan(local(), orphan, Mode.MERGE).txns.single()
        assertNull(written.cardId)
        assertEquals(TxStatus.PENDING, written.status)
        assertEquals(PendingReason.NO_CARD_MATCH, written.pendingReason)
    }

    // ------------------------------------------------------- 병합: 설정·소스

    @Test
    fun `설정은 더 최근 값을 쓰고 온보딩 완료는 되돌리지 않는다`() {
        val plan = ImportPlanner.plan(
            local(settings = listOf(
                Setting(Settings.LIMIT_AMOUNT, "500000", updatedAt = 100),
                Setting(Settings.ONBOARDING_DONE, "true", updatedAt = 999),
            )),
            backup(settings = listOf(
                Setting(Settings.LIMIT_AMOUNT, "800000", updatedAt = 200),
                Setting(Settings.ONBOARDING_DONE, "false", updatedAt = 1_000),
            )),
            Mode.MERGE,
        )
        assertEquals("800000", plan.settings.single { it.key == Settings.LIMIT_AMOUNT }.value)
        assertTrue("온보딩을 다시 하게 만든다", plan.settings.none { it.key == Settings.ONBOARDING_DONE })
    }

    @Test
    fun `알림 소스는 이 기기 설정을 지키고 없는 앱만 더한다`() {
        val plan = ImportPlanner.plan(
            local(sources = listOf(SourceApp("com.kakao.talk", "카카오톡", null, enabled = false, lastSeenAt = 5))),
            backup(sources = listOf(
                SourceApp("com.kakao.talk", "카카오톡", null, enabled = true, lastSeenAt = 0),
                SourceApp("com.shcard.smartpay", "신한", "SHINHAN", enabled = true, lastSeenAt = 0),
            )),
            Mode.MERGE,
        )
        assertEquals(listOf("com.shcard.smartpay"), plan.sourceApps.map { it.packageName })
    }

    // ------------------------------------------------------- 전체 교체

    @Test
    fun `전체 교체는 로컬을 비우고 사라지는 양을 알린다`() {
        val plan = ImportPlanner.plan(
            local(cards = listOf(card("c1"), card("c2")), txns = listOf(txn("l1", "fp-1"), txn("l2", "fp-2"), txn("l3", "fp-3"))),
            backup(cards = listOf(card("c9")), txns = listOf(txn("b1", "fp-9", cardId = "c9"))),
            Mode.REPLACE,
        )
        assertTrue(plan.clearFirst)
        assertEquals(2, plan.summary.localCardsRemoved)
        assertEquals(3, plan.summary.localTxnsRemoved)
        assertEquals(listOf("c9"), plan.cards.map { it.id })
    }

    @Test
    fun `전체 교체도 알림 소스는 켬 끔만 가져오고 이 기기의 진단은 지킨다`() {
        val plan = ImportPlanner.plan(
            local(sources = listOf(
                SourceApp("com.kakao.talk", "카카오톡", null, enabled = false, lastSeenAt = 5, recognizedCount = 12),
            )),
            backup(sources = listOf(SourceApp("com.kakao.talk", "카카오톡", null, enabled = true, lastSeenAt = 0))),
            Mode.REPLACE,
        )
        val kakao = plan.sourceApps.single()
        assertTrue(kakao.enabled)
        assertEquals(12, kakao.recognizedCount)
    }

    @Test
    fun `전체 교체에서 백업 안의 끊긴 취소 연결은 떼어 낸다`() {
        val plan = ImportPlanner.plan(
            local(),
            backup(cards = listOf(card("c1")), txns = listOf(txn("cancel", "fp-c", related = "missing", direction = TxDirection.CANCEL))),
            Mode.REPLACE,
        )
        assertNull(plan.txns.single().relatedTransactionId)
    }
}
