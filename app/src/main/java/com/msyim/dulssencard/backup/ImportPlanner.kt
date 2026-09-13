package com.msyim.dulssencard.backup

import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.model.Adjustment
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.CycleSnapshot
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.Setting
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import java.util.UUID

/**
 * 백업 가져오기 계획. **무엇을 쓸지 전부 미리 계산**하고, 쓰기는 저장소가 한 트랜잭션으로 한다.
 *
 * 계산과 쓰기를 나눈 이유 둘:
 *  1. 사용자에게 **적용 전에** "카드 2장 추가 · 거래 14건 추가 · 3건 갱신" 을 보여 줄 수 있다.
 *  2. 충돌 규칙이 순수 함수라 DB 없이 모든 경우를 테스트할 수 있다.
 *
 * ## 충돌 정책
 *
 * **전체 교체(REPLACE)** — 새 기기로 옮길 때. 카드·거래·변경 기록·설정·주기 스냅샷을 백업으로 바꾼다.
 * 알림 소스는 기기 고유라 표를 비우지 않고, 백업에 있는 앱의 켬/끔만 가져온다.
 *
 * **병합(MERGE)** — 두 기기의 기록을 합칠 때.
 *  - **거래는 지문으로 같은 결제를 판정한다.** id 는 기기마다 따로 생기므로 같은 결제라도 다르다.
 *    같은 결제면 `updatedAt` 이 **더 최근인 쪽**을 쓴다. 같으면(옛 데이터는 둘 다 0) **로컬을 유지**한다 —
 *    근거 없이 덮어쓰는 것보다 지금 보고 있는 값을 지키는 편이 안전하다.
 *    백업 쪽이 이겨도 **로컬 id 를 유지**하고, 백업 id 를 가리키던 취소 연결·변경 기록을 로컬 id 로 옮긴다.
 *  - 카드는 id 로 판정한다(카드 id 는 사용자가 만든 것이라 기기 간에 같다). 최신 우선, 동률은 로컬.
 *  - 설정은 키로, 최신 우선. 단 온보딩 완료는 한쪽이라도 완료면 완료로 둔다.
 *  - 변경 기록·주기 스냅샷은 없는 것만 더한다.
 *  - 알림 소스는 로컬을 유지하고 없는 앱만 더한다.
 *  - 카드가 없는 거래(백업·로컬 어디에도 그 카드가 없음)는 **미분류·확인 필요**로 넣는다.
 *
 * **실패 시 전체 롤백** — 저장소의 적용 함수가 한 트랜잭션이므로 도중에 하나라도 실패하면 아무것도 안 바뀐다.
 */
object ImportPlanner {

    enum class Mode { MERGE, REPLACE }

    /** 현재 기기의 데이터. */
    data class Local(
        val cards: List<Card>,
        val txns: List<Txn>,
        val adjustments: List<Adjustment>,
        val settings: List<Setting>,
        val cycleSnapshots: List<CycleSnapshot>,
        val sourceApps: List<SourceApp>,
    )

    data class Summary(
        val cardsAdded: Int,
        val cardsUpdated: Int,
        val cardsKept: Int,
        val txnsAdded: Int,
        val txnsUpdated: Int,
        /** 같은 결제가 이미 있고 로컬이 더 최근이거나 같아서 건너뛴 거래. */
        val txnsKept: Int,
        /** 카드를 찾지 못해 미분류로 들어가는 거래. */
        val txnsUnassigned: Int,
        val settingsChanged: Int,
        /** REPLACE 에서 사라지는 로컬 데이터. 확인 화면에서 경고한다. */
        val localCardsRemoved: Int,
        val localTxnsRemoved: Int,
    )

    data class Plan(
        val mode: Mode,
        val clearFirst: Boolean,
        val cards: List<Card>,
        val txns: List<Txn>,
        val adjustments: List<Adjustment>,
        val settings: List<Setting>,
        val cycleSnapshots: List<CycleSnapshot>,
        val sourceApps: List<SourceApp>,
        val summary: Summary,
    )

    fun plan(local: Local, backup: BackupPayload, mode: Mode): Plan = when (mode) {
        Mode.REPLACE -> replace(local, backup)
        Mode.MERGE -> merge(local, backup)
    }

    // ------------------------------------------------------------------ REPLACE

    private fun replace(local: Local, backup: BackupPayload): Plan {
        val cards = backup.cards.map { it.toEntity() }
        val cardIds = cards.map { it.id }.toSet()
        val txnIds = backup.txns.map { it.id }.toSet()
        val txns = backup.txns.map { dto ->
            dto.toEntity().let { t ->
                t.copy(relatedTransactionId = t.relatedTransactionId?.takeIf { it in txnIds })
            }.let { detachIfCardMissing(it, cardIds) }
        }
        val localSources = local.sourceApps.associateBy { it.packageName }
        val sources = backup.sourceApps.map { dto ->
            // 켬/끔만 가져오고, 이 기기에서 센 진단 기록은 유지한다.
            localSources[dto.packageName]?.copy(enabled = dto.enabled) ?: dto.toEntity()
        }
        return Plan(
            mode = Mode.REPLACE,
            clearFirst = true,
            cards = cards,
            txns = txns,
            adjustments = backup.adjustments.map { it.toEntity() },
            settings = backup.settings.map { it.toEntity() },
            cycleSnapshots = backup.cycleSnapshots.map { it.toEntity() },
            sourceApps = sources,
            summary = Summary(
                cardsAdded = cards.size,
                cardsUpdated = 0,
                cardsKept = 0,
                txnsAdded = txns.size,
                txnsUpdated = 0,
                txnsKept = 0,
                txnsUnassigned = txns.count { it.cardId == null && it.pendingReason == PendingReason.NO_CARD_MATCH },
                settingsChanged = backup.settings.size,
                localCardsRemoved = local.cards.size,
                localTxnsRemoved = local.txns.size,
            ),
        )
    }

    // ------------------------------------------------------------------ MERGE

    private fun merge(local: Local, backup: BackupPayload): Plan {
        // ---- 카드
        val localCards = local.cards.associateBy { it.id }
        var cardsAdded = 0
        var cardsUpdated = 0
        var cardsKept = 0
        val cardWrites = mutableListOf<Card>()
        backup.cards.map { it.toEntity() }.forEach { incoming ->
            val existing = localCards[incoming.id]
            when {
                existing == null -> { cardWrites += incoming; cardsAdded++ }
                incoming.updatedAt > existing.updatedAt -> { cardWrites += incoming; cardsUpdated++ }
                else -> cardsKept++
            }
        }
        val knownCardIds = localCards.keys + backup.cards.map { it.id }

        // ---- 거래: 1차 — 같은 결제 판정과 id 재매핑표
        val localByFingerprint = local.txns.associateBy { it.messageFingerprint }
        val localIds = local.txns.map { it.id }.toSet()
        val idMap = HashMap<String, String>()
        val incoming = backup.txns.map { it.toEntity() }

        data class Decision(val txn: Txn, val write: Boolean, val kind: Char) // A=추가 U=갱신 K=유지
        val decisions = incoming.map { t ->
            val same = localByFingerprint[t.messageFingerprint]
            when {
                same != null -> {
                    idMap[t.id] = same.id
                    if (t.updatedAt > same.updatedAt) Decision(t.copy(id = same.id), true, 'U')
                    else Decision(same, false, 'K')
                }
                t.id in localIds -> {
                    // 지문은 다른데 id 만 겹친다(무작위 UUID 라 사실상 없음). 덮어쓰지 않고 새 id 를 준다.
                    val fresh = UUID.randomUUID().toString()
                    idMap[t.id] = fresh
                    Decision(t.copy(id = fresh), true, 'A')
                }
                else -> {
                    idMap[t.id] = t.id
                    Decision(t, true, 'A')
                }
            }
        }

        // ---- 거래: 2차 — 참조 재매핑(취소 연결은 뒤에 오는 거래를 가리킬 수 있어 두 번에 나눈다)
        val resolvableIds = localIds + idMap.values
        val txnWrites = decisions.filter { it.write }.map { d ->
            val remappedRelated = d.txn.relatedTransactionId
                ?.let { idMap[it] ?: it }
                ?.takeIf { it in resolvableIds }
            detachIfCardMissing(d.txn.copy(relatedTransactionId = remappedRelated), knownCardIds)
        }

        // ---- 변경 기록: 없는 것만, 거래 id 재매핑
        val localAdjustmentIds = local.adjustments.map { it.id }.toSet()
        val adjustmentWrites = backup.adjustments
            .filter { it.id !in localAdjustmentIds }
            .map { dto -> dto.toEntity().let { a -> a.copy(transactionId = a.transactionId?.let { idMap[it] ?: it }) } }

        // ---- 설정
        val localSettings = local.settings.associateBy { it.key }
        val settingWrites = backup.settings.map { it.toEntity() }.mapNotNull { s ->
            val existing = localSettings[s.key]
            when {
                s.key == Settings.ONBOARDING_DONE ->
                    if (existing?.value != "true" && s.value == "true") s else null
                existing == null -> s
                s.updatedAt > existing.updatedAt && s.value != existing.value -> s
                else -> null
            }
        }

        // ---- 주기 스냅샷·알림 소스: 없는 것만
        val localSnapshotKeys = local.cycleSnapshots.map { it.cycleKey }.toSet()
        val snapshotWrites = backup.cycleSnapshots.filter { it.cycleKey !in localSnapshotKeys }.map { it.toEntity() }
        val localPackages = local.sourceApps.map { it.packageName }.toSet()
        val sourceWrites = backup.sourceApps.filter { it.packageName !in localPackages }.map { it.toEntity() }

        return Plan(
            mode = Mode.MERGE,
            clearFirst = false,
            cards = cardWrites,
            txns = txnWrites,
            adjustments = adjustmentWrites,
            settings = settingWrites,
            cycleSnapshots = snapshotWrites,
            sourceApps = sourceWrites,
            summary = Summary(
                cardsAdded = cardsAdded,
                cardsUpdated = cardsUpdated,
                cardsKept = cardsKept,
                txnsAdded = decisions.count { it.kind == 'A' },
                txnsUpdated = decisions.count { it.kind == 'U' },
                txnsKept = decisions.count { it.kind == 'K' },
                txnsUnassigned = txnWrites.count { it.cardId == null && it.pendingReason == PendingReason.NO_CARD_MATCH },
                settingsChanged = settingWrites.size,
                localCardsRemoved = 0,
                localTxnsRemoved = 0,
            ),
        )
    }

    /** 가리키는 카드가 어디에도 없으면 미분류·확인 필요로 바꾼다. 없는 카드에 합산되면 합계가 사라진다. */
    private fun detachIfCardMissing(txn: Txn, knownCardIds: Set<String>): Txn =
        if (txn.cardId != null && txn.cardId !in knownCardIds) {
            txn.copy(cardId = null, status = TxStatus.PENDING, pendingReason = PendingReason.NO_CARD_MATCH)
        } else {
            txn
        }
}
