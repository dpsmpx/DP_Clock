package com.dpsmpx.dpclock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.ui.AlarmsDialog
import com.dpsmpx.dpclock.ui.AlarmsMenu
import com.dpsmpx.dpclock.ui.AlarmsScreen
import com.dpsmpx.dpclock.ui.AppIcons
import com.dpsmpx.dpclock.ui.ExceptionsScreen
import com.dpsmpx.dpclock.ui.SettingsScreen
import com.dpsmpx.dpclock.ui.StopwatchScreen
import com.dpsmpx.dpclock.ui.TimersScreen
import com.dpsmpx.dpclock.ui.orderTitle
import com.dpsmpx.dpclock.ui.rememberNotificationPermissionRequest
import kotlinx.coroutines.launch

/** Главный экран: четыре раздела в нижней панели со свайпом между ними (D-006, D-010). */
class MainActivity : ComponentActivity() {
    /** Раздел, который просит открыть уведомление; растёт номер — новая просьба, -1 — просьбы нет. */
    private var request by mutableStateOf(SectionRequest(Section.ALARMS, 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // После поворота экрана раздел остаётся прежним: просьба интента — только при первом запуске.
        request = SectionRequest(sectionOf(intent), if (savedInstanceState == null) 0 else -1)
        // При запуске — пересчитать расписания: после принудительной остановки
        // Android 15 отменяет будильники (D-008, п. 6).
        persistLater { DpClockApp.graph(this).repository.rescheduleAll() }
        setContent {
            DpClockTheme {
                AppRoot(request)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = SectionRequest(sectionOf(intent), request.serial + 1)
    }

    private fun sectionOf(intent: Intent?): Section =
        intent?.getStringExtra(EXTRA_SECTION)?.let { name -> Section.entries.firstOrNull { it.name == name } }
            ?: Section.ALARMS

    companion object {
        private const val EXTRA_SECTION = "section"

        fun intent(context: Context, section: Section): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SECTION, section.name)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

data class SectionRequest(val section: Section, val serial: Int)

private val SECTION_TITLES = mapOf(
    Section.ALARMS to R.string.section_alarms,
    Section.TIMERS to R.string.section_timers,
    Section.STOPWATCH to R.string.section_stopwatch,
    Section.CLOCK to R.string.section_clock,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(request: SectionRequest) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var exceptionsOpen by rememberSaveable { mutableStateOf(false) }
    var alarmsDialog by rememberSaveable { mutableStateOf<AlarmsDialog?>(null) }
    val pager = rememberPagerState(initialPage = request.section.ordinal) { Section.entries.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val requestNotifications = rememberNotificationPermissionRequest()

    LaunchedEffect(request) {
        if (request.serial < 0) return@LaunchedEffect
        settingsOpen = false
        exceptionsOpen = false
        pager.scrollToPage(request.section.ordinal)
    }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(onBack = { settingsOpen = false })
        return
    }
    if (exceptionsOpen) {
        BackHandler { exceptionsOpen = false }
        ExceptionsScreen(onBack = { exceptionsOpen = false })
        return
    }

    val section = Section.entries[pager.currentPage]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(SECTION_TITLES.getValue(section))) },
                actions = {
                    if (section == Section.ALARMS) {
                        AlarmOrderMenu()
                        AlarmsMenu(openExceptions = { exceptionsOpen = true }, onDialog = { alarmsDialog = it })
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(AppIcons.Settings, contentDescription = stringResource(R.string.settings))
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                for (item in Section.entries) {
                    NavigationBarItem(
                        selected = pager.currentPage == item.ordinal,
                        onClick = { scope.launch { pager.animateScrollToPage(item.ordinal) } },
                        icon = {
                            Icon(
                                when (item) {
                                    Section.ALARMS -> AppIcons.Alarm
                                    Section.TIMERS -> AppIcons.Timer
                                    Section.STOPWATCH -> AppIcons.Stopwatch
                                    Section.CLOCK -> AppIcons.Clock
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(SECTION_TITLES.getValue(item))) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        HorizontalPager(
            state = pager,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            beyondViewportPageCount = 1,
        ) { page ->
            when (Section.entries[page]) {
                Section.ALARMS -> AlarmsScreen(
                    snackbar,
                    requestNotifications,
                    openSettings = { settingsOpen = true },
                    dialog = alarmsDialog,
                    onDialog = { alarmsDialog = it },
                )
                Section.TIMERS -> TimersScreen(requestNotifications)
                Section.STOPWATCH -> StopwatchScreen()
                Section.CLOCK -> ClockScreen()
            }
        }
    }
}

/** Порядок списка будильников — кнопкой в верхней панели (D-010, п. 6). */
@Composable
private fun AlarmOrderMenu() {
    val repository = DpClockApp.graph(LocalContext.current).repository
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(AppIcons.Sort, contentDescription = stringResource(R.string.alarm_order)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        for (order in AlarmOrder.entries) {
            DropdownMenuItem(
                text = { Text(stringResource(orderTitle(order))) },
                leadingIcon = { RadioButton(selected = settings.alarmOrder == order, onClick = null) },
                onClick = {
                    open = false
                    persistLater { repository.updateSettings { it.copy(alarmOrder = order) } }
                },
            )
        }
    }
}
