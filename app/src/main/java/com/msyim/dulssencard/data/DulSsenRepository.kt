package com.msyim.dulssencard.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import com.msyim.dulssencard.data.crypto.DbPassphrase
import com.msyim.dulssencard.data.db.AppDatabase
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.CycleSnapshot
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.Setting
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxDirection
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.ingest.Fingerprint
import com.msyim.dulssencard.ingest.Ingestor
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ingest.LedgerScreenParser
import com.msyim.dulssencard.ingest.PaymentParser
import com.msyim.dulssencard.ingest.RawMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * 로컬 데이터 하나뿐인 저장소. 네트워크 경로는 존재하지 않는다.
 *
 * 알림 리스너·화면이 모두 이 클래스를 통해 같은 암호화 DB 를 본다.
 *
 * ## 여러 표를 고치는 쓰기는 전부 한 트랜잭션이다
 *
 * 카드 삭제(거래 떼기 + 카드 지우기), 거래 보정(거래 + 변경 기록), 전체 삭제(표 6개),
 * 알림 수집(지문 대조 + 삽입 + 소스 통계), 되돌리기(비우고 다시 채우기)는 모두 여러 번 쓴다.
 * 그 사이에 앱이 죽으면 — 알림 리스너는 시스템이 언제든 죽일 수 있다 — 반쪽만 반영된다.
 * 되돌리기가 표를 비운 직후 죽으면 **데이터가 통째로 사라진다.**
 * 그래서 [atomically] 로 묶어, 끝까지 가거나 아무것도 안 바뀌거나 둘 중 하나만 일어나게 한다.
 */
class DulSsenRepository @VisibleForTesting internal constructor(
    private val context: Context,
    private val database: AppDatabase?,
) {

    private val db: AppDatabase get() = database ?: AppDatabase.get(context)

    val cards: Flow<List<Card>> get() = db.cardDao().observeAll()
    val txns: Flow<List<Txn>> get() = db.txnDao().observeAll()
    val sourceApps: Flow<List<SourceApp>> get() = db.sourceAppDao().observeAll()
    val adjustments: Flow<List<Adjustment>> get() = db.adjustmentDao().observeAll()

    val settings: Flow<Map<String, String>>
        get() = db.settingDao().observeAll().map { rows -> rows.associate { it.key to it.value } }

    /**
     * 테스트 전용 고장 주입. 트랜잭션 **한가운데**에서 예외를 던져, 앱이 거기서 죽었을 때
     * 아무것도 반영되지 않는지 확인한다. 프로덕션에서는 늘 null 이다.
     */
    @VisibleForTesting
    internal var faultInjector: (suspend (checkpoint: String) -> Unit)? = null

    private suspend fun checkpoint(name: String) {
        faultInjector?.invoke(name)
    }

    private suspend fun <R> atomically(block: suspend () -> R): R = db.withTransaction { block() }

    /**
     * `IN (:ids)` 에 한 번에 묶을 목록을 나눈다. 바인딩 변수 개수에는 상한이 있고
     * (SQLCipher 4.18 이 싣는 SQLite 기준 99,999, 같은 질의의 다른 인자도 함께 센다),
     * 넘으면 `too many SQL variables` 로 죽는다. 사용자가 목록에서 한 번에 지우는 건수에는
     * 상한이 없으므로 여유를 크게 두고 나눈다.
     */
    private fun <T> Collection<T>.forSql(): List<List<T>> = toList().chunked(SQL_VARIABLE_CHUNK)

    // ---------------------------------------------------------------- 수집

    /** 한 건을 반영한 결과. 화면 문구와 알림 소스 진단이 이 값을 그대로 쓴다. */
    sealed interface IngestResult {
        /** 실제로 DB 에 들어갔다. */
        data class Inserted(val txn: Txn) : IngestResult

        /**
         * 같은 결제가 이미 있어 넣지 않았다.
         * 사전 대조에서 걸렸든, 동시에 들어온 다른 경로와의 경합에서 졌든 똑같이 여기로 온다.
         */
        data class Duplicate(val existingId: String?) : IngestResult

        /** 결제 통지가 아니다. 아무것도 남기지 않았다. */
        data class NotAPayment(val looksLikePayment: Boolean) : IngestResult
    }

    /**
     * 메시지 한 건을 집계에 반영한다.
     *
     * 원문([RawMessage])은 이 함수 밖으로 나가지 않고, 파싱 결과만 저장된다.
     *
     * 지문 대조부터 삽입·소스 통계까지 한 트랜잭션이다. 대조와 삽입 사이에 다른 경로(문자와 푸시가
     * 거의 동시에 온다)가 끼어들 틈이 없고, 그래도 경합이 나면 유니크 인덱스가 막는다.
     * **삽입 반환값을 확인한다** — 인덱스에 막혀 -1 이 나오면 [IngestResult.Duplicate] 다.
     * 이걸 안 보면 들어가지도 않은 거래를 "추가됨"으로 알린다.
     */
    suspend fun ingest(raw: RawMessage): IngestResult = atomically {
        val outcome = Ingestor.ingest(
            raw = raw,
            cards = db.cardDao().all(),
            existingByFingerprint = { fingerprint -> db.txnDao().byFingerprint(fingerprint) },
            cancelOriginFinder = { amount, issuerKey, before, cardId ->
                db.txnDao().findCancelOrigin(amount, issuerKey, before, cardId)
            },
        )
        val result = when (outcome) {
            Ingestor.Outcome.NotAPayment ->
                IngestResult.NotAPayment(PaymentParser.looksLikePayment(raw.title, raw.body))
            is Ingestor.Outcome.Duplicate -> IngestResult.Duplicate(outcome.existingId)
            is Ingestor.Outcome.Insert -> {
                checkpoint("ingest:beforeInsert")
                val rowId = db.txnDao().insertIgnoringDuplicates(outcome.txn)
                if (rowId == -1L) {
                    IngestResult.Duplicate(db.txnDao().byFingerprint(outcome.txn.messageFingerprint)?.id)
                } else {
                    IngestResult.Inserted(outcome.txn)
                }
            }
        }
        checkpoint("ingest:beforeSourceStats")
        recordSourceOutcome(raw.senderKey, raw.receivedAt, result)
        result
    }

    /**
     * 알림 소스 진단 카운터. **알림 내용은 남기지 않고** 시각과 개수만 센다.
     *
     * 결제와 무관한 알림(대화·광고)은 세지 않는다 — 셌다가는 실패 수가 의미를 잃는다.
     * 실패는 "결제처럼 보이는데 못 읽었다" 또는 "읽었는데 금액·시각을 못 뽑았다"만 친다.
     */
    private suspend fun recordSourceOutcome(packageName: String, at: Long, result: IngestResult) {
        // 인식과 실패는 **배타적**이다. 금액을 못 뽑아 '확인 필요'로 들어간 건은 실패로만 센다 —
        // 한 알림이 양쪽에 다 잡히면 "인식 1 · 실패 1"이 같은 알림 하나라 사용자가 헷갈린다.
        val failed = when (result) {
            is IngestResult.NotAPayment -> result.looksLikePayment
            is IngestResult.Inserted -> result.txn.pendingReason in PARSE_TROUBLE
            is IngestResult.Duplicate -> false
        }
        val recognized = !failed &&
            (result is IngestResult.Inserted || result is IngestResult.Duplicate)
        if (!recognized && !failed) return
        val app = db.sourceAppDao().byPackage(packageName) ?: return

        val stale = app.countsSince == 0L || at - app.countsSince > SOURCE_STATS_WINDOW_MILLIS
        val base = if (stale) app.copy(recognizedCount = 0, failedCount = 0, countsSince = at) else app
        db.sourceAppDao().upsert(
            base.copy(
                lastPaymentAt = if (recognized) maxOf(base.lastPaymentAt, at) else base.lastPaymentAt,
                recognizedCount = base.recognizedCount + if (recognized) 1 else 0,
                failedCount = base.failedCount + if (failed) 1 else 0,
            ),
        )
    }

    /**
     * 카드사 앱 **이용내역 목록 화면**에서 읽은 행들을 거래로 넣는다.
     *
     * 목록 화면은 과거 내역을 몰아 넣는 용도라 **자동 반영하지 않고 전부 확인 필요**로 둔다.
     * 한 장의 화면은 한 트랜잭션이다 — 절반만 들어가면 사용자가 무엇이 빠졌는지 알 길이 없다.
     */
    suspend fun importLedgerRows(
        rows: List<LedgerScreenParser.Row>,
        issuerKey: String?,
        receivedAt: Long,
        matchText: String,
    ): ImportResult = atomically {
        // 통지 경로와 똑같이 **사용자가 등록한 카드의 인식 키워드**로 맞춰 본다.
        // 목록 화면에는 카드사 이름이 제목에 한 번만 나오므로 화면 전체 텍스트로 맞춘다.
        val matched = db.cardDao().all().filter { card ->
            card.active && card.matchKeywords.any { keyword ->
                keyword.isNotBlank() && matchText.contains(keyword.trim(), ignoreCase = true)
            }
        }
        val card = matched.singleOrNull()
        val reason = when {
            matched.size > 1 -> PendingReason.MULTIPLE_CARD_MATCH
            card == null -> PendingReason.NO_CARD_MATCH
            else -> PendingReason.IMAGE_IMPORT
        }

        var inserted = 0
        var duplicates = 0
        rows.forEach { row ->
            val fingerprint = Fingerprint.of(
                issuerKey = issuerKey,
                occurredAt = row.occurredAt,
                amount = row.amount,
                direction = TxDirection.APPROVAL,
            )
            val rowId = db.txnDao().insertIgnoringDuplicates(
                Txn(
                    id = UUID.randomUUID().toString(),
                    cardId = card?.id,
                    occurredAt = row.occurredAt,
                    occurredAtEstimated = row.occurredAtEstimated,
                    receivedAt = receivedAt,
                    amount = row.amount,
                    currency = "KRW",
                    foreignAmountMinor = null,
                    direction = TxDirection.APPROVAL,
                    status = TxStatus.PENDING,
                    source = TxSource.IMAGE,
                    merchant = row.merchant,
                    countsTowardTarget = card?.defaultCountsTowardTarget ?: true,
                    countsTowardPurchaseLimit = card?.defaultCountsTowardPurchaseLimit ?: true,
                    parserVersion = PaymentParser.VERSION + " · LEDGER",
                    confidence = 0.5,
                    messageFingerprint = fingerprint,
                    relatedTransactionId = null,
                    pendingReason = reason,
                    issuerKey = issuerKey,
                    installment = false,
                    overseas = false,
                    updatedAt = receivedAt,
                ),
            )
            // 반환값으로 센다. 사전 조회로 세면 같은 화면 안에서 겹친 행을 둘 다 "추가"로 센다.
            if (rowId == -1L) duplicates++ else inserted++
        }
        ImportResult(inserted = inserted, duplicates = duplicates)
    }

    /**
     * 불러오기 결과.
     *
     * 중복 건수를 따로 돌려주는 이유: 화면에 6건이 보이는데 5건만 들어오면
     * 사용자는 파서가 한 건을 놓친 것으로 읽는다. "이미 있어서 건너뛴 것"과
     * "못 읽은 것"은 전혀 다른 상황이라 반드시 구분해서 알려야 한다.
     */
    data class ImportResult(val inserted: Int, val duplicates: Int)

    // ---------------------------------------------------------------- 카드

    /**
     * 카드를 저장한다. 초기 사용액이 바뀌었으면 그 사실을 변경 기록에 함께 남긴다(한 트랜잭션).
     * [Card.updatedAt] 은 여기서 찍는다 — 백업 병합이 이 값으로 최근 설정을 고른다.
     */
    suspend fun upsertCard(card: Card, now: Long = System.currentTimeMillis()) = atomically {
        val before = db.cardDao().byId(card.id)
        db.cardDao().upsert(card.copy(updatedAt = now))
        if (before != null && (before.initialAmount != card.initialAmount)) {
            checkpoint("upsertCard:beforeLog")
            db.adjustmentDao().insert(initialAmountLog(before, card, now))
        }
    }

    /**
     * 홈에서 초기 사용액만 빠르게 다시 맞춘다. 카드사 앱 총액이 달라졌을 때 쓰는 경로다.
     *
     * 기준 시각은 **지금**이다. 이 시각 이전 거래는 새로 넣은 금액에 이미 들어 있다고 본다.
     * 0 을 넣으면 초기값을 끄고 통지만 센다.
     */
    suspend fun setInitialAmount(cardId: String, amount: Long, now: Long = System.currentTimeMillis()): Card? =
        atomically {
            val before = db.cardDao().byId(cardId) ?: return@atomically null
            val after = before.copy(
                initialAmount = amount.coerceAtLeast(0L),
                initialAmountAt = if (amount > 0L) now else 0L,
                updatedAt = now,
            )
            db.cardDao().upsert(after)
            checkpoint("setInitialAmount:beforeLog")
            db.adjustmentDao().insert(initialAmountLog(before, after, now))
            after
        }

    private fun initialAmountLog(before: Card, after: Card, now: Long) = Adjustment(
        id = UUID.randomUUID().toString(),
        transactionId = null,
        changeType = ChangeType.INITIAL_AMOUNT,
        beforeState = "card=${before.id} initial=${before.initialAmount}",
        afterState = "card=${after.id} initial=${after.initialAmount}",
        reason = null,
        createdAt = now,
    )

    /**
     * 카드를 지운다. 그 카드에 붙어 있던 거래는 지우지 않고 미분류·확인 필요로 되돌린다.
     * 카드를 지웠다고 이미 쓴 돈이 사라지면 안 된다.
     *
     * 거래를 떼고 카드를 지우는 사이에 죽으면 "카드는 있는데 거래가 전부 미분류"가 되므로 한 트랜잭션이다.
     */
    suspend fun deleteCard(card: Card) = atomically {
        db.txnDao().detachFromCard(card.id)
        checkpoint("deleteCard:afterDetach")
        db.cardDao().delete(card)
    }

    suspend fun card(id: String): Card? = db.cardDao().byId(id)

    // ---------------------------------------------------------------- 거래 보정

    suspend fun txn(id: String): Txn? = db.txnDao().byId(id)

    /**
     * 거래를 바꾸고 변경 기록을 남긴다. PRD §6.3: 변경 시 원값·변경값·시간·사유를 기록한다.
     * 거래만 바뀌고 기록이 빠지면 되돌리기·감사 추적이 어긋나므로 한 트랜잭션이다.
     */
    suspend fun applyChange(
        before: Txn,
        after: Txn,
        changeType: ChangeType,
        reason: String? = null,
        now: Long = System.currentTimeMillis(),
    ) = atomically {
        db.txnDao().update(after.copy(updatedAt = now))
        checkpoint("applyChange:beforeLog")
        db.adjustmentDao().insert(changeLog(before, after, changeType, reason, now))
    }

    /**
     * 사용자가 거래를 직접 추가한다 — 현금성 결제, 알림이 안 온 결제, 증감 보정.
     *
     * 지문은 무작위다. 사용자가 적은 시각은 분 단위로 정확하지 않아, 통지 지문 규칙을 쓰면
     * 멀쩡한 결제와 우연히 겹치거나(버려짐) 겹쳐야 할 때 안 겹친다. 대신 확인 없이 바로 반영하고,
     * 나중에 같은 결제의 알림이 늦게 오면 사용자가 둘 중 하나를 제외하게 한다.
     *
     * [amount] 는 [TxDirection.MANUAL] 일 때 음수를 허용한다(감액 보정).
     */
    suspend fun addManualTxn(
        cardId: String?,
        amount: Long,
        direction: TxDirection,
        occurredAt: Long,
        merchant: String?,
        now: Long = System.currentTimeMillis(),
    ): Txn = atomically {
        val card = cardId?.let { db.cardDao().byId(it) }
        val txn = Txn(
            id = UUID.randomUUID().toString(),
            cardId = card?.id,
            occurredAt = occurredAt,
            occurredAtEstimated = false,
            receivedAt = now,
            amount = if (direction == TxDirection.MANUAL) amount else kotlin.math.abs(amount),
            currency = "KRW",
            foreignAmountMinor = null,
            direction = direction,
            status = if (card != null) TxStatus.AUTO else TxStatus.PENDING,
            source = TxSource.MANUAL,
            merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
            countsTowardTarget = card?.defaultCountsTowardTarget ?: true,
            countsTowardPurchaseLimit = card?.defaultCountsTowardPurchaseLimit ?: true,
            parserVersion = "manual",
            confidence = 1.0,
            messageFingerprint = "manual:" + UUID.randomUUID().toString(),
            relatedTransactionId = null,
            pendingReason = if (card != null) null else PendingReason.NO_CARD_MATCH,
            issuerKey = null,
            installment = false,
            overseas = false,
            updatedAt = now,
        )
        db.txnDao().insertIgnoringDuplicates(txn)
        checkpoint("addManualTxn:beforeLog")
        db.adjustmentDao().insert(
            Adjustment(
                id = UUID.randomUUID().toString(),
                transactionId = txn.id,
                changeType = ChangeType.MANUAL_ENTRY,
                beforeState = "-",
                afterState = describe(txn),
                reason = null,
                createdAt = now,
            ),
        )
        txn
    }

    /**
     * 합계에 없는 거래(제외 · 확인 필요)를 영구히 지운다. 결제가 아닌 알림이 잡혔을 때 결과함에서 바로 치운다.
     * **자동 반영 거래는 넘겨도 지우지 않는다** — 합계에 들어간 거래를 실수로 지우면 사용자가 모르는 사이
     * 숫자가 줄어든다. 지우려면 먼저 제외해야 한다. 실제로 지운 건수를 돌려준다.
     *
     * 한 트랜잭션에서 함께 한다: 이 거래를 원 거래로 가리키던 취소를 제외하고 연결 끊기, 이 거래의
     * 변경 기록 삭제, 거래 삭제. 연결을 안 끊으면 취소가 없는 거래를 가리키고, 제외하지 않으면
     * 원 거래 없는 취소가 합계를 음수로 끌어내린다(원 거래는 합계에 없던 거래다).
     *
     * 지운 결제는 지문도 사라진다. 같은 결제가 들어 있는 백업을 병합하면 다시 들어온다(제외 상태 그대로).
     */
    suspend fun deleteUncountedTxns(ids: List<String>, now: Long = System.currentTimeMillis()): Int = atomically {
        if (ids.isEmpty()) return@atomically 0
        val deletable = ids.forSql().flatMap { db.txnDao().uncountedIds(it) }
        if (deletable.isEmpty()) return@atomically 0
        deletable.forSql().forEach { db.txnDao().excludeCancelsOf(it, now) }
        deletable.forSql().forEach { db.txnDao().unlinkFrom(it) }
        deletable.forSql().forEach { db.adjustmentDao().deleteForTxns(it) }
        checkpoint("deleteExcluded:beforeDelete")
        deletable.forSql().sumOf { db.txnDao().deleteUncounted(it) }
    }

    /** 금액 정정. 알림에서 잘못 읽었거나 부분 취소가 반영 안 된 경우. */
    suspend fun correctAmount(txn: Txn, newAmount: Long, now: Long = System.currentTimeMillis()) =
        applyChange(txn, txn.copy(amount = newAmount), ChangeType.AMOUNT_MANUAL, now = now)

    /** 연결에 실패한 취소 거래의 원 거래 후보. */
    suspend fun cancelCandidates(cancel: Txn): List<Txn> = db.txnDao().cancelCandidates(
        amount = cancel.amount,
        before = cancel.occurredAt ?: cancel.receivedAt,
        cancelId = cancel.id,
    )

    /**
     * 취소 거래를 사용자가 고른 원 승인 거래에 잇는다.
     *
     * 원 거래의 카드를 따라간다 — 취소 통지에는 카드 뒷자리가 빠지는 경우가 많아 미분류로 남기 쉽다.
     * 연결 실패가 유일한 보류 사유였다면 이제 반영한다.
     */
    suspend fun linkCancel(cancel: Txn, origin: Txn, now: Long = System.currentTimeMillis()): Txn =
        atomically {
            // 사용자가 직접 고른 원 거래가 더 확실한 근거다. 취소가 다른 카드에 잘못 붙어 있었으면
            // 그대로 두면 원 거래 카드는 차감이 안 되고 엉뚱한 카드가 음수가 된다.
            val cardId = origin.cardId ?: cancel.cardId
            val onlyBlockedByLink = cancel.pendingReason == PendingReason.UNLINKED_CANCEL ||
                (cancel.pendingReason == PendingReason.NO_CARD_MATCH && cardId != null)
            val after = cancel.copy(
                relatedTransactionId = origin.id,
                cardId = cardId,
                status = if (cancel.status == TxStatus.PENDING && onlyBlockedByLink) TxStatus.AUTO else cancel.status,
                pendingReason = if (onlyBlockedByLink) null else cancel.pendingReason,
                updatedAt = now,
            )
            db.txnDao().update(after)
            checkpoint("linkCancel:beforeLog")
            db.adjustmentDao().insert(changeLog(cancel, after, ChangeType.LINK_CANCEL, null, now))
            after
        }

    fun adjustmentsFor(txnId: String): Flow<List<Adjustment>> =
        db.adjustmentDao().observeForTxn(txnId)

    private fun changeLog(before: Txn, after: Txn, type: ChangeType, reason: String?, now: Long) = Adjustment(
        id = UUID.randomUUID().toString(),
        transactionId = before.id,
        changeType = type,
        beforeState = describe(before),
        afterState = describe(after),
        reason = reason,
        createdAt = now,
    )

    /**
     * 거래 상태 요약. 변경 기록에 남는 값이라 **금액과 상태만** 담고 가맹점·원문은 넣지 않는다.
     * PRD §10: 진단 로그에 거래 데이터를 기록하지 않는다.
     */
    private fun describe(txn: Txn): String = buildString {
        append("card=").append(txn.cardId ?: "-")
        append(" status=").append(txn.status.name)
        append(" amount=").append(txn.amount)
        append(" target=").append(if (txn.countsTowardTarget) "1" else "0")
        append(" limit=").append(if (txn.countsTowardPurchaseLimit) "1" else "0")
        txn.relatedTransactionId?.let { append(" origin=").append(it) }
    }

    // ---------------------------------------------------------------- 되돌리기

    /**
     * 되돌리기용 스냅샷. 마지막 1건만 되돌릴 수 있으므로 통째로 떠 둔다.
     * 변경 기록도 담는다 — 안 담으면 되돌린 뒤에도 "바꿨다"는 기록이 남아 이력이 거짓이 된다.
     */
    data class Snapshot(
        val cards: List<Card>,
        val txns: List<Txn>,
        val adjustments: List<Adjustment>,
        val settings: List<Setting>,
        val cycleSnapshots: List<CycleSnapshot>,
    )

    suspend fun snapshot(): Snapshot = atomically {
        Snapshot(
            cards = db.cardDao().all(),
            txns = db.txnDao().all(),
            adjustments = db.adjustmentDao().all(),
            settings = db.settingDao().all(),
            cycleSnapshots = db.cycleSnapshotDao().all(),
        )
    }

    /**
     * 스냅샷으로 되돌린다. **표를 비운 뒤 다시 채우므로 반드시 한 트랜잭션이어야 한다** —
     * 비운 직후에 죽으면 데이터가 통째로 사라진다.
     *
     * 알림 소스(기기 설정)는 건드리지 않는다. 되돌리기 대상은 사용자의 집계 데이터다.
     */
    suspend fun restore(snapshot: Snapshot) = atomically {
        db.txnDao().clear()
        db.cardDao().clear()
        db.adjustmentDao().clear()
        db.settingDao().clear()
        db.cycleSnapshotDao().clear()
        checkpoint("restore:afterClear")
        snapshot.cards.forEach { db.cardDao().upsert(it) }
        snapshot.txns.forEach { db.txnDao().upsert(it) }
        snapshot.adjustments.forEach { db.adjustmentDao().upsert(it) }
        snapshot.settings.forEach { db.settingDao().put(it) }
        snapshot.cycleSnapshots.forEach { db.cycleSnapshotDao().upsert(it) }
    }

    /**
     * 스낵바 '되돌리기' 전용 복원. [restore] 와 달리 **거래 표를 비우지 않는다.**
     *
     * 되돌리기를 기다리는 4.2초 사이에 알림이 들어와 새 결제가 저장될 수 있다. 표를 비우고 스냅샷으로
     * 덮으면 사용자가 방금 한 일을 취소하려다 **그 결제를 잃는다** — 수집이 백그라운드에서 계속 도는
     * 앱이라 드문 일이 아니다.
     *
     * 되살리는 것은 스냅샷에 있던 거래뿐이고, 지우는 것은 [createdTxnIds](되돌릴 동작이 직접 만든
     * 거래)뿐이다. 스냅샷에도 없고 동작이 만든 것도 아닌 거래 = 그 사이 수집된 것이므로 그대로 둔다.
     *
     * 나머지 표는 통째로 바꿔도 안전하다. 수집 경로가 쓰는 표는 `txns` 와 알림 소스 통계뿐이고,
     * 알림 소스는 [restore] 와 마찬가지로 건드리지 않는다.
     */
    suspend fun restoreForUndo(
        snapshot: Snapshot,
        createdTxnIds: Collection<String> = emptyList(),
    ) = atomically {
        db.cardDao().clear()
        db.adjustmentDao().clear()
        db.settingDao().clear()
        db.cycleSnapshotDao().clear()
        createdTxnIds.forSql().forEach { db.txnDao().deleteByIds(it) }
        // 되살릴 지문을 다른 id 가 차지하고 있으면 유니크 인덱스에 걸린다. 먼저 비켜 준다.
        snapshot.txns.forSql().forEach { chunk ->
            db.txnDao().deleteByFingerprints(chunk.map { it.messageFingerprint })
        }
        checkpoint("restoreForUndo:afterClear")
        snapshot.cards.forEach { db.cardDao().upsert(it) }
        snapshot.txns.forEach { db.txnDao().upsert(it) }
        snapshot.adjustments.forEach { db.adjustmentDao().upsert(it) }
        snapshot.settings.forEach { db.settingDao().put(it) }
        snapshot.cycleSnapshots.forEach { db.cycleSnapshotDao().upsert(it) }
    }

    // ---------------------------------------------------------------- 백업

    /** 백업에 담을 현재 데이터. 한 트랜잭션에서 읽어 표끼리 어긋난 순간을 담지 않는다. */
    suspend fun readForBackup(): com.msyim.dulssencard.backup.ImportPlanner.Local = atomically {
        com.msyim.dulssencard.backup.ImportPlanner.Local(
            cards = db.cardDao().all(),
            txns = db.txnDao().all(),
            adjustments = db.adjustmentDao().all(),
            settings = db.settingDao().all(),
            cycleSnapshots = db.cycleSnapshotDao().all(),
            sourceApps = db.sourceAppDao().all(),
        )
    }

    /**
     * 가져오기 계획을 적용한다. **전부 한 트랜잭션** — 중간에 하나라도 실패하면 아무것도 바뀌지 않는다.
     *
     * `@Upsert` 는 지문 유니크 인덱스에 걸리면 조용히 0행 갱신으로 끝날 수 있다. 계획기가 그런 경우를
     * 만들지 않지만, 만약을 위해 **쓴 거래가 실제로 있는지 트랜잭션 안에서 확인**하고 없으면 예외로
     * 전체를 되돌린다. 조용히 빠진 거래는 사용자가 절대 알아챌 수 없기 때문이다.
     */
    suspend fun applyImport(
        plan: com.msyim.dulssencard.backup.ImportPlanner.Plan,
        now: Long = System.currentTimeMillis(),
    ) = atomically { writePlan(plan, now) }

    /**
     * 가져오기를 **처음부터 끝까지 한 트랜잭션**으로 한다: 현재 데이터 읽기 → [beforeApply](자동 백업) →
     * 계획 → 적용.
     *
     * 미리보기 때 세운 계획을 그대로 쓰지 않고 여기서 다시 세운다. 미리보기를 보는 사이에 결제 알림이
     * 들어왔을 수 있다 — 그 거래가 자동 백업에서도, 병합 판정에서도 빠지면 조용히 사라진다.
     * 자동 백업이 실패하면 예외가 나서 아무것도 적용되지 않는다.
     */
    suspend fun importAtomically(
        backup: com.msyim.dulssencard.backup.BackupPayload,
        mode: com.msyim.dulssencard.backup.ImportPlanner.Mode,
        now: Long = System.currentTimeMillis(),
        beforeApply: suspend (com.msyim.dulssencard.backup.ImportPlanner.Local) -> Unit,
    ): com.msyim.dulssencard.backup.ImportPlanner.Plan = atomically {
        val local = readForBackup()
        beforeApply(local)
        checkpoint("import:afterAutoBackup")
        val plan = com.msyim.dulssencard.backup.ImportPlanner.plan(local, backup, mode)
        writePlan(plan, now)
        plan
    }

    private suspend fun writePlan(plan: com.msyim.dulssencard.backup.ImportPlanner.Plan, now: Long) {
        if (plan.clearFirst) {
            db.txnDao().clear()
            db.cardDao().clear()
            db.adjustmentDao().clear()
            db.settingDao().clear()
            db.cycleSnapshotDao().clear()
        }
        checkpoint("applyImport:afterClear")
        plan.cards.forEach { db.cardDao().upsert(it) }
        plan.txns.forEach { db.txnDao().upsert(it) }
        checkpoint("applyImport:afterTxns")
        plan.adjustments.forEach { db.adjustmentDao().insertIgnoring(it) }
        plan.settings.forEach { db.settingDao().put(it) }
        plan.cycleSnapshots.forEach { db.cycleSnapshotDao().upsert(it) }
        plan.sourceApps.forEach { db.sourceAppDao().upsert(it) }

        plan.txns.forEach { written ->
            val row = db.txnDao().byId(written.id)
            check(row != null && row.messageFingerprint == written.messageFingerprint) {
                "가져온 거래가 반영되지 않았다(지문 충돌). 전체를 되돌린다."
            }
        }

        val s = plan.summary
        db.adjustmentDao().insert(
            Adjustment(
                id = UUID.randomUUID().toString(),
                transactionId = null,
                changeType = ChangeType.IMPORT_BACKUP,
                beforeState = "mode=${plan.mode}",
                afterState = "cards+${s.cardsAdded}~${s.cardsUpdated} txns+${s.txnsAdded}~${s.txnsUpdated}",
                reason = null,
                createdAt = now,
            ),
        )
    }

    // ---------------------------------------------------------------- 설정

    suspend fun putSetting(key: String, value: String, now: Long = System.currentTimeMillis()) =
        db.settingDao().put(Setting(key, value, updatedAt = now))

    suspend fun getSetting(key: String): String? = db.settingDao().get(key)

    // ---------------------------------------------------------------- 알림 소스

    /**
     * 알림을 띄운 적 있는 패키지를 기록한다. **알림 내용은 넘어오지 않는다** — 패키지명과 라벨뿐이다.
     * 사용자가 설정에서 켜기 전까지 이 앱의 알림 내용은 읽지도 저장하지도 않는다.
     */
    suspend fun noteSourceAppSeen(packageName: String, label: String) {
        val issuer = IssuerRegistry.byPackage(packageName)
        db.sourceAppDao().touch(packageName, label, issuer?.key, System.currentTimeMillis())
    }

    /**
     * 결제 통지를 띄우는 앱을 목록에 미리 채운다.
     *
     * 이게 없으면 알림 접근을 켜도 목록이 비어 있어(그 앱이 알림을 한 번 띄우기 전까지)
     * 사용자가 켤 대상이 없다. 알림 접근이 유일한 수집 경로이므로, 목록이 비면 앱이 통째로 죽는다.
     *
     * 기본값
     *  - **카드사 앱: 켬.** 알림이 결제 통지가 전부라 사용자가 기대하는 동작이다.
     *  - **기본 문자 앱: 켬.** 결제 문자를 읽는 유일한 통로다. SMS 권한을 선언하지 않는 대신
     *    여기서 읽으므로, 꺼 두면 결제 문자가 통째로 누락된다.
     *  - **카카오톡·기본이 아닌 문자 앱: 끔.** 카카오톡은 일상 대화가 오가는 메신저다.
     *    알림톡 결제 통지를 읽으려면 대화 알림도 같은 통로로 지나가므로 명시적으로 켜게 둔다.
     */
    suspend fun seedKnownSourceApps(defaultSmsPackage: String?, label: (String) -> String?) = atomically {
        val candidates = buildList {
            addAll(IssuerRegistry.SUGGESTED_PACKAGES)
            addAll(IssuerRegistry.MESSAGING_PACKAGES)
            defaultSmsPackage?.let { add(it) }
        }.distinct()

        candidates.forEach { pkg ->
            if (db.sourceAppDao().byPackage(pkg) != null) return@forEach
            val name = label(pkg) ?: return@forEach
            val issuer = IssuerRegistry.byPackage(pkg)
            db.sourceAppDao().upsert(
                SourceApp(
                    packageName = pkg,
                    label = name,
                    issuerKey = issuer?.key,
                    enabled = issuer != null || pkg == defaultSmsPackage,
                    lastSeenAt = 0L,
                ),
            )
        }
    }

    /** 현재 등록된 알림 소스 전체. 진단용. */
    suspend fun sourceAppsSnapshot(): List<SourceApp> = db.sourceAppDao().all()

    suspend fun setSourceAppEnabled(packageName: String, label: String, enabled: Boolean) = atomically {
        val existing = db.sourceAppDao().byPackage(packageName)
        val issuer = IssuerRegistry.byPackage(packageName)
        db.sourceAppDao().upsert(
            (existing ?: SourceApp(packageName, label, issuer?.key, enabled, System.currentTimeMillis()))
                .copy(issuerKey = issuer?.key, enabled = enabled),
        )
    }

    suspend fun isSourceAppEnabled(packageName: String): Boolean =
        db.sourceAppDao().byPackage(packageName)?.enabled == true

    // ---------------------------------------------------------------- 전체 삭제

    /**
     * 로컬 데이터 전체 삭제. 표 여섯 개를 비운다. 중간에 죽어 절반만 지워지면
     * "카드는 없는데 거래는 남은" 상태가 되므로 한 트랜잭션이다.
     */
    suspend fun wipeAll() = atomically {
        db.txnDao().clear()
        db.cardDao().clear()
        checkpoint("wipeAll:half")
        db.adjustmentDao().clear()
        db.sourceAppDao().clear()
        db.cycleSnapshotDao().clear()
        db.settingDao().clear()
    }

    /** 앱을 완전히 초기화한다. 호출 뒤 프로세스를 종료해야 안전하다. */
    fun destroyDatabaseFile() {
        AppDatabase.closeAndForget()
        context.deleteDatabase(AppDatabase.DB_NAME)
        DbPassphrase.destroy(context)
    }

    companion object {
        /** `IN (:ids)` 한 번에 묶는 최대 개수. [forSql] 참고. */
        private const val SQL_VARIABLE_CHUNK = 900

        /** 알림 소스 인식/실패 수를 세는 기간. 넘으면 0부터 다시 센다. */
        const val SOURCE_STATS_WINDOW_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** 결제로는 읽었지만 필수 값을 못 뽑은 경우. 진단의 '실패'로 친다. */
        private val PARSE_TROUBLE = setOf(
            PendingReason.PARSE_FAILED,
            PendingReason.LOW_CONFIDENCE,
            PendingReason.ZERO_AMOUNT,
            PendingReason.UNKNOWN_TIME,
        )

        @Volatile
        private var instance: DulSsenRepository? = null

        fun get(context: Context): DulSsenRepository =
            instance ?: synchronized(this) {
                instance ?: DulSsenRepository(context.applicationContext, database = null).also { instance = it }
            }
    }
}

/** 설정 키. 값은 전부 문자열로 저장하고 읽을 때 변환한다. */
object Settings {
    const val LIMIT_AMOUNT = "limit_amount"
    const val LIMIT_CYCLE_START_DAY = "limit_cycle_start_day"
    const val AUTO_COLLECT_ENABLED = "auto_collect_enabled"
    const val ONBOARDING_DONE = "onboarding_done"
    const val HOME_SORT_BY_NAME = "home_sort_by_name"

    val ALL_KEYS = listOf(
        LIMIT_AMOUNT,
        LIMIT_CYCLE_START_DAY,
        AUTO_COLLECT_ENABLED,
        ONBOARDING_DONE,
        HOME_SORT_BY_NAME,
    )

    /** 홈 한도 카드의 제안값. PRD §7: 제안값일 뿐이며 사용자가 언제든 바꿀 수 있다. */
    const val DEFAULT_LIMIT_AMOUNT = 1_000_000L
    const val DEFAULT_LIMIT_CYCLE_START_DAY = 1
}
