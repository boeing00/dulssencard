package com.msyim.dulssencard.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.db.AppDatabase
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * 백업 **전체 흐름** 테스트. 실제 Room DB(메모리) 두 개를 "원래 기기"와 "새 기기"로 쓴다.
 *
 * Android Keystore 는 JVM 에 없으므로 기기 키 자리에 일반 AES 키를 넣는다 — 형식 코드는 같은 경로를 탄다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BackupFlowTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val fast = BackupCrypto.KdfParams(memoryKiB = 1024, iterations = 1, parallelism = 1)
    private val deviceKey: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private lateinit var oldDb: AppDatabase
    private lateinit var newDb: AppDatabase
    private lateinit var oldRepo: DulSsenRepository
    private lateinit var newRepo: DulSsenRepository

    @Before
    fun open() {
        oldDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        newDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        oldRepo = DulSsenRepository(context, oldDb)
        newRepo = DulSsenRepository(context, newDb)
    }

    @After
    fun close() {
        oldDb.close()
        newDb.close()
    }

    private fun manager(repo: DulSsenRepository, store: AutoBackupStore) =
        BackupManager(repo, store, appVersion = "test", kdfParams = fast)

    private fun store(name: String, key: () -> SecretKey = { deviceKey }) =
        AutoBackupStore(folder.newFolder(name), key)

    private suspend fun seedOriginal() {
        oldRepo.upsertCard(
            Card(
                id = "c1", nickname = "우리 카드의정석", trackingTarget = 500_000, cycleStartDay = 15,
                matchKeywords = listOf("우리카드", "4321"), excludeKeywords = listOf("해외"),
                defaultCountsTowardTarget = true, defaultCountsTowardPurchaseLimit = false,
                initialAmount = 320_000, initialAmountAt = 1_788_900_000_000L,
            ),
        )
        oldRepo.addManualTxn("c1", 16_000, TxDirection.APPROVAL, 1_788_950_000_000L, "푸른들컨트리클럽")
        oldDb.txnDao().insertIgnoringDuplicates(
            Txn(
                id = "usd", cardId = "c1", occurredAt = 1_788_960_000_000L, receivedAt = 1_788_960_000_000L,
                amount = 0, currency = "USD", foreignAmountMinor = 4_250, direction = TxDirection.APPROVAL,
                status = TxStatus.PENDING, source = TxSource.PUSH, merchant = "AMAZON", countsTowardTarget = true,
                countsTowardPurchaseLimit = true, parserVersion = "t", confidence = 0.7, messageFingerprint = "fp-usd",
                relatedTransactionId = null, pendingReason = null, issuerKey = "WOORI", installment = false,
                overseas = true, updatedAt = 1_788_960_000_000L,
            ),
        )
        oldRepo.putSetting(Settings.LIMIT_AMOUNT, "700000")
    }

    private fun opened(result: BackupManager.OpenResult): BackupPayload {
        if (result !is BackupManager.OpenResult.Opened) fail("열리지 않았다: $result")
        return (result as BackupManager.OpenResult.Opened).payload
    }

    // ------------------------------------------------------- 왕복

    @Test
    fun `내보낸 백업을 새 기기에 전체 교체로 넣으면 원본과 같다`() = runBlocking {
        seedOriginal()
        val file = manager(oldRepo, store("old")).export("우리집 비밀번호 7".toCharArray())

        val newManager = manager(newRepo, store("new"))
        assertEquals(true, newManager.requiresPassword(file))
        val payload = opened(newManager.open(file, "우리집 비밀번호 7".toCharArray()))
        newManager.apply(payload, ImportPlanner.Mode.REPLACE)

        val card = newDb.cardDao().byId("c1")!!
        assertEquals(320_000L, card.initialAmount)
        assertEquals(listOf("우리카드", "4321"), card.matchKeywords)
        assertEquals(15, card.cycleStartDay)
        val txns = newDb.txnDao().all().associateBy { it.currency }
        assertEquals(16_000L, txns.getValue("KRW").amount)
        assertEquals("외화 금액이 정수 그대로 옮겨져야 한다", 4_250L, txns.getValue("USD").foreignAmountMinor)
        assertEquals("700000", newRepo.getSetting(Settings.LIMIT_AMOUNT))
        assertTrue("직접 입력 기록이 따라와야 한다", newDb.adjustmentDao().all().isNotEmpty())
    }

    @Test
    fun `틀린 비밀번호로는 열리지 않는다`() = runBlocking {
        seedOriginal()
        val file = manager(oldRepo, store("old")).export("correct horse".toCharArray())
        val result = manager(newRepo, store("new")).open(file, "correct hors".toCharArray())
        assertEquals(BackupManager.OpenResult.Failed(BackupCrypto.Failure.WrongPasswordOrCorrupted), result)
    }

    // ------------------------------------------------------- 복원 전 자동 백업

    @Test
    fun `적용 전에 현재 상태를 자동 백업하고 그걸로 되돌릴 수 있다`() = runBlocking {
        seedOriginal()
        val backupFile = manager(oldRepo, store("old")).export("password123".toCharArray())

        // 새 기기에는 이미 다른 데이터가 있다. 실수로 전체 교체를 누른다.
        newRepo.upsertCard(
            Card("mine", "내 카드", 100_000, 1, listOf("신한"), emptyList(), true, true),
        )
        val autoStore = store("auto")
        val newManager = manager(newRepo, autoStore)
        newManager.apply(opened(newManager.open(backupFile, "password123".toCharArray())), ImportPlanner.Mode.REPLACE)
        assertEquals(listOf("c1"), newDb.cardDao().all().map { it.id })

        // 자동 백업이 하나 생겼고, 그 안에는 교체 **전** 상태가 있다.
        val entry = newManager.autoBackupList().single()
        val before = opened(newManager.openAutoBackup(entry))
        assertEquals(listOf("mine"), before.cards.map { it.id })

        // 되돌린다.
        newManager.apply(before, ImportPlanner.Mode.REPLACE)
        assertEquals(listOf("mine"), newDb.cardDao().all().map { it.id })
        assertEquals("되돌리기 직전 상태도 자동 백업된다", 2, newManager.autoBackupList().size)
    }

    @Test
    fun `자동 백업을 만들지 못하면 가져오기를 적용하지 않는다`() = runBlocking {
        seedOriginal()
        val file = manager(oldRepo, store("old")).export("password123".toCharArray())
        newRepo.upsertCard(Card("mine", "내 카드", 100_000, 1, listOf("신한"), emptyList(), true, true))

        val broken = store("broken") { throw IllegalStateException("Keystore 사용 불가") }
        val newManager = manager(newRepo, broken)
        val payload = opened(manager(newRepo, store("reader")).open(file, "password123".toCharArray()))
        try {
            newManager.apply(payload, ImportPlanner.Mode.REPLACE)
            fail("안전망 없이 적용됐다")
        } catch (expected: IllegalStateException) {
            // 정상
        }
        assertEquals("자동 백업 실패 후에도 교체됐다", listOf("mine"), newDb.cardDao().all().map { it.id })
    }

    @Test
    fun `적용 도중 죽으면 전체가 되돌아간다`() = runBlocking {
        seedOriginal()
        val file = manager(oldRepo, store("old")).export("password123".toCharArray())
        newRepo.upsertCard(Card("mine", "내 카드", 100_000, 1, listOf("신한"), emptyList(), true, true))
        val newManager = manager(newRepo, store("auto"))
        val payload = opened(newManager.open(file, "password123".toCharArray()))

        newRepo.faultInjector = { if (it == "applyImport:afterTxns") throw IllegalStateException("중단") }
        try {
            newManager.apply(payload, ImportPlanner.Mode.REPLACE)
            fail("중단이 주입되지 않았다")
        } catch (expected: IllegalStateException) {
            // 정상
        }
        newRepo.faultInjector = null
        assertEquals("표를 비운 뒤 죽었는데 로컬 카드가 사라졌다", listOf("mine"), newDb.cardDao().all().map { it.id })
        assertTrue(newDb.txnDao().all().isEmpty())
    }

    // ------------------------------------------------------- 미리보기와 병합

    @Test
    fun `미리보기는 아무것도 쓰지 않는다`() = runBlocking {
        seedOriginal()
        val file = manager(oldRepo, store("old")).export("password123".toCharArray())
        val newManager = manager(newRepo, store("new"))
        val plan = newManager.preview(opened(newManager.open(file, "password123".toCharArray())), ImportPlanner.Mode.MERGE)
        assertEquals(1, plan.summary.cardsAdded)
        assertEquals(2, plan.summary.txnsAdded)
        assertTrue(newDb.cardDao().all().isEmpty())
        assertTrue(newManager.autoBackupList().isEmpty())
    }

    @Test
    fun `같은 백업을 두 번 병합해도 거래가 늘지 않는다`() = runBlocking {
        seedOriginal()
        val file = manager(oldRepo, store("old")).export("password123".toCharArray())
        val newManager = manager(newRepo, store("new"))
        val payload = opened(newManager.open(file, "password123".toCharArray()))
        newManager.apply(payload, ImportPlanner.Mode.MERGE)
        val second = newManager.apply(payload, ImportPlanner.Mode.MERGE)
        assertEquals("이중 집계", 2, newDb.txnDao().all().size)
        assertEquals(0, second.summary.txnsAdded)
    }

    // ------------------------------------------------------- 열지 않는 백업

    @Test
    fun `더 새 앱이 만든 백업은 열지 않는다`() = runBlocking {
        seedOriginal()
        val future = BackupManager(oldRepo, store("old"), appVersion = "9.0", schemaVersion = 99, kdfParams = fast)
        val file = future.export("password123".toCharArray())
        val result = manager(newRepo, store("new")).open(file, "password123".toCharArray())
        assertEquals(BackupManager.OpenResult.NewerApp(99, AppDatabase.SCHEMA_VERSION), result)
    }

    @Test
    fun `복호화는 되지만 내용이 모순되면 가져오지 않는다`() = runBlocking {
        // 없는 카드를 가리키는 거래 — 버그 있는 옛 앱이 만든 백업을 흉내 낸다.
        val broken = BackupPayload(
            payloadVersion = 1, createdAt = 1, appVersion = "old", schemaVersion = 5,
            cards = emptyList(),
            txns = listOf(TxnDto.from(Txn(
                id = "t", cardId = "ghost", occurredAt = 1, receivedAt = 1, amount = 1_000, currency = "KRW",
                direction = TxDirection.APPROVAL, status = TxStatus.AUTO, source = TxSource.SMS, merchant = null,
                countsTowardTarget = true, countsTowardPurchaseLimit = true, parserVersion = "t", confidence = 1.0,
                messageFingerprint = "fp", relatedTransactionId = null, pendingReason = null, issuerKey = null,
                installment = false, overseas = false,
            ))),
            adjustments = emptyList(), settings = emptyList(), cycleSnapshots = emptyList(), sourceApps = emptyList(),
        )
        val file = BackupCrypto.encryptWithPassword(BackupPayload.encode(broken), "password123".toCharArray(), fast)
        val result = manager(newRepo, store("new")).open(file, "password123".toCharArray())
        assertTrue("모순된 내용이 열렸다: $result", result is BackupManager.OpenResult.Invalid)
    }

    @Test
    fun `짧거나 확인이 다른 비밀번호는 받지 않는다`() {
        val m = manager(newRepo, store("new"))
        assertTrue(m.passwordProblem("short".toCharArray(), "short".toCharArray()) != null)
        assertTrue(m.passwordProblem("long enough 1".toCharArray(), "long enough 2".toCharArray()) != null)
        assertTrue(m.passwordProblem("aaaaaaaaaa".toCharArray(), "aaaaaaaaaa".toCharArray()) != null)
        assertEquals(null, m.passwordProblem("우리집 비밀번호 7".toCharArray(), "우리집 비밀번호 7".toCharArray()))
    }

    @Test
    fun `자동 백업은 최근 세 개만 남는다`() {
        val autoStore = store("auto")
        repeat(5) { i -> autoStore.save("{\"n\":$i}".toByteArray(), now = 1_000L + i) }
        assertEquals(listOf(1_004L, 1_003L, 1_002L), autoStore.list().map { it.createdAt })
        autoStore.deleteAll()
        assertTrue(autoStore.list().isEmpty())
    }
}
