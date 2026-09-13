package com.msyim.dulssencard.backup

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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 백업 파일 안의 평문(암호화 전) 구조.
 *
 * ## 왜 Room 엔티티를 그대로 직렬화하지 않나
 *
 * 엔티티는 스키마를 따라 바뀐다 — v5 에서 `foreignAmount: Double` 이 `foreignAmountMinor: Long` 이 됐다.
 * 엔티티를 그대로 쓰면 필드 하나 이름을 바꾸는 순간 **옛 백업이 안 열린다.** 백업은 몇 년 뒤에도
 * 열려야 하므로 별도 DTO 로 형식을 고정하고, 열거형도 이름 문자열로 담아 검증한다.
 */
@Serializable
data class BackupPayload(
    /** 이 JSON 구조의 버전. [BackupCrypto.FORMAT_VERSION](바이너리 틀)과 별개다. */
    val payloadVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    /** 만든 앱의 DB 스키마 버전. 지금 앱보다 새면 열지 않는다. */
    val schemaVersion: Int,
    val cards: List<CardDto>,
    val txns: List<TxnDto>,
    val adjustments: List<AdjustmentDto>,
    val settings: List<SettingDto>,
    val cycleSnapshots: List<CycleSnapshotDto>,
    val sourceApps: List<SourceAppDto>,
) {
    companion object {
        const val PAYLOAD_VERSION = 1

        private val json = Json {
            ignoreUnknownKeys = true // 더 새 앱이 필드를 더해도 같은 payloadVersion 이면 연다
            encodeDefaults = true
        }

        fun encode(payload: BackupPayload): ByteArray = json.encodeToString(serializer(), payload).toByteArray()

        fun decode(bytes: ByteArray): BackupPayload = json.decodeFromString(serializer(), bytes.decodeToString())

        fun of(
            cards: List<Card>,
            txns: List<Txn>,
            adjustments: List<Adjustment>,
            settings: List<Setting>,
            cycleSnapshots: List<CycleSnapshot>,
            sourceApps: List<SourceApp>,
            appVersion: String,
            schemaVersion: Int,
            now: Long,
        ) = BackupPayload(
            payloadVersion = PAYLOAD_VERSION,
            createdAt = now,
            appVersion = appVersion,
            schemaVersion = schemaVersion,
            cards = cards.map(CardDto::from),
            txns = txns.map(TxnDto::from),
            adjustments = adjustments.map(AdjustmentDto::from),
            settings = settings.map(SettingDto::from),
            cycleSnapshots = cycleSnapshots.map(CycleSnapshotDto::from),
            // 진단 카운터는 기기 고유 상태라 담지 않는다. 켬/끔과 이름만.
            sourceApps = sourceApps.map(SourceAppDto::from),
        )
    }
}

@Serializable
data class CardDto(
    val id: String,
    val nickname: String,
    val trackingTarget: Long,
    val cycleStartDay: Int,
    val matchKeywords: List<String>,
    val excludeKeywords: List<String>,
    val defaultCountsTowardTarget: Boolean,
    val defaultCountsTowardPurchaseLimit: Boolean,
    val initialAmount: Long = 0L,
    val initialAmountAt: Long = 0L,
    val active: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    fun toEntity() = Card(
        id, nickname, trackingTarget, cycleStartDay, matchKeywords, excludeKeywords,
        defaultCountsTowardTarget, defaultCountsTowardPurchaseLimit, initialAmount, initialAmountAt,
        active, createdAt, updatedAt,
    )

    companion object {
        fun from(c: Card) = CardDto(
            c.id, c.nickname, c.trackingTarget, c.cycleStartDay, c.matchKeywords, c.excludeKeywords,
            c.defaultCountsTowardTarget, c.defaultCountsTowardPurchaseLimit, c.initialAmount, c.initialAmountAt,
            c.active, c.createdAt, c.updatedAt,
        )
    }
}

@Serializable
data class TxnDto(
    val id: String,
    val cardId: String?,
    val occurredAt: Long?,
    val occurredAtEstimated: Boolean = false,
    val receivedAt: Long,
    val amount: Long,
    val currency: String,
    val foreignAmountMinor: Long? = null,
    val direction: String,
    val status: String,
    val source: String,
    val merchant: String?,
    val countsTowardTarget: Boolean,
    val countsTowardPurchaseLimit: Boolean,
    val parserVersion: String,
    val confidence: Double,
    val messageFingerprint: String,
    val relatedTransactionId: String?,
    val pendingReason: String?,
    val issuerKey: String?,
    val installment: Boolean,
    val overseas: Boolean,
    val updatedAt: Long = 0L,
) {
    /** 검증을 통과한 DTO 만 부른다. 알 수 없는 열거형 이름은 [BackupValidator] 가 먼저 거른다. */
    fun toEntity() = Txn(
        id = id,
        cardId = cardId,
        occurredAt = occurredAt,
        occurredAtEstimated = occurredAtEstimated,
        receivedAt = receivedAt,
        amount = amount,
        currency = currency,
        foreignAmountMinor = foreignAmountMinor,
        direction = TxDirection.valueOf(direction),
        status = TxStatus.valueOf(status),
        source = TxSource.valueOf(source),
        merchant = merchant,
        countsTowardTarget = countsTowardTarget,
        countsTowardPurchaseLimit = countsTowardPurchaseLimit,
        parserVersion = parserVersion,
        confidence = confidence,
        messageFingerprint = messageFingerprint,
        relatedTransactionId = relatedTransactionId,
        pendingReason = pendingReason?.let { PendingReason.valueOf(it) },
        issuerKey = issuerKey,
        installment = installment,
        overseas = overseas,
        updatedAt = updatedAt,
    )

    companion object {
        fun from(t: Txn) = TxnDto(
            t.id, t.cardId, t.occurredAt, t.occurredAtEstimated, t.receivedAt, t.amount, t.currency,
            t.foreignAmountMinor, t.direction.name, t.status.name, t.source.name, t.merchant,
            t.countsTowardTarget, t.countsTowardPurchaseLimit, t.parserVersion, t.confidence,
            t.messageFingerprint, t.relatedTransactionId, t.pendingReason?.name, t.issuerKey,
            t.installment, t.overseas, t.updatedAt,
        )
    }
}

@Serializable
data class AdjustmentDto(
    val id: String,
    val transactionId: String?,
    val changeType: String,
    val beforeState: String,
    val afterState: String,
    val reason: String?,
    val createdAt: Long,
) {
    fun toEntity() = Adjustment(id, transactionId, ChangeType.valueOf(changeType), beforeState, afterState, reason, createdAt)

    companion object {
        fun from(a: Adjustment) =
            AdjustmentDto(a.id, a.transactionId, a.changeType.name, a.beforeState, a.afterState, a.reason, a.createdAt)
    }
}

@Serializable
data class SettingDto(val key: String, val value: String, val updatedAt: Long = 0L) {
    fun toEntity() = Setting(key, value, updatedAt)

    companion object {
        fun from(s: Setting) = SettingDto(s.key, s.value, s.updatedAt)
    }
}

@Serializable
data class CycleSnapshotDto(val cycleKey: String, val cardTotals: String, val purchaseLimitTotal: Long, val closedAt: Long) {
    fun toEntity() = CycleSnapshot(cycleKey, cardTotals, purchaseLimitTotal, closedAt)

    companion object {
        fun from(c: CycleSnapshot) = CycleSnapshotDto(c.cycleKey, c.cardTotals, c.purchaseLimitTotal, c.closedAt)
    }
}

@Serializable
data class SourceAppDto(val packageName: String, val label: String, val issuerKey: String?, val enabled: Boolean) {
    /** 진단 카운터는 새로 센다 — 다른 기기의 수집 기록은 이 기기의 진단에 의미가 없다. */
    fun toEntity() = SourceApp(packageName, label, issuerKey, enabled, lastSeenAt = 0L)

    companion object {
        fun from(s: SourceApp) = SourceAppDto(s.packageName, s.label, s.issuerKey, s.enabled)
    }
}

/**
 * 복호화에 성공한 내용이 **말이 되는지** 검사한다.
 *
 * GCM 태그가 맞으면 파일은 변조되지 않았다. 그래도 검사하는 이유는 두 가지다 —
 * 버그 있는 옛 앱이 만든 백업일 수 있고, 내용이 틀린 채로 들어가면 **합계가 조용히 틀어진다.**
 * 문제가 하나라도 있으면 가져오지 않는다(부분 가져오기는 하지 않는다).
 */
object BackupValidator {

    sealed interface Result {
        data object Valid : Result
        data class NewerSchema(val backupSchema: Int, val appSchema: Int) : Result
        data class Invalid(val problems: List<String>) : Result
    }

    fun validate(payload: BackupPayload, appSchemaVersion: Int): Result {
        if (payload.payloadVersion != BackupPayload.PAYLOAD_VERSION) {
            return Result.Invalid(listOf("지원하지 않는 백업 내용 버전 ${payload.payloadVersion}"))
        }
        if (payload.schemaVersion > appSchemaVersion) {
            return Result.NewerSchema(payload.schemaVersion, appSchemaVersion)
        }

        val problems = mutableListOf<String>()
        fun duplicates(label: String, keys: List<String>) {
            val dup = keys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dup.isNotEmpty()) problems += "$label 가 겹칩니다(${dup.size}건)"
        }

        duplicates("카드 id", payload.cards.map { it.id })
        duplicates("거래 id", payload.txns.map { it.id })
        duplicates("거래 지문", payload.txns.map { it.messageFingerprint })
        duplicates("변경 기록 id", payload.adjustments.map { it.id })
        duplicates("설정 키", payload.settings.map { it.key })

        payload.cards.forEach { card ->
            if (card.id.isBlank()) problems += "id 가 빈 카드"
            if (card.cycleStartDay !in 1..28) problems += "주기 시작일이 1~28 밖인 카드"
            if (card.trackingTarget < 0 || card.initialAmount < 0) problems += "금액이 음수인 카드"
        }

        val cardIds = payload.cards.map { it.id }.toSet()
        payload.txns.forEach { t ->
            if (t.id.isBlank() || t.messageFingerprint.isBlank()) problems += "id 또는 지문이 빈 거래"
            val direction = enumOrNull<TxDirection>(t.direction)
            if (direction == null) problems += "알 수 없는 거래 방향 ${t.direction}"
            if (enumOrNull<TxStatus>(t.status) == null) problems += "알 수 없는 거래 상태 ${t.status}"
            if (enumOrNull<TxSource>(t.source) == null) problems += "알 수 없는 수집 경로 ${t.source}"
            if (t.pendingReason != null && enumOrNull<PendingReason>(t.pendingReason) == null) {
                problems += "알 수 없는 보류 사유 ${t.pendingReason}"
            }
            if (t.amount < 0 && direction != TxDirection.MANUAL) problems += "금액이 음수인 거래"
            if (t.foreignAmountMinor != null && t.foreignAmountMinor <= 0) problems += "외화 금액이 0 이하인 거래"
            if (t.cardId != null && t.cardId !in cardIds) problems += "백업에 없는 카드를 가리키는 거래"
        }
        payload.adjustments.forEach { a ->
            if (enumOrNull<ChangeType>(a.changeType) == null) problems += "알 수 없는 변경 종류 ${a.changeType}"
        }
        return if (problems.isEmpty()) Result.Valid else Result.Invalid(problems.distinct())
    }

    private inline fun <reified E : Enum<E>> enumOrNull(name: String): E? =
        enumValues<E>().firstOrNull { it.name == name }
}
