package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.days
import com.dpsmpx.dpclock.model.isRepeating
import com.dpsmpx.dpclock.platform.TimeText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle

private const val DAY_MILLIS = 86_400_000L

/**
 * Редактор будильника (SCREEN_MAP.md, п. 2; D-013): время, повтор по дням или
 * дата, название; в «Дополнительно» — мелодия, вибрация, нарастание, свой
 * интервал «Отложить», пропуск ближайшего срабатывания.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditor(
    alarm: Alarm,
    settings: Settings,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()

    var hour by rememberSaveable { mutableIntStateOf(alarm.hour) }
    var minute by rememberSaveable { mutableIntStateOf(alarm.minute) }
    var byDate by rememberSaveable { mutableStateOf(alarm.repeat is Repeat.OnDate) }
    var days by remember { mutableStateOf(alarm.repeat.days) }
    var dateDay by rememberSaveable { mutableStateOf((alarm.repeat as? Repeat.OnDate)?.date?.toEpochDay()) }
    var label by rememberSaveable { mutableStateOf(alarm.label) }
    var sound by remember { mutableStateOf(alarm.sound) }
    var vibrate by rememberSaveable { mutableStateOf(alarm.vibrate) }
    var rampSeconds by rememberSaveable { mutableIntStateOf(alarm.rampSeconds) }
    var snoozeMinutes by rememberSaveable { mutableStateOf(alarm.snoozeMinutes) }
    var skipNext by rememberSaveable { mutableStateOf(alarm.skipDate != null) }
    var advanced by rememberSaveable { mutableStateOf(hasAdvanced(alarm)) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    val repeat: Repeat? = if (byDate) dateDay?.let { Repeat.OnDate(LocalDate.ofEpochDay(it)) } else Repeat.of(days)
    val draft = repeat?.let { r ->
        val base = alarm.copy(
            hour = hour,
            minute = minute,
            repeat = r,
            label = label.trim(),
            sound = sound,
            vibrate = vibrate,
            rampSeconds = rampSeconds,
            snoozeMinutes = snoozeMinutes,
            skipDate = null,
        )
        val now = Instant.now()
        // Пропуск — дата ближайшего срабатывания по новым правилам (D-013, п. 3).
        val skip = if (skipNext && r.isRepeating) AlarmRules.nextIgnoringSkip(base, now, zone)?.atZone(zone)?.toLocalDate() else null
        base.copy(skipDate = skip)
    }
    val next = draft?.let { AlarmRules.nextTrigger(it, Instant.now(), zone) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.edit_alarm)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = {
                        TextButton(enabled = next != null, onClick = { draft?.let(onSave) }) { Text(stringResource(R.string.save)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(onClick = { dialog = "time" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text(TimeText.time(context, hour, minute), style = MaterialTheme.typography.displayLarge)
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(Alarm.MAX_LABEL) },
                    label = { Text(stringResource(R.string.alarm_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.repeat), style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !byDate,
                        onClick = { byDate = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                    ) { Text(stringResource(R.string.repeat_by_days)) }
                    SegmentedButton(
                        selected = byDate,
                        onClick = {
                            byDate = true
                            if (dateDay == null) dialog = "date"
                        },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                    ) { Text(stringResource(R.string.repeat_on_date)) }
                }
                if (byDate) {
                    OutlinedButton(onClick = { dialog = "date" }) {
                        Text(dateDay?.let { TimeText.date(context, LocalDate.ofEpochDay(it), withYear = true) } ?: stringResource(R.string.pick_date))
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in orderedDays(locale)) {
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)) },
                            )
                        }
                    }
                }
                Text(
                    when {
                        repeat == null -> stringResource(R.string.pick_date)
                        next == null -> stringResource(R.string.date_passed)
                        else -> repeatText(context, repeat) + " · " + stringResource(R.string.rings_in, untilText(resources, next.toEpochMilli() - System.currentTimeMillis()))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (repeat != null && next == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.advanced)) },
                    trailingContent = { Icon(if (advanced) AppIcons.Up else AppIcons.Down, contentDescription = null) },
                    modifier = Modifier.clickable { advanced = !advanced },
                )
                if (advanced) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.alarm_sound)) },
                        supportingContent = { Text(soundName(sound)) },
                        modifier = Modifier.clickable { dialog = "sound" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.vibration)) },
                        trailingContent = { Switch(checked = vibrate, onCheckedChange = { vibrate = it }) },
                        modifier = Modifier.clickable { vibrate = !vibrate },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.volume_ramp)) },
                        supportingContent = { Text(rampText(rampSeconds)) },
                        modifier = Modifier.clickable { dialog = "ramp" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.snooze_interval)) },
                        supportingContent = { Text(snoozeText(snoozeMinutes, settings.snoozeMinutes)) },
                        modifier = Modifier.clickable { dialog = "snooze" },
                    )
                    if (repeat?.isRepeating == true) {
                        val skippable = draft?.let { AlarmRules.nextIgnoringSkip(it, Instant.now(), zone) }?.atZone(zone)?.toLocalDate()
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.skip_next)) },
                            supportingContent = skippable?.let { { Text(TimeText.date(context, it)) } },
                            trailingContent = { Switch(checked = skipNext, onCheckedChange = { skipNext = it }) },
                            modifier = Modifier.clickable { skipNext = !skipNext },
                        )
                    }
                    if (sound == AlarmSound.Silent && !vibrate) {
                        Text(
                            stringResource(R.string.unnoticeable),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                HorizontalDivider()
                TextButton(onClick = onDelete) {
                    Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.delete))
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }

    when (dialog) {
        "time" -> TimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { dialog = null },
            onConfirm = { h, m ->
                hour = h
                minute = m
                dialog = null
            },
        )
        "date" -> {
            val today = LocalDate.now(zone).toEpochDay()
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (dateDay ?: today) * DAY_MILLIS,
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / DAY_MILLIS >= today
                },
            )
            DatePickerDialog(
                onDismissRequest = {
                    dialog = null
                    if (dateDay == null) byDate = false
                },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { dateDay = it / DAY_MILLIS }
                        dialog = null
                    }) { Text(stringResource(R.string.done)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        dialog = null
                        if (dateDay == null) byDate = false
                    }) { Text(stringResource(R.string.cancel)) }
                },
            ) { DatePicker(state) }
        }
        "sound" -> SoundPicker(
            initial = sound,
            onDismiss = { dialog = null },
            onPick = {
                sound = it
                dialog = null
            },
        )
        "ramp" -> ChoiceDialog(
            title = stringResource(R.string.volume_ramp),
            choices = Settings.RAMP_CHOICES,
            selected = rampSeconds,
            label = { rampText(it) },
            onDismiss = { dialog = null },
            onSelect = { rampSeconds = it },
        )
        "snooze" -> ChoiceDialog(
            title = stringResource(R.string.snooze_interval),
            choices = listOf<Int?>(null) + Settings.SNOOZE_CHOICES,
            selected = snoozeMinutes,
            label = { snoozeText(it, settings.snoozeMinutes) },
            onDismiss = { dialog = null },
            onSelect = { snoozeMinutes = it },
        )
    }
}

/** Блок «Дополнительно» раскрыт сразу, если в нём уже что-то изменено. */
private fun hasAdvanced(alarm: Alarm): Boolean =
    alarm.sound != AlarmSound.Default || !alarm.vibrate || alarm.rampSeconds > 0 || alarm.snoozeMinutes != null || alarm.skipDate != null

@Composable
fun rampText(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.ramp_off)
    seconds < 60 -> stringResource(R.string.seconds_value, seconds)
    else -> stringResource(R.string.minutes_value, seconds / 60)
}

@Composable
private fun snoozeText(minutes: Int?, common: Int): String =
    if (minutes == null) stringResource(R.string.snooze_common, common) else stringResource(R.string.minutes_value, minutes)
