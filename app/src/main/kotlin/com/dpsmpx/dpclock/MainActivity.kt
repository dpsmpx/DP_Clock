package com.dpsmpx.dpclock

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.platform.Appearances
import com.dpsmpx.dpclock.platform.CalendarSync
import com.dpsmpx.dpclock.platform.SunLocation
import com.dpsmpx.dpclock.platform.Clocks
import com.dpsmpx.dpclock.ui.AlarmsDialog
import com.dpsmpx.dpclock.ui.AlarmsMenu
import com.dpsmpx.dpclock.ui.AlarmsScreen
import com.dpsmpx.dpclock.ui.AppBackdrop
import com.dpsmpx.dpclock.ui.AppIcons
import com.dpsmpx.dpclock.ui.ExceptionsScreen
import com.dpsmpx.dpclock.ui.FitText
import com.dpsmpx.dpclock.ui.OnboardingDialog
import com.dpsmpx.dpclock.ui.SettingsScreen
import com.dpsmpx.dpclock.ui.StatsScreen
import com.dpsmpx.dpclock.ui.TasksScreen
import com.dpsmpx.dpclock.ui.TimersSection
import com.dpsmpx.dpclock.ui.TitleText
import com.dpsmpx.dpclock.ui.WorldClockScreen
import com.dpsmpx.dpclock.ui.isShown
import com.dpsmpx.dpclock.ui.orderTitle
import com.dpsmpx.dpclock.ui.rememberNotificationPermissionRequest
import com.dpsmpx.dpclock.ui.rememberWindowLayout
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Главный экран: разделы в нижней панели со свайпом между ними (D-006, D-010);
 * «Мировые часы» — с 0.8.0 (D-019). На широком окне разделы — в боковой панели (D-022, п. 2).
 */
class MainActivity : ComponentActivity() {
    /** Раздел, который просит открыть уведомление; растёт номер — новая просьба, -1 — просьбы нет. */
    private var request by mutableStateOf(SectionRequest(Section.ALARMS, 0))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // После поворота экрана раздел остаётся прежним: просьба интента — только при первом запуске.
        request = if (savedInstanceState == null) requestOf(intent, 0) else SectionRequest(sectionOf(intent), -1)
        // При запуске — пересчитать расписания: после принудительной остановки
        // Android 15 отменяет будильники (D-008, п. 6).
        persistLater { DpClockApp.graph(this).repository.rescheduleAll() }
        // Календарь телефона мог измениться, пока приложение не работало (D-023, п. 6).
        val appContext = applicationContext
        persistLater { CalendarSync.refresh(appContext) }
        // Google Tasks убран (D-034): ключи прежних версий — с телефона.
        persistLater { DpClockApp.graph(appContext).tasks.cleanUp() }
        // Тема из базы — и после восстановления на новом телефоне, где ночной режим приложения ещё не задан (D-021, п. 2).
        if (savedInstanceState == null) {
            val app = applicationContext
            persistLater { Appearances.apply(app, DpClockApp.graph(app).repository.currentSettings().appearance) }
        }
        setContent {
            DpClockTheme {
                AppRoot(request)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Синхронизация (D-030): при открытии — прочитать общий файл.
        DpClockApp.graph(this).cloud.opened()
        // Восход и закат (D-035): место телефона — пока приложение открыто.
        val appContext = applicationContext
        persistLater { SunLocation.refresh(appContext) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        request = requestOf(intent, request.serial + 1)
    }

    /** Просьба интента: раздел и, у ярлыков и команд, быстрое действие (D-020). */
    private fun requestOf(intent: Intent?, serial: Int): SectionRequest {
        val quick = quickOf(intent)
        if (quick == QuickAction.START_STOPWATCH) {
            persistLater { DpClockApp.graph(this).repository.updateStopwatch { it.start(Clocks.moment()) } }
        }
        val section = when (quick) {
            QuickAction.NEW_ALARM, QuickAction.EDIT_ALARM -> Section.ALARMS
            QuickAction.SLEEP_TIMER, QuickAction.START_STOPWATCH -> Section.TIMERS
            null -> sectionOf(intent)
        }
        // Секундомер — вкладка «Таймеров» (D-033); прежние ярлыки и уведомления просят раздел «STOPWATCH».
        val stopwatch = quick == QuickAction.START_STOPWATCH || intent?.getBooleanExtra(EXTRA_STOPWATCH, false) == true ||
            intent?.getStringExtra(EXTRA_SECTION) == OLD_STOPWATCH
        return SectionRequest(
            section,
            serial,
            quick?.takeIf { it != QuickAction.START_STOPWATCH }?.let { QuickRequest(it, intent?.getStringExtra(EXTRA_ALARM_ID), SystemClock.elapsedRealtimeNanos()) },
            stopwatch = stopwatch,
        )
    }

    private fun quickOf(intent: Intent?): QuickAction? {
        val name = intent?.getStringExtra(EXTRA_QUICK) ?: when (intent?.action) {
            ACTION_NEW_ALARM -> QuickAction.NEW_ALARM.name
            ACTION_SLEEP_TIMER -> QuickAction.SLEEP_TIMER.name
            ACTION_START_STOPWATCH -> QuickAction.START_STOPWATCH.name
            else -> null
        }
        return QuickAction.entries.firstOrNull { it.name == name }
    }

    private fun sectionOf(intent: Intent?): Section {
        val name = intent?.getStringExtra(EXTRA_SECTION)
        if (name == OLD_STOPWATCH) return Section.TIMERS
        return name?.let { Section.entries.firstOrNull { it.name == name } } ?: Section.ALARMS
    }

    companion object {
        private const val EXTRA_SECTION = "section"
        private const val EXTRA_QUICK = "quick"
        private const val EXTRA_ALARM_ID = "alarmId"
        private const val EXTRA_STOPWATCH = "stopwatch"

        /** Раздел «Секундомер» до 0.20.0. */
        private const val OLD_STOPWATCH = "STOPWATCH"

        /** Ярлыки на иконке (`res/xml/shortcuts.xml`, D-020). */
        const val ACTION_NEW_ALARM = "com.dpsmpx.dpclock.action.NEW_ALARM"
        const val ACTION_SLEEP_TIMER = "com.dpsmpx.dpclock.action.SLEEP_TIMER"
        const val ACTION_START_STOPWATCH = "com.dpsmpx.dpclock.action.START_STOPWATCH"

        /** Открыть раздел; [stopwatch] — у «Таймеров» сразу вкладку «Секундомер». */
        fun intent(context: Context, section: Section, stopwatch: Boolean = false): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_SECTION, section.name)
                .putExtra(EXTRA_STOPWATCH, stopwatch)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** Открыть с быстрым действием: новый будильник, будильник [alarmId] в редакторе, таймер засыпания. */
        fun quickIntent(context: Context, action: QuickAction, alarmId: String? = null): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_QUICK, action.name)
                .putExtra(EXTRA_ALARM_ID, alarmId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** Быстрые действия ярлыков и команд ассистента (D-020). */
enum class QuickAction { NEW_ALARM, EDIT_ALARM, SLEEP_TIMER, START_STOPWATCH }

/** Быстрое действие для экрана; [token] — своё у каждого интента: действие выполняется один раз. */
data class QuickRequest(val action: QuickAction, val alarmId: String?, val token: Long)

/** [stopwatch] — у «Таймеров» открыть вкладку «Секундомер» (D-033). */
data class SectionRequest(val section: Section, val serial: Int, val quick: QuickRequest? = null, val stopwatch: Boolean = false)

private val SECTION_TITLES = mapOf(
    Section.ALARMS to R.string.section_alarms,
    Section.TIMERS to R.string.section_timers,
    Section.TASKS to R.string.section_tasks,
    Section.CLOCK to R.string.section_clock,
    Section.WORLD to R.string.section_world,
)

/** В нижней панели пять подписей — у мировых часов короткая. */
/** Подписи разделов ужимаются до этого, а не обрезаются: «Секундомер» в пять пунктов на 393 dp не влезает в 12 sp. */
private val NAV_MIN_FONT = 9.sp

private val NAV_LABELS = SECTION_TITLES + (Section.WORLD to R.string.nav_world)

private fun Section.icon(): ImageVector = when (this) {
    Section.ALARMS -> AppIcons.Alarm
    Section.TIMERS -> AppIcons.Timer
    Section.TASKS -> AppIcons.TaskDone
    Section.CLOCK -> AppIcons.Clock
    Section.WORLD -> AppIcons.Globe
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot(request: SectionRequest) {
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    /** С какого раздела открыть настройки: с плашки надёжности — сразу «Разрешения и надёжность». */
    var settingsSection by rememberSaveable { mutableStateOf<Int?>(null) }
    var exceptionsOpen by rememberSaveable { mutableStateOf(false) }
    var statsOpen by rememberSaveable { mutableStateOf(false) }
    var alarmsDialog by rememberSaveable { mutableStateOf<AlarmsDialog?>(null) }
    val pager = rememberPagerState(initialPage = request.section.ordinal) { Section.entries.size }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val requestNotifications = rememberNotificationPermissionRequest()
    // Выполненное быстрое действие не повторяется после поворота и возврата на раздел.
    var handledQuick by rememberSaveable { mutableLongStateOf(0L) }
    val quick = request.quick?.takeIf { it.token != handledQuick }

    LaunchedEffect(request) {
        if (request.serial < 0) return@LaunchedEffect
        settingsOpen = false
        exceptionsOpen = false
        statsOpen = false
        pager.scrollToPage(request.section.ordinal)
    }

    // Первый запуск — три шага с разрешениями (D-029, п. 10); показывается один раз.
    val app = DpClockApp.graph(LocalContext.current).repository
    val onboarded by remember(app) { app.settings.map { it.onboardingDone } }.collectAsStateWithLifecycle(true)
    if (!onboarded) {
        OnboardingDialog(
            onXiaomiDone = { persistLater { app.updateSettings { it.copy(xiaomiHintsDone = true) } } },
            onDone = { persistLater { app.updateSettings { it.copy(onboardingDone = true) } } },
        )
    }

    if (settingsOpen) {
        BackHandler { settingsOpen = false }
        SettingsScreen(onBack = { settingsOpen = false }, initialSection = settingsSection)
        return
    }
    if (exceptionsOpen) {
        BackHandler { exceptionsOpen = false }
        ExceptionsScreen(onBack = { exceptionsOpen = false })
        return
    }
    if (statsOpen) {
        BackHandler { statsOpen = false }
        StatsScreen(onBack = { statsOpen = false })
        return
    }


    val section = Section.entries[pager.currentPage]
    val layout = rememberWindowLayout()
    val settings by DpClockApp.graph(LocalContext.current).repository.settings.collectAsStateWithLifecycle(null)
    fun open(item: Section) {
        scope.launch { pager.animateScrollToPage(item.ordinal) }
    }
    // Фон приложения (D-027): один на все разделы. За списками — едва заметно; «Часы» рисуют его
    // сами, ярко и с касанием, поверх этого — здесь он тогда не нужен и стоит.
    val background = settings?.appearance?.background ?: ClockBackground.NONE
    val backdrop = background.isShown(LocalContext.current)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (backdrop) AppBackdrop(background, active = section != Section.CLOCK)
        Row(Modifier.fillMaxSize()) {
            if (layout.rail) {
                // Разделы — по центру по высоте: телефон боком держат двумя руками, большим пальцем достать проще.
                NavigationRail(windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start)) {
                    Spacer(Modifier.weight(1f))
                    for (item in Section.entries) {
                        NavigationRailItem(
                            selected = pager.currentPage == item.ordinal,
                            onClick = { open(item) },
                            icon = { Icon(item.icon(), contentDescription = null) },
                            label = { FitText(stringResource(NAV_LABELS.getValue(item)), style = MaterialTheme.typography.labelMedium, minFontSize = NAV_MIN_FONT) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            Scaffold(
                // Отступ от края уже у боковой панели.
                modifier = if (layout.rail) Modifier.weight(1f).consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)) else Modifier.weight(1f),
                containerColor = if (backdrop) Color.Transparent else MaterialTheme.colorScheme.background,
                // На прозрачном каркасе цвет текста не выводится из фона — задать явно, как у фона темы.
                contentColor = MaterialTheme.colorScheme.onBackground,
                topBar = {
                    TopAppBar(
                        title = { TitleText(stringResource(SECTION_TITLES.getValue(section))) },
                        actions = {
                            if (section == Section.ALARMS) {
                                // Статистика (D-023, п. 7; выбор автора — кнопка в «Будильниках»).
                                IconButton(onClick = { statsOpen = true }) {
                                    Icon(AppIcons.Chart, contentDescription = stringResource(R.string.stats))
                                }
                                AlarmOrderMenu()
                                AlarmsMenu(openExceptions = { exceptionsOpen = true }, onDialog = { alarmsDialog = it })
                            }
                            IconButton(onClick = {
                                settingsSection = null
                                settingsOpen = true
                            }) {
                                Icon(AppIcons.Settings, contentDescription = stringResource(R.string.settings))
                            }
                        },
                    )
                },
                bottomBar = {
                    if (!layout.rail) {
                        NavigationBar {
                            for (item in Section.entries) {
                                NavigationBarItem(
                                    selected = pager.currentPage == item.ordinal,
                                    onClick = { open(item) },
                                    icon = { Icon(item.icon(), contentDescription = null) },
                                    label = { FitText(stringResource(NAV_LABELS.getValue(item)), style = MaterialTheme.typography.labelMedium, minFontSize = NAV_MIN_FONT) },
                                )
                            }
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                HorizontalPager(
                    state = pager,
                    modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                    beyondViewportPageCount = 1,
                    // С боковой панелью свайп не листает разделы: в двух колонках он мешал бы редактору.
                    userScrollEnabled = !layout.rail,
                ) { page ->
                    when (Section.entries[page]) {
                        Section.ALARMS -> AlarmsScreen(
                            snackbar,
                            requestNotifications,
                            openSettings = {
                                settingsSection = R.string.settings_reliability
                                settingsOpen = true
                            },
                            dialog = alarmsDialog,
                            onDialog = { alarmsDialog = it },
                            quick = quick?.takeIf { it.action == QuickAction.NEW_ALARM || it.action == QuickAction.EDIT_ALARM },
                            onQuickHandled = { handledQuick = it },
                            visible = pager.currentPage == Section.ALARMS.ordinal,
                        )
                        Section.TIMERS -> TimersSection(
                            requestNotifications,
                            quick = quick?.takeIf { it.action == QuickAction.SLEEP_TIMER },
                            onQuickHandled = { handledQuick = it },
                            request = request,
                        )
                        Section.TASKS -> TasksScreen(snackbar)
                        Section.CLOCK -> ClockScreen(visible = pager.currentPage == Section.CLOCK.ordinal, openTasks = { open(Section.TASKS) })
                        Section.WORLD -> WorldClockScreen(visible = pager.currentPage == Section.WORLD.ordinal && !pager.isScrollInProgress)
                    }
                }
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
