package com.msyim.dulssencard.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msyim.dulssencard.backup.message
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.domain.Cycle
import com.msyim.dulssencard.domain.FreeTier
import com.msyim.dulssencard.domain.Money
import com.msyim.dulssencard.domain.Times
import com.msyim.dulssencard.ingest.Ingestor
import com.msyim.dulssencard.ingest.RawMessage
import com.msyim.dulssencard.ingest.IssuerRegistry
import com.msyim.dulssencard.ingest.LedgerScreenParser
import com.msyim.dulssencard.ingest.OcrText
import com.msyim.dulssencard.ingest.SourceGate
import com.msyim.dulssencard.ocr.ImageOcrHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** 화면. [label] 은 탭 이름이자 하위 화면 위 "← ○○" 에 쓰는 이름이다 — 같은 곳은 어디서나 같은 이름으로 부른다. */
enum class Screen(val label: String) {
    ONBOARD("시작하기"),
    HOME("홈"),
    INBOX("거래"),
    DETAIL("거래 상세"),
    CARDS("카드"),
    EDIT("카드 편집"),
    SETTINGS("설정"),
    SOURCES("알림 소스"),
    CARD_DETAIL("카드 상세"),
    MANUAL("직접 입력"),
    BACKUP("백업"),
}

enum class InboxTab { PENDING, ALL, EXCLUDED }

data class CardForm(
    val nickname: String = "",
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

/** 직접 입력 폼. 현금성 결제·알림이 안 온 결제·증감 보정. */
data class ManualForm(
    val kind: ManualKind = ManualKind.PAYMENT,
    val cardId: String? = null,
    val amount: String = "",
    /** 증감 보정일 때 감액인가. */
    val negative: Boolean = false,
    /** 오늘로부터 며칠 전인가(0 = 오늘). 날짜 선택기 대신 쓴다 — 누락 결제는 대개 최근 며칠이다. */
    val daysAgo: Int = 0,
    val merchant: String = "",
)

enum class ManualKind(val label: String) {
    PAYMENT("결제 추가"),
    CANCEL("취소 추가"),
    ADJUST("금액 보정"),
}

/** 백업 가져오기 진행 단계. */
sealed interface ImportStage {
    data object Idle : ImportStage
    data object Working : ImportStage
    data class NeedPassword(val error: String? = null) : ImportStage
    data class Preview(
        val payload: com.msyim.dulssencard.backup.BackupPayload,
        val mode: com.msyim.dulssencard.backup.ImportPlanner.Mode,
        val summary: com.msyim.dulssencard.backup.ImportPlanner.Summary,
    ) : ImportStage
    data class Failed(val message: String) : ImportStage
}

data class Toast(val message: String, val undoable: Boolean)

data class UiState(
    val loading: Boolean = true,
    val screen: Screen = Screen.ONBOARD,
    val backTo: Screen = Screen.INBOX,
    val inboxTab: InboxTab = InboxTab.PENDING,
    val sortByName: Boolean = false,
    val selectedTxnId: String? = null,
    val editingCardId: String? = null,
    val cards: List<Card> = emptyList(),
    val txns: List<Txn> = emptyList(),
    val sourceApps: List<SourceApp> = emptyList(),
    val limitAmount: Long = Settings.DEFAULT_LIMIT_AMOUNT,
    val limitCycleStartDay: Int = Settings.DEFAULT_LIMIT_CYCLE_START_DAY,
    val limitInput: String = Money.grouped(Settings.DEFAULT_LIMIT_AMOUNT),
    val autoCollectEnabled: Boolean = false,
    val hasNotificationAccess: Boolean = false,
    val defaultSmsPackage: String? = null,
    val form: CardForm = CardForm(),
    val toast: Toast? = null,
    val showImagePicker: Boolean = false,
    /** 카드 상세에서 보고 있는 카드. */
    val selectedCardId: String? = null,
    /** 결과함 카드 필터. null = 전체, [InboxFilter.UNASSIGNED] = 미분류. */
    val inboxCardFilter: String? = null,
    val inboxThisCycleOnly: Boolean = false,
    /** 온보딩 단계 0~3. */
    val onboardingStep: Int = 0,
    val manualForm: ManualForm = ManualForm(),
    val exportBusy: Boolean = false,
    val importStage: ImportStage = ImportStage.Idle,
    val autoBackups: List<com.msyim.dulssencard.backup.AutoBackupStore.Entry> = emptyList(),
    /** 취소 거래 상세에서 고를 원 승인 거래 후보. null = 아직 안 불러옴. */
    val cancelCandidates: List<Txn>? = null,
    /** 한 번 결제(카드 등록 제한 해제) 상태. */
    val pro: com.msyim.dulssencard.billing.ProUnlock.State = com.msyim.dulssencard.billing.ProUnlock.State(),
    /** 무료 한도에 걸려 카드 추가를 막았을 때 띄우는 안내. */
    val showUpgrade: Boolean = false,
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
        "눌러서 설정에서 자동 집계를 켜세요",
    ),
    NO_NOTIFICATION_ACCESS(
        "알림 접근이 꺼져 있습니다",
        "이 앱의 유일한 수집 경로입니다 — 눌러서 시스템 설정에서 켜세요",
    ),
    NO_SOURCE_APPS(
        "읽을 앱을 아직 고르지 않았습니다",
        "눌러서 카드사 앱과 문자 앱을 켜세요",
    ),
    SMS_APP_OFF(
        "결제 문자를 못 읽고 있습니다",
        "눌러서 문자 앱을 켜면 결제 문자도 잡힙니다",
    ),
}

/** 하단 탭 화면. 여기로 가면 뒤로가기 기록을 비운다. */
private val TOP_LEVEL = setOf(Screen.HOME, Screen.INBOX, Screen.CARDS, Screen.SETTINGS, Screen.ONBOARD)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DulSsenRepository.get(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var undoSnapshot: DulSsenRepository.Snapshot? = null
    private var toastJob: Job? = null

    private val proUnlock = com.msyim.dulssencard.billing.ProUnlock(app)

    init {
        proUnlock.start()
        viewModelScope.launch {
            proUnlock.state.collect { pro ->
                val justUnlocked = pro.owned && !_state.value.pro.owned && !_state.value.loading
                _state.value = _state.value.copy(pro = pro, showUpgrade = _state.value.showUpgrade && !pro.owned)
                if (justUnlocked) say("카드 등록 제한이 풀렸습니다. 고맙습니다!", undoable = false)
            }
        }
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
                _state.value = _state.value.copy(
                    loading = false,
                    cards = cards,
                    txns = txns,
                    sourceApps = sources,
                    limitAmount = limit,
                    limitCycleStartDay = settings[Settings.LIMIT_CYCLE_START_DAY]?.toIntOrNull()
                        ?: Settings.DEFAULT_LIMIT_CYCLE_START_DAY,
                    limitInput = Money.grouped(limit),
                    autoCollectEnabled = settings[Settings.AUTO_COLLECT_ENABLED] != "false",
                    sortByName = settings[Settings.HOME_SORT_BY_NAME] == "true",
                    screen = if (_state.value.loading) {
                        if (onboarded) Screen.HOME else Screen.ONBOARD
                    } else {
                        _state.value.screen
                    },
                )
            }
        }
        viewModelScope.launch { seedSourceApps() }
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
                java.io.File(context.filesDir, "sources_dump.txt").writeText(report.toString())
            }
        }
    }

    /**
     * 알림 접근은 앱이 요청할 수 없고 사용자가 시스템 설정에서만 켤 수 있다.
     * 그래서 화면에 돌아올 때마다 다시 읽는 것 말고는 상태를 알 방법이 없다.
     */
    fun refreshPermissions() {
        val context = getApplication<Application>()
        _state.value = _state.value.copy(
            hasNotificationAccess = SourceGate.hasNotificationAccess(context),
            defaultSmsPackage = SourceGate.defaultSmsPackage(context),
        )
    }

    // ------------------------------------------------------------- 화면 이동

    /**
     * 뒤로가기 기록. 하위 화면(거래 상세 · 카드 상세 · 편집 · 직접 입력 · 알림 소스 · 백업)으로 갈 때
     * 지금 화면을 쌓고, 탭 화면으로 가면 비운다. 뒤로가기는 **들어온 길을 그대로 되짚는다** —
     * 전에는 화면마다 돌아갈 곳이 고정돼 있어, 카드 상세에서 연 거래를 닫으면 결과함으로 가는 식이었다.
     */
    private val history = ArrayDeque<Screen>()

    /** 기록 없이 들어온 하위 화면의 부모(예: 알림에서 바로 연 거래 상세). */
    private fun parentOf(screen: Screen): Screen = when (screen) {
        Screen.DETAIL, Screen.MANUAL -> Screen.INBOX
        Screen.EDIT -> Screen.CARDS
        Screen.SOURCES, Screen.BACKUP -> Screen.SETTINGS
        else -> Screen.HOME
    }

    /**
     * 화면을 옮긴다. 스낵바는 즉시 닫는다(README: 화면 전환 시 스낵바 즉시 닫기).
     * [backTo][UiState.backTo] 는 뒤로가기가 실제로 갈 곳이다 — 화면 위 "← ○○" 표시가 이 값을 쓴다.
     */
    private fun navigate(screen: Screen, update: (UiState) -> UiState = { it }) {
        toastJob?.cancel()
        val current = _state.value.screen
        if (screen in TOP_LEVEL) {
            history.clear()
        } else if (current != screen) {
            history.addLast(current)
        }
        val backTo = history.lastOrNull() ?: parentOf(screen)
        _state.value = update(_state.value).copy(screen = screen, backTo = backTo, toast = null)
    }

    fun go(screen: Screen) = navigate(screen)

    fun openInbox(tab: InboxTab) = navigate(Screen.INBOX) { it.copy(inboxTab = tab) }

    fun selectInboxTab(tab: InboxTab) {
        _state.value = _state.value.copy(inboxTab = tab)
    }

    fun openTxn(id: String) = navigate(Screen.DETAIL) { it.copy(selectedTxnId = id, cancelCandidates = null) }

    /** 들어온 길을 한 단계 되짚는다. 기록이 없으면 그 화면의 부모로 간다. */
    fun back() {
        toastJob?.cancel()
        val target = history.removeLastOrNull() ?: parentOf(_state.value.screen)
        val backTo = history.lastOrNull() ?: parentOf(target)
        _state.value = _state.value.copy(screen = target, backTo = backTo, toast = null)
    }

    fun toggleSort() {
        val next = !_state.value.sortByName
        _state.value = _state.value.copy(sortByName = next)
        viewModelScope.launch {
            repository.putSetting(Settings.HOME_SORT_BY_NAME, next.toString())
        }
    }

    // ------------------------------------------------------------- 온보딩

    /**
     * 온보딩은 네 단계다: 알림 접근 허용 → 읽을 앱 선택 → 카드 등록 → 초기 사용액.
     * 한 화면에서 전부 설명하면 사용자가 무엇을 해야 끝나는지 모른다. 단계마다 할 일이 하나다.
     */
    fun onboardingNext() {
        _state.value = _state.value.copy(onboardingStep = (_state.value.onboardingStep + 1).coerceAtMost(3))
    }

    fun onboardingBack() {
        _state.value = _state.value.copy(onboardingStep = (_state.value.onboardingStep - 1).coerceAtLeast(0))
    }

    fun completeOnboarding(enableCollection: Boolean) {
        viewModelScope.launch {
            repository.putSetting(Settings.ONBOARDING_DONE, "true")
            repository.putSetting(Settings.AUTO_COLLECT_ENABLED, enableCollection.toString())
            _state.value = _state.value.copy(screen = Screen.HOME)
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
        // 무료 한도면 폼을 다 채운 뒤에 막지 않고 들어가기 전에 알린다.
        if (!FreeTier.canAddCard(_state.value.cards.size, _state.value.pro.owned)) {
            _state.value = _state.value.copy(showUpgrade = true)
            return
        }
        navigate(Screen.EDIT) { it.copy(editingCardId = null, form = CardForm()) }
    }

    // ------------------------------------------------------------- 한 번 결제

    /** 결제 창. Play 스토어가 없거나 상품 정보를 아직 못 받았으면 알린다. */
    fun buyPro(activity: android.app.Activity) {
        if (!proUnlock.launch(activity)) {
            say("지금은 결제 창을 열 수 없습니다. Play 스토어에 로그인돼 있는지 확인해 주세요", undoable = false)
        }
    }

    /** 구매 복원. 재설치했거나 같은 계정의 다른 기기에서 산 경우. */
    fun restorePro() {
        proUnlock.refreshPurchases()
        say("구매 내역을 확인했습니다", undoable = false)
    }

    fun dismissUpgrade() {
        _state.value = _state.value.copy(showUpgrade = false)
    }

    override fun onCleared() {
        proUnlock.close()
        super.onCleared()
    }

    fun editCard(card: Card) = navigate(Screen.EDIT) {
        it.copy(
            editingCardId = card.id,
            form = CardForm(
                nickname = card.nickname,
                target = Money.grouped(card.trackingTarget),
                keywords = card.matchKeywords.joinToString(", "),
                exclude = card.excludeKeywords.joinToString(", "),
                startDay = card.cycleStartDay,
                defaultTarget = card.defaultCountsTowardTarget,
                defaultLimit = card.defaultCountsTowardPurchaseLimit,
                // 지난 주기에 넣은 초기값은 이미 합계에서 빠졌다. 그걸 그대로 보여 주면
                // 사용자가 아직 유효한 값으로 오해하므로, 이번 주기 것만 채운다.
                initialAmount = if (
                    card.initialAmount != 0L &&
                    Cycle.windowFor(card.cycleStartDay).contains(card.initialAmountAt)
                ) {
                    Money.grouped(card.initialAmount)
                } else {
                    ""
                },
            ),
        )
    }

    fun updateForm(transform: (CardForm) -> CardForm) {
        _state.value = _state.value.copy(form = transform(_state.value.form))
    }

    /** [after] 저장 뒤 갈 화면. 온보딩에서는 다음 단계에 머문다. null 이면 들어온 곳으로 돌아간다. */
    fun saveCard(after: Screen? = null) {
        val form = _state.value.form
        val target = Money.parseAmount(form.target)
        val keywords = splitKeywords(form.keywords)
        if (form.nickname.isBlank() || target == null || target <= 0L || keywords.isEmpty()) {
            say("별명·목표·인식 키워드는 필수입니다", undoable = false)
            return
        }
        // 초기 사용액은 선택 항목이다. 비워 두면 지금까지처럼 통지만 센다.
        val initial = if (form.initialAmount.isBlank()) 0L else Money.parseAmount(form.initialAmount)
        if (initial == null || initial < 0L) {
            say("초기 사용액은 숫자로 입력해 주세요", undoable = false)
            return
        }
        val editingId = _state.value.editingCardId
        // 새 카드만 한도를 본다(온보딩 포함). 기존 카드 편집은 한도와 무관하다.
        if (editingId == null && !FreeTier.canAddCard(_state.value.cards.size, _state.value.pro.owned)) {
            _state.value = _state.value.copy(showUpgrade = true)
            return
        }
        viewModelScope.launch {
            val existing = editingId?.let { repository.card(it) }
            // 기준 시각 규칙은 Aggregator.initialAmountAtOnSave 주석 참고.
            val stamp = Aggregator.initialAmountAtOnSave(existing, initial, form.startDay)
            repository.upsertCard(
                Card(
                    id = editingId ?: UUID.randomUUID().toString(),
                    nickname = form.nickname.trim(),
                    trackingTarget = target,
                    cycleStartDay = Cycle.normalizeStartDay(form.startDay),
                    matchKeywords = keywords,
                    excludeKeywords = splitKeywords(form.exclude),
                    defaultCountsTowardTarget = form.defaultTarget,
                    defaultCountsTowardPurchaseLimit = form.defaultLimit,
                    initialAmount = initial,
                    initialAmountAt = stamp,
                    active = existing?.active ?: true,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                ),
            )
            if (after == null) {
                back()
            } else {
                _state.value = _state.value.copy(
                    screen = after,
                    form = if (after == Screen.ONBOARD) CardForm() else _state.value.form,
                )
            }
            say(if (editingId == null) "카드를 저장했습니다" else "변경을 저장했습니다", undoable = false)
        }
    }

    fun deleteCard(card: Card) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.deleteCard(card)
            // 지운 카드의 상세로 돌아가지 않도록 기록을 비우고 카드 목록으로 간다.
            go(Screen.CARDS)
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
        message = "집계를 확정했습니다",
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
        _state.value = _state.value.copy(limitInput = Money.reformatInput(raw))
    }

    fun commitLimit() {
        val amount = Money.parseAmount(_state.value.limitInput) ?: return
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
     * 끄면 SMS 리시버 컴포넌트까지 꺼서 브로드캐스트 자체가 도달하지 않게 한다.
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
            // 복원 전 자동 백업도 데이터 사본이다. 남겨 두면 "전체 삭제" 뒤에도 기기에 거래가 남는다.
            // 파일과 그걸 여는 Keystore 키를 함께 버린다. (방금 삭제는 4.2초 되돌리기가 메모리 스냅샷으로 가능하다.)
            withContext(Dispatchers.IO) {
                runCatching { backupManager.deleteAllAutoBackups() }
                com.msyim.dulssencard.backup.DeviceBackupKey.destroy()
            }
            // 삭제는 알림 소스 목록도 비운다. 곧바로 다시 채우지 않으면 수집이 통째로 멈춘다.
            seedSourceApps()
            say("로컬 데이터를 모두 삭제했습니다", undoable = true)
        }
    }

    // ------------------------------------------------------------- 카드 상세 · 초기 사용액

    fun openCard(card: Card) = navigate(Screen.CARD_DETAIL) { it.copy(selectedCardId = card.id) }

    /**
     * 초기 사용액을 바로 다시 맞춘다(홈·카드 상세). 기준 시각은 지금이다.
     * 되돌릴 수 있게 스냅샷을 뜬다 — 숫자를 잘못 쳤을 때 카드 편집까지 들어가지 않게.
     */
    fun setInitialAmount(card: Card, amount: Long) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.setInitialAmount(card.id, amount)
            say(
                if (amount == 0L) "${card.nickname} 초기 사용액을 껐습니다" else "${card.nickname} 기준액을 ${Money.won(amount)}으로 맞췄습니다",
                undoable = true,
            )
        }
    }

    // ------------------------------------------------------------- 결과함 필터

    fun setInboxCardFilter(cardId: String?) {
        _state.value = _state.value.copy(inboxCardFilter = cardId)
    }

    fun toggleInboxThisCycle() {
        _state.value = _state.value.copy(inboxThisCycleOnly = !_state.value.inboxThisCycleOnly)
    }

    /** 카드 상세의 "확인 필요 N건"에서 결과함으로 넘어갈 때 그 카드로 걸러 둔다. */
    fun openInboxFor(cardId: String?, tab: InboxTab) =
        navigate(Screen.INBOX) { it.copy(inboxTab = tab, inboxCardFilter = cardId) }

    // ------------------------------------------------------------- 직접 입력 · 금액 정정

    fun openManualEntry(cardId: String?) = navigate(Screen.MANUAL) {
        it.copy(manualForm = ManualForm(cardId = cardId ?: it.cards.singleOrNull()?.id))
    }

    fun updateManualForm(transform: (ManualForm) -> ManualForm) {
        _state.value = _state.value.copy(manualForm = transform(_state.value.manualForm))
    }

    fun saveManualEntry() {
        val form = _state.value.manualForm
        val amount = Money.parseAmount(form.amount)
        if (amount == null || amount <= 0L) {
            say("금액을 입력해 주세요", undoable = false)
            return
        }
        if (form.cardId == null) {
            say("카드를 골라 주세요", undoable = false)
            return
        }
        // 날짜만 고르고 시각은 지금으로 둔다. 며칠 전을 고르면 그날 정오로 — 주기 경계(자정)에 걸리지 않게.
        val occurredAt = if (form.daysAgo == 0) {
            System.currentTimeMillis()
        } else {
            java.time.LocalDate.now(Cycle.ZONE).minusDays(form.daysAgo.toLong())
                .atTime(12, 0).atZone(Cycle.ZONE).toInstant().toEpochMilli()
        }
        val direction = when (form.kind) {
            ManualKind.PAYMENT -> com.msyim.dulssencard.data.model.TxDirection.APPROVAL
            ManualKind.CANCEL -> com.msyim.dulssencard.data.model.TxDirection.CANCEL
            ManualKind.ADJUST -> com.msyim.dulssencard.data.model.TxDirection.MANUAL
        }
        val signed = if (form.kind == ManualKind.ADJUST && form.negative) -amount else amount
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.addManualTxn(form.cardId, signed, direction, occurredAt, form.merchant)
            back()
            say("${form.kind.label}: ${Money.won(signed)}", undoable = true)
        }
    }

    /** 합계에 없는 거래(제외 · 확인 필요) 영구 삭제. 직후 4.2초 동안 되돌릴 수 있다. 상세 화면에서 지웠으면 목록으로 돌아간다. */
    fun deleteUncounted(txns: List<Txn>) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            val deleted = repository.deleteUncountedTxns(txns.map { it.id })
            if (_state.value.screen == Screen.DETAIL && txns.any { it.id == _state.value.selectedTxnId }) {
                back()
            }
            say(
                if (deleted == 0) "지울 수 있는 거래가 없습니다" else "거래 ${deleted}건을 삭제했습니다",
                undoable = deleted > 0,
            )
        }
    }

    fun correctAmount(txn: Txn, amount: Long) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.correctAmount(txn, amount)
            say("금액을 ${Money.won(amount)}으로 정정했습니다", undoable = true)
        }
    }

    // ------------------------------------------------------------- 취소 거래 연결

    fun loadCancelCandidates(cancel: Txn) {
        viewModelScope.launch {
            _state.value = _state.value.copy(cancelCandidates = repository.cancelCandidates(cancel))
        }
    }

    fun clearCancelCandidates() {
        _state.value = _state.value.copy(cancelCandidates = null)
    }

    fun linkCancel(cancel: Txn, origin: Txn) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.linkCancel(cancel, origin)
            _state.value = _state.value.copy(cancelCandidates = null)
            say("원 승인 거래에 연결했습니다", undoable = true)
        }
    }

    // ------------------------------------------------------------- 백업

    private val backupManager by lazy {
        val context = getApplication<Application>()
        com.msyim.dulssencard.backup.BackupManager(
            repository = repository,
            autoBackups = com.msyim.dulssencard.backup.AutoBackupStore(
                directory = File(context.filesDir, "auto-backups"),
                keyProvider = com.msyim.dulssencard.backup.DeviceBackupKey::getOrCreate,
            ),
            appVersion = com.msyim.dulssencard.BuildConfig.VERSION_NAME,
        )
    }

    /** 가져오기 중인 파일. 상태(StateFlow)에 두지 않는다 — 화면 재구성마다 복사·비교되면 안 된다. */
    private var importBytes: ByteArray? = null

    fun openBackup() {
        navigate(Screen.BACKUP)
        refreshAutoBackups()
    }

    private fun refreshAutoBackups() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { backupManager.autoBackupList() }.getOrDefault(emptyList())
            _state.value = _state.value.copy(autoBackups = list)
        }
    }

    fun exportPasswordProblem(password: String, confirm: String): String? =
        backupManager.passwordProblem(password.toCharArray(), confirm.toCharArray())

    /**
     * 사용자가 파일 선택기에서 고른 위치에 암호화 백업을 쓴다.
     *
     * 평문은 메모리에만 있다가 암호화되고, 파일에는 암호문만 나간다. 비밀번호 문자 배열은 끝나면 지운다.
     * (화면 입력칸의 String 사본까지 지울 수는 없다 — JVM 문자열은 불변이다.)
     */
    fun exportTo(uri: Uri, password: String) {
        val chars = password.toCharArray()
        _state.value = _state.value.copy(exportBusy = true)
        viewModelScope.launch {
            try {
                val bytes = backupManager.export(chars)
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    requireNotNull(resolver.openOutputStream(uri, "wt")) { "파일을 열 수 없습니다" }.use { it.write(bytes) }
                }
                say("암호화 백업을 저장했습니다", undoable = false)
            } catch (e: Exception) {
                say("백업을 저장하지 못했습니다", undoable = false)
            } finally {
                chars.fill(Char(0))
                _state.value = _state.value.copy(exportBusy = false)
            }
        }
    }

    fun importPicked(uri: Uri) {
        _state.value = _state.value.copy(importStage = ImportStage.Working)
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        // 64MB 를 넘는 파일은 끝까지 읽지 않는다. 엉뚱한 동영상을 골랐을 때 메모리를 지킨다.
                        val limit = com.msyim.dulssencard.backup.BackupCrypto.MAX_FILE_BYTES
                        val buffer = input.readNBytesCompat(limit + 1)
                        if (buffer.size > limit) null else buffer
                    }
                }.getOrNull()
            }
            _state.value = _state.value.copy(
                importStage = when {
                    bytes == null -> ImportStage.Failed("파일을 읽지 못했습니다(너무 크거나 열 수 없음)")
                    backupManager.requiresPassword(bytes) == null -> ImportStage.Failed("덜쎈카드 백업 파일이 아닙니다")
                    backupManager.requiresPassword(bytes) == false ->
                        ImportStage.Failed("이 기기의 자동 백업 파일입니다. 아래 '자동 백업'에서 되돌리세요")
                    else -> {
                        importBytes = bytes
                        ImportStage.NeedPassword()
                    }
                },
            )
        }
    }

    fun openImport(password: String) {
        val bytes = importBytes ?: return
        val chars = password.toCharArray()
        _state.value = _state.value.copy(importStage = ImportStage.Working)
        viewModelScope.launch {
            val result = try {
                backupManager.open(bytes, chars)
            } finally {
                chars.fill(Char(0))
            }
            _state.value = _state.value.copy(importStage = stageFor(result, com.msyim.dulssencard.backup.ImportPlanner.Mode.MERGE))
        }
    }

    private suspend fun stageFor(
        result: com.msyim.dulssencard.backup.BackupManager.OpenResult,
        mode: com.msyim.dulssencard.backup.ImportPlanner.Mode,
    ): ImportStage = when (result) {
        is com.msyim.dulssencard.backup.BackupManager.OpenResult.Opened ->
            ImportStage.Preview(result.payload, mode, backupManager.preview(result.payload, mode).summary)
        is com.msyim.dulssencard.backup.BackupManager.OpenResult.Failed ->
            if (result.failure == com.msyim.dulssencard.backup.BackupCrypto.Failure.WrongPasswordOrCorrupted) {
                ImportStage.NeedPassword(error = "비밀번호가 틀렸거나 파일이 손상되었습니다")
            } else {
                ImportStage.Failed(result.failure.message())
            }
        is com.msyim.dulssencard.backup.BackupManager.OpenResult.NewerApp ->
            ImportStage.Failed("더 새 버전의 앱에서 만든 백업입니다. 앱을 업데이트한 뒤 가져오세요")
        is com.msyim.dulssencard.backup.BackupManager.OpenResult.Invalid ->
            ImportStage.Failed("백업 내용에 문제가 있어 가져오지 않았습니다: " + result.problems.take(3).joinToString(" · "))
    }

    fun selectImportMode(mode: com.msyim.dulssencard.backup.ImportPlanner.Mode) {
        val preview = _state.value.importStage as? ImportStage.Preview ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                importStage = preview.copy(mode = mode, summary = backupManager.preview(preview.payload, mode).summary),
            )
        }
    }

    /** 적용. 저장소가 [자동 백업 → 재계획 → 쓰기]를 한 트랜잭션으로 한다. */
    fun applyImport() {
        val preview = _state.value.importStage as? ImportStage.Preview ?: return
        _state.value = _state.value.copy(importStage = ImportStage.Working)
        viewModelScope.launch {
            try {
                val plan = backupManager.apply(preview.payload, preview.mode)
                importBytes = null
                _state.value = _state.value.copy(importStage = ImportStage.Idle)
                refreshAutoBackups()
                val s = plan.summary
                say("가져왔습니다 · 카드 +${s.cardsAdded} · 거래 +${s.txnsAdded} 갱신 ${s.txnsUpdated}", undoable = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    importStage = ImportStage.Failed("가져오지 못했습니다. 아무것도 바뀌지 않았습니다"),
                )
            }
        }
    }

    fun cancelImport() {
        importBytes = null
        _state.value = _state.value.copy(importStage = ImportStage.Idle)
    }

    /** 자동 백업으로 되돌린다. 이것도 적용 전에 현재 상태를 자동 백업한다. */
    fun restoreAutoBackup(entry: com.msyim.dulssencard.backup.AutoBackupStore.Entry) {
        _state.value = _state.value.copy(importStage = ImportStage.Working)
        viewModelScope.launch {
            try {
                when (val result = backupManager.openAutoBackup(entry)) {
                    is com.msyim.dulssencard.backup.BackupManager.OpenResult.Opened -> {
                        backupManager.apply(result.payload, com.msyim.dulssencard.backup.ImportPlanner.Mode.REPLACE)
                        _state.value = _state.value.copy(importStage = ImportStage.Idle)
                        refreshAutoBackups()
                        say("${Times.logStamp(entry.createdAt)} 시점으로 되돌렸습니다", undoable = false)
                    }
                    else -> _state.value = _state.value.copy(importStage = stageFor(result, com.msyim.dulssencard.backup.ImportPlanner.Mode.REPLACE))
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(importStage = ImportStage.Failed("되돌리지 못했습니다. 아무것도 바뀌지 않았습니다"))
            }
        }
    }

    /**
     * 이미지에서 불러오기. 저장소 권한을 요청하지 않는다 —
     * 시스템 사진 선택기가 사용자가 고른 한 장만 넘겨준다.
     */
    fun requestImageImport() {
        _state.value = _state.value.copy(showImagePicker = true)
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
            // 저장소가 삽입 반환값까지 확인한 결과다. 경합에서 진 삽입은 DUPLICATE 로 온다.
            is DulSsenRepository.IngestResult.Inserted -> Ingested.INSERTED
            is DulSsenRepository.IngestResult.Duplicate -> Ingested.DUPLICATE
            is DulSsenRepository.IngestResult.NotAPayment -> Ingested.NOT_A_PAYMENT
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
        _state.value = _state.value.copy(showImagePicker = false)
    }

    // ------------------------------------------------------------- 스낵바 · 되돌리기

    private fun say(message: String, undoable: Boolean) {
        toastJob?.cancel()
        if (!undoable) undoSnapshot = null
        _state.value = _state.value.copy(toast = Toast(message, undoable))
        toastJob = viewModelScope.launch {
            delay(TOAST_DURATION_MS)
            undoSnapshot = null
            _state.value = _state.value.copy(toast = null)
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
        _state.value = _state.value.copy(toast = null)
    }

    private companion object {
        /** README: 스낵바는 4.2초 후 자동 소멸하고, 되돌리기도 그때 만료된다. */
        const val TOAST_DURATION_MS = 4_200L
    }
}

/**
 * `InputStream.readNBytes(int)` 는 API 33 부터다(minSdk 26). 최대 [limit] 바이트까지만 읽는다 —
 * 사용자가 엉뚱한 대용량 파일을 골라도 끝까지 메모리에 올리지 않기 위해서다.
 */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0
    while (total < limit) {
        val read = read(chunk, 0, minOf(chunk.size, limit - total))
        if (read < 0) break
        out.write(chunk, 0, read)
        total += read
    }
    return out.toByteArray()
}
