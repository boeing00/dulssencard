package com.msyim.dulssencard.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.msyim.dulssencard.data.DulSsenRepository
import com.msyim.dulssencard.domain.Aggregator
import com.msyim.dulssencard.ingest.SourceGate
import com.msyim.dulssencard.ui.component.CountBadge
import com.msyim.dulssencard.ui.component.DsSnackbar
import com.msyim.dulssencard.ui.component.Hairline
import com.msyim.dulssencard.ui.screen.CardEditScreen
import com.msyim.dulssencard.ui.screen.CardListScreen
import com.msyim.dulssencard.ui.screen.DetailScreen
import com.msyim.dulssencard.ui.screen.HomeScreen
import com.msyim.dulssencard.ui.screen.InboxScreen
import com.msyim.dulssencard.ui.screen.OnboardingScreen
import com.msyim.dulssencard.ui.screen.SettingsScreen
import com.msyim.dulssencard.ui.screen.SourceAppsScreen
import com.msyim.dulssencard.ui.theme.Ds
import com.msyim.dulssencard.ui.theme.DsType

@Composable
fun DulSsenApp(viewModel: MainViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Ds.paper)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                ScreenContent(state = state, viewModel = viewModel, context = context)
            }

            // 온보딩에서는 내비를 숨긴다.
            if (state.screen != Screen.ONBOARD) {
                BottomNav(
                    current = state.screen,
                    pendingCount = state.pendingCount,
                    onSelect = viewModel::go,
                )
            }
        }

        state.toast?.let { toast ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (state.screen == Screen.ONBOARD) 26.dp else 78.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                DsSnackbar(toast.message, toast.undoable, viewModel::undo)
            }
        }
    }
}

@Composable
private fun ScreenContent(
    state: UiState,
    viewModel: MainViewModel,
    context: Context,
) {
    if (state.loading) return

    val limitProgress = remember(state.txns, state.limitAmount, state.limitCycleStartDay) {
        Aggregator.limitProgress(state.limitAmount, state.limitCycleStartDay, state.txns)
    }
    val foreignSpend = remember(state.txns, state.limitCycleStartDay) {
        Aggregator.foreignSpend(state.txns, state.limitCycleStartDay)
    }
    val cardRows = remember(state.cards, state.txns, state.sortByName) {
        Aggregator.sortForHome(
            state.cards.map { Aggregator.cardProgress(it, state.txns) },
            byName = state.sortByName,
        )
    }

    when (state.screen) {
        Screen.ONBOARD -> OnboardingScreen(
            onAccept = {
                viewModel.completeOnboarding(enableCollection = true)
                // 알림 접근은 앱이 요청할 수 없다. 동의 직후 시스템 설정으로 보내 준다.
                if (!state.hasNotificationAccess) {
                    context.startActivity(SourceGate.notificationAccessSettingsIntent())
                }
            },
            onDecline = { viewModel.completeOnboarding(enableCollection = false) },
        )

        Screen.HOME -> HomeScreen(
            limit = limitProgress,
            rows = cardRows,
            pendingCount = state.pendingCount,
            sortByName = state.sortByName,
            foreignSpend = foreignSpend,
            collectionGap = state.collectionGap,
            onOpenLimitSettings = { viewModel.go(Screen.SETTINGS) },
            onOpenPending = { viewModel.openInbox(InboxTab.PENDING) },
            onToggleSort = viewModel::toggleSort,
            onCardClick = { viewModel.openInbox(InboxTab.ALL) },
            onOpenSettings = { viewModel.go(Screen.SETTINGS) },
        )

        Screen.INBOX -> InboxScreen(
            txns = state.txns,
            cards = state.cards,
            tab = state.inboxTab,
            onSelectTab = viewModel::selectInboxTab,
            onOpenTxn = { viewModel.openTxn(it.id, Screen.INBOX) },
        )

        Screen.DETAIL -> {
            val txn = state.txns.firstOrNull { it.id == state.selectedTxnId }
            if (txn == null) {
                viewModel.go(Screen.INBOX)
            } else {
                val repository = remember { DulSsenRepository.get(context) }
                val adjustments by repository.adjustmentsFor(txn.id)
                    .collectAsState(initial = emptyList())
                DetailScreen(
                    txn = txn,
                    cards = state.cards,
                    adjustments = adjustments,
                    onBack = viewModel::back,
                    onMoveToCard = { viewModel.moveTxnToCard(txn, it) },
                    onToggleTarget = { viewModel.toggleCountsTowardTarget(txn) },
                    onToggleLimit = { viewModel.toggleCountsTowardLimit(txn) },
                    onConfirm = { viewModel.confirmTxn(txn) },
                    onExclude = { viewModel.excludeTxn(txn) },
                    onRestore = { viewModel.restoreTxn(txn) },
                )
            }
        }

        Screen.CARDS -> CardListScreen(
            cards = state.cards,
            onAdd = viewModel::newCard,
            onEdit = viewModel::editCard,
        )

        Screen.EDIT -> CardEditScreen(
            form = state.form,
            isNew = state.editingCardId == null,
            existingCard = state.cards.firstOrNull { it.id == state.editingCardId },
            onBack = { viewModel.go(Screen.CARDS) },
            onChange = viewModel::updateForm,
            onSave = viewModel::saveCard,
            onDelete = viewModel::deleteCard,
        )

        Screen.SETTINGS -> SettingsScreen(
            limitInput = state.limitInput,
            limitCycleStartDay = state.limitCycleStartDay,
            autoCollectEnabled = state.autoCollectEnabled,
            hasNotificationAccess = state.hasNotificationAccess,
            smsAppEnabled = state.smsAppEnabled,
            hasDefaultSmsApp = state.defaultSmsPackage != null,
            enabledSourceCount = state.sourceApps.count { it.enabled },
            onLimitInputChange = viewModel::updateLimitInput,
            onLimitCommit = viewModel::commitLimit,
            onLimitCycleStartDayChange = viewModel::setLimitCycleStartDay,
            onToggleAutoCollect = viewModel::setAutoCollectEnabled,
            onOpenNotificationAccess = {
                context.startActivity(SourceGate.notificationAccessSettingsIntent())
            },
            onOpenSourceApps = { viewModel.go(Screen.SOURCES) },
            onExportEncrypted = viewModel::exportEncrypted,
            onImportFromImage = viewModel::requestImageImport,
            onWipe = viewModel::wipeAll,
        )

        Screen.SOURCES -> SourceAppsScreen(
            apps = state.sourceApps,
            onBack = { viewModel.go(Screen.SETTINGS) },
            onToggle = viewModel::setSourceAppEnabled,
        )
    }
}

/** 하단 4탭. 결과함 탭에는 확인 필요 건수 배지가 붙는다(0건이면 숨김). */
@Composable
private fun BottomNav(current: Screen, pendingCount: Int, onSelect: (Screen) -> Unit) {
    val tabs = listOf(
        Screen.HOME to "홈",
        Screen.INBOX to "결과함",
        Screen.CARDS to "카드",
        Screen.SETTINGS to "설정",
    )
    // 상세·편집·소스는 각각 결과함·카드·설정 탭에 속한 화면으로 표시한다.
    val activeTab = when (current) {
        Screen.DETAIL -> Screen.INBOX
        Screen.EDIT -> Screen.CARDS
        Screen.SOURCES -> Screen.SETTINGS
        else -> current
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Ds.paper2)
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Hairline()
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { (screen, label) ->
                val active = screen == activeTab
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(screen) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(if (active) Ds.ink else Ds.paper2),
                    )
                    Row(
                        Modifier.padding(top = 13.dp, bottom = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            label,
                            style = DsType.navLabel.copy(color = if (active) Ds.ink else Ds.text3),
                        )
                        if (screen == Screen.INBOX) CountBadge(pendingCount)
                    }
                }
            }
        }
    }
}

@Suppress("unused")
private fun Modifier.unusedSpacer() = this.then(Modifier)

@Composable
private fun VerticalGap(height: androidx.compose.ui.unit.Dp) = Spacer(Modifier.height(height))
