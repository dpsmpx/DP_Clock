package com.dpsmpx.dpclock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.CyclePreset
import com.dpsmpx.dpclock.model.Exceptions
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.RuCalendar
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SleepRules
import com.dpsmpx.dpclock.model.days
import com.dpsmpx.dpclock.model.isRepeating
import com.dpsmpx.dpclock.platform.TimeText
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

private const val DAY_MILLIS = 86_400_000L

/** Время подъёма перед ночной сменой по умолчанию — правится в редакторе. */
private val NIGHT_DEFAULT: LocalTime = LocalTime.of(17, 0)

/** Вид повтора в редакторе (D-013, D-014). */
private enum class RepeatMode { DAYS, WORKDAYS, CYCLE, DATE }

/**
 * Редактор будильника (SCREEN_MAP.md, п. 2; D-013, D-014): время, название,
 * повтор — дни недели, рабочие дни, сменный график или дата; в «Дополнительно» —
 * мелодия, вибрация, нарастание, «Отложить», пропуск, группа, исключения.
 * [inline] — в правой колонке рядом со списком (D-022, п. 2), иначе во весь экран;
 * [handleBack] — «Назад» закрывает его в колонке, пока раздел на экране.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlarmEditor(
    alarm: Alarm,
    settings: Settings,
    groups: List<AlarmGroup>,
    exceptions: Exceptions,
    onDismiss: () -> Unit,
    onSave: (Alarm) -> Unit,
    onDelete: () -> Unit,
    inline: Boolean = false,
    handleBack: Boolean = true,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = ZoneId.systemDefault()
    val initialCycle = alarm.repeat as? Repeat.Cycle

    var hour by rememberSaveable { mutableIntStateOf(alarm.hour) }
    var minute by rememberSaveable { mutableIntStateOf(alarm.minute) }
    var mode by rememberSaveable {
        mutableStateOf(
            when (alarm.repeat) {
                is Repeat.OnDate -> RepeatMode.DATE
                is Repeat.Cycle -> RepeatMode.CYCLE
                Repeat.Workdays -> RepeatMode.WORKDAYS
                Repeat.Once, is Repeat.Weekly -> RepeatMode.DAYS
            },
        )
    }
    var days by remember { mutableStateOf(alarm.repeat.days) }
    var dateDay by rememberSaveable { mutableStateOf((alarm.repeat as? Repeat.OnDate)?.date?.toEpochDay()) }
    var anchorDay by rememberSaveable { mutableLongStateOf(initialCycle?.anchor?.toEpochDay() ?: LocalDate.now(zone).toEpochDay()) }
    var cycleDays by remember { mutableStateOf(initialCycle?.days ?: emptyList()) }
    var label by rememberSaveable { mutableStateOf(alarm.label) }
    var sound by remember { mutableStateOf(alarm.sound) }
    var vibrate by rememberSaveable { mutableStateOf(alarm.vibrate) }
    var rampSeconds by rememberSaveable { mutableIntStateOf(alarm.rampSeconds) }
    var snoozeMinutes by rememberSaveable { mutableStateOf(alarm.snoozeMinutes) }
    var skipNext by rememberSaveable { mutableStateOf(alarm.skipDate != null) }
    var honorExceptions by rememberSaveable { mutableStateOf(alarm.honorExceptions) }
    var groupId by rememberSaveable { mutableStateOf(alarm.groupId?.takeIf { id -> groups.any { it.id == id } }) }
    var wakeCheck by remember { mutableStateOf(alarm.wakeCheck) }
    var gentleMinutes by rememberSaveable { mutableIntStateOf(alarm.gentleMinutes) }
    var after by remember { mutableStateOf(alarm.after) }
    val presets by DpClockApp.graph(context).repository.presets.collectAsStateWithLifecycle(emptyList())
    var advanced by rememberSaveable { mutableStateOf(hasAdvanced(alarm)) }
    var dialog by rememberSaveable { mutableStateOf<String?>(null) }

    fun startCycle(preset: CyclePreset) {
        cycleDays = preset.days(LocalTime.of(hour, minute), NIGHT_DEFAULT)
    }

    val repeat: Repeat? = when (mode) {
        RepeatMode.DAYS -> Repeat.of(days)
        RepeatMode.WORKDAYS -> Repeat.Workdays
        RepeatMode.DATE -> dateDay?.let { Repeat.OnDate(LocalDate.ofEpochDay(it)) }
        RepeatMode.CYCLE -> cycleDays.takeIf { list -> list.size >= 2 && list.any { it != null } }
            ?.let { Repeat.Cycle(LocalDate.ofEpochDay(anchorDay), it) }
    }
    val draft = repeat?.let { r ->
        val time = (r as? Repeat.Cycle)?.firstTime ?: LocalTime.of(hour, minute)
        val base = alarm.copy(
            hour = time.hour,
            minute = time.minute,
            repeat = r,
            label = label.trim(),
            sound = sound,
            vibrate = vibrate,
            rampSeconds = rampSeconds,
            snoozeMinutes = snoozeMinutes,
            skipDate = null,
            honorExceptions = honorExceptions,
            groupId = groupId,
            wakeCheck = wakeCheck,
            gentleMinutes = gentleMinutes,
            after = after,
        )
        val now = Instant.now()
        // Пропуск — дата ближайшего срабатывания по новым правилам (D-013, п. 3).
        val skip = if (skipNext && r.isRepeating) AlarmRules.nextIgnoringSkip(base, now, zone, exceptions)?.atZone(zone)?.toLocalDate() else null
        base.copy(skipDate = skip)
    }
    val next = draft?.let { AlarmRules.nextTrigger(it, Instant.now(), zone, exceptions) }

    val body: @Composable () -> Unit = {
        Scaffold(
            containerColor = if (inline) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    colors = if (inline) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent) else TopAppBarDefaults.topAppBarColors(),
                    title = { TitleText(stringResource(R.string.edit_alarm)) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) }
                    },
                    actions = {
                        // Повторяющийся, у которого все даты в исключениях, сохранить можно: исключения правятся отдельно.
                        val canSave = draft != null && (next != null || draft.repeat.isRepeating)
                        TextButton(enabled = canSave, onClick = { draft?.let(onSave) }) { Text(stringResource(R.string.save)) }
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
                if (mode != RepeatMode.CYCLE) {
                    TextButton(onClick = { dialog = "time" }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        FitText(TimeText.time(context, hour, minute), style = MaterialTheme.typography.displayLarge)
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(Alarm.MAX_LABEL) },
                    label = { Text(stringResource(R.string.alarm_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.repeat), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (option in RepeatMode.entries) {
                        FilterChip(
                            selected = mode == option,
                            onClick = {
                                mode = option
                                if (option == RepeatMode.DATE && dateDay == null) dialog = "date"
                                if (option == RepeatMode.CYCLE && cycleDays.isEmpty()) startCycle(CyclePreset.TWO_BY_TWO)
                            },
                            label = { Text(stringResource(modeTitle(option))) },
                        )
                    }
                }
                when (mode) {
                    RepeatMode.DAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (day in orderedDays(locale)) {
                            FilterChip(
                                selected = day in days,
                                onClick = { days = if (day in days) days - day else days + day },
                                label = { Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale)) },
                                // TalkBack читает день целиком, а не одну букву (D-022, п. 3).
                                modifier = Modifier.semantics { contentDescription = day.getDisplayName(TextStyle.FULL_STANDALONE, locale) },
                            )
                        }
                    }
                    RepeatMode.WORKDAYS -> {
                        val years = RuCalendar.decreedYears.sorted()
                        Text(
                            stringResource(R.string.workdays_hint, years.first(), years.last()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    RepeatMode.DATE -> OutlinedButton(onClick = { dialog = "date" }) {
                        Text(dateDay?.let { TimeText.date(context, LocalDate.ofEpochDay(it), withYear = true) } ?: stringResource(R.string.pick_date))
                    }
                    RepeatMode.CYCLE -> CycleEditor(
                        anchor = LocalDate.ofEpochDay(anchorDay),
                        days = cycleDays,
                        onPreset = ::startCycle,
                        onAnchor = { dialog = "anchor" },
                        onTime = { index -> dialog = "cycleTime:$index" },
                        onChange = { cycleDays = it },
                    )
                }
                Text(
                    when {
                        repeat == null -> stringResource(R.string.pick_date)
                        next == null && repeat.isRepeating -> stringResource(R.string.all_dates_excepted)
                        next == null -> stringResource(R.string.date_passed)
                        else -> repeatText(context, repeat) + " · " + stringResource(R.string.rings_in, untilText(resources, next.toEpochMilli() - System.currentTimeMillis()))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (repeat != null && next == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (draft != null && draft.repeat.isRepeating && next != null) {
                    val upcoming = AlarmRules.upcoming(draft, Instant.now(), zone, exceptions, 4)
                    Text(
                        stringResource(R.string.upcoming) + " " + upcoming.joinToString(" · ") {
                            TimeText.date(context, it.atZone(zone).toLocalDate()) + " " + TimeText.time(context, it.toEpochMilli())
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

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
                        trailingContent = { Switch(checked = vibrate, onCheckedChange = null) },
                        modifier = Modifier.toggleable(value = vibrate, role = Role.Switch) { vibrate = it },
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
                        val skippable = draft?.let { AlarmRules.nextIgnoringSkip(it, Instant.now(), zone, exceptions) }?.atZone(zone)?.toLocalDate()
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.skip_next)) },
                            supportingContent = skippable?.let { { Text(TimeText.date(context, it)) } },
                            trailingContent = { Switch(checked = skipNext, onCheckedChange = null) },
                            modifier = Modifier.toggleable(value = skipNext, role = Role.Switch) { skipNext = it },
                        )
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.honor_exceptions)) },
                            supportingContent = { Text(exceptionsSummary(exceptions)) },
                            trailingContent = { Switch(checked = honorExceptions, onCheckedChange = null) },
                            modifier = Modifier.toggleable(value = honorExceptions, role = Role.Switch) { honorExceptions = it },
                        )
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.gentle_wake)) },
                        supportingContent = {
                            Text(if (gentleMinutes == 0) stringResource(R.string.ramp_off) else stringResource(R.string.gentle_before, gentleMinutes))
                        },
                        modifier = Modifier.clickable { dialog = "gentle" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.after_alarm)) },
                        supportingContent = { Text(afterSummary(after, presets.firstOrNull { it.id == after.presetId }?.name)) },
                        modifier = Modifier.clickable { dialog = "after" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.wake_check)) },
                        supportingContent = { Text(wakeSummary(wakeCheck)) },
                        modifier = Modifier.clickable { dialog = "wake" },
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.group)) },
                        supportingContent = { Text(groups.firstOrNull { it.id == groupId }?.name ?: stringResource(R.string.no_group)) },
                        modifier = Modifier.clickable { dialog = "group" },
                    )
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
    if (inline) {
        BackHandler(enabled = handleBack, onBack = onDismiss)
        body()
    } else {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { body() }
    }

    val current = dialog
    when {
        current == "time" -> TimePickerDialog(
            initialHour = hour,
            initialMinute = minute,
            onDismiss = { dialog = null },
            onConfirm = { h, m ->
                hour = h
                minute = m
                dialog = null
            },
        )
        current?.startsWith("cycleTime:") == true -> {
            val index = current.removePrefix("cycleTime:").toInt()
            val time = cycleDays.getOrNull(index) ?: LocalTime.of(hour, minute)
            TimePickerDialog(
                initialHour = time.hour,
                initialMinute = time.minute,
                onDismiss = { dialog = null },
                onConfirm = { h, m ->
                    cycleDays = cycleDays.toMutableList().also { if (index < it.size) it[index] = LocalTime.of(h, m) }
                    dialog = null
                },
            )
        }
        current == "date" -> DayPickerDialog(
            initialDay = dateDay ?: LocalDate.now(zone).toEpochDay(),
            minDay = LocalDate.now(zone).toEpochDay(),
            onDismiss = {
                dialog = null
                if (dateDay == null) mode = RepeatMode.DAYS
            },
            onPick = {
                dateDay = it
                dialog = null
            },
        )
        current == "anchor" -> DayPickerDialog(
            initialDay = anchorDay,
            minDay = null,
            onDismiss = { dialog = null },
            onPick = {
                anchorDay = it
                dialog = null
            },
        )
        current == "gentle" -> ChoiceDialog(
            title = stringResource(R.string.gentle_wake),
            choices = SleepRules.GENTLE_CHOICES,
            selected = gentleMinutes,
            label = { if (it == 0) stringResource(R.string.ramp_off) else stringResource(R.string.gentle_before, it) },
            onDismiss = { dialog = null },
            onSelect = { gentleMinutes = it },
        )
        current == "after" -> AfterAlarmEditor(
            initial = after,
            onDismiss = { dialog = null },
            onDone = {
                after = it
                dialog = null
            },
        )
        current == "wake" -> WakeCheckEditor(
            initial = wakeCheck,
            onDismiss = { dialog = null },
            onDone = {
                wakeCheck = it
                dialog = null
            },
        )
        current == "sound" -> SoundPicker(
            initial = sound,
            onDismiss = { dialog = null },
            onPick = {
                sound = it ?: AlarmSound.Default
                dialog = null
            },
        )
        current == "ramp" -> ChoiceDialog(
            title = stringResource(R.string.volume_ramp),
            choices = Settings.RAMP_CHOICES,
            selected = rampSeconds,
            label = { rampText(it) },
            onDismiss = { dialog = null },
            onSelect = { rampSeconds = it },
        )
        current == "snooze" -> ChoiceDialog(
            title = stringResource(R.string.snooze_interval),
            choices = listOf<Int?>(null) + Settings.SNOOZE_CHOICES,
            selected = snoozeMinutes,
            label = { snoozeText(it, settings.snoozeMinutes) },
            onDismiss = { dialog = null },
            onSelect = { snoozeMinutes = it },
        )
        current == "group" -> {
            val noGroup = stringResource(R.string.no_group)
            ChoiceDialog(
                title = stringResource(R.string.group),
                choices = listOf<String?>(null) + groups.map { it.id },
                selected = groupId,
                label = { id -> groups.firstOrNull { it.id == id }?.name ?: noGroup },
                onDismiss = { dialog = null },
                onSelect = { groupId = it },
            )
        }
    }
}

/**
 * Сменный график (D-014, п. 2): готовые 2/2, «сутки через трое», «день/ночь»;
 * первый день цикла; у каждого дня — время или выходной; длина от 2 до 28 дней.
 */
@Composable
private fun CycleEditor(
    anchor: LocalDate,
    days: List<LocalTime?>,
    onPreset: (CyclePreset) -> Unit,
    onAnchor: () -> Unit,
    onTime: (Int) -> Unit,
    onChange: (List<LocalTime?>) -> Unit,
) {
    val context = LocalContext.current
    val workdays = days.count { it != null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in CyclePreset.entries) {
                AssistChip(onClick = { onPreset(preset) }, label = { Text(stringResource(presetTitle(preset))) })
            }
        }
        OutlinedButton(onClick = onAnchor) {
            Text(stringResource(R.string.cycle_anchor, TimeText.date(context, anchor, withYear = true)))
        }
        days.forEachIndexed { index, time ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dayLabel = stringResource(R.string.cycle_day, index + 1)
                Text(dayLabel, modifier = Modifier.widthIn(min = 72.dp))
                if (time != null) {
                    TextButton(onClick = { onTime(index) }) {
                        Text(TimeText.time(context, time.hour, time.minute), style = MaterialTheme.typography.titleLarge)
                    }
                } else {
                    Text(stringResource(R.string.cycle_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = time != null,
                    // Хотя бы один рабочий день в цикле.
                    enabled = time == null || workdays > 1,
                    modifier = Modifier.semantics { contentDescription = dayLabel },
                    onCheckedChange = { on ->
                        val previous = days.filterNotNull().firstOrNull() ?: LocalTime.of(7, 0)
                        onChange(days.toMutableList().also { it[index] = if (on) previous else null })
                    },
                )
            }
        }
        Row {
            TextButton(enabled = days.size < Repeat.Cycle.MAX_DAYS, onClick = { onChange(days + null) }) {
                Text(stringResource(R.string.cycle_add_day))
            }
            TextButton(
                enabled = days.size > 2 && days.dropLast(1).any { it != null },
                onClick = { onChange(days.dropLast(1)) },
            ) { Text(stringResource(R.string.cycle_remove_day)) }
        }
        Text(
            pluralStringResource(R.plurals.cycle_summary, days.size, days.size, workdays),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Выбор дня; [minDay] — раньше нельзя (null — можно любой). Дни — от эпохи. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initialDay: Long, minDay: Long?, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDay * DAY_MILLIS,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = minDay == null || utcTimeMillis / DAY_MILLIS >= minDay
        },
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                val picked = state.selectedDateMillis
                if (picked != null) onPick(picked / DAY_MILLIS) else onDismiss()
            }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    ) { DatePicker(state) }
}

private fun modeTitle(mode: RepeatMode): Int = when (mode) {
    RepeatMode.DAYS -> R.string.repeat_by_days
    RepeatMode.WORKDAYS -> R.string.repeat_workdays
    RepeatMode.CYCLE -> R.string.repeat_cycle
    RepeatMode.DATE -> R.string.repeat_on_date
}

private fun presetTitle(preset: CyclePreset): Int = when (preset) {
    CyclePreset.TWO_BY_TWO -> R.string.cycle_two_two
    CyclePreset.DAY_THREE_OFF -> R.string.cycle_day_three
    CyclePreset.DAY_NIGHT -> R.string.cycle_day_night
}

@Composable
private fun exceptionsSummary(exceptions: Exceptions): String = when {
    exceptions.periods.isEmpty() && !exceptions.holidays -> stringResource(R.string.exceptions_none)
    exceptions.periods.isEmpty() -> stringResource(R.string.exceptions_holidays_only)
    else -> pluralStringResource(R.plurals.exceptions_periods, exceptions.periods.size, exceptions.periods.size) +
        if (exceptions.holidays) " · " + stringResource(R.string.exceptions_holidays_short) else ""
}

/** Блок «Дополнительно» раскрыт сразу, если в нём уже что-то изменено. */
private fun hasAdvanced(alarm: Alarm): Boolean =
    alarm.sound != AlarmSound.Default || !alarm.vibrate || alarm.rampSeconds > 0 || alarm.snoozeMinutes != null ||
        alarm.skipDate != null || !alarm.honorExceptions || alarm.groupId != null ||
        alarm.wakeCheck.required || alarm.wakeCheck.recheckMinutes > 0 || alarm.gentleMinutes > 0 || !alarm.after.isEmpty

@Composable
fun rampText(seconds: Int): String = when {
    seconds == 0 -> stringResource(R.string.ramp_off)
    seconds < 60 -> stringResource(R.string.seconds_value, seconds)
    else -> stringResource(R.string.minutes_value, seconds / 60)
}

@Composable
private fun snoozeText(minutes: Int?, common: Int): String =
    if (minutes == null) stringResource(R.string.snooze_common, common) else stringResource(R.string.minutes_value, minutes)
