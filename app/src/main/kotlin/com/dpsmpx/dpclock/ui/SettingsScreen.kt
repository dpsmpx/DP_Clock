package com.dpsmpx.dpclock.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.Appearance
import com.dpsmpx.dpclock.model.ClockFont
import com.dpsmpx.dpclock.model.ClockStyle
import com.dpsmpx.dpclock.model.NoiseKind
import com.dpsmpx.dpclock.model.ThemeMode
import com.dpsmpx.dpclock.model.Bedtime
import com.dpsmpx.dpclock.model.BedtimeMode
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalStyle
import com.dpsmpx.dpclock.model.VolumeButtons
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.TimeText
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

    fun update(change: (Settings) -> Settings) = persistLater { repository.updateSettings(change) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.back)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item { Header(R.string.settings_alarms) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.snooze_interval)) },
                    supportingContent = { Text(stringResource(R.string.minutes_value, settings.snoozeMinutes)) },
                    modifier = Modifier.clickable { dialog = "snooze" },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.auto_stop)) },
                    supportingContent = { Text(autoStopText(settings.autoStopMinutes)) },
                    modifier = Modifier.clickable { dialog = "autoStop" },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.alarm_order)) },
                    supportingContent = { Text(stringResource(orderTitle(settings.alarmOrder))) },
                    modifier = Modifier.clickable { dialog = "order" },
                )
            }
            item { Header(R.string.settings_signal) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.signal_style)) },
                    supportingContent = { Text(stringResource(styleTitle(settings.signalStyle))) },
                    modifier = Modifier.clickable { dialog = "style" },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.volume_buttons)) },
                    supportingContent = { Text(stringResource(volumeButtonsTitle(settings.volumeButtons))) },
                    modifier = Modifier.clickable { dialog = "volume" },
                )
            }
            item { Header(R.string.settings_sleep) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.bedtime_reminder)) },
                    supportingContent = { Text(stringResource(bedtimeModeTitle(settings.bedtime.mode))) },
                    modifier = Modifier.clickable { dialog = "bedtimeMode" },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.sleep_goal)) },
                    supportingContent = { Text(sleepText(settings.bedtime.sleepMinutes)) },
                    modifier = Modifier.clickable { dialog = "sleep" },
                )
            }
            if (settings.bedtime.mode == BedtimeMode.SCHEDULE) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bedtime_at)) },
                        supportingContent = { Text(TimeText.time(context, settings.bedtime.hour, settings.bedtime.minute)) },
                        modifier = Modifier.clickable { dialog = "bedtimeTime" },
                    )
                }
                item {
                    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in orderedDays(locale)) {
                            val days = settings.bedtime.days
                            FilterChip(
                                selected = day in days,
                                onClick = { update { it.copy(bedtime = it.bedtime.copy(days = if (day in days) days - day else days + day)) } },
                                label = { Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)) },
                            )
                        }
                    }
                }
            }
            if (settings.bedtime.mode != BedtimeMode.OFF) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.bedtime_lead)) },
                        supportingContent = { Text(leadText(settings.bedtime.leadMinutes)) },
                        modifier = Modifier.clickable { dialog = "lead" },
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.night_dream)) },
                    supportingContent = { Text(stringResource(if (noDream) R.string.night_dream_missing else R.string.night_dream_text)) },
                    modifier = Modifier.clickable {
                        noDream = runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)) }.isFailure
                    },
                )
            }
            item { Header(R.string.settings_appearance) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.appearance_theme)) },
                    supportingContent = { Text(stringResource(themeTitle(settings.appearance.theme))) },
                    modifier = Modifier.clickable { dialog = "theme" },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.appearance_color)) },
                    supportingContent = { ColorLabel(settings.appearance.seedColor) },
                    modifier = Modifier.clickable { dialog = "color" },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.clock_style)) },
                    supportingContent = { Text(stringResource(clockStyleTitle(settings.appearance.clockStyle))) },
                    modifier = Modifier.clickable { dialog = "clockStyle" },
                )
            }
            if (settings.appearance.clockStyle == ClockStyle.DIGITS) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.clock_font)) },
                        supportingContent = { Text(stringResource(clockFontTitle(settings.appearance.clockFont))) },
                        modifier = Modifier.clickable { dialog = "clockFont" },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.clock_size)) },
                        supportingContent = { Text(stringResource(R.string.percent_value, settings.appearance.clockSize)) },
                        modifier = Modifier.clickable { dialog = "clockSize" },
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.night_noise)) },
                    supportingContent = { Text(stringResource(noiseTitle(settings.appearance.nightNoise))) },
                    modifier = Modifier.clickable { dialog = "noise" },
                )
            }
            item { Header(R.string.settings_world) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.weather_title)) },
                    supportingContent = { Text(weatherModeText(settings.weather.mode, settings.weather.place)) },
                    modifier = Modifier.clickable { weatherChoice.openOptions() },
                )
            }
            item { Header(R.string.settings_reliability) }
            item { PermissionRow(Issue.NOTIFICATIONS, reliability.notifications) }
            item { PermissionRow(Issue.FULL_SCREEN, reliability.fullScreen) }
            item { PermissionRow(Issue.EXACT_ALARMS, reliability.exactAlarms) }
            item {
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
            }
            if (reliability.dndBlocksAlarms) item { PermissionRow(Issue.DND, granted = false) }
            if (reliability.isXiaomi) {
                item {
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
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.journal)) },
                    supportingContent = { Text(stringResource(R.string.journal_text)) },
                    modifier = Modifier.clickable { dialog = "journal" },
                )
            }
            item { Header(R.string.settings_transfer) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_save)) },
                    supportingContent = { Text(stringResource(R.string.backup_save_text)) },
                    modifier = Modifier.clickable { backup.save() },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.backup_load)) },
                    supportingContent = { Text(stringResource(R.string.backup_load_text)) },
                    modifier = Modifier.clickable { backup.load() },
                )
            }
            item { Header(R.string.about) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                    supportingContent = { Text(stringResource(R.string.version_value, BuildConfig.VERSION_NAME)) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.licenses)) },
                    supportingContent = { Text(stringResource(R.string.licenses_text)) },
                )
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

@Composable
private fun Header(text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
    )
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
        title = { Text(title) },
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
