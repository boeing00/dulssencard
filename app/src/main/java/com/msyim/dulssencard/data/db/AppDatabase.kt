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
    version = 4,
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

        /**
         * 시각 추정 플래그 추가.
         * 기존 거래는 문구에서 시각을 읽어 낸 것들이므로 기본값 0(추정 아님)이 맞다.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE txns ADD COLUMN occurredAtEstimated INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        /** 해외 사용 금액을 통화별로 따로 보여 주기 위한 컬럼. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
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
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cards ADD COLUMN initialAmount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cards ADD COLUMN initialAmountAt INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * 마이그레이션 전체. 계측 테스트(`AppDatabaseMigrationTest`)가 이 목록을 그대로 검증한다.
         * 새 마이그레이션을 더하면 여기에 넣고, 테스트에 케이스를 함께 추가한다.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

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
    }
}
