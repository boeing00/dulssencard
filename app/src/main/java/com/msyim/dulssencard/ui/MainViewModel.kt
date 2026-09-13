package com.msyim.dulssencard.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.data.Settings
import com.msyim.dulssencard.data.model.Card
import com.msyim.dulssencard.data.model.ChangeType
import com.msyim.dulssencard.data.model.PendingReason
import com.msyim.dulssencard.data.model.SourceApp
import com.msyim.dulssencard.data.model.TxSource
import com.msyim.dulssencard.data.model.TxStatus
import com.msyim.dulssencard.data.model.Txn
import com.msyim.dulssencard.domain.Cycle
import com.msyim.dulssencard.domain.Money
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

enum class Screen { ONBOARD, HOME, INBOX, DETAIL, CARDS, EDIT, SETTINGS, SOURCES }

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
    val needsSmsPermission: Boolean = false,
    val exportFileUri: String? = null,
    val needsImagePermission: Boolean = false,
    val showImagePicker: Boolean = false,
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

    /** 화면을 옮길 때 스낵바를 즉시 닫는다(README: 화면 전환 시 스낵바 즉시 닫기). */
    fun go(screen: Screen) {
        toastJob?.cancel()
        _state.value = _state.value.copy(screen = screen, toast = null)
    }

    fun openInbox(tab: InboxTab) {
        toastJob?.cancel()
        _state.value = _state.value.copy(screen = Screen.INBOX, inboxTab = tab, toast = null)
    }

    fun selectInboxTab(tab: InboxTab) {
        _state.value = _state.value.copy(inboxTab = tab)
    }

    fun openTxn(id: String, from: Screen) {
        toastJob?.cancel()
        _state.value = _state.value.copy(
            screen = Screen.DETAIL,
            selectedTxnId = id,
            backTo = from,
            toast = null,
        )
    }

    fun back() {
        go(_state.value.backTo)
    }

    fun toggleSort() {
        val next = !_state.value.sortByName
        _state.value = _state.value.copy(sortByName = next)
        viewModelScope.launch {
            repository.putSetting(Settings.HOME_SORT_BY_NAME, next.toString())
        }
    }

    // ------------------------------------------------------------- 온보딩

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
        toastJob?.cancel()
        _state.value = _state.value.copy(
            screen = Screen.EDIT,
            editingCardId = null,
            backTo = Screen.CARDS,
            form = CardForm(),
            toast = null,
        )
    }

    fun editCard(card: Card) {
        toastJob?.cancel()
        _state.value = _state.value.copy(
            screen = Screen.EDIT,
            editingCardId = card.id,
            backTo = Screen.CARDS,
            toast = null,
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

    fun saveCard() {
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
        viewModelScope.launch {
            val existing = editingId?.let { repository.card(it) }
            // 기준 시각은 **저장하는 지금**이다. 이 시각 이전 거래는 이미 초기값에 들어 있다.
            // 값을 바꾸지 않았으면 기존 기준 시각을 유지해, 저장만 눌렀다고 해서
            // 그 사이 들어온 거래가 합계에서 사라지지 않게 한다.
            val initialChanged = initial != (existing?.initialAmount ?: 0L)
            val stamp = when {
                initial == 0L -> 0L
                initialChanged || existing?.initialAmountAt == 0L -> System.currentTimeMillis()
                else -> existing?.initialAmountAt ?: System.currentTimeMillis()
            }
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
            _state.value = _state.value.copy(screen = Screen.CARDS)
            say(if (editingId == null) "카드를 저장했습니다" else "변경을 저장했습니다", undoable = false)
        }
    }

    fun deleteCard(card: Card) {
        viewModelScope.launch {
            undoSnapshot = repository.snapshot()
            repository.deleteCard(card)
            _state.value = _state.value.copy(screen = Screen.CARDS)
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
            // 삭제는 알림 소스 목록도 비운다. 곧바로 다시 채우지 않으면 수집이 통째로 멈춘다.
            seedSourceApps()
            say("로컬 데이터를 모두 삭제했습니다", undoable = true)
        }
    }

    /**
     * 암호화 백업이 완성되기 전까지 **아무것도 쓰지 않는다.**
     *
     * 이전 구현은 이름과 달리 암호화 없이 전체 거래를 평문 JSON 으로 외부 저장소에 썼다.
     * SQLCipher 로 DB 를 잠가 놓고 그 옆에 평문 사본을 떨구는 셈이라 막아 둔다.
     */
    fun exportEncrypted() {
        say("암호화 백업을 준비 중입니다", undoable = false)
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
