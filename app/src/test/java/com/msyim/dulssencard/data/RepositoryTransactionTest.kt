package com.msyim.dulssencard.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.msyim.dulssencard.data.db.AppDatabase
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Cycle
import com.msyim.dulssencard.ingest.LedgerScreenParser
import com.msyim.dulssencard.ingest.RawMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import java.time.ZonedDateTime

/**
 * 저장소 쓰기의 **원자성**과 **삽입 결과 정확성** 테스트.
 *
 * "앱이 중간에 죽으면"을 흉내 내려고 트랜잭션 한가운데 지점([DulSsenRepository.faultInjector])에서
 * 예외를 던진다. 통과 조건은 하나다 — **아무것도 바뀌지 않았어야 한다.**
 * 알림 리스너는 시스템이 언제든 죽일 수 있으므로 이건 가정이 아니라 일상이다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RepositoryTransactionTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: DulSsenRepository

    private class Crash : RuntimeException("테스트가 주입한 중단")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long =
        ZonedDateTime.of(LocalDateTime.of(y, mo, d, h, mi), Cycle.ZONE).toInstant().toEpochMilli()

    @Before
    fun open() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = DulSsenRepository(context, db)
    }

    @After
    fun close() {
        db.close()
    }

    private val shinhan = Card(
        id = "c1",
        nickname = "신한 딥드림",
        trackingTarget = 500_000,
        cycleStartDay = 1,
        matchKeywords = listOf("신한"),
        excludeKeywords = emptyList(),
        defaultCountsTowardTarget = true,
        defaultCountsTowardPurchaseLimit = true,
    )

    private fun sms(amount: String = "84,300", at: String = "09/08 19:42", received: Long = at(2026, 9, 8, 19, 43)) =
        RawMessage(
            source = TxSource.SMS,
            senderKey = MESSAGING,
            title = null,
            body = "[Web발신]\n신한카드(1234)승인 홍*동\n${amount}원 일시불\n$at\n편의점",
            receivedAt = received,
        )

    private fun crashAt(point: String) {
        repo.faultInjector = { if (it == point) throw Crash() }
    }

    private fun <T> expectCrash(block: suspend () -> T) = runBlocking {
        try {
            block()
            fail("주입한 중단이 일어나지 않았다 — 지점 이름이 바뀌었나?")
        } catch (expected: Crash) {
            // 정상
        }
        repo.faultInjector = null
    }

    private suspend fun seedTxn(id: String = "t1", cardId: String? = "c1", amount: Long = 10_000L) =
        db.txnDao().insertIgnoringDuplicates(
            Txn(
                id = id, cardId = cardId, occurredAt = at(2026, 9, 5, 12, 0), receivedAt = at(2026, 9, 5, 12, 0),
                amount = amount, currency = "KRW", direction = TxDirection.APPROVAL, status = TxStatus.AUTO,
                source = TxSource.SMS, merchant = "가게", countsTowardTarget = true, countsTowardPurchaseLimit = true,
                parserVersion = "t", confidence = 1.0, messageFingerprint = "fp-$id", relatedTransactionId = null,
                pendingReason = null, issuerKey = "SHINHAN", installment = false, overseas = false,
            ),
        )

    // ------------------------------------------------------- 원자성

    @Test
    fun `카드 삭제 중 죽으면 카드도 거래 연결도 그대로다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn()
        crashAt("deleteCard:afterDetach")
        expectCrash { repo.deleteCard(shinhan) }

        assertNotNull("카드가 지워졌다", db.cardDao().byId("c1"))
        val txn = db.txnDao().byId("t1")!!
        assertEquals("거래가 카드에서 떨어졌다", "c1", txn.cardId)
        assertEquals(TxStatus.AUTO, txn.status)
    }

    @Test
    fun `되돌리기가 표를 비운 직후 죽어도 데이터가 사라지지 않는다`() = runBlocking {
        // 가장 치명적인 경우. 트랜잭션이 없으면 여기서 카드와 거래가 통째로 날아간다.
        repo.upsertCard(shinhan)
        seedTxn()
        val snapshot = repo.snapshot()
        crashAt("restore:afterClear")
        expectCrash { repo.restore(snapshot) }

        assertEquals(1, db.cardDao().all().size)
        assertEquals(1, db.txnDao().all().size)
    }

    @Test
    fun `전체 삭제 중 죽으면 아무것도 지워지지 않는다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn()
        repo.putSetting(Settings.LIMIT_AMOUNT, "700000")
        crashAt("wipeAll:half")
        expectCrash { repo.wipeAll() }

        assertEquals(1, db.cardDao().all().size)
        assertEquals(1, db.txnDao().all().size)
        assertEquals("700000", repo.getSetting(Settings.LIMIT_AMOUNT))
    }

    @Test
    fun `거래 보정 중 죽으면 거래도 변경 기록도 남지 않는다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn()
        val before = db.txnDao().byId("t1")!!
        crashAt("applyChange:beforeLog")
        expectCrash {
            repo.applyChange(before, before.copy(status = TxStatus.EXCLUDED), ChangeType.EXCLUDE)
        }
        assertEquals(TxStatus.AUTO, db.txnDao().byId("t1")!!.status)
        assertTrue("기록만 남았다", db.adjustmentDao().all().isEmpty())
    }

    @Test
    fun `초기 사용액 수정 중 죽으면 금액이 바뀌지 않는다`() = runBlocking {
        repo.upsertCard(shinhan.copy(initialAmount = 100_000, initialAmountAt = at(2026, 9, 2, 9, 0)))
        crashAt("setInitialAmount:beforeLog")
        expectCrash { repo.setInitialAmount("c1", 320_000) }
        assertEquals(100_000L, db.cardDao().byId("c1")!!.initialAmount)
    }

    @Test
    fun `수동 거래 추가 중 죽으면 거래가 생기지 않는다`() = runBlocking {
        repo.upsertCard(shinhan)
        crashAt("addManualTxn:beforeLog")
        expectCrash {
            repo.addManualTxn("c1", 5_000, TxDirection.APPROVAL, at(2026, 9, 8, 12, 0), "시장")
        }
        assertTrue(db.txnDao().all().isEmpty())
    }

    @Test
    fun `알림 수집 중 죽으면 거래가 들어가지 않는다`() = runBlocking {
        repo.upsertCard(shinhan)
        crashAt("ingest:beforeSourceStats")
        expectCrash { repo.ingest(sms()) }
        assertTrue("반쪽 수집이 남았다", db.txnDao().all().isEmpty())
    }

    // ------------------------------------------------------- 삽입 결과

    @Test
    fun `같은 결제가 두 번 오면 두 번째는 중복으로 알린다`() = runBlocking {
        repo.upsertCard(shinhan)
        val first = repo.ingest(sms())
        val second = repo.ingest(sms(received = at(2026, 9, 8, 19, 44)))
        assertTrue(first is DulSsenRepository.IngestResult.Inserted)
        assertTrue("두 번째가 '추가됨'이다: $second", second is DulSsenRepository.IngestResult.Duplicate)
        assertEquals(1, db.txnDao().all().size)
    }

    @Test
    fun `대조 직후 다른 경로가 먼저 넣으면 추가됨이 아니라 중복이다`() = runBlocking {
        // P1 제보 그대로: 지문 대조는 통과했는데 삽입 직전에 같은 결제가 먼저 들어간 경우.
        // DB 는 유니크 인덱스로 무시(-1)하는데, 반환값을 안 보면 화면에 "추가됨"이 뜬다.
        repo.upsertCard(shinhan)
        var raced: Txn? = null
        repo.faultInjector = { point ->
            if (point == "ingest:beforeInsert" && raced == null) {
                // 문자 알림 처리가 대조를 끝낸 순간, 푸시가 같은 결제를 먼저 넣었다.
                val otherPath = DulSsenRepository(ApplicationProvider.getApplicationContext(), db)
                val probe = requireNotNull(com.msyim.dulssencard.ingest.PaymentParser.parse(sms()))
                val txn = Txn(
                    id = "push-first", cardId = "c1", occurredAt = probe.occurredAt, receivedAt = probe.occurredAt!!,
                    amount = probe.amount, currency = "KRW", direction = probe.direction, status = TxStatus.AUTO,
                    source = TxSource.PUSH, merchant = probe.merchant, countsTowardTarget = true,
                    countsTowardPurchaseLimit = true, parserVersion = "t", confidence = 1.0,
                    messageFingerprint = com.msyim.dulssencard.ingest.Fingerprint.of(
                        probe.issuerKey, probe.occurredAt, probe.amount, probe.direction,
                    ),
                    relatedTransactionId = null, pendingReason = null, issuerKey = probe.issuerKey,
                    installment = false, overseas = false,
                )
                db.txnDao().insertIgnoringDuplicates(txn)
                raced = txn
                check(otherPath !== repo)
            }
        }
        val result = repo.ingest(sms())
        repo.faultInjector = null

        assertTrue("경합에서 진 삽입을 '추가됨'으로 알렸다: $result", result is DulSsenRepository.IngestResult.Duplicate)
        assertEquals("push-first", (result as DulSsenRepository.IngestResult.Duplicate).existingId)
        assertEquals("이중 집계", 1, db.txnDao().all().size)
    }

    @Test
    fun `목록 화면 불러오기는 삽입 결과로 중복을 센다`() = runBlocking {
        // 같은 화면 안에서 같은 시각·같은 금액 행이 두 번 읽힌 경우도 한 건만 들어가야 한다.
        val row = LedgerScreenParser.Row(occurredAt = at(2026, 9, 7, 21, 18), occurredAtEstimated = false, amount = 16_000, merchant = "푸른들컨트리클럽")
        val result = repo.importLedgerRows(listOf(row, row), "WOORI", at(2026, 9, 8, 10, 0), "우리WON피드")
        assertEquals(1, result.inserted)
        assertEquals(1, result.duplicates)
        assertEquals(1, db.txnDao().all().size)
    }

    // ------------------------------------------------------- 제외 거래 삭제

    @Test
    fun `제외한 거래만 지우고 합계에 들어간 거래는 넘겨도 지우지 않는다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "keep", amount = 10_000)
        seedTxn(id = "gone", amount = 20_000)
        val gone = db.txnDao().byId("gone")!!
        repo.applyChange(gone, gone.copy(status = TxStatus.EXCLUDED), ChangeType.EXCLUDE)

        val deleted = repo.deleteUncountedTxns(listOf("keep", "gone"))

        assertEquals(1, deleted)
        assertNotNull("합계에 들어간 거래가 지워졌다", db.txnDao().byId("keep"))
        assertNull(db.txnDao().byId("gone"))
        assertTrue("지운 거래의 변경 기록이 남았다", db.adjustmentDao().all().none { it.transactionId == "gone" })
    }

    @Test
    fun `지운 거래를 가리키던 취소는 연결이 끊긴다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "origin", amount = 20_000)
        val origin = db.txnDao().byId("origin")!!
        db.txnDao().update(origin.copy(status = TxStatus.EXCLUDED))
        db.txnDao().insertIgnoringDuplicates(
            origin.copy(id = "cancel", direction = TxDirection.CANCEL, status = TxStatus.AUTO,
                messageFingerprint = "fp-cancel", relatedTransactionId = "origin"),
        )
        repo.deleteUncountedTxns(listOf("origin"))
        val cancel = db.txnDao().byId("cancel")!!
        assertNull("취소가 없는 거래를 가리킨다", cancel.relatedTransactionId)
        // 원 거래는 합계에 없던 거래다. 연결만 끊고 두면 원 거래 없는 취소가 되어 합계를 음수로 끌어내린다.
        assertEquals("제외한 거래의 취소가 합계에 남았다", TxStatus.EXCLUDED, cancel.status)
    }

    @Test
    fun `취소를 원 거래에 이으면 원 거래의 카드로 옮긴다`() = runBlocking {
        repo.upsertCard(shinhan)
        repo.upsertCard(shinhan.copy(id = "c2", nickname = "신한 다른 카드", matchKeywords = listOf("신한2")))
        seedTxn(id = "origin", cardId = "c1", amount = 20_000)
        seedTxn(id = "cancel", cardId = "c2", amount = 20_000)
        val cancel = db.txnDao().byId("cancel")!!.copy(direction = TxDirection.CANCEL)
        db.txnDao().update(cancel)
        val linked = repo.linkCancel(cancel, db.txnDao().byId("origin")!!)
        assertEquals("c1", linked.cardId)
    }

    @Test
    fun `자동 연결은 같은 카드의 승인을 먼저 고른다`() = runBlocking {
        // 같은 카드사 카드 둘로 같은 금액을 결제했다. 시각만 보면 나중 결제(다른 카드)에 붙는다.
        repo.upsertCard(shinhan)
        seedTxn(id = "mine", cardId = "c1", amount = 84_300)
        val later = db.txnDao().byId("mine")!!.copy(
            id = "other", cardId = "c2", messageFingerprint = "fp-other",
            occurredAt = at(2026, 9, 7, 12, 0), receivedAt = at(2026, 9, 7, 12, 0),
        )
        db.txnDao().insertIgnoringDuplicates(later)
        val found = db.txnDao().findCancelOrigin(84_300, "SHINHAN", at(2026, 9, 8, 20, 0), "c1")
        assertEquals("mine", found!!.id)
        // 카드를 모르면 예전처럼 가장 최근 것.
        assertEquals("other", db.txnDao().findCancelOrigin(84_300, "SHINHAN", at(2026, 9, 8, 20, 0), null)!!.id)
    }

    @Test
    fun `확인 필요 거래도 결과함에서 바로 지운다`() = runBlocking {
        // 결제가 아닌 알림이 잡혔을 때, 제외를 한 번 거치지 않고 치운다. 합계에 없던 거래라 숫자는 안 바뀐다.
        repo.upsertCard(shinhan)
        seedTxn(id = "junk", amount = 1_000_000)
        val junk = db.txnDao().byId("junk")!!
        db.txnDao().update(junk.copy(status = TxStatus.PENDING, pendingReason = PendingReason.IMAGE_IMPORT))
        assertEquals(1, repo.deleteUncountedTxns(listOf("junk")))
        assertNull(db.txnDao().byId("junk"))
    }

    @Test
    fun `지운 확인 필요 거래를 가리키던 취소도 제외한다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "origin", amount = 20_000)
        val origin = db.txnDao().byId("origin")!!
        db.txnDao().update(origin.copy(status = TxStatus.PENDING, pendingReason = PendingReason.IMAGE_IMPORT))
        db.txnDao().insertIgnoringDuplicates(
            origin.copy(id = "cancel", direction = TxDirection.CANCEL, status = TxStatus.AUTO,
                pendingReason = null, messageFingerprint = "fp-cancel", relatedTransactionId = "origin"),
        )
        repo.deleteUncountedTxns(listOf("origin"))
        assertEquals(TxStatus.EXCLUDED, db.txnDao().byId("cancel")!!.status)
    }

    @Test
    fun `지운 거래와 무관한 취소는 그대로 둔다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "origin", amount = 20_000)
        seedTxn(id = "other", amount = 30_000)
        val origin = db.txnDao().byId("origin")!!
        db.txnDao().update(origin.copy(status = TxStatus.EXCLUDED))
        val other = db.txnDao().byId("other")!!
        db.txnDao().insertIgnoringDuplicates(
            other.copy(id = "cancel", direction = TxDirection.CANCEL, status = TxStatus.AUTO,
                messageFingerprint = "fp-cancel", relatedTransactionId = "other"),
        )
        repo.deleteUncountedTxns(listOf("origin"))
        val cancel = db.txnDao().byId("cancel")!!
        assertEquals("other", cancel.relatedTransactionId)
        assertEquals(TxStatus.AUTO, cancel.status)
    }

    @Test
    fun `삭제 중 죽으면 연결도 기록도 거래도 그대로다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "gone", amount = 20_000)
        val gone = db.txnDao().byId("gone")!!
        repo.applyChange(gone, gone.copy(status = TxStatus.EXCLUDED), ChangeType.EXCLUDE)
        crashAt("deleteExcluded:beforeDelete")
        expectCrash { repo.deleteUncountedTxns(listOf("gone")) }
        assertNotNull(db.txnDao().byId("gone"))
        assertEquals("변경 기록만 사라졌다", 1, db.adjustmentDao().all().size)
    }

    @Test
    fun `지운 제외 거래는 되돌리기로 복구된다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "gone", amount = 20_000)
        val gone = db.txnDao().byId("gone")!!
        repo.applyChange(gone, gone.copy(status = TxStatus.EXCLUDED), ChangeType.EXCLUDE)
        val snapshot = repo.snapshot()
        repo.deleteUncountedTxns(listOf("gone"))
        repo.restore(snapshot)
        assertEquals(TxStatus.EXCLUDED, db.txnDao().byId("gone")!!.status)
    }

    // ------------------------------------------------------- 되돌리기의 완전성

    @Test
    fun `되돌리면 변경 기록도 함께 되돌아간다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn()
        val snapshot = repo.snapshot()
        val before = db.txnDao().byId("t1")!!
        repo.applyChange(before, before.copy(status = TxStatus.EXCLUDED), ChangeType.EXCLUDE)
        repo.restore(snapshot)
        assertEquals(TxStatus.AUTO, db.txnDao().byId("t1")!!.status)
        assertTrue("되돌렸는데 '제외했다'는 기록이 남았다", db.adjustmentDao().all().isEmpty())
    }

    // ------------------------------------------------------- 알림 소스 진단

    private suspend fun seedSource() = db.sourceAppDao().upsert(
        SourceApp(packageName = MESSAGING, label = "메시지", issuerKey = null, enabled = true, lastSeenAt = 0L),
    )

    @Test
    fun `결제를 인식하면 마지막 수집 시각과 인식 수가 오른다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedSource()
        repo.ingest(sms(received = at(2026, 9, 8, 19, 43)))
        repo.ingest(sms(received = at(2026, 9, 8, 19, 44))) // 중복도 '인식'이다
        val app = db.sourceAppDao().byPackage(MESSAGING)!!
        assertEquals(at(2026, 9, 8, 19, 44), app.lastPaymentAt)
        assertEquals(2, app.recognizedCount)
        assertEquals(0, app.failedCount)
    }

    @Test
    fun `결제로 읽었지만 금액을 못 뽑으면 실패로만 센다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedSource()
        // 카드사가 문구 형식을 바꿔 금액 줄이 깨진 경우를 흉내 낸다. 승인 통지로는 읽히지만 금액이 없다.
        repo.ingest(
            RawMessage(TxSource.SMS, MESSAGING, null, "신한카드(1234)승인 홍*동\n일시불\n09/08 09:00\n편의점", at(2026, 9, 8, 9, 0)),
        )
        val app = db.sourceAppDao().byPackage(MESSAGING)!!
        assertEquals("실패로 셌어야 한다", 1, app.failedCount)
        assertEquals("같은 알림을 인식에도 셌다", 0, app.recognizedCount)
        assertEquals("성공하지 못했는데 '최근 수집'이 갱신됐다", 0L, app.lastPaymentAt)
    }

    @Test
    fun `결제와 무관한 알림은 진단 카운터에 잡히지 않는다`() = runBlocking {
        seedSource()
        // 기본 문자 앱에는 일상 대화도 온다. 이걸 실패로 세면 실패 수가 의미를 잃는다.
        repo.ingest(RawMessage(TxSource.SMS, MESSAGING, null, "오늘 저녁 몇 시에 봐?", at(2026, 9, 8, 9, 1)))
        repo.ingest(RawMessage(TxSource.SMS, MESSAGING, null, "[광고] 가을맞이 세일 최대 50%", at(2026, 9, 8, 9, 2)))
        val app = db.sourceAppDao().byPackage(MESSAGING)!!
        assertEquals(0, app.failedCount)
        assertEquals(0, app.recognizedCount)
    }

    @Test
    fun `일주일이 지나면 진단 카운터를 새로 센다`() = runBlocking {
        repo.upsertCard(shinhan)
        db.sourceAppDao().upsert(
            SourceApp(MESSAGING, "메시지", null, true, 0L, recognizedCount = 40, failedCount = 9, countsSince = at(2026, 8, 20, 0, 0)),
        )
        repo.ingest(sms(received = at(2026, 9, 8, 19, 43)))
        val app = db.sourceAppDao().byPackage(MESSAGING)!!
        assertEquals(1, app.recognizedCount)
        assertEquals(0, app.failedCount)
        assertEquals(at(2026, 9, 8, 19, 43), app.countsSince)
    }

    // ------------------------------------------------------- 취소 연결

    @Test
    fun `연결 실패한 취소를 원 거래에 이으면 원 거래의 카드로 반영된다`() = runBlocking {
        repo.upsertCard(shinhan)
        seedTxn(id = "origin", amount = 20_000)
        val cancel = Txn(
            id = "cancel", cardId = null, occurredAt = at(2026, 9, 8, 10, 0), receivedAt = at(2026, 9, 8, 10, 0),
            amount = 20_000, currency = "KRW", direction = TxDirection.CANCEL, status = TxStatus.PENDING,
            source = TxSource.PUSH, merchant = null, countsTowardTarget = true, countsTowardPurchaseLimit = true,
            parserVersion = "t", confidence = 1.0, messageFingerprint = "fp-cancel", relatedTransactionId = null,
            pendingReason = PendingReason.UNLINKED_CANCEL, issuerKey = null, installment = false, overseas = false,
        )
        db.txnDao().insertIgnoringDuplicates(cancel)

        val candidates = repo.cancelCandidates(cancel)
        assertEquals("origin", candidates.first().id)

        val linked = repo.linkCancel(cancel, candidates.first())
        assertEquals("origin", linked.relatedTransactionId)
        assertEquals("c1", linked.cardId)
        assertEquals(TxStatus.AUTO, linked.status)
        assertNull(linked.pendingReason)
        // 이미 이어진 원 거래는 다른 취소의 후보에서 빠진다.
        val other = cancel.copy(id = "cancel2", messageFingerprint = "fp-cancel2")
        db.txnDao().insertIgnoringDuplicates(other)
        assertTrue(repo.cancelCandidates(other).none { it.id == "origin" })
    }

    private companion object {
        const val MESSAGING = "com.samsung.android.messaging"
    }
}
