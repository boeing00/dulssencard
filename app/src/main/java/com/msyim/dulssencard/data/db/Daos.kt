package com.msyim.dulssencard.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.CycleSnapshot
import com.msyim.dulssencard.data.model.Setting
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.Txn
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<Card>>

    @Query("SELECT * FROM cards ORDER BY createdAt ASC")
    suspend fun all(): List<Card>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun byId(id: String): Card?

    @Upsert
    suspend fun upsert(card: Card)

    @Delete
    suspend fun delete(card: Card)

    @Query("DELETE FROM cards")
    suspend fun clear()
}

@Dao
interface TxnDao {
    @Query("SELECT * FROM txns ORDER BY COALESCE(occurredAt, receivedAt) DESC, receivedAt DESC")
    fun observeAll(): Flow<List<Txn>>

    @Query("SELECT * FROM txns ORDER BY COALESCE(occurredAt, receivedAt) DESC, receivedAt DESC")
    suspend fun all(): List<Txn>

    @Query("SELECT * FROM txns WHERE id = :id")
    suspend fun byId(id: String): Txn?

    @Query("SELECT * FROM txns WHERE messageFingerprint = :fingerprint LIMIT 1")
    suspend fun byFingerprint(fingerprint: String): Txn?

    /**
     * 취소 거래를 원 승인 거래에 연결할 후보.
     * 같은 카드사·같은 금액·승인 방향이고, 취소 시각보다 앞선 것 중 가장 최근 것을 고른다.
     * 이미 다른 취소가 물린 거래는 [relatedTransactionId] 역참조로 걸러 낸다.
     */
    @Query(
        """
        SELECT * FROM txns
        WHERE direction = 'APPROVAL'
          AND amount = :amount
          AND (:issuerKey IS NULL OR issuerKey IS NULL OR issuerKey = :issuerKey)
          AND COALESCE(occurredAt, receivedAt) <= :before
          AND id NOT IN (SELECT relatedTransactionId FROM txns WHERE relatedTransactionId IS NOT NULL)
        ORDER BY COALESCE(occurredAt, receivedAt) DESC
        LIMIT 1
        """,
    )
    suspend fun findCancelOrigin(amount: Long, issuerKey: String?, before: Long): Txn?

    /**
     * 연결에 실패한 취소 거래의 **원 승인 거래 후보**. 사용자가 직접 고르게 보여 준다.
     *
     * 자동 연결([findCancelOrigin])보다 넓게 잡는다 — 카드사가 달라도, 금액이 커도(부분 취소) 보인다.
     * 취소 시각 이전의 승인만, 이미 다른 취소에 묶인 것은 빼고, 같은 금액을 먼저 최근 순으로.
     */
    @Query(
        """
        SELECT * FROM txns
        WHERE direction = 'APPROVAL'
          AND amount >= :amount
          AND COALESCE(occurredAt, receivedAt) <= :before
          AND id != :cancelId
          AND id NOT IN (
              SELECT relatedTransactionId FROM txns
              WHERE relatedTransactionId IS NOT NULL AND id != :cancelId
          )
        ORDER BY (amount = :amount) DESC, COALESCE(occurredAt, receivedAt) DESC
        LIMIT 30
        """,
    )
    suspend fun cancelCandidates(amount: Long, before: Long, cancelId: String): List<Txn>

    /**
     * 지문 유니크 인덱스에 걸리면 조용히 무시한다. **반환값이 -1 이면 들어가지 않은 것이다** —
     * 호출자는 반드시 확인해야 한다. 확인하지 않으면 경합에서 진 쪽을 "추가됨"으로 알린다.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoringDuplicates(txn: Txn): Long

    @Update
    suspend fun update(txn: Txn)

    @Upsert
    suspend fun upsert(txn: Txn)

    @Query("UPDATE txns SET cardId = NULL, status = 'PENDING', pendingReason = 'NO_CARD_MATCH' WHERE cardId = :cardId")
    suspend fun detachFromCard(cardId: String)

    @Query("DELETE FROM txns")
    suspend fun clear()
}

@Dao
interface AdjustmentDao {
    @Query("SELECT * FROM adjustments WHERE transactionId = :txnId ORDER BY createdAt DESC")
    fun observeForTxn(txnId: String): Flow<List<Adjustment>>

    @Query("SELECT * FROM adjustments ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Adjustment>>

    @Query("SELECT * FROM adjustments ORDER BY createdAt DESC")
    suspend fun all(): List<Adjustment>

    @Insert
    suspend fun insert(adjustment: Adjustment)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(adjustment: Adjustment): Long

    @Upsert
    suspend fun upsert(adjustment: Adjustment)

    @Query("DELETE FROM adjustments")
    suspend fun clear()
}

@Dao
interface SettingDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<Setting>>

    @Query("SELECT value FROM settings WHERE key = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM settings")
    suspend fun all(): List<Setting>

    @Upsert
    suspend fun put(setting: Setting)

    @Query("DELETE FROM settings")
    suspend fun clear()
}

@Dao
interface SourceAppDao {
    @Query("SELECT * FROM source_apps ORDER BY enabled DESC, lastSeenAt DESC")
    fun observeAll(): Flow<List<SourceApp>>

    @Query("SELECT * FROM source_apps WHERE packageName = :pkg")
    suspend fun byPackage(pkg: String): SourceApp?

    @Query("SELECT * FROM source_apps ORDER BY enabled DESC, label ASC")
    suspend fun all(): List<SourceApp>

    @Query("SELECT packageName FROM source_apps WHERE enabled = 1")
    suspend fun enabledPackages(): List<String>

    @Upsert
    suspend fun upsert(app: SourceApp)

    /**
     * 알림을 띄운 적 있다는 사실만 남긴다. 이미 있는 행의 enabled 는 건드리지 않는다.
     * 알림 내용은 여기에 들어오지 않는다.
     *
     * **컬럼을 전부 적는다.** 직접 쓴 SQL 이라 Room 이 컬럼을 채워 주지 않는다. v5 에서 진단 컬럼을
     * 더하고 여기를 안 고쳐, 새로 설치한 기기에서 처음 보는 앱이 알림을 띄울 때마다 NOT NULL 위반으로
     * 앱이 죽었다(에뮬레이터 검증에서 발견). 컬럼을 더하면 여기도 고칠 것 — `SourceAppDaoTest` 가 잡는다.
     */
    @Query(
        """
        INSERT INTO source_apps
            (packageName, label, issuerKey, enabled, lastSeenAt, lastPaymentAt, recognizedCount, failedCount, countsSince)
        VALUES (:pkg, :label, :issuerKey, 0, :seenAt, 0, 0, 0, 0)
        ON CONFLICT(packageName) DO UPDATE SET lastSeenAt = :seenAt, label = :label
        """,
    )
    suspend fun touch(pkg: String, label: String, issuerKey: String?, seenAt: Long)

    @Query("DELETE FROM source_apps")
    suspend fun clear()
}

@Dao
interface CycleSnapshotDao {
    @Query("SELECT * FROM cycle_snapshots ORDER BY closedAt DESC")
    fun observeAll(): Flow<List<CycleSnapshot>>

    @Query("SELECT * FROM cycle_snapshots ORDER BY closedAt DESC")
    suspend fun all(): List<CycleSnapshot>

    @Upsert
    suspend fun upsert(snapshot: CycleSnapshot)

    @Query("DELETE FROM cycle_snapshots")
    suspend fun clear()
}
