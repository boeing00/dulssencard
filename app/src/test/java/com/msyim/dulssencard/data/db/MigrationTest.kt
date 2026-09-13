package com.msyim.dulssencard.data.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Room 마이그레이션 테스트. **실제 DB 파일**을 과거 스키마(내보낸 `schemas/N.json`)로 만들고,
 * 데이터를 넣은 뒤 **앱과 똑같이 [Room.databaseBuilder] 로 열어** 최신 버전까지 올린다.
 *
 * 마이그레이션을 빠뜨리거나 틀리면 사용자 데이터가 조용히 날아간다 — 앱을 업데이트했더니
 * 카드와 거래가 사라지는 식이다. 단위 테스트로는 못 잡고, 기기에서는 이미 늦다.
 *
 * ## 왜 MigrationTestHelper 를 안 쓰나
 *
 * `androidx.sqlite` 2.8.4 의 `SupportSQLiteDriver.open()` 이 DB 이름을 경로에서 슬래시(`/`)로만
 * 잘라 비교한다(바이트코드 확인). Windows 경로는 역슬래시라 항상 불일치해 **Windows 에서는
 * 테스트가 전부 실패**한다. Linux CI 에서만 도는 테스트는 로컬에서 아무도 안 돌린다.
 *
 * 그래서 헬퍼가 하는 일을 직접 한다: 스키마 JSON 의 CREATE 문으로 과거 버전 파일을 만들고,
 * 앱 코드 경로로 연다. Room 은 열 때 생성 코드로 결과 스키마를 검증하므로(프로덕션과 같은 검증)
 * 틀린 마이그레이션은 여기서 예외로 드러난다.
 *
 * 암호화(SQLCipher)는 쓰지 않는다 — 스키마 변환은 암호화와 무관하고, 앱은 같은
 * [AppDatabase.MIGRATIONS] 목록을 쓴다. Robolectric 위에서 돌아 CI 에서 매 PR 마다 실행된다.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {

    private val name = "migration-test.db"
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var room: AppDatabase? = null

    @Before
    fun clean() {
        context.deleteDatabase(name)
    }

    @After
    fun close() {
        room?.close()
        context.deleteDatabase(name)
    }

    // ------------------------------------------------------- 도구

    /** 내보낸 스키마 JSON 으로 [version] 의 DB 파일을 만들고, [seed] 로 데이터를 넣는다. */
    private fun createDatabase(version: Int, seed: (SQLiteDatabase) -> Unit) {
        // Gradle 단위 테스트의 작업 디렉터리는 모듈 루트(app/)다.
        val json = File("schemas/com.msyim.dulssencard.data.db.AppDatabase/$version.json")
        assertTrue("스키마 파일이 없다: ${json.absolutePath}", json.exists())
        val schema = Json.parseToJsonElement(json.readText()).jsonObject.getValue("database").jsonObject

        val file = context.getDatabasePath(name).also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val obj = entity.jsonObject
                val table = obj.getValue("tableName").jsonPrimitive.content
                db.execSQL(createSql(obj, table))
                obj["indices"]?.jsonArray?.forEach { index -> db.execSQL(createSql(index.jsonObject, table)) }
            }
            // room_master_table 과 그 버전의 identity hash. Room 이 이걸 보고 어떤 스키마인지 판단한다.
            schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
            db.version = version
            seed(db)
        }
    }

    private fun createSql(obj: JsonObject, table: String): String =
        obj.getValue("createSql").jsonPrimitive.content.replace(TABLE_PLACEHOLDER, table)

    /** 앱과 같은 경로로 연다. 마이그레이션이 돌고, Room 이 결과 스키마를 검증한다. */
    private fun migrate(): SupportSQLiteDatabase {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        room = db
        return db.openHelper.writableDatabase.also { opened ->
            assertEquals("최신 버전까지 올라가지 않았다", 5, opened.version)
        }
    }

    private fun values(vararg pairs: Pair<String, Any?>) = ContentValues().apply {
        pairs.forEach { (key, value) ->
            when (value) {
                null -> putNull(key)
                is String -> put(key, value)
                is Int -> put(key, value)
                is Long -> put(key, value)
                is Double -> put(key, value)
                is Boolean -> put(key, if (value) 1 else 0)
                else -> error("unsupported $value")
            }
        }
    }

    private fun SQLiteDatabase.put(table: String, vararg pairs: Pair<String, Any?>) {
        insertOrThrow(table, null, values(*pairs))
    }

    private fun <T> SupportSQLiteDatabase.single(sql: String, read: (Cursor) -> T): T =
        query(sql).use { cursor ->
            assertTrue("행이 없다: $sql", cursor.moveToFirst())
            read(cursor)
        }

    /** v1 에 있던 거래 컬럼. 버전별로 필요한 것만 덧붙인다. */
    private fun v1Txn(
        id: String,
        fingerprint: String,
        amount: Long,
        currency: String = "KRW",
    ): Array<Pair<String, Any?>> = arrayOf(
        "id" to id,
        "cardId" to "c1",
        "occurredAt" to 1_788_000_000_000L,
        "receivedAt" to 1_788_000_060_000L,
        "amount" to amount,
        "currency" to currency,
        "direction" to "APPROVAL",
        "status" to "AUTO",
        "source" to "SMS",
        "merchant" to "편의점",
        "countsTowardTarget" to true,
        "countsTowardPurchaseLimit" to false,
        "parserVersion" to "p1",
        "confidence" to 0.9,
        "messageFingerprint" to fingerprint,
        "relatedTransactionId" to null,
        "pendingReason" to null,
        "issuerKey" to "SHINHAN",
        "installment" to false,
        "overseas" to false,
    )

    private fun v1Card(): Array<Pair<String, Any?>> = arrayOf(
        "id" to "c1",
        "nickname" to "신한 딥드림",
        "trackingTarget" to 500_000L,
        "cycleStartDay" to 15,
        "matchKeywords" to "신한\n신한카드",
        "excludeKeywords" to "해외",
        "defaultCountsTowardTarget" to true,
        "defaultCountsTowardPurchaseLimit" to false,
        "active" to true,
        "createdAt" to 1_787_000_000_000L,
    )

    // ------------------------------------------------------- v1 → 최신

    @Test
    fun `v1 카드와 거래가 최신 버전까지 그대로 살아남는다`() {
        createDatabase(1) { db ->
            db.put("cards", *v1Card())
            db.put("txns", *v1Txn("t1", "fp-1", 84_300L))
            db.put("settings", "key" to "limit_amount", "value" to "1000000")
            db.put(
                "source_apps",
                "packageName" to "com.shcard.smartpay", "label" to "신한 SOL페이",
                "issuerKey" to "SHINHAN", "enabled" to true, "lastSeenAt" to 1_788_000_000_000L,
            )
        }

        val db = migrate()

        db.single("SELECT * FROM cards WHERE id = 'c1'") { c ->
            assertEquals("신한 딥드림", c.getString(c.getColumnIndexOrThrow("nickname")))
            assertEquals(500_000L, c.getLong(c.getColumnIndexOrThrow("trackingTarget")))
            assertEquals(15, c.getInt(c.getColumnIndexOrThrow("cycleStartDay")))
            assertEquals("신한\n신한카드", c.getString(c.getColumnIndexOrThrow("matchKeywords")))
            // v4 에서 생긴 컬럼 — 초기값을 쓴 적 없는 카드이므로 0 이어야 한다.
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("initialAmount")))
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("initialAmountAt")))
            assertEquals(0L, c.getLong(c.getColumnIndexOrThrow("updatedAt")))
        }
        db.single("SELECT * FROM txns WHERE id = 't1'") { c ->
            assertEquals(84_300L, c.getLong(c.getColumnIndexOrThrow("amount")))
            assertEquals("fp-1", c.getString(c.getColumnIndexOrThrow("messageFingerprint")))
            assertEquals("편의점", c.getString(c.getColumnIndexOrThrow("merchant")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("countsTowardPurchaseLimit")))
            // v2 에서 생긴 컬럼 — 옛 거래는 문구에서 시각을 읽은 것이라 추정이 아니다.
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("occurredAtEstimated")))
            assertTrue(c.isNull(c.getColumnIndexOrThrow("foreignAmountMinor")))
        }
        db.single("SELECT value FROM settings WHERE key = 'limit_amount'") { c ->
            assertEquals("1000000", c.getString(0))
        }
        db.single("SELECT * FROM source_apps") { c ->
            assertEquals(1, c.getInt(c.getColumnIndexOrThrow("enabled")))
            assertEquals(0, c.getInt(c.getColumnIndexOrThrow("failedCount")))
        }
    }

    // ------------------------------------------------------- 시각 추정 플래그 (v2)

    @Test
    fun `v2 시각 추정 플래그가 보존된다`() {
        createDatabase(2) { db ->
            db.put("cards", *v1Card())
            db.put("txns", *v1Txn("t-est", "fp-est", 12_000L), "occurredAtEstimated" to true)
            db.put("txns", *v1Txn("t-exact", "fp-exact", 9_000L), "occurredAtEstimated" to false)
        }
        val db = migrate()
        db.single("SELECT occurredAtEstimated FROM txns WHERE id = 't-est'") { assertEquals(1, it.getInt(0)) }
        db.single("SELECT occurredAtEstimated FROM txns WHERE id = 't-exact'") { assertEquals(0, it.getInt(0)) }
    }

    // ------------------------------------------------------- 외화 금액 (v3 REAL → v5 최소 단위)

    @Test
    fun `v3 외화 금액이 통화별 최소 단위 정수로 바뀐다`() {
        createDatabase(3) { db ->
            db.put("cards", *v1Card())
            fun foreign(id: String, currency: String, value: Double?) = db.put(
                "txns",
                *v1Txn(id, "fp-$id", 0L, currency),
                "occurredAtEstimated" to false,
                "foreignAmount" to value,
            )
            foreign("usd", "USD", 42.5)
            foreign("jpy", "JPY", 3000.0)
            // Double 로 정확히 표현되지 않는 값. 19.99 는 19.989999… 로 저장된다.
            foreign("edge", "USD", 19.99)
            foreign("sum", "USD", 0.1 + 0.2)
            foreign("zero", "EUR", 0.0)
            foreign("none", "USD", null)
        }

        val db = migrate()

        fun minor(id: String): Long? = db.single("SELECT foreignAmountMinor FROM txns WHERE id = '$id'") {
            if (it.isNull(0)) null else it.getLong(0)
        }
        assertEquals(4_250L, minor("usd"))
        assertEquals("JPY 는 소수 자릿수가 0이다", 3_000L, minor("jpy"))
        assertEquals("반올림 경계에서 1센트가 틀어졌다", 1_999L, minor("edge"))
        assertEquals("0.1+0.2 가 30센트가 아니다", 30L, minor("sum"))
        assertNull("0 은 금액이 아니다", minor("zero"))
        assertNull(minor("none"))
    }

    // ------------------------------------------------------- 초기 사용액 (v4)

    @Test
    fun `v4 초기 사용액과 기준 시각이 보존된다`() {
        createDatabase(4) { db ->
            db.put(
                "cards", *v1Card(),
                "initialAmount" to 320_000L,
                "initialAmountAt" to 1_788_900_000_000L,
            )
        }
        val db = migrate()
        db.single("SELECT initialAmount, initialAmountAt FROM cards WHERE id = 'c1'") { c ->
            assertEquals(320_000L, c.getLong(0))
            assertEquals(1_788_900_000_000L, c.getLong(1))
        }
    }

    // ------------------------------------------------------- 이중 집계 방어선

    @Test
    fun `표를 새로 만든 뒤에도 지문 유니크 인덱스가 살아 있다`() {
        // v5 는 txns 를 새로 만들어 복사한다. 인덱스를 다시 만들지 않으면 이중 집계의
        // 마지막 방어선(지문 유니크 인덱스)이 조용히 사라진다.
        createDatabase(4) { db ->
            db.put("cards", *v1Card(), "initialAmount" to 0L, "initialAmountAt" to 0L)
            db.put(
                "txns", *v1Txn("t1", "same-fp", 1_000L),
                "occurredAtEstimated" to false, "foreignAmount" to null,
            )
        }
        val db = migrate()
        val duplicate = values(
            *v1Txn("t2", "same-fp", 1_000L),
            "occurredAtEstimated" to false, "foreignAmountMinor" to null, "updatedAt" to 0L,
        )
        try {
            db.insert("txns", SQLiteDatabase.CONFLICT_ABORT, duplicate)
            fail("같은 지문이 두 번 들어갔다")
        } catch (expected: SQLiteConstraintException) {
            // 정상
        }
    }

    // ------------------------------------------------------- 실제 앱 코드로 읽기

    @Test
    fun `마이그레이션한 파일을 앱의 DAO 로 읽을 수 있다`() {
        // 스키마 검증을 통과해도 엔티티 매핑(타입 변환기·nullable)에서 깨질 수 있다. 실제 DAO 로 읽는다.
        createDatabase(3) { db ->
            db.put("cards", *v1Card())
            db.put(
                "txns", *v1Txn("t1", "fp-1", 0L, "USD"),
                "occurredAtEstimated" to true, "foreignAmount" to 42.5,
            )
        }
        migrate()
        val app = requireNotNull(room)
        runBlocking {
            val card = app.cardDao().all().single()
            assertEquals(listOf("신한", "신한카드"), card.matchKeywords)
            assertFalse(card.defaultCountsTowardPurchaseLimit)
            val txn = app.txnDao().all().single()
            assertEquals(4_250L, txn.foreignAmountMinor)
            assertTrue(txn.occurredAtEstimated)
            assertEquals("USD", txn.currency)
        }
    }

    private companion object {
        /** 스키마 JSON 의 CREATE 문에 들어 있는 표 이름 자리. */
        const val TABLE_PLACEHOLDER = "\${TABLE_NAME}"
    }
}
