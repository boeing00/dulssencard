package com.msyim.dulssencard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.msyim.dulssencard.data.crypto.DbPassphrase
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.CycleSnapshot
import com.msyim.dulssencard.data.model.Setting
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.ForeignMoney
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Card::class,
        Txn::class,
        Adjustment::class,
        Setting::class,
        SourceApp::class,
        CycleSnapshot::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardDao(): CardDao
    abstract fun txnDao(): TxnDao
    abstract fun adjustmentDao(): AdjustmentDao
    abstract fun settingDao(): SettingDao
    abstract fun sourceAppDao(): SourceAppDao
    abstract fun cycleSnapshotDao(): CycleSnapshotDao

    companion object {
        const val DB_NAME = "dulssencard.db"

        /** 현재 스키마 버전. 백업 파일에 적어 두고, 더 새 앱이 만든 백업은 열지 않는 데 쓴다. */
        const val SCHEMA_VERSION = 5

        /**
         * 시각 추정 플래그 추가.
         * 기존 거래는 문구에서 시각을 읽어 낸 것들이므로 기본값 0(추정 아님)이 맞다.
         */
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE txns ADD COLUMN occurredAtEstimated INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** 해외 사용 금액을 통화별로 따로 보여 주기 위한 컬럼. */
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE txns ADD COLUMN foreignAmount REAL")
            }
        }

        /**
         * 초기 사용액. 과거 내역을 OCR 로 복원하는 대신 사용자가 직접 입력받는다.
         *
         * 기존 카드는 0 이 맞다 — 초기값을 입력한 적이 없으므로 지금까지처럼 통지만 센다.
         * `initialAmountAt` 0 은 "초기값을 쓰지 않는 카드"라는 뜻이라 기준 시각 필터가 걸리지 않는다.
         */
        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN initialAmount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN initialAmountAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v5 — 네 가지를 한 번에 바꾼다(마이그레이션을 쪼개면 중간 버전으로 출시된 적 없는 스키마가 생긴다).
         *
         *  1. **외화 금액 Double → 최소 통화 단위 정수.** `foreignAmount REAL` 을 없애고
         *     `foreignAmountMinor INTEGER` 로 옮긴다. SQLite 구버전은 DROP COLUMN 이 없어서
         *     표를 새로 만들어 복사한다. 자릿수는 통화마다 달라(USD 2, JPY 0) SQL 로는 못 정하므로
         *     변환은 Kotlin 에서 [ForeignMoney] 로 한다.
         *  2. **updatedAt** (cards · txns · settings) — 백업 병합에서 최근에 바꾼 쪽을 고르는 기준.
         *     기존 행은 0(모름)으로 둔다. 추측한 시각을 넣으면 병합 판정이 근거 없이 기운다.
         *  3. **알림 소스 진단 카운터** — 마지막 결제 인식 시각, 인식/실패 수.
         */
        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE settings ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE source_apps ADD COLUMN lastPaymentAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE source_apps ADD COLUMN recognizedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE source_apps ADD COLUMN failedCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE source_apps ADD COLUMN countsSince INTEGER NOT NULL DEFAULT 0")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `txns_new` (`id` TEXT NOT NULL, `cardId` TEXT, " +
                        "`occurredAt` INTEGER, `occurredAtEstimated` INTEGER NOT NULL, " +
                        "`receivedAt` INTEGER NOT NULL, `amount` INTEGER NOT NULL, " +
                        "`currency` TEXT NOT NULL, `foreignAmountMinor` INTEGER, " +
                        "`direction` TEXT NOT NULL, `status` TEXT NOT NULL, `source` TEXT NOT NULL, " +
                        "`merchant` TEXT, `countsTowardTarget` INTEGER NOT NULL, " +
                        "`countsTowardPurchaseLimit` INTEGER NOT NULL, `parserVersion` TEXT NOT NULL, " +
                        "`confidence` REAL NOT NULL, `messageFingerprint` TEXT NOT NULL, " +
                        "`relatedTransactionId` TEXT, `pendingReason` TEXT, `issuerKey` TEXT, " +
                        "`installment` INTEGER NOT NULL, `overseas` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                val shared = "id, cardId, occurredAt, occurredAtEstimated, receivedAt, amount, currency, " +
                    "direction, status, source, merchant, countsTowardTarget, countsTowardPurchaseLimit, " +
                    "parserVersion, confidence, messageFingerprint, relatedTransactionId, pendingReason, " +
                    "issuerKey, installment, overseas"
                db.execSQL(
                    "INSERT INTO txns_new ($shared, foreignAmountMinor, updatedAt) " +
                        "SELECT $shared, NULL, 0 FROM txns",
                )

                db.query("SELECT id, currency, foreignAmount FROM txns WHERE foreignAmount IS NOT NULL")
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getString(0)
                            val currency = cursor.getString(1)
                            val minor = ForeignMoney.fromLegacyDouble(cursor.getDouble(2), currency)
                                ?: continue
                            db.execSQL(
                                "UPDATE txns_new SET foreignAmountMinor = ? WHERE id = ?",
                                arrayOf<Any>(minor, id),
                            )
                        }
                    }

                db.execSQL("DROP TABLE txns")
                db.execSQL("ALTER TABLE txns_new RENAME TO txns")
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_txns_messageFingerprint` ON `txns` (`messageFingerprint`)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txns_cardId` ON `txns` (`cardId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txns_status` ON `txns` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_txns_occurredAt` ON `txns` (`occurredAt`)")
            }
        }

        /** 앱과 마이그레이션 테스트가 **같은 목록**을 쓴다. 테스트만 통과하고 앱에서 빠지는 일을 막는다. */
        internal val MIGRATIONS: Array<Migration> =
            arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * SQLCipher 로 열린 로컬 DB.
         *
         * 암호는 [DbPassphrase] 가 만들어 Android Keystore 키로 봉인해 둔 32바이트 난수다.
         * 평문 암호는 디스크에 남지 않는다.
         *
         * BroadcastReceiver·NotificationListenerService 도 같은 인스턴스를 쓴다.
         * 프로세스가 새로 떠도 Keystore 에서 다시 풀 수 있으므로 잠금 화면 상태와 무관하게 열린다.
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase {
            System.loadLibrary("sqlcipher")
            val factory = SupportOpenHelperFactory(DbPassphrase.getOrCreate(context))
            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(*MIGRATIONS)
                // 마이그레이션을 빠뜨린 채 출시하면 사용자 데이터가 조용히 날아간다.
                // 스키마를 바꿀 때는 반드시 Migration 을 추가한다.
                .build()
        }

        /** '로컬 데이터 전체 삭제' 후 다음 열기에서 새 파일을 만들도록 인스턴스를 버린다. */
        fun closeAndForget() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
