package com.msyim.dulssencard.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * 거래가 들어온 경로.
 *
 * PRD v4 §4 는 원래 "알림 접근 권한을 통한 앱 푸시 파싱"을 비목표로 두었으나,
 * 실제 한국 카드 결제 통지가 SMS 에서 카드사 앱 푸시·카카오 알림톡으로 옮겨간 현실에 따라
 * 수집 소스를 셋으로 확장했다. 파싱·집계·보정 규칙은 소스와 무관하게 동일하다.
 */
@Serializable
enum class TxSource {
    /** 카드사 발신 결제 SMS (RECEIVE_SMS). */
    SMS,

    /** 카드사 앱이 띄운 결제 푸시 알림 (NotificationListenerService). */
    PUSH,

    /** 카카오톡으로 온 카드사 알림톡 (NotificationListenerService). */
    KAKAO,

    /** 캡처 이미지 OCR 로 불러온 거래. */
    IMAGE,

    /** 사용자가 직접 입력하거나 보정으로 만든 거래. */
    MANUAL,
}

/** 승인인지 취소인지. */
@Serializable
enum class TxDirection {
    APPROVAL,
    CANCEL,

    /** 사용자가 손으로 넣은 증감. */
    MANUAL,
}

/** 합계 반영 상태. PRD §7 확인 결과함 표와 1:1 대응한다. */
@Serializable
enum class TxStatus {
    /** 자동 반영 — 거래별 포함 설정에 따라 합산한다. */
    AUTO,

    /** 확인 필요 — 어떤 합계에도 반영하지 않는다. */
    PENDING,

    /** 제외됨 — 합산하지 않으며 복원할 수 있다. */
    EXCLUDED,
}

/**
 * 자동 반영하지 않은 이유. 사용자에게 보여줄 문구는 [message] 로 고정한다.
 * 이유를 자유 텍스트로 두면 원문 조각이 새어 들어갈 수 있어 열거형으로 묶었다.
 */
@Serializable
enum class PendingReason(val message: String) {
    MULTIPLE_CARD_MATCH("키워드가 두 개 이상의 카드와 일치합니다"),
    NO_CARD_MATCH("어느 카드의 인식 키워드와도 일치하지 않습니다"),
    PARSE_FAILED("금액·시각·발신 정보를 추출하지 못했습니다"),
    LOW_CONFIDENCE("파싱 신뢰도가 자동 반영 기준에 미치지 못했습니다"),
    UNLINKED_CANCEL("연결할 원 승인 거래를 찾지 못했습니다"),
    FOREIGN_CURRENCY("해외 승인이라 자동 반영하지 않았습니다"),
    INSTALLMENT("할부 거래라 자동 반영하지 않았습니다"),
    ZERO_AMOUNT("금액을 0원으로 읽어 자동 반영하지 않았습니다"),

    /**
     * **지금은 도달하지 않는다.** 파서가 시각을 못 읽으면 수신 시각으로 대신하고
     * [Txn.occurredAtEstimated] 로 표시하기 때문이다(현대카드처럼 시각을 아예 안 적는
     * 형식이 있어서 그렇게 바꿨다). 남겨 두는 이유는 둘이다 — 이 값으로 저장된 옛 거래가
     * 있을 수 있고, 나중에 직접 입력 경로가 생기면 다시 쓰인다.
     */
    UNKNOWN_TIME("거래 시각을 확인하지 못했습니다"),
    EXCLUDE_KEYWORD("카드의 제외 키워드에 걸렸습니다"),
    USER_EXCLUDED("사용자가 제외 처리했습니다"),

    /**
     * 같은 결제로 보이는 거래가 이미 있는데 수신 시각이 멀리 떨어져 있어 확신할 수 없는 경우.
     * 조용히 버리면 결제를 잃고, 그냥 넣으면 이중 집계가 된다. 그래서 사용자에게 넘긴다.
     */
    DUPLICATE_SUSPECTED("같은 결제가 이미 있는 것으로 보입니다"),

    /**
     * 캡처 이미지에서 불러온 거래. 카드까지 맞았어도 자동 반영하지 않는다 —
     * 캡처는 무엇이든 담을 수 있어서 사람이 한 번 훑는 편이 안전하다.
     */
    IMAGE_IMPORT("캡처 이미지에서 불러왔습니다 — 확인 후 반영하세요"),
}

/** 보정 종류. Adjustment 로그와 되돌리기의 단위다. */
@Serializable
enum class ChangeType {
    MOVE_CARD,
    TOGGLE_TARGET,
    TOGGLE_LIMIT,
    CONFIRM,
    EXCLUDE,
    RESTORE,
    AMOUNT_MANUAL,
    WIPE,
}

@Serializable
@Entity(tableName = "cards")
data class Card(
    @PrimaryKey val id: String,
    val nickname: String,
    val trackingTarget: Long,
    /** 1~28. 매월 이 날 00:00(KST)부터 다음 달 같은 날 직전까지가 한 주기다. */
    val cycleStartDay: Int,
    val matchKeywords: List<String>,
    val excludeKeywords: List<String>,
    val defaultCountsTowardTarget: Boolean,
    val defaultCountsTowardPurchaseLimit: Boolean,
    /**
     * 주기 시작일부터 [initialAmountAt] 까지 이 카드로 쓴 금액.
     * 사용자가 카드사 앱에서 보고 직접 입력한다 — 과거 내역을 통지로 복원할 수 없기 때문이다.
     *
     * **입력한 그 주기에만 유효하다.** 주기가 넘어가면 [Aggregator] 가 0으로 읽는다.
     * 값을 지우지 않는 이유는 사용자가 언제 얼마를 입력했는지 남겨 두기 위해서다.
     */
    val initialAmount: Long = 0L,
    /**
     * [initialAmount] 를 입력한 시각. 이 시각 **이전** 거래는 이미 그 금액에 포함돼 있으므로
     * 합계에 다시 더하지 않는다. 0 이면 초기값을 쓰지 않는 카드다.
     */
    val initialAmountAt: Long = 0L,
    val active: Boolean = true,
    val createdAt: Long = 0L,
)

/**
 * 거래 한 건.
 *
 * 저장하지 않는 것: SMS/알림 본문, 발신 번호 원문, 주민등록번호, 카드번호, CVC.
 * [messageFingerprint] 는 발신 식별자·정규화 시각·금액·상태를 단방향 해시한 값이고,
 * [issuerKey] 는 원문이 아니라 레지스트리가 판정한 카드사 라벨이다.
 */
@Serializable
@Entity(
    tableName = "txns",
    indices = [
        Index(value = ["messageFingerprint"], unique = true),
        Index(value = ["cardId"]),
        Index(value = ["status"]),
        Index(value = ["occurredAt"]),
    ],
)
data class Txn(
    @PrimaryKey val id: String,
    val cardId: String?,
    /** 거래 시각(epoch millis, UTC 저장 / Asia/Seoul 표시). null 이면 시각 미상. */
    val occurredAt: Long?,
    /**
     * 문구에 시각이 없어 수신 시각으로 대신했는가.
     * 현대카드처럼 날짜·시각을 아예 안 적는 형식이 있어서 필요하다.
     */
    val occurredAtEstimated: Boolean = false,
    /** 메시지를 실제로 받은 시각. 주기 판정에는 쓰지 않고 정렬 안정성에만 쓴다. */
    val receivedAt: Long,
    val amount: Long,
    val currency: String,
    /**
     * 해외 승인의 외화 금액. 원화 합계와 섞지 않고 통화별로 따로 보여 준다.
     * 이 앱은 네트워크를 쓰지 않아 환율을 모르므로 원화 환산은 하지 않는다.
     */
    val foreignAmount: Double? = null,
    val direction: TxDirection,
    val status: TxStatus,
    val source: TxSource,
    val merchant: String?,
    val countsTowardTarget: Boolean,
    val countsTowardPurchaseLimit: Boolean,
    val parserVersion: String,
    val confidence: Double,
    val messageFingerprint: String,
    val relatedTransactionId: String?,
    val pendingReason: PendingReason?,
    val issuerKey: String?,
    val installment: Boolean,
    val overseas: Boolean,
) {
    /** 합계에 더할 부호 있는 금액. 취소는 차감한다. */
    val signedAmount: Long
        get() = if (direction == TxDirection.CANCEL) -amount else amount
}

@Serializable
@Entity(tableName = "adjustments", indices = [Index(value = ["transactionId"])])
data class Adjustment(
    @PrimaryKey val id: String,
    val transactionId: String?,
    val changeType: ChangeType,
    val beforeState: String,
    val afterState: String,
    val reason: String?,
    val createdAt: Long,
)

/**
 * 알림 수집을 허용한 앱. 사용자가 켠 패키지의 알림만 내용을 읽는다.
 *
 * [lastSeenAt] 은 "이 패키지가 알림을 띄운 적이 있다"는 사실만 기록한다.
 * 꺼져 있는 패키지의 알림 내용은 어디에도 저장하지 않고 즉시 버린다.
 */
@Serializable
@Entity(tableName = "source_apps")
data class SourceApp(
    @PrimaryKey val packageName: String,
    val label: String,
    val issuerKey: String?,
    val enabled: Boolean,
    val lastSeenAt: Long,
)

/** 키-값 설정. 한도·정렬·온보딩 완료 여부처럼 작고 반응형이어야 하는 값만 담는다. */
@Serializable
@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String,
)

/** 마감된 주기의 스냅샷. 주기가 넘어갈 때 합계를 얼려 둔다. */
@Serializable
@Entity(tableName = "cycle_snapshots")
data class CycleSnapshot(
    @PrimaryKey val cycleKey: String,
    /** "cardId=합계" 를 줄바꿈으로 이은 값. 카드가 지워져도 과거 합계는 남는다. */
    val cardTotals: String,
    val purchaseLimitTotal: Long,
    val closedAt: Long,
)
