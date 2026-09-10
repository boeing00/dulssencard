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
     * 이미 다른 취소가 물린 거래는 [Txn.relatedTransactionId] 역참조로 걸러 낸다.
     *
     * 좁혀 둔 조건 셋 — 셋 다 없으면 합계가 조용히 어긋난다:
     *
     *  - **`status = 'AUTO'`**: 합계에 들어간 거래만 원본으로 삼는다. 확인 필요·제외된 거래에
     *    취소가 물면, 더한 적 없는 돈을 빼서 누적이 실제보다 작아진다. 원본이 아직 확인 필요면
     *    이 취소도 `UNLINKED_CANCEL` 로 남아 둘을 함께 처리하게 되는데, 그게 맞는 동작이다.
     *  - **[notBefore] 하한**: 기간 제한이 없으면 반년 전 같은 금액 거래에 물린다.
     *  - **카드사**: 예전에는 `issuerKey IS NULL OR` 가 끼어 있어서, 카드사를 판정하지 못한
     *    거래가 **아무 카드사의 취소와도** 맞아떨어졌다.
     */
    @Query(
        """
        SELECT * FROM txns
        WHERE direction = 'APPROVAL'
          AND status = 'AUTO'
          AND amount = :amount
          AND (:issuerKey IS NULL OR issuerKey = :issuerKey)
          AND COALESCE(occurredAt, receivedAt) <= :before
          AND COALESCE(occurredAt, receivedAt) >= :notBefore
          AND id NOT IN (SELECT relatedTransactionId FROM txns WHERE relatedTransactionId IS NOT NULL)
        ORDER BY COALESCE(occurredAt, receivedAt) DESC
        LIMIT 1
        """,
    )
    suspend fun findCancelOrigin(
        amount: Long,
        issuerKey: String?,
        before: Long,
        notBefore: Long,
    ): Txn?

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

    @Upsert
    suspend fun upsert(app: SourceApp)

    /**
     * 알림을 띄운 적 있다는 사실만 남긴다. 이미 있는 행의 enabled 는 건드리지 않는다.
     * 알림 내용은 여기에 들어오지 않는다.
     */
    @Query(
        """
        INSERT INTO source_apps (packageName, label, issuerKey, enabled, lastSeenAt)
        VALUES (:pkg, :label, :issuerKey, 0, :seenAt)
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

    @Upsert
    suspend fun upsert(snapshot: CycleSnapshot)

    @Query("DELETE FROM cycle_snapshots")
    suspend fun clear()
}
