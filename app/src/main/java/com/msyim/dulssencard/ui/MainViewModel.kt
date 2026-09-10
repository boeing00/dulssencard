package com.msyim.dulssencard.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.crypto.BackupCrypto
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Cycle
import com.msyim.dulssencard.domain.InitialAmountPolicy
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.ingest.Ingestor
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ingest.LedgerScreenParser
import com.msyim.dulssencard.ingest.OcrText
import com.msyim.dulssencard.ingest.RawMessage
import com.msyim.dulssencard.ingest.SourceGate
import com.msyim.dulssencard.ocr.ImageOcrHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class Screen { ONBOARD, HOME, INBOX, DETAIL, CARDS, EDIT, SETTINGS, SOURCES }

enum class InboxTab { PENDING, ALL, EXCLUDED }

/** 비밀번호를 물어야 하는 지점. 백업은 사용자가 기억하는 비밀번호로만 열린다. */
enum class BackupPrompt { EXPORT, IMPORT }

data class CardForm(
    val nickname: String = "",
    /** 숫자만 담는다. 천 단위 쉼표는 화면에서만 붙인다(커서가 끝으로 튀지 않게). */
    val target: String = "",
    val keywords: String = "",
    val exclude: String = "",
    val startDay: Int = 1,
    val defaultTarget: Boolean = true,
    val defaultLimit: Boolean = true,
    /**
     * 주기 시작일부터 지금까지 이 카드로 쓴 금액. 사용자가 카드사 앱에서 보고 옮겨 적는다.
     * 비워 두면 초기값 없이 통지만 세고, 값을 넣으면 저장 시각이 기준 시각이 된다.
     */
    val initialAmount: String = "",
)

data class Toast(val message: String, val undoable: Boolean)

data class UiState(
    val loading: Boolean = true,
    val screen: Screen = Screen.ONBOARD,
    val backTo: Screen = Screen.INBOX,
    val inboxTab: InboxTab = InboxTab.PENDING,
    /** 결과함을 이 카드의 거래로만 좁힌다. null 이면 전체다. */
    val cardFilterId: String? = null,
    val sortByName: Boolean = false,
    val selectedTxnId: String? = null,
    val editingCardId: String? = null,
    val cards: List<Card> = emptyList(),
    val txns: List<Txn> = emptyList(),
    val sourceApps: List<SourceApp> = emptyList(),
    val limitAmount: Long = Settings.DEFAULT_LIMIT_AMOUNT,
    val limitCycleStartDay: Int = Settings.DEFAULT_LIMIT_CYCLE_START_DAY,
    /** 숫자만 담는다. [CardForm.target] 과 같은 이유다. */
    val limitInput: String = Settings.DEFAULT_LIMIT_AMOUNT.toString(),
    val autoCollectEnabled: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val defaultSmsPackage: String? = null,
    val form: CardForm = CardForm(),
    val toast: Toast? = null,
    val showImagePicker: Boolean = false,
    /** 비밀번호 입력 다이얼로그. */
    val backupPrompt: BackupPrompt? = null,
    /** 사용자가 고른 백업 파일. 비밀번호를 받은 뒤에 읽는다. */
    val pendingImportUri: String? = null,
    val showBackupPicker: Boolean = false,
    /** 내보내기가 끝난 파일. 공유 시트로 넘긴 뒤 비운다. */
    val shareBackupPath: String? = null,
    /** 금액을 손으로 고치는 중인 거래. */
    val editingAmountTxnId: String? = null,
) {
    val pendingCount: Int get() = txns.count { it.status == TxStatus.PENDING }

    /** 기본 문자 앱의 알림이 켜져 있는가. 꺼져 있으면 결제 문자가 통째로 누락된다. */
    val smsAppEnabled: Boolean
        get() = defaultSmsPackage != null &&
            sourceApps.any { it.packageName == defaultSmsPackage && it.enabled }

    /**
     * 수집이 반쪽만 열린 상태를 홈에서 알려 주기 위한 값.
     *
     * 알림 접근이 유일한 수집 경로라, 그게 꺼져 있으면 아무것도 안 잡힌다.
     * 켜져 있어도 읽을 앱을 고르지 않았거나 문자 앱을 꺼 두면 일부가 조용히 누락되는데,
     * 화면에 표시가 없으면 사용자는 "왜 어떤 결제는 안 잡히지?"만 겪게 된다.
     */
    val collectionGap: CollectionGap
        get() = when {
            !autoCollectEnabled -> CollectionGap.DISABLED
            !hasNotificationAccess -> CollectionGap.NO_NOTIFICATION_ACCESS
            sourceApps.none { it.enabled } -> CollectionGap.NO_SOURCE_APPS
            defaultSmsPackage != null && !smsAppEnabled -> CollectionGap.SMS_APP_OFF
            else -> CollectionGap.NONE
        }
}

/** 홈 상단 배너로 알릴 수집 공백. */
enum class CollectionGap(val title: String, val subtitle: String) {
    NONE("", ""),
    DISABLED(
        "제한 모드 — 새 결제가 자동으로 잡히지 않습니다",
        "설정에서 자동 집계를 켜세요",
    ),
    NO_NOTIFICATION_ACCESS(
        "알림 접근이 꺼져 있습니다",
        "이 앱의 유일한 수집 경로입니다 — 눌러서 시스템 설정에서 켜세요",
    ),
    NO_SOURCE_APPS(
        "읽을 앱을 아직 고르지 않았습니다",
        "설정 > 알림 소스에서 카드사 앱과 문자 앱을 켜세요",
    ),
    SMS_APP_OFF(
        "결제 문자를 못 읽고 있습니다",
        "설정 > 알림 소스에서 문자 앱을 켜면 결제 문자도 잡힙니다",
    ),
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DulSsenRepository.get(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var undoSnapshot: DulSsenRepository.Snapshot? = null
    private var toastJob: Job? = null

    /**
     * 백업 JSON 파서.
     * `ignoreUnknownKeys` 를 켜는 이유: 나중 버전이 필드를 더한 백업을 지금 앱으로 열어도
     * 통째로 실패하지 않게 하려는 것이다. 형식 버전 검사는 [DulSsenRepository.importData] 가 한다.
     */
    private val backupJson = Json { ignoreUnknownKeys = true }

    init {
        viewModelScope.launch {
            combine(
                repository.cards,
                repository.txns,
                repository.settings,
                repository.sourceApps,
            ) { cards, txns, settings, sources ->
                Quad(cards, txns, settings, sources)
            }.collect { (cards, txns, settings, sources) ->
                val limit = settings[Settings.LIMIT_AMOUNT]?.toLongOrNull()
                    ?: Settings.DEFAULT_LIMIT_AMOUNT
                val onboarded = settings[Settings.ONBOARDING_DONE] == "true"
                _state.update { current ->
                    current.copy(
                        loading = false,
                        cards = cards,
                        txns = txns,
                        sourceApps = sources,
                        limitAmount = limit,
                        limitCycleStartDay = settings[Settings.LIMIT_CYCLE_START_DAY]?.toIntOrNull()
                            ?: Settings.DEFAULT_LIMIT_CYCLE_START_DAY,
                        limitInput = limit.toString(),
                        autoCollectEnabled = settings[Settings.AUTO_COLLECT_ENABLED] != "false",
                        sortByName = settings[Settings.HOME_SORT_BY_NAME] == "true",
                        screen = if (current.loading) {
                            if (onboarded) Screen.HOME else Screen.ONBOARD
                        } else {
                            current.screen
                        },
                    )
                }
            }
        }
        viewModelScope.launch { seedSourceApps() }
        viewModelScope.launch {
            // 주기가 넘어가면 지난 주기 숫자는 다시 만들 수 없다. 화면은 아직 없지만
            // 기록만은 지금부터 남긴다. 실패해도 앱은 그대로 돌아야 한다.
            runCatching {
                repository.recordClosedCycle(
                    repository.getSetting(Settings.LIMIT_CYCLE_START_DAY)?.toIntOrNull()
                        ?: Settings.DEFAULT_LIMIT_CYCLE_START_DAY,
                )
            }
        }
        refreshPermissions()
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /**
     * 설치된 카드사 앱과 기본 문자 앱을 알림 소스 목록에 채운다.
     *
     * 앱 시작 때뿐 아니라 **'로컬 데이터 전체 삭제' 직후에도** 불러야 한다.
     * 삭제가 source_apps 테이블까지 비우는데 다시 채우지 않으면, 목록이 텅 빈 채로
     * 남아 그때부터 알림을 띄운 앱만 하나씩 들어온다. 사용자에게는 "결제 문자 수집에
     * 메시지 하나만 뜬다"로 나타난다 — 실제로 그랬다.
     */
    private suspend fun seedSourceApps() {
        val context = getApplication<Application>()
        val pm = context.packageManager
        val defaultSms = SourceGate.defaultSmsPackage(context)
        val report = StringBuilder("defaultSms=").appendLine(defaultSms)
        repository.seedKnownSourceApps(defaultSms) { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()
            report.append(pkg).append(" -> ").appendLine(label ?: "(보이지 않음)")
            label
        }
        // 디버그 빌드에서만 남기는 진단. **앱 이름과 패키지명뿐**이고 거래 정보는 들어가지 않는다.
        // 기기마다 어떤 앱이 보이는지가 수집 실패의 첫 번째 원인이라 확인 수단을 남겨 둔다.
        if (com.msyim.dulssencard.BuildConfig.DEBUG) {
            report.appendLine("--- 현재 테이블 ---")
            runCatching {
                repository.sourceAppsSnapshot().forEach { app ->
                    report.append(if (app.enabled) "[켬] " else "[끔] ")
                        .append(app.label).append(" / ").appendLine(app.packageName)
                }
                File(context.filesDir, "sources_dump.txt").writeText(report.toString())
            }
        }
    }

    /**
     * 알림 접근은 앱이 요청할 수 없고 사용자가 시스템 설정에서만 켤 수 있다.
     * 그래서 화면에 돌아올 때마다 다시 읽는 것 말고는 상태를 알 방법이 없다.
     */
    fun refreshPermissions() {
        val context = getApplication<Application>()
        _state.update {
            it.copy(
                hasNotificationAccess = SourceGate.hasNotificationAccess(context),
                defaultSmsPackage = SourceGate.defaultSmsPackage(context),
            )
        }
    }

    // ------------------------------------------------------------- 화면 이동

    /** 화면을 옮길 때 스낵바를 즉시 닫는다(README: 화면 전환 시 스낵바 즉시 닫기). */
    fun go(screen: Screen) {
        toastJob?.cancel()
        _state.update { it.copy(screen = screen, toast = null) }
    }

    fun openInbox(tab: InboxTab) {
        toastJob?.cancel()
        _state.update {
            it.copy(screen = Screen.INBOX, inboxTab = tab, cardFilterId = null, toast = null)
        }
    }

    /**
     * 홈에서 카드를 눌렀을 때. 그 카드의 거래만 보여 준다.
     *
     * 예전에는 결과함 '전체' 탭으로만 보냈다. 카드가 여러 장이면 어느 줄이 그 카드 것인지
     * 사용자가 눈으로 골라내야 했고, 홈의 숫자가 왜 그 값인지 확인할 방법이 없었다.
     */
    fun openCardTransactions(card: Card) {
        toastJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.INBOX,
                inboxTab = InboxTab.ALL,
                cardFilterId = card.id,
                toast = null,
            )
        }
    }

    fun clearCardFilter() {
        _state.update { it.copy(cardFilterId = null) }
    }

    fun selectInboxTab(tab: InboxTab) {
        _state.update { it.copy(inboxTab = tab) }
    }

    fun openTxn(id: String, from: Screen) {
        toastJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.DETAIL,
                selectedTxnId = id,
                backTo = from,
                editingAmountTxnId = null,
                toast = null,
            )
        }
    }

    fun back() {
        go(_state.value.backTo)
    }

    fun toggleSort() {
        val next = !_state.value.sortByName
        _state.update { it.copy(sortByName = next) }
        viewModelScope.launch {
            repository.putSetting(Settings.HOME_SORT_BY_NAME, next.toString())
        }
    }

    // ------------------------------------------------------------- 온보딩

    fun completeOnboarding(enableCollection: Boolean) {
        viewModelScope.launch {
            repository.putSetting(Settings.ONBOARDING_DONE, "true")
            repository.putSetting(Settings.AUTO_COLLECT_ENABLED, enableCollection.toString())
            _state.update { it.copy(screen = Screen.HOME) }
            say(
                if (enableCollection) {
                    "자동 집계를 켰습니다"
                } else {
                    "제한 모드로 시작합니다 — 수동 보정만 가능합니다"
                },
                undoable = false,
            )
        }
    }

    // ------------------------------------------------------------- 카드

    fun newCard() {
        toastJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.EDIT,
                editingCardId = null,
                backTo = Screen.CARDS,
                form = CardForm(),
                toast = null,
            )
        }
    }

    fun editCard(card: Card) {
        toastJob?.cancel()
        _state.update {
            it.copy(
                screen = Screen.EDIT,
                editingCardId = card.id,
                backTo = Screen.CARDS,
                toast = null,
                form = CardForm(
                    nickname = card.nickname,
                    target = card.trackingTarget.toString(),
                    keywords = card.matchKeywords.joinToString(", "),
                    exclude = card.excludeKeywords.joinToString(", "),
                    startDay = card.cycleStartDay,
                    defaultTarget = card.defaultCountsTowardTarget,
                    defaultLimit = card.defaultCountsTowardPurchaseLimit,
                    // 지난 주기에 넣은 초기값은 이미 합계에서 빠졌다. 그걸 그대로 보여 주면
                    // 사용자가 아직 유효한 값으로 오해하므로, 이번 주기 것만 채운다.
                    // (비워 둔 채로 저장해도 지난 주기 기록은 지워지지 않는다 —
                    //  InitialAmountPolicy 규칙 4.)
                    initialAmount = if (
                        card.initialAmount != 0L &&
                        Cycle.windowFor(card.cycleStartDay).contains(card.initialAmountAt)
                    ) {
                        card.initialAmount.toString()
                    } else {
                        ""
                    },
                ),
            )
        }
    }

    fun updateForm(transform: (CardForm) -> CardForm) {
        _state.update { it.copy(form = transform(it.form)) }
    }

    fun saveCard() {
        val form = _state.value.form
        val target = Money.parseAmount(form.target)
        val keywords = splitKeywords(form.keywords)
        if (form.nickname.isBlank() || target == null || target <= 0L || keywords.isEmpty()) {
            say("별명·목표·인식 키워드는 필수입니다", undoable = false)
            return
        }
        // 초기 사용액은 선택 항목이다. **비어 있는 것(null)과 0 을 넣은 것을 구분한다** —
        // 비어 있으면 지난 주기 기록을 건드리지 않고, 0 이면 초기값을 쓰지 않겠다는 뜻이다.
        val initialInput = if (form.initialAmount.isBlank()) null else Money.parseAmount(form.initialAmount)
        if (form.initialAmount.isNotBlank() && initialInput == null) {
            say("초기 사용액은 숫자로 입력해 주세요", undoable = false)
            return
        }

        val editingId = _state.value.editingCardId
        val startDay = Cycle.normalizeStartDay(form.startDay)
        viewModelScope.launch {
            val existing = editingId?.let { repository.card(it) }
            val initial = InitialAmountPolicy.resolve(
                existingAmount = existing?.initialAmount ?: 0L,
                existingAt = existing?.initialAmountAt ?: 0L,
                input = initialInput,
                cycleStartDay = startDay,
                now = System.currentTimeMillis(),
            )
            repository.upsertCard(
                Card(
                    id = editingId ?: UUID.randomUUID().toString(),
                    nickname = form.nickname.trim(),
                    trackingTarget = target,
                    cycleStartDay = startDay,
                    matchKeywords = keywords,
                    excludeKeywords = splitKeywords(form.exclude),
                    defaultCountsTowardTarget = form.defaultTarget,
                    defaultCountsTowardPurchaseLimit = form.defaultLimit,
                    initialAmount = initial.amount,
                    initialAmountAt = initial.at,
                    active = existing?.active ?: true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                ),
            )
            _state.update { it.copy(screen = Screen.CARDS) }
            say(if (editingId == null) "카드를 저장했습니다" else "변경을 저장했습니다", undoable = false)
        }
    }

    fun deleteCard(card: Card) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.deleteCard(card)
            _state.update { it.copy(screen = Screen.CARDS) }
            say("${card.nickname} 카드를 지웠습니다 — 거래는 미분류로 남았습니다", undoable = true)
        }
    }

    /** 쉼표로 나누고 공백·빈 항목·개행을 걷어 낸다(개행은 DB 구분자라 반드시 제거한다). */
    private fun splitKeywords(raw: String): List<String> =
        raw.split(',')
            .map { it.replace("\n", " ").trim() }
            .filter { it.isNotBlank() }
            .distinct()

    // ------------------------------------------------------------- 거래 보정

    fun moveTxnToCard(txn: Txn, card: Card) = mutate(
        txn = txn,
        changeType = ChangeType.MOVE_CARD,
        message = "${card.nickname}(으)로 이동했습니다",
    ) { current ->
        current.copy(
            cardId = card.id,
            // 확인 필요였던 거래는 사용자가 카드를 지정한 순간 자동 반영으로 승격한다.
            status = if (current.status == TxStatus.PENDING) TxStatus.AUTO else current.status,
            pendingReason = if (current.status == TxStatus.PENDING) null else current.pendingReason,
        )
    }

    fun toggleCountsTowardTarget(txn: Txn) = mutate(
        txn = txn,
        changeType = ChangeType.TOGGLE_TARGET,
        message = "목표 추적 ${if (txn.countsTowardTarget) "제외" else "포함"}으로 변경했습니다",
    ) { it.copy(countsTowardTarget = !it.countsTowardTarget) }

    fun toggleCountsTowardLimit(txn: Txn) = mutate(
        txn = txn,
        changeType = ChangeType.TOGGLE_LIMIT,
        message = "구매 추적 한도 ${if (txn.countsTowardPurchaseLimit) "제외" else "포함"}으로 변경했습니다",
    ) { it.copy(countsTowardPurchaseLimit = !it.countsTowardPurchaseLimit) }

    fun confirmTxn(txn: Txn) = mutate(
        txn = txn,
        changeType = ChangeType.CONFIRM,
        message = "집계에 반영했습니다",
    ) { it.copy(status = TxStatus.AUTO, pendingReason = null) }

    fun excludeTxn(txn: Txn) = mutate(
        txn = txn,
        changeType = ChangeType.EXCLUDE,
        message = "실적에서 제외했습니다",
    ) { it.copy(status = TxStatus.EXCLUDED, pendingReason = PendingReason.USER_EXCLUDED) }

    fun restoreTxn(txn: Txn) = mutate(
        txn = txn,
        changeType = ChangeType.RESTORE,
        message = "제외를 복원했습니다",
    ) { it.copy(status = TxStatus.AUTO, pendingReason = null) }

    // ------------------------------------------------------------- 금액 수동 보정

    fun startAmountEdit(txn: Txn) {
        _state.update { it.copy(editingAmountTxnId = txn.id) }
    }

    fun cancelAmountEdit() {
        _state.update { it.copy(editingAmountTxnId = null) }
    }

    /**
     * 금액을 손으로 고친다.
     *
     * PRD 는 "모든 집계값을 변경·제외·되돌릴 수 있다"고 했는데 금액만 손댈 방법이 없었다.
     * OCR 이 `12,820원` 을 `12,820l` 로 읽거나 자릿수를 하나 흘리는 일이 실제로 있어서,
     * 거래를 통째로 버리는 것 말고 고쳐 쓰는 길이 필요하다.
     */
    fun correctAmount(txn: Txn, raw: String) {
        val amount = Money.parseAmount(raw)
        if (amount == null || amount <= 0L) {
            say("금액은 1원 이상 숫자로 입력해 주세요", undoable = false)
            return
        }
        if (amount == txn.amount) {
            _state.update { it.copy(editingAmountTxnId = null) }
            return
        }
        _state.update { it.copy(editingAmountTxnId = null) }
        mutate(
            txn = txn,
            changeType = ChangeType.AMOUNT_MANUAL,
            message = "금액을 ${Money.won(amount)}(으)로 고쳤습니다",
        ) { it.copy(amount = amount) }
    }

    private fun mutate(
        txn: Txn,
        changeType: ChangeType,
        message: String,
        transform: (Txn) -> Txn,
    ) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            val after = transform(txn)
            repository.applyChange(txn, after, changeType)
            say(message, undoable = true)
        }
    }

    // ------------------------------------------------------------- 설정

    fun updateLimitInput(raw: String) {
        _state.update { it.copy(limitInput = Money.onlyDigits(raw)) }
    }

    fun commitLimit() {
        val amount = Money.parseAmount(_state.value.limitInput)
        if (amount == null || amount <= 0L) {
            // 예전에는 조용히 아무것도 안 했다. 사용자는 저장된 줄 알고 화면을 떠난다.
            say("한도는 1원 이상 숫자로 입력해 주세요", undoable = false)
            return
        }
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.putSetting(Settings.LIMIT_AMOUNT, amount.toString())
            say("한도를 ${Money.won(amount)}(으)로 바꿨습니다", undoable = true)
        }
    }

    fun setLimitCycleStartDay(day: Int) {
        viewModelScope.launch {
            repository.putSetting(
                Settings.LIMIT_CYCLE_START_DAY,
                Cycle.normalizeStartDay(day).toString(),
            )
        }
    }

    /**
     * 자동 집계 스위치.
     * 끄면 [com.msyim.dulssencard.notification.PaymentNotificationListener] 가 알림 내용을
     * 아예 읽지 않는다(설정을 먼저 보고 나간다). 서비스 자체는 계속 바인딩돼 있는데,
     * 알림 접근은 시스템 설정에서만 끌 수 있어 앱이 해제할 수 없기 때문이다.
     */
    fun setAutoCollectEnabled(enabled: Boolean) {
        viewModelScope.launch {
            repository.putSetting(Settings.AUTO_COLLECT_ENABLED, enabled.toString())
            say(
                if (enabled) {
                    "자동 집계를 켰습니다"
                } else {
                    "자동 집계를 껐습니다 — 새 결제 수집을 중단했습니다"
                },
                undoable = false,
            )
        }
    }

    fun setSourceAppEnabled(app: SourceApp, enabled: Boolean) {
        viewModelScope.launch {
            repository.setSourceAppEnabled(app.packageName, app.label, enabled)
        }
    }

    fun wipeAll() {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.wipeAll()
            // 삭제는 알림 소스 목록도 비운다. 곧바로 다시 채우지 않으면 수집이 통째로 멈춘다.
            seedSourceApps()
            // 무엇을 언제 지웠는지 기록으로 남긴다. 거래에 붙지 않는 전역 기록이라
            // transactionId 는 null 이다.
            repository.logGlobalChange(ChangeType.WIPE, "로컬 데이터 전체 삭제")
            say("로컬 데이터를 모두 삭제했습니다", undoable = true)
        }
    }

    // ------------------------------------------------------------- 암호화 백업

    fun requestExport() {
        _state.update { it.copy(backupPrompt = BackupPrompt.EXPORT) }
    }

    fun requestImport() {
        _state.update { it.copy(showBackupPicker = true) }
    }

    fun clearBackupPickerRequest() {
        _state.update { it.copy(showBackupPicker = false) }
    }

    fun onBackupFilePicked(uri: Uri) {
        _state.update {
            it.copy(pendingImportUri = uri.toString(), backupPrompt = BackupPrompt.IMPORT)
        }
    }

    fun dismissBackupPrompt() {
        _state.update { it.copy(backupPrompt = null, pendingImportUri = null) }
    }

    fun clearShareRequest() {
        _state.update { it.copy(shareBackupPath = null) }
    }

    /**
     * 전체 데이터를 **비밀번호로 암호화해서** 파일 하나로 내보낸다.
     *
     * 예전에는 이 함수가 같은 이름("암호화 내보내기")으로 **평문 JSON** 을 외부 저장소에 썼다.
     * SQLCipher 와 Keystore 로 쌓아 올린 방어가 그 한 줄로 통째로 우회됐고,
     * 화면에는 암호화한다고 적혀 있었다.
     */
    fun exportEncrypted(password: String) {
        if (password.length < BackupCrypto.MIN_PASSWORD_LENGTH) {
            say("비밀번호는 ${BackupCrypto.MIN_PASSWORD_LENGTH}자 이상이어야 합니다", undoable = false)
            return
        }
        _state.update { it.copy(backupPrompt = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                val path = withContext(Dispatchers.IO) {
                    val json = backupJson.encodeToString(
                        DulSsenRepository.BackupData.serializer(),
                        repository.exportData(),
                    )
                    val sealed = BackupCrypto.seal(
                        json.toByteArray(Charsets.UTF_8),
                        password.toCharArray(),
                    )
                    val dir = File(context.filesDir, EXPORT_DIR).apply { mkdirs() }
                    // 예전 백업은 지운다. 암호화돼 있긴 해도 기기에 사본을 쌓아 둘 이유가 없다.
                    dir.listFiles()?.forEach { it.delete() }
                    val stamp = FILE_STAMP.format(Instant.now())
                    File(dir, "dulssencard-backup-$stamp.dsc").apply { writeBytes(sealed) }.absolutePath
                }
                _state.update { it.copy(shareBackupPath = path) }
                say("백업을 암호화해서 만들었습니다 — 저장할 곳을 고르세요", undoable = false)
            } catch (e: Exception) {
                say("내보내기에 실패했습니다", undoable = false)
            }
        }
    }

    /** 고른 백업 파일을 풀어 기존 데이터에 합친다. */
    fun importBackup(password: String) {
        val uriString = _state.value.pendingImportUri
        if (uriString == null) {
            say("불러올 파일을 먼저 고르세요", undoable = false)
            return
        }
        _state.update { it.copy(backupPrompt = null, pendingImportUri = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            undoSnapshot = repository.snapshot()
            try {
                val counts = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(Uri.parse(uriString))
                        ?.use { it.readBytes() }
                        ?: error("파일을 열 수 없습니다")
                    val json = String(
                        BackupCrypto.open(bytes, password.toCharArray()),
                        Charsets.UTF_8,
                    )
                    repository.importData(
                        backupJson.decodeFromString(DulSsenRepository.BackupData.serializer(), json),
                    )
                }
                say(
                    "백업을 불러왔습니다 — 카드 ${counts.cards}장 · 거래 ${counts.txns}건",
                    undoable = true,
                )
            } catch (e: BackupCrypto.WrongPasswordException) {
                say("비밀번호가 다르거나 파일이 손상되었습니다", undoable = false)
            } catch (e: IllegalArgumentException) {
                say(e.message ?: "지원하지 않는 백업 형식입니다", undoable = false)
            } catch (e: Exception) {
                say("백업 파일을 읽지 못했습니다", undoable = false)
            }
        }
    }

    /**
     * 이미지에서 불러오기. 저장소 권한을 요청하지 않는다 —
     * 시스템 사진 선택기가 사용자가 고른 한 장만 넘겨준다.
     */
    fun requestImageImport() {
        _state.update { it.copy(showImagePicker = true) }
    }

    /**
     * 캡처 이미지에서 결제 통지를 읽어 온다.
     *
     * OCR 텍스트를 **줄 단위로 파서에 넣으면 안 된다.** 카드 통지는 카드사·금액·시각·가맹점이
     * 각기 다른 줄에 있어서 어느 한 줄도 필수 항목을 채우지 못하고 전부 버려진다.
     * 게다가 그렇게 만들어진 엉터리 거래가 지문을 선점하면, 이어지는 올바른 파싱이
     * '중복'으로 막혀 결과적으로 아무것도 안 들어온다.
     *
     * 그래서 [OcrText.blocks] 로 통지 단위로 자른 뒤 덩어리째 넣고,
     * 그래도 못 건지면 전체 텍스트로 한 번 더 시도한다.
     */
    fun importFromImage(uri: Uri) {
        viewModelScope.launch {
            try {
                val context = getApplication<Application>()
                val text = withContext(Dispatchers.IO) {
                    ImageOcrHelper.extractText(context, uri)
                }

                if (text.isNullOrBlank()) {
                    say("이미지에서 글자를 읽지 못했습니다", undoable = false)
                    return@launch
                }

                val now = System.currentTimeMillis()
                val result = withContext(Dispatchers.IO) {
                    // 이용내역 **목록** 화면이면 행 단위로 읽는다. 통지 파서로는 한 건밖에 못 건진다.
                    if (LedgerScreenParser.looksLikeLedger(text)) {
                        val rows = LedgerScreenParser.parse(text)
                        val issuer = IssuerRegistry.detect(text)?.key
                        repository.importLedgerRows(rows, issuer, now, text)
                    } else {
                        var inserted = 0
                        var duplicates = 0
                        OcrText.blocks(text).forEach { block ->
                            when (ingestOcrBlock(block, now)) {
                                Ingested.INSERTED -> inserted++
                                Ingested.DUPLICATE -> duplicates++
                                Ingested.NOT_A_PAYMENT -> Unit
                            }
                        }
                        // 통지 시작점을 못 찾아 통째로 넘어온 경우까지 포함해 한 번 더.
                        if (inserted == 0 && duplicates == 0) {
                            when (ingestOcrBlock(text, now)) {
                                Ingested.INSERTED -> inserted++
                                Ingested.DUPLICATE -> duplicates++
                                Ingested.NOT_A_PAYMENT -> Unit
                            }
                        }
                        DulSsenRepository.ImportResult(inserted, duplicates)
                    }
                }

                say(describeImport(result), undoable = false)
            } catch (e: Exception) {
                say("이미지 처리에 실패했습니다", undoable = false)
            }
        }
    }

    private enum class Ingested { INSERTED, DUPLICATE, NOT_A_PAYMENT }

    private suspend fun ingestOcrBlock(body: String, receivedAt: Long): Ingested =
        when (
            repository.ingest(
                RawMessage(
                    source = TxSource.IMAGE,
                    senderKey = "image-ocr",
                    title = null,
                    body = body,
                    receivedAt = receivedAt,
                ),
            )
        ) {
            is Ingestor.Outcome.Insert -> Ingested.INSERTED
            is Ingestor.Outcome.Duplicate -> Ingested.DUPLICATE
            Ingestor.Outcome.NotAPayment -> Ingested.NOT_A_PAYMENT
        }

    /**
     * 불러오기 결과 문구.
     *
     * 중복을 반드시 밝힌다. 화면에 6건이 보이는데 5건만 들어오면 사용자는
     * 파서가 한 건을 놓친 것으로 읽는다 — 실제로 그런 오해가 있었다.
     */
    private fun describeImport(result: DulSsenRepository.ImportResult): String = when {
        result.inserted > 0 && result.duplicates > 0 ->
            "결제 ${result.inserted}건을 불러왔습니다 · 이미 있는 ${result.duplicates}건은 건너뜀"
        result.inserted > 0 -> "이미지에서 결제 ${result.inserted}건을 불러왔습니다"
        result.duplicates > 0 -> "모두 이미 있는 결제입니다 (${result.duplicates}건)"
        else -> "이미지에서 결제 정보를 찾지 못했습니다"
    }

    fun clearImagePickerRequest() {
        _state.update { it.copy(showImagePicker = false) }
    }

    // ------------------------------------------------------------- 스낵바 · 되돌리기

    private fun say(message: String, undoable: Boolean) {
        toastJob?.cancel()
        if (!undoable) undoSnapshot = null
        _state.update { it.copy(toast = Toast(message, undoable)) }
        toastJob = viewModelScope.launch {
            delay(TOAST_DURATION_MS)
            undoSnapshot = null
            _state.update { it.copy(toast = null) }
        }
    }

    fun undo() {
        val snapshot = undoSnapshot ?: return
        viewModelScope.launch {
            repository.restore(snapshot)
            undoSnapshot = null
            say("되돌렸습니다", undoable = false)
        }
    }

    fun dismissToast() {
        toastJob?.cancel()
        _state.update { it.copy(toast = null) }
    }

    private companion object {
        /** README: 스낵바는 4.2초 후 자동 소멸하고, 되돌리기도 그때 만료된다. */
        const val TOAST_DURATION_MS = 4_200L

        /** 내부 저장소 안의 백업 폴더. `@xml/file_paths` 가 여는 경로와 같아야 한다. */
        const val EXPORT_DIR = "exports"

        val FILE_STAMP: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(Cycle.ZONE)
    }
}
