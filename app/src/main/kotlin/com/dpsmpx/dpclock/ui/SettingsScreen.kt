package com.dpsmpx.dpclock.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.Appearance
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.ClockFont
import com.dpsmpx.dpclock.model.ClockStyle
import com.dpsmpx.dpclock.model.NoiseKind
import com.dpsmpx.dpclock.model.ThemeMode
import com.dpsmpx.dpclock.model.Bedtime
import com.dpsmpx.dpclock.model.Scenes
import com.dpsmpx.dpclock.model.CalendarSettings
import com.dpsmpx.dpclock.model.ChimeEvery
import com.dpsmpx.dpclock.model.Chimes
import com.dpsmpx.dpclock.model.BedtimeMode
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalStyle
import com.dpsmpx.dpclock.model.VolumeButtons
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.CalendarSync
import com.dpsmpx.dpclock.platform.ClockWallpaper
import com.dpsmpx.dpclock.platform.TimeText
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.TextStyle

/** Настройки MVP (SCREEN_MAP.md, п. 9). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    val reliability = rememberReliability()
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }
    var noDream by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val snackbar = remember { SnackbarHostState() }
    val backup = rememberBackupActions(snackbar)
    val weatherChoice = rememberWeatherChoice()
    val calendarStore = DpClockApp.graph(context).calendar
    val calendarSettings by calendarStore.settings.collectAsStateWithLifecycle()
    val calendarAccess = rememberCalendarAccess()
    val phoneCalendars = rememberPhoneCalendars(calendarAccess.granted)

    /** Календарь телефона (D-023): настройки — в хранилище устройства, потом перечитать календарь. */
    fun updateCalendar(change: (CalendarSettings) -> CalendarSettings) {
        calendarStore.update(change)
        val app = context.applicationContext
        persistLater { CalendarSync.refresh(app) }
    }

    fun update(change: (Settings) -> Settings) = persistLater { repository.updateSettings(change) }

    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            if (saveBackground(context, uri)) {
                update { it.copy(appearance = it.appearance.copy(background = ClockBackground.IMAGE)) }
            } else {
                snackbar.showSnackbar(resources.getString(R.string.background_failed))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TitleText(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Разделы настроек; на широком окне — в две колонки (D-022, п. 2).
        val sections = listOf(
            SettingsSection(R.string.settings_alarms) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.snooze_interval)) },
                    supportingContent = { Text(stringResource(R.string.minutes_value, settings.snoozeMinutes)) },
                    modifier = Modifier.clickable { dialog = "snooze" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.auto_stop)) },
                    supportingContent = { Text(autoStopText(settings.autoStopMinutes)) },
                    modifier = Modifier.clickable { dialog = "autoStop" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alarm_order)) },
                    supportingContent = { Text(stringResource(orderTitle(settings.alarmOrder))) },
                    modifier = Modifier.clickable { dialog = "order" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alarm_countdown)) },
                    supportingContent = { Text(stringResource(R.string.alarm_countdown_text)) },
                    trailingContent = { Switch(checked = settings.showAlarmCountdown, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.showAlarmCountdown, role = Role.Switch) { show ->
                        update { it.copy(showAlarmCountdown = show) }
                    },
                )
                // Полная дата и предупреждения до сигнала (D-025, п. 2, 5).
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alarm_date)) },
                    supportingContent = { Text(stringResource(R.string.alarm_date_text)) },
                    trailingContent = { Switch(checked = settings.showAlarmDate, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.showAlarmDate, role = Role.Switch) { show ->
                        update { it.copy(showAlarmDate = show) }
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.warnings_default)) },
                    supportingContent = { Text(warningsText(context, settings.defaultWarnings)) },
                    modifier = Modifier.clickable { dialog = "defaultWarnings" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.warning_sound)) },
                    supportingContent = { Text(stringResource(R.string.warning_sound_text)) },
                    trailingContent = { Switch(checked = settings.warningSound, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.warningSound, role = Role.Switch) { on ->
                        update { it.copy(warningSound = on) }
                    },
                )
            },
            SettingsSection(R.string.settings_chimes) {
                val chimes = settings.chimes
                fun updateChimes(change: (Chimes) -> Chimes) = update { it.copy(chimes = change(it.chimes)) }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chimes)) },
                    supportingContent = { Text(stringResource(chimeEveryTitle(chimes.every))) },
                    modifier = Modifier.clickable { dialog = "chimeEvery" },
                )
                if (chimes.every != ChimeEvery.OFF) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chimes_vibrate)) },
                        supportingContent = { Text(stringResource(R.string.chimes_vibrate_text)) },
                        trailingContent = { Switch(checked = chimes.vibrate, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = chimes.vibrate, role = Role.Switch) { on -> updateChimes { it.copy(vibrate = on) } },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chimes_count)) },
                        supportingContent = { Text(stringResource(R.string.chimes_count_text)) },
                        trailingContent = { Switch(checked = chimes.countHours, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = chimes.countHours, role = Role.Switch) { on -> updateChimes { it.copy(countHours = on) } },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.chimes_quiet)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.chimes_quiet_text,
                                    TimeText.time(context, chimes.quietFrom.hour, chimes.quietFrom.minute),
                                    TimeText.time(context, chimes.quietTo.hour, chimes.quietTo.minute),
                                ),
                            )
                        },
                        trailingContent = { Switch(checked = chimes.quiet, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = chimes.quiet, role = Role.Switch) { on -> updateChimes { it.copy(quiet = on) } },
                    )
                    if (chimes.quiet) {
                        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { dialog = "chimeFrom" }) {
                                Text(stringResource(R.string.rules_window_from, TimeText.time(context, chimes.quietFrom.hour, chimes.quietFrom.minute)))
                            }
                            OutlinedButton(onClick = { dialog = "chimeTo" }) {
                                Text(stringResource(R.string.rules_window_to, TimeText.time(context, chimes.quietTo.hour, chimes.quietTo.minute)))
                            }
                        }
                    }
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        DayChips(chimes.days) { day -> updateChimes { it.copy(days = if (day in it.days) it.days - day else it.days + day) } }
                    }
                }
            },
            SettingsSection(R.string.settings_calendar) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.calendar_exceptions)) },
                    supportingContent = {
                        Text(calendarNames(calendarSettings.exceptionCalendars, phoneCalendars, stringResource(R.string.calendar_exceptions_none)))
                    },
                    modifier = Modifier.clickable { calendarAccess.request { dialog = "calendarExceptions" } },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.calendar_events)) },
                    supportingContent = {
                        Text(calendarNames(calendarSettings.eventCalendars, phoneCalendars, stringResource(R.string.calendar_events_none)))
                    },
                    modifier = Modifier.clickable { calendarAccess.request { dialog = "calendarEvents" } },
                )
                if (calendarSettings.eventCalendars.isNotEmpty()) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.calendar_early)) },
                        supportingContent = { Text(earlyLeadText(calendarSettings.earlyLeadMinutes)) },
                        modifier = Modifier.clickable { dialog = "calendarLead" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.calendar_show_next)) },
                        supportingContent = { Text(stringResource(R.string.calendar_show_next_text)) },
                        trailingContent = { Switch(checked = calendarSettings.showNext, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = calendarSettings.showNext, role = Role.Switch) { on ->
                            updateCalendar { it.copy(showNext = on) }
                        },
                    )
                }
                // Доступ отняли в настройках Android — исключения и события не читаются.
                if (calendarSettings.isEnabled && !calendarAccess.granted) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.calendar_no_access)) },
                        supportingContent = { Text(stringResource(R.string.calendar_no_access_text)) },
                        trailingContent = { TextButton(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.fix)) } },
                    )
                }
            },
            SettingsSection(R.string.settings_signal) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.signal_style)) },
                    supportingContent = { Text(stringResource(styleTitle(settings.signalStyle))) },
                    modifier = Modifier.clickable { dialog = "style" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.volume_buttons)) },
                    supportingContent = { Text(stringResource(volumeButtonsTitle(settings.volumeButtons))) },
                    modifier = Modifier.clickable { dialog = "volume" },
                )
            },
            SettingsSection(R.string.settings_sleep) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bedtime_reminder)) },
                    supportingContent = { Text(stringResource(bedtimeModeTitle(settings.bedtime.mode))) },
                    modifier = Modifier.clickable { dialog = "bedtimeMode" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sleep_goal)) },
                    supportingContent = { Text(sleepText(settings.bedtime.sleepMinutes)) },
                    modifier = Modifier.clickable { dialog = "sleep" },
                )
                if (settings.bedtime.mode == BedtimeMode.SCHEDULE) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bedtime_at)) },
                        supportingContent = { Text(TimeText.time(context, settings.bedtime.hour, settings.bedtime.minute)) },
                        modifier = Modifier.clickable { dialog = "bedtimeTime" },
                    )
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in orderedDays(locale)) {
                            val days = settings.bedtime.days
                            FilterChip(
                                selected = day in days,
                                onClick = { update { it.copy(bedtime = it.bedtime.copy(days = if (day in days) days - day else days + day)) } },
                                label = { Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)) },
                                // TalkBack читает день целиком, а не одну букву.
                                modifier = Modifier.semantics { contentDescription = day.getDisplayName(TextStyle.FULL_STANDALONE, locale) },
                            )
                        }
                    }
                }
                if (settings.bedtime.mode != BedtimeMode.OFF) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bedtime_lead)) },
                        supportingContent = { Text(leadText(settings.bedtime.leadMinutes)) },
                        modifier = Modifier.clickable { dialog = "lead" },
                    )
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.night_dream)) },
                    supportingContent = { Text(stringResource(if (noDream) R.string.night_dream_missing else R.string.night_dream_text)) },
                    modifier = Modifier.clickable {
                        noDream = runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }.isFailure
                    },
                )
            },
            SettingsSection(R.string.settings_appearance) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.appearance_theme)) },
                    supportingContent = { Text(stringResource(themeTitle(settings.appearance.theme))) },
                    modifier = Modifier.clickable { dialog = "theme" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.appearance_color)) },
                    supportingContent = { ColorLabel(settings.appearance.seedColor) },
                    modifier = Modifier.clickable { dialog = "color" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.high_contrast)) },
                    supportingContent = { Text(stringResource(R.string.high_contrast_text)) },
                    trailingContent = { Switch(checked = settings.appearance.highContrast, onCheckedChange = null) },
                    modifier = Modifier.toggleable(value = settings.appearance.highContrast, role = Role.Switch) { on ->
                        update { it.copy(appearance = it.appearance.copy(highContrast = on)) }
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.clock_style)) },
                    supportingContent = { Text(stringResource(clockStyleTitle(settings.appearance.clockStyle))) },
                    modifier = Modifier.clickable { dialog = "clockStyle" },
                )
                if (settings.appearance.clockStyle == ClockStyle.DIGITS) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.clock_font)) },
                        supportingContent = { Text(stringResource(clockFontTitle(settings.appearance.clockFont))) },
                        modifier = Modifier.clickable { dialog = "clockFont" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.clock_size)) },
                        supportingContent = { Text(stringResource(R.string.percent_value, settings.appearance.clockSize)) },
                        modifier = Modifier.clickable { dialog = "clockSize" },
                    )
                }
                // Один фон на всё приложение (D-027).
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_background)) },
                    supportingContent = {
                        Column {
                            Text(stringResource(backgroundTitle(settings.appearance.background)))
                            Text(stringResource(R.string.app_background_text), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    modifier = Modifier.clickable { dialog = "background" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.wallpaper)) },
                    supportingContent = { Text(stringResource(backgroundTitle(settings.appearance.wallpaper))) },
                    trailingContent = {
                        TextButton(onClick = { context.setLiveWallpaper() }) { Text(stringResource(R.string.wallpaper_set)) }
                    },
                    modifier = Modifier.clickable { dialog = "wallpaper" },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.night_noise)) },
                    supportingContent = { Text(stringResource(noiseTitle(settings.appearance.nightNoise))) },
                    modifier = Modifier.clickable { dialog = "noise" },
                )
            },
            SettingsSection(R.string.settings_world) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.weather_title)) },
                    supportingContent = { Text(weatherModeText(settings.weather.mode, settings.weather.place)) },
                    modifier = Modifier.clickable { weatherChoice.openOptions() },
                )
            },
            SettingsSection(R.string.settings_reliability) {
                PermissionRow(Issue.NOTIFICATIONS, reliability.notifications)
                PermissionRow(Issue.FULL_SCREEN, reliability.fullScreen)
                PermissionRow(Issue.EXACT_ALARMS, reliability.exactAlarms)
                val issue = if (reliability.volumeOff) Issue.VOLUME_OFF else Issue.VOLUME_LOW
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alarm_volume)) },
                    supportingContent = {
                        Text(
                            if (reliability.volumeOff || reliability.volumeLow) stringResource(issue.text)
                            else stringResource(R.string.volume_value, reliability.alarmVolume, reliability.alarmVolumeMax),
                        )
                    },
                    trailingContent = {
                        if (reliability.volumeOff || reliability.volumeLow) {
                            TextButton(onClick = { context.openFix(issue) }) { Text(stringResource(R.string.fix)) }
                        }
                    },
                )
                if (reliability.dndBlocksAlarms) PermissionRow(Issue.DND, granted = false)
                if (reliability.isXiaomi) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(stringResource(R.string.xiaomi_title), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.xiaomi_hints), style = MaterialTheme.typography.bodyMedium)
                        Row {
                            FilledTonalButton(onClick = { context.openFix(Issue.XIAOMI) }) {
                                Text(stringResource(R.string.open_app_settings))
                            }
                            TextButton(onClick = { update { it.copy(xiaomiHintsDone = !it.xiaomiHintsDone) } }) {
                                Text(stringResource(if (settings.xiaomiHintsDone) R.string.xiaomi_not_done else R.string.xiaomi_done))
                            }
                        }
                    }
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.journal)) },
                    supportingContent = { Text(stringResource(R.string.journal_text)) },
                    modifier = Modifier.clickable { dialog = "journal" },
                )
            },
            SettingsSection(R.string.settings_transfer) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_save)) },
                    supportingContent = { Text(stringResource(R.string.backup_save_text)) },
                    modifier = Modifier.clickable { backup.save() },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_load)) },
                    supportingContent = { Text(stringResource(R.string.backup_load_text)) },
                    modifier = Modifier.clickable { backup.load() },
                )
            },
            SettingsSection(R.string.about) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.version_value, BuildConfig.VERSION_NAME)) },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.licenses)) },
                    supportingContent = { Text(stringResource(R.string.licenses_text)) },
                )
            },
        )
        if (rememberWindowLayout().twoPane) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
            ) {
                items(sections.size) { index -> sections[index].Show() }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
                items(sections.size) { index -> sections[index].Show() }
            }
        }
    }

    when (dialog) {
        "snooze" -> ChoiceDialog(
            title = stringResource(R.string.snooze_interval),
            choices = Settings.SNOOZE_CHOICES,
            selected = settings.snoozeMinutes,
            label = { stringResource(R.string.minutes_value, it) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(snoozeMinutes = value) } },
        )
        "autoStop" -> ChoiceDialog(
            title = stringResource(R.string.auto_stop),
            choices = Settings.AUTO_STOP_CHOICES,
            selected = settings.autoStopMinutes,
            label = { autoStopText(it) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(autoStopMinutes = value) } },
        )
        "order" -> ChoiceDialog(
            title = stringResource(R.string.alarm_order),
            choices = AlarmOrder.entries,
            selected = settings.alarmOrder,
            label = { stringResource(orderTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(alarmOrder = value) } },
        )
        "style" -> ChoiceDialog(
            title = stringResource(R.string.signal_style),
            choices = SignalStyle.entries,
            selected = settings.signalStyle,
            label = { stringResource(styleTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(signalStyle = value) } },
        )
        "bedtimeMode" -> ChoiceDialog(
            title = stringResource(R.string.bedtime_reminder),
            choices = BedtimeMode.entries,
            selected = settings.bedtime.mode,
            label = { stringResource(bedtimeModeTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(bedtime = it.bedtime.copy(mode = value)) } },
        )
        "sleep" -> ChoiceDialog(
            title = stringResource(R.string.sleep_goal),
            choices = Bedtime.SLEEP_CHOICES,
            selected = settings.bedtime.sleepMinutes,
            label = { sleepText(it) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(bedtime = it.bedtime.copy(sleepMinutes = value)) } },
        )
        "lead" -> ChoiceDialog(
            title = stringResource(R.string.bedtime_lead),
            choices = Bedtime.LEAD_CHOICES,
            selected = settings.bedtime.leadMinutes,
            label = { leadText(it) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(bedtime = it.bedtime.copy(leadMinutes = value)) } },
        )
        "bedtimeTime" -> TimePickerDialog(
            initialHour = settings.bedtime.hour,
            initialMinute = settings.bedtime.minute,
            onDismiss = { dialog = null },
            onConfirm = { h, m ->
                dialog = null
                update { it.copy(bedtime = it.bedtime.copy(hour = h, minute = m)) }
            },
        )
        "journal" -> JournalDialog(onDismiss = { dialog = null })
        "defaultWarnings" -> WarningsDialog(
            title = stringResource(R.string.warnings_default),
            initial = settings.defaultWarnings,
            onDismiss = { dialog = null },
            onDone = { list ->
                dialog = null
                update { it.copy(defaultWarnings = list) }
            },
        )
        "chimeEvery" -> ChoiceDialog(
            title = stringResource(R.string.chimes),
            choices = ChimeEvery.entries,
            selected = settings.chimes.every,
            label = { stringResource(chimeEveryTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(chimes = it.chimes.copy(every = value)) } },
        )
        "chimeFrom", "chimeTo" -> {
            val from = dialog == "chimeFrom"
            val time = if (from) settings.chimes.quietFrom else settings.chimes.quietTo
            TimePickerDialog(
                initialHour = time.hour,
                initialMinute = time.minute,
                onDismiss = { dialog = null },
                onConfirm = { h, m ->
                    dialog = null
                    val picked = LocalTime.of(h, m)
                    update { it.copy(chimes = if (from) it.chimes.copy(quietFrom = picked) else it.chimes.copy(quietTo = picked)) }
                },
            )
        }
        "calendarExceptions" -> CalendarChooserDialog(
            title = stringResource(R.string.calendar_exceptions),
            calendars = phoneCalendars,
            selected = calendarSettings.exceptionCalendars,
            onDismiss = { dialog = null },
            onSave = { ids ->
                dialog = null
                updateCalendar { it.copy(exceptionCalendars = ids) }
            },
        )
        "calendarEvents" -> CalendarChooserDialog(
            title = stringResource(R.string.calendar_events),
            calendars = phoneCalendars,
            selected = calendarSettings.eventCalendars,
            onDismiss = { dialog = null },
            onSave = { ids ->
                dialog = null
                updateCalendar { it.copy(eventCalendars = ids) }
            },
        )
        "calendarLead" -> ChoiceDialog(
            title = stringResource(R.string.calendar_early),
            choices = CalendarSettings.LEAD_CHOICES,
            selected = calendarSettings.earlyLeadMinutes,
            label = { earlyLeadText(it) },
            onDismiss = { dialog = null },
            onSelect = { value -> updateCalendar { it.copy(earlyLeadMinutes = value) } },
        )
        "theme" -> ChoiceDialog(
            title = stringResource(R.string.appearance_theme),
            choices = ThemeMode.entries,
            selected = settings.appearance.theme,
            label = { stringResource(themeTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(appearance = it.appearance.copy(theme = value)) } },
        )
        "color" -> ColorDialog(
            current = settings.appearance.seedColor,
            onDismiss = { dialog = null },
            onPick = { value ->
                dialog = null
                update { it.copy(appearance = it.appearance.copy(seedColor = value)) }
            },
        )
        "clockStyle" -> ChoiceDialog(
            title = stringResource(R.string.clock_style),
            choices = ClockStyle.entries,
            selected = settings.appearance.clockStyle,
            label = { stringResource(clockStyleTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(appearance = it.appearance.copy(clockStyle = value)) } },
        )
        "clockFont" -> ChoiceDialog(
            title = stringResource(R.string.clock_font),
            choices = ClockFont.entries,
            selected = settings.appearance.clockFont,
            label = { stringResource(clockFontTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(appearance = it.appearance.copy(clockFont = value)) } },
        )
        "clockSize" -> ChoiceDialog(
            title = stringResource(R.string.clock_size),
            choices = Appearance.SIZE_CHOICES,
            selected = settings.appearance.clockSize,
            label = { stringResource(R.string.percent_value, it) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(appearance = it.appearance.copy(clockSize = value)) } },
        )
        "background" -> ChoiceDialog(
            title = stringResource(R.string.app_background),
            choices = ClockBackground.entries,
            selected = settings.appearance.background,
            label = { stringResource(backgroundTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value ->
                // Своя картинка — сначала выбрать её; фон меняется, когда она сохранена.
                if (value == ClockBackground.IMAGE) {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                } else {
                    update { it.copy(appearance = it.appearance.copy(background = value)) }
                }
            },
        )
        "wallpaper" -> ChoiceDialog(
            title = stringResource(R.string.wallpaper),
            choices = Scenes.WALLPAPERS,
            selected = settings.appearance.wallpaper,
            label = { stringResource(backgroundTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(appearance = it.appearance.copy(wallpaper = value)) } },
        )
        "noise" -> ChoiceDialog(
            title = stringResource(R.string.night_noise),
            choices = NoiseKind.entries,
            selected = settings.appearance.nightNoise,
            label = { stringResource(noiseTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(appearance = it.appearance.copy(nightNoise = value)) } },
        )
        "volume" -> ChoiceDialog(
            title = stringResource(R.string.volume_buttons),
            choices = VolumeButtons.entries,
            selected = settings.volumeButtons,
            label = { stringResource(volumeButtonsTitle(it)) },
            onDismiss = { dialog = null },
            onSelect = { value -> update { it.copy(volumeButtons = value) } },
        )
    }
}

/** Раздел настроек: заголовок — для TalkBack заголовок (D-022, п. 3), под ним строки. */
private class SettingsSection(val title: Int, val content: @Composable () -> Unit) {
    @Composable
    fun Show() {
        Column {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
            )
            content()
        }
    }
}

@Composable
private fun PermissionRow(issue: Issue, granted: Boolean) {
    val context = LocalContext.current
    ListItem(
        headlineContent = { Text(stringResource(issue.title)) },
        supportingContent = { Text(stringResource(if (granted) R.string.permission_ok else issue.text)) },
        trailingContent = {
            if (!granted) TextButton(onClick = { context.openFix(issue) }) { Text(stringResource(R.string.fix)) }
        },
    )
}

/** «За 1 ч 30 мин до событий до 12:00» или «Выключено». */
@Composable
private fun earlyLeadText(minutes: Int): String =
    if (minutes == 0) {
        stringResource(R.string.calendar_early_off)
    } else {
        stringResource(
            R.string.calendar_early_value,
            durationText(LocalResources.current, minutes * 60_000L),
            TimeText.time(LocalContext.current, CalendarSettings.EARLY_BEFORE.hour, CalendarSettings.EARLY_BEFORE.minute),
        )
    }

@Composable
private fun autoStopText(minutes: Int?): String =
    if (minutes == null) stringResource(R.string.never) else stringResource(R.string.minutes_value, minutes)

private fun bedtimeModeTitle(mode: BedtimeMode): Int = when (mode) {
    BedtimeMode.OFF -> R.string.ramp_off
    BedtimeMode.FROM_ALARM -> R.string.bedtime_from_alarm
    BedtimeMode.SCHEDULE -> R.string.bedtime_schedule
}

@Composable
fun sleepText(minutes: Int): String =
    if (minutes % 60 == 0) stringResource(R.string.hours_value, minutes / 60)
    else stringResource(R.string.hours_minutes_value, minutes / 60, minutes % 60)

@Composable
private fun leadText(minutes: Int): String =
    if (minutes == 0) stringResource(R.string.bedtime_lead_none) else stringResource(R.string.bedtime_lead_value, minutes)

private fun styleTitle(style: SignalStyle): Int = when (style) {
    SignalStyle.FULL_SCREEN -> R.string.signal_style_full_screen
    SignalStyle.NOTIFICATION -> R.string.signal_style_notification
}

private fun volumeButtonsTitle(action: VolumeButtons): Int = when (action) {
    VolumeButtons.SNOOZE -> R.string.volume_buttons_snooze
    VolumeButtons.DISMISS -> R.string.volume_buttons_dismiss
    VolumeButtons.NOTHING -> R.string.volume_buttons_nothing
}

fun orderTitle(order: AlarmOrder): Int = when (order) {
    AlarmOrder.TIME_OF_DAY -> R.string.order_time_of_day
    AlarmOrder.NEXT -> R.string.order_next
    AlarmOrder.CREATED -> R.string.order_created
}

/** Выбор одного значения из списка. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onDismiss: () -> Unit,
    onSelect: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { TitleText(title) },
        text = {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(choices.size) { index ->
                    val choice = choices[index]
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onSelect(choice)
                            onDismiss()
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = choice == selected, onClick = null)
                        Text(label(choice), modifier = Modifier.padding(16.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Поставить живые обои DP Clock (D-024, п. 2): системное окно сразу с ними;
 * у оболочек без него — общий выбор живых обоев.
 */
private fun Context.setLiveWallpaper() {
    val component = ComponentName(this, ClockWallpaper::class.java)
    runCatching {
        startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
    }.recoverCatching {
        startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
    }
}

private fun chimeEveryTitle(every: ChimeEvery): Int = when (every) {
    ChimeEvery.OFF -> R.string.chimes_off
    ChimeEvery.HOUR -> R.string.chimes_hour
    ChimeEvery.HALF_HOUR -> R.string.chimes_half_hour
    ChimeEvery.QUARTER -> R.string.chimes_quarter
}
