package com.msyim.dulssencard.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.msyim.dulssencard.data.crypto.DbPassphrase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 마이그레이션 검증. **기기가 있어야 돈다** (`./gradlew :app:connectedDebugAndroidTest`).
 *
 * ## 왜 필요한가
 *
 * [AppDatabase] 는 "마이그레이션을 빠뜨린 채 출시하면 사용자 데이터가 조용히 날아간다"고
 * 주석으로 경고해 두었는데, 정작 1→2→3→4 가 **한 번도 검증된 적이 없었다.**
 * 마이그레이션은 틀려도 개발 기기에서는 티가 안 난다 — 늘 새로 설치하니까.
 * 이미 쓰고 있는 사용자의 기기에서만 터지고, 그때는 돌이킬 수 없다.
 *
 * 두 가지를 본다:
 *  1. 옛 버전에 넣어 둔 값이 마이그레이션 뒤에도 그대로 있는가.
 *  2. 마이그레이션이 끝난 스키마가 `@Database` 선언과 정확히 같은가
 *     (`runMigrationsAndValidate` 가 `app/schemas` 의 json 과 대조한다).
 *
 * 프로덕션과 같게 **SQLCipher 로** 연다. 마이그레이션 SQL 자체는 평범하지만,
 * 실제로 도는 엔진에서 확인하는 편이 값이 있다.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory(passphrase()),
    )

    @Test
    fun `1에서 4까지 올려도 카드가 살아남는다`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.insertCardV1()
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 4, true, *AppDatabase.MIGRATIONS)

        migrated.query(
            "SELECT nickname, trackingTarget, cycleStartDay, initialAmount, initialAmountAt FROM cards",
        ).use { cursor ->
            assertTrue("카드가 사라졌다", cursor.moveToFirst())
            assertEquals("우리카드", cursor.getString(0))
            assertEquals(300_000L, cursor.getLong(1))
            assertEquals(15L, cursor.getLong(2))
            // 3→4 로 생긴 칸이다. 초기값을 입력한 적이 없는 기존 카드는 0 이 맞다 —
            // 0 이 아니면 지난 사용액이 이번 주기에 얹힌다.
            assertEquals(0L, cursor.getLong(3))
            assertEquals(0L, cursor.getLong(4))
        }
    }

    @Test
    fun `1에서 4까지 올려도 거래가 살아남는다`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.insertTxnV1()
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 4, true, *AppDatabase.MIGRATIONS)

        migrated.query(
            "SELECT amount, merchant, occurredAtEstimated, foreignAmount FROM txns",
        ).use { cursor ->
            assertTrue("거래가 사라졌다", cursor.moveToFirst())
            assertEquals(84_300L, cursor.getLong(0))
            assertEquals("이마트 성수", cursor.getString(1))
            // 1→2 로 생긴 칸. 기존 거래는 문구에서 시각을 읽어 낸 것들이라 '추정 아님'이 맞다.
            assertEquals(0, cursor.getInt(2))
            // 2→3 으로 생긴 칸. 원화 거래이므로 비어 있어야 한다.
            assertTrue("외화 금액이 채워져 있다", cursor.isNull(3))
        }
    }

    @Test
    fun `2에서 시작한 DB 도 4까지 올라간다`() {
        // 중간 버전에서 멈춰 있던 기기(업데이트를 건너뛴 사용자)도 올라와야 한다.
        helper.createDatabase(DB_NAME, 1).use { db -> db.insertCardV1() }
        helper.runMigrationsAndValidate(DB_NAME, 2, true, AppDatabase.MIGRATIONS[0]).close()

        val migrated = helper.runMigrationsAndValidate(
            DB_NAME,
            4,
            true,
            AppDatabase.MIGRATIONS[1],
            AppDatabase.MIGRATIONS[2],
        )

        migrated.query("SELECT nickname FROM cards").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("우리카드", cursor.getString(0))
        }
    }

    @Test
    fun `한 단계씩 올려도 결과가 같다`() {
        helper.createDatabase(DB_NAME, 1).use { db ->
            db.insertCardV1()
            db.insertTxnV1()
        }

        helper.runMigrationsAndValidate(DB_NAME, 2, true, AppDatabase.MIGRATIONS[0]).close()
        helper.runMigrationsAndValidate(DB_NAME, 3, true, AppDatabase.MIGRATIONS[1]).close()
        val migrated = helper.runMigrationsAndValidate(DB_NAME, 4, true, AppDatabase.MIGRATIONS[2])

        migrated.query("SELECT COUNT(*) FROM cards").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.query("SELECT COUNT(*) FROM txns").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
    }

    private fun SupportSQLiteDatabase.insertCardV1() = execSQL(
        """
        INSERT INTO cards
          (id, nickname, trackingTarget, cycleStartDay, matchKeywords, excludeKeywords,
           defaultCountsTowardTarget, defaultCountsTowardPurchaseLimit, active, createdAt)
        VALUES ('c1', '우리카드', 300000, 15, '우리카드', '', 1, 1, 1, 1757300000000)
        """.trimIndent(),
    )

    private fun SupportSQLiteDatabase.insertTxnV1() = execSQL(
        """
        INSERT INTO txns
          (id, cardId, occurredAt, receivedAt, amount, currency, direction, status, source,
           merchant, countsTowardTarget, countsTowardPurchaseLimit, parserVersion, confidence,
           messageFingerprint, relatedTransactionId, pendingReason, issuerKey, installment, overseas)
        VALUES ('t1', 'c1', 1757300000000, 1757300060000, 84300, 'KRW', 'APPROVAL', 'AUTO', 'SMS',
                '이마트 성수', 1, 1, 'v1.0', 0.9, 'fp-1', NULL, NULL, 'WOORI', 0, 0)
        """.trimIndent(),
    )

    private companion object {
        const val DB_NAME = "migration-test.db"

        /** 프로덕션과 같은 SQLCipher 암호. 네이티브 라이브러리를 먼저 올려야 한다. */
        fun passphrase(): ByteArray {
            System.loadLibrary("sqlcipher")
            return DbPassphrase.getOrCreate(
                InstrumentationRegistry.getInstrumentation().targetContext,
            )
        }
    }
}
