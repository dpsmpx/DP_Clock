package com.dpsmpx.dpclock.ui

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.DatePattern
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.RepeatRule
import com.dpsmpx.dpclock.model.RuleTimes
import com.dpsmpx.dpclock.platform.TimeText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

/** Вид дат правила в редакторе (D-025, п. 3). */
private enum class PatternKind { WEEKS, MONTH_DAYS, MONTH_WEEKDAY, YEARLY, SHIFTS, DATES, WORKDAYS }

private fun kindOf(pattern: DatePattern): PatternKind = when (pattern) {
    is DatePattern.Weeks -> PatternKind.WEEKS
    is DatePattern.MonthDays -> PatternKind.MONTH_DAYS
    is DatePattern.MonthWeekday -> PatternKind.MONTH_WEEKDAY
    is DatePattern.Yearly -> PatternKind.YEARLY
    is DatePattern.Shifts -> PatternKind.SHIFTS
    is DatePattern.Dates -> PatternKind.DATES
    DatePattern.Workdays -> PatternKind.WORKDAYS
}

private fun kindTitle(kind: PatternKind): Int = when (kind) {
    PatternKind.WEEKS -> R.string.rules_kind_weeks
    PatternKind.MONTH_DAYS -> R.string.rules_kind_month_days
    PatternKind.MONTH_WEEKDAY -> R.string.rules_kind_month_weekday
    PatternKind.YEARLY -> R.string.rules_kind_yearly
    PatternKind.SHIFTS -> R.string.rules_kind_shifts
    PatternKind.DATES -> R.string.rules_kind_dates
    PatternKind.WORKDAYS -> R.string.repeat_workdays
}

/** Промежутки «каждые N минут» (D-025, п. 4). */
private val EVERY_CHOICES = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720)

/** Начальный повтор правилами — из того, что было в редакторе: дни недели и время. */
fun startRules(days: Set<DayOfWeek>, time: LocalTime): Repeat.Rules =
    Repeat.Rules(listOf(RepeatRule(DatePattern.Weeks(days.ifEmpty { DayOfWeek.entries.toSet() }), RuleTimes.At(listOf(time)))))

/**
 * Сложный повтор в редакторе будильника (D-025, п. 3): правила карточками — у
 * каждого свои даты и время, — и «кроме». Правка — в отдельном окне.
 */
@Composable
fun RulesEditor(rules: Repeat.Rules, defaultTime: LocalTime, onChange: (Repeat.Rules) -> Unit) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<Int?>(null) }
    var editingExcept by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rules.rules.forEachIndexed { index, rule ->
            RuleCard(
                text = ruleText(context, rule),
                onEdit = { editing = index },
                onDelete = if (rules.rules.size > 1) ({ onChange(rules.copy(rules = rules.rules.filterIndexed { i, _ -> i != index })) }) else null,
            )
        }
        if (rules.rules.size < Repeat.Rules.MAX_RULES) {
            TextButton(onClick = { editing = -1 }) { Text(stringResource(R.string.rules_add)) }
        }
        Text(stringResource(R.string.rules_except), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        if (rules.except.isEmpty()) {
            Text(stringResource(R.string.rules_except_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rules.except.forEachIndexed { index, pattern ->
            RuleCard(
                text = patternText(context, pattern),
                onEdit = { editingExcept = index },
                onDelete = { onChange(rules.copy(except = rules.except.filterIndexed { i, _ -> i != index })) },
            )
        }
        if (rules.except.size < Repeat.Rules.MAX_EXCEPT) {
            TextButton(onClick = { editingExcept = -1 }) { Text(stringResource(R.string.rules_except_add)) }
        }
    }
    editing?.let { index ->
        RuleDialog(
            initial = rules.rules.getOrNull(index),
            defaultTime = defaultTime,
            onDismiss = { editing = null },
            onDone = { rule ->
                onChange(rules.copy(rules = if (index < 0) rules.rules + rule else rules.rules.mapIndexed { i, old -> if (i == index) rule else old }))
                editing = null
            },
        )
    }
    editingExcept?.let { index ->
        ExceptDialog(
            initial = rules.except.getOrNull(index),
            onDismiss = { editingExcept = null },
            onDone = { pattern ->
                onChange(rules.copy(except = if (index < 0) rules.except + pattern else rules.except.mapIndexed { i, old -> if (i == index) pattern else old }))
                editingExcept = null
            },
        )
    }
}

@Composable
private fun RuleCard(text: String, onEdit: () -> Unit, onDelete: (() -> Unit)?) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.edit), onClick = onEdit),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (onDelete != null) {
                IconButton(onClick = onDelete) { Icon(AppIcons.Delete, contentDescription = stringResource(R.string.delete)) }
            } else {
                Spacer(Modifier.size(16.dp))
            }
        }
    }
}

/** Даты правила — состояние окна правки. */
private class PatternDraft(initial: DatePattern?, today: LocalDate) {
    var kind by mutableStateOf(initial?.let(::kindOf) ?: PatternKind.WEEKS)
    var weekDays by mutableStateOf((initial as? DatePattern.Weeks)?.days ?: setOf(today.dayOfWeek))
    var everyWeeks by mutableIntStateOf((initial as? DatePattern.Weeks)?.everyWeeks ?: 1)
    var anchor by mutableStateOf(
        (initial as? DatePattern.Weeks)?.anchor?.takeIf { it != DatePattern.EPOCH_MONDAY } ?: DatePattern.monday(today),
    )
    var monthDays by mutableStateOf((initial as? DatePattern.MonthDays)?.days ?: setOf(today.dayOfMonth))
    var week by mutableIntStateOf((initial as? DatePattern.MonthWeekday)?.week ?: ((today.dayOfMonth - 1) / 7 + 1))
    var weekday by mutableStateOf((initial as? DatePattern.MonthWeekday)?.day ?: today.dayOfWeek)

    /** Год — високосный, чтобы выбрать и 29 февраля. */
    var yearly by mutableStateOf((initial as? DatePattern.Yearly)?.let { LocalDate.of(LEAP_YEAR, it.month, it.day) } ?: today.withYear(LEAP_YEAR))
    var shiftAnchor by mutableStateOf((initial as? DatePattern.Shifts)?.anchor ?: today)
    var shiftOn by mutableStateOf((initial as? DatePattern.Shifts)?.on ?: listOf(true, true, false, false))
    var dates by mutableStateOf((initial as? DatePattern.Dates)?.dates ?: emptySet())

    fun build(): DatePattern? = runCatching {
        when (kind) {
            PatternKind.WEEKS -> DatePattern.Weeks(weekDays, everyWeeks, if (everyWeeks == 1) DatePattern.EPOCH_MONDAY else DatePattern.monday(anchor))
            PatternKind.MONTH_DAYS -> DatePattern.MonthDays(monthDays)
            PatternKind.MONTH_WEEKDAY -> DatePattern.MonthWeekday(week, weekday)
            PatternKind.YEARLY -> DatePattern.Yearly(yearly.monthValue, yearly.dayOfMonth)
            PatternKind.SHIFTS -> DatePattern.Shifts(shiftAnchor, shiftOn)
            PatternKind.DATES -> DatePattern.Dates(dates)
            PatternKind.WORKDAYS -> DatePattern.Workdays
        }
    }.getOrNull()

    companion object {
        const val LEAP_YEAR = 2028
    }
}

/** Время правила — состояние окна правки. */
private class TimesDraft(initial: RuleTimes?, default: LocalTime) {
    var every by mutableStateOf(initial is RuleTimes.Every)
    var times by mutableStateOf((initial as? RuleTimes.At)?.list ?: listOf(default))
    var start by mutableStateOf((initial as? RuleTimes.Every)?.start ?: LocalTime.of(9, 0))
    var end by mutableStateOf((initial as? RuleTimes.Every)?.end ?: LocalTime.of(18, 0))
    var minutes by mutableIntStateOf((initial as? RuleTimes.Every)?.minutes ?: 60)

    fun build(): RuleTimes? = runCatching { if (every) RuleTimes.Every(start, end, minutes) else RuleTimes.At(times) }.getOrNull()
}

/** Окно правила: даты, время, дни недели, своё время по дням, период. */
@Composable
private fun RuleDialog(initial: RepeatRule?, defaultTime: LocalTime, onDismiss: () -> Unit, onDone: (RepeatRule) -> Unit) {
    val today = LocalDate.now(ZoneId.systemDefault())
    val dates = remember { PatternDraft(initial?.dates, today) }
    val times = remember { TimesDraft(initial?.times, defaultTime) }
    var weekdays by remember { mutableStateOf(initial?.weekdays ?: emptySet()) }
    var onlyDays by remember { mutableStateOf(initial?.weekdays?.isNotEmpty() == true) }
    var weekdayTimes by remember { mutableStateOf(initial?.weekdayTimes ?: emptyMap()) }
    var ownTimes by remember { mutableStateOf(initial?.weekdayTimes?.isNotEmpty() == true) }
    var from by remember { mutableStateOf(initial?.from) }
    var until by remember { mutableStateOf(initial?.until) }
    var picker by remember { mutableStateOf<String?>(null) }

    val pattern = dates.build()
    val ruleTimes = times.build()
    val rule = if (pattern != null && ruleTimes != null) {
        runCatching {
            RepeatRule(
                pattern,
                ruleTimes,
                weekdays = if (onlyDays) weekdays else emptySet(),
                weekdayTimes = if (ownTimes) weekdayTimes else emptyMap(),
                from = from,
                until = until,
            )
        }.getOrNull()
    } else {
        null
    }

    RuleScaffold(
        title = stringResource(if (initial == null) R.string.rules_new else R.string.rules_edit),
        canSave = rule != null,
        onDismiss = onDismiss,
        onDone = { rule?.let(onDone) },
    ) {
        PatternEditor(dates, onPick = { picker = it })
        HorizontalDivider()
        Heading(R.string.rules_times)
        TimesEditor(times, onPick = { picker = it })
        HorizontalDivider()
        if (dates.kind != PatternKind.WEEKS) {
            SwitchRow(stringResource(R.string.rules_only_days_title), onlyDays) { onlyDays = it }
            if (onlyDays) DayChips(weekdays) { day -> weekdays = if (day in weekdays) weekdays - day else weekdays + day }
        }
        SwitchRow(stringResource(R.string.rules_own_times), ownTimes, stringResource(R.string.rules_own_times_text)) { ownTimes = it }
        if (ownTimes) WeekdayTimes(weekdayTimes, onPick = { picker = "wtime:" + it.name }, onClear = { weekdayTimes = weekdayTimes - it })
        HorizontalDivider()
        Heading(R.string.rules_period)
        PeriodRow(R.string.rules_from_title, from, onPick = { picker = "from" }, onClear = { from = null })
        PeriodRow(R.string.rules_until_title, until, onPick = { picker = "until" }, onClear = { until = null })
        val start = from
        val end = until
        if (start != null && end != null && end.isBefore(start)) {
            Text(stringResource(R.string.rules_period_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    val current = picker
    when {
        current == null -> Unit
        current.startsWith("wtime:") -> {
            val day = DayOfWeek.valueOf(current.removePrefix("wtime:"))
            val time = weekdayTimes[day] ?: times.times.firstOrNull() ?: defaultTime
            TimePickerDialog(time.hour, time.minute, onDismiss = { picker = null }, onConfirm = { h, m ->
                weekdayTimes = weekdayTimes + (day to LocalTime.of(h, m))
                picker = null
            })
        }
        current == "from" || current == "until" -> DayPickerDialog(
            initialDay = (if (current == "from") from else until)?.toEpochDay() ?: today.toEpochDay(),
            minDay = null,
            onDismiss = { picker = null },
            onPick = { day ->
                if (current == "from") from = LocalDate.ofEpochDay(day) else until = LocalDate.ofEpochDay(day)
                picker = null
            },
        )
        else -> CommonPickers(current, dates, times, defaultTime, onClose = { picker = null })
    }
}

/** Окно «кроме»: только даты. */
@Composable
private fun ExceptDialog(initial: DatePattern?, onDismiss: () -> Unit, onDone: (DatePattern) -> Unit) {
    val today = LocalDate.now(ZoneId.systemDefault())
    val dates = remember { PatternDraft(initial, today) }
    var picker by remember { mutableStateOf<String?>(null) }
    val pattern = dates.build()
    RuleScaffold(
        title = stringResource(R.string.rules_except),
        canSave = pattern != null,
        onDismiss = onDismiss,
        onDone = { pattern?.let(onDone) },
    ) {
        Text(stringResource(R.string.rules_except_text), style = MaterialTheme.typography.bodyMedium)
        PatternEditor(dates, onPick = { picker = it })
    }
    picker?.let { current -> CommonPickers(current, dates, TimesDraft(null, LocalTime.NOON), LocalTime.NOON, onClose = { picker = null }) }
}

@Composable
private fun CommonPickers(current: String, dates: PatternDraft, times: TimesDraft, defaultTime: LocalTime, onClose: () -> Unit) {
    // Выбор дня: с чего начать и куда записать.
    val day: Pair<LocalDate, (LocalDate) -> Unit>? = when (current) {
        "anchor" -> dates.anchor to { picked -> dates.anchor = picked }
        "shiftAnchor" -> dates.shiftAnchor to { picked -> dates.shiftAnchor = picked }
        "yearly" -> dates.yearly to { picked -> dates.yearly = picked }
        "date:add" -> LocalDate.now(ZoneId.systemDefault()) to { picked -> dates.dates = dates.dates + picked }
        else -> null
    }
    if (day != null) {
        DayPickerDialog(initialDay = day.first.toEpochDay(), minDay = null, onDismiss = onClose, onPick = {
            day.second(LocalDate.ofEpochDay(it))
            onClose()
        })
        return
    }
    when {
        current == "start" || current == "end" -> {
            val time = if (current == "start") times.start else times.end
            TimePickerDialog(time.hour, time.minute, onDismiss = onClose, onConfirm = { h, m ->
                if (current == "start") times.start = LocalTime.of(h, m) else times.end = LocalTime.of(h, m)
                onClose()
            })
        }
        current.startsWith("time:") -> {
            val index = current.removePrefix("time:").toIntOrNull()
            val time = index?.let { times.times.getOrNull(it) } ?: times.times.lastOrNull() ?: defaultTime
            TimePickerDialog(time.hour, time.minute, onDismiss = onClose, onConfirm = { h, m ->
                val picked = LocalTime.of(h, m)
                times.times = (if (index == null) times.times + picked else times.times.mapIndexed { i, old -> if (i == index) picked else old }).distinct().sorted()
                onClose()
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleScaffold(title: String, canSave: Boolean, onDismiss: () -> Unit, onDone: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { TitleText(title) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.cancel)) } },
                    actions = { TextButton(enabled = canSave, onClick = onDone) { Text(stringResource(R.string.done)) } },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun Heading(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
}

/** Выбор дат правила: вид и его подробности. */
@Composable
private fun PatternEditor(draft: PatternDraft, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Heading(R.string.rules_dates)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (kind in PatternKind.entries) {
            FilterChip(selected = draft.kind == kind, onClick = { draft.kind = kind }, label = { Text(stringResource(kindTitle(kind))) })
        }
    }
    when (draft.kind) {
        PatternKind.WEEKS -> {
            DayChips(draft.weekDays) { day -> draft.weekDays = if (day in draft.weekDays) draft.weekDays - day else draft.weekDays + day }
            Stepper(
                stringResource(R.string.rules_every_weeks_title),
                draft.everyWeeks.toString(),
                onMinus = if (draft.everyWeeks > 1) ({ draft.everyWeeks-- }) else null,
                onPlus = if (draft.everyWeeks < DatePattern.MAX_EVERY_WEEKS) ({ draft.everyWeeks++ }) else null,
            )
            if (draft.everyWeeks > 1) {
                OutlinedButton(onClick = { onPick("anchor") }) {
                    Text(stringResource(R.string.rules_first_week, TimeText.date(context, DatePattern.monday(draft.anchor), withYear = true)))
                }
            }
        }
        PatternKind.MONTH_DAYS -> FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (day in (1..31) + DatePattern.LAST) {
                val on = day in draft.monthDays
                FilterChip(
                    selected = on,
                    onClick = { draft.monthDays = if (on) draft.monthDays - day else draft.monthDays + day },
                    label = { Text(if (day == DatePattern.LAST) stringResource(R.string.rules_last_day) else day.toString()) },
                    modifier = Modifier.widthIn(min = 48.dp),
                )
            }
        }
        PatternKind.MONTH_WEEKDAY -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (week in (1..5) + DatePattern.LAST) {
                    FilterChip(
                        selected = draft.week == week,
                        onClick = { draft.week = week },
                        label = { Text(if (week == DatePattern.LAST) stringResource(R.string.rules_last_week) else stringResource(R.string.rules_week_n, week)) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (day in orderedDays(locale)) {
                    FilterChip(
                        selected = draft.weekday == day,
                        onClick = { draft.weekday = day },
                        label = { Text(day.getDisplayName(TextStyle.SHORT_STANDALONE, locale)) },
                    )
                }
            }
        }
        PatternKind.YEARLY -> OutlinedButton(onClick = { onPick("yearly") }) { Text(dayMonth(context, draft.yearly.monthValue, draft.yearly.dayOfMonth)) }
        PatternKind.SHIFTS -> ShiftsEditor(draft, onAnchor = { onPick("shiftAnchor") })
        PatternKind.DATES -> {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (date in draft.dates.sorted()) {
                    InputChip(
                        selected = false,
                        onClick = { draft.dates = draft.dates - date },
                        label = { Text(TimeText.date(context, date)) },
                        trailingIcon = { Icon(AppIcons.Close, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(16.dp)) },
                    )
                }
            }
            if (draft.dates.size < DatePattern.MAX_DATES) {
                TextButton(onClick = { onPick("date:add") }) { Text(stringResource(R.string.rules_add_date)) }
            }
        }
        PatternKind.WORKDAYS -> Text(
            stringResource(R.string.rules_workdays_text),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (draft.build() == null) {
        Text(stringResource(R.string.rules_dates_empty), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

/** Смены: готовые 2/2, «сутки через трое», 5/2 и свой цикл; рабочий день — отметкой. */
@Composable
private fun ShiftsEditor(draft: PatternDraft, onAnchor: () -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { draft.shiftOn = listOf(true, true, false, false) }, label = { Text(stringResource(R.string.cycle_two_two)) })
        AssistChip(onClick = { draft.shiftOn = listOf(true, false, false, false) }, label = { Text(stringResource(R.string.cycle_day_three)) })
        AssistChip(onClick = { draft.shiftOn = List(7) { it < 5 } }, label = { Text(stringResource(R.string.rules_five_two)) })
    }
    OutlinedButton(onClick = onAnchor) { Text(stringResource(R.string.cycle_anchor, TimeText.date(context, draft.shiftAnchor, withYear = true))) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        draft.shiftOn.forEachIndexed { index, on ->
            FilterChip(
                selected = on,
                // Хотя бы один рабочий день в цикле.
                enabled = !on || draft.shiftOn.count { it } > 1,
                onClick = { draft.shiftOn = draft.shiftOn.mapIndexed { i, old -> if (i == index) !old else old } },
                label = { Text((index + 1).toString()) },
            )
        }
    }
    Row {
        TextButton(enabled = draft.shiftOn.size < Repeat.Cycle.MAX_DAYS, onClick = { draft.shiftOn = draft.shiftOn + false }) {
            Text(stringResource(R.string.cycle_add_day))
        }
        TextButton(
            enabled = draft.shiftOn.size > 2 && draft.shiftOn.dropLast(1).any { it },
            onClick = { draft.shiftOn = draft.shiftOn.dropLast(1) },
        ) { Text(stringResource(R.string.cycle_remove_day)) }
    }
}

/** Время: несколько раз за день или каждые N минут в окне. */
@Composable
private fun TimesEditor(draft: TimesDraft, onPick: (String) -> Unit) {
    val context = LocalContext.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !draft.every, onClick = { draft.every = false }, label = { Text(stringResource(R.string.rules_times_at)) })
        FilterChip(selected = draft.every, onClick = { draft.every = true }, label = { Text(stringResource(R.string.rules_times_every)) })
    }
    if (!draft.every) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            draft.times.forEachIndexed { index, time ->
                InputChip(
                    selected = false,
                    onClick = { onPick("time:$index") },
                    label = { Text(TimeText.time(context, time.hour, time.minute), style = MaterialTheme.typography.titleMedium) },
                    trailingIcon = if (draft.times.size > 1) {
                        {
                            IconButton(onClick = { draft.times = draft.times.filterIndexed { i, _ -> i != index } }, modifier = Modifier.size(24.dp)) {
                                Icon(AppIcons.Close, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        null
                    },
                )
            }
        }
        if (draft.times.size < RuleTimes.MAX_TIMES) {
            TextButton(onClick = { onPick("time:add") }) { Text(stringResource(R.string.rules_add_time)) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onPick("start") }) { Text(stringResource(R.string.rules_window_from, TimeText.time(context, draft.start.hour, draft.start.minute))) }
            OutlinedButton(onClick = { onPick("end") }) { Text(stringResource(R.string.rules_window_to, TimeText.time(context, draft.end.hour, draft.end.minute))) }
        }
        ChoiceStepper(
            stringResource(R.string.rules_every_title),
            EVERY_CHOICES,
            draft.minutes.takeIf { it in EVERY_CHOICES } ?: 60,
            text = { leadText(context, it) },
            onChange = { draft.minutes = it },
        )
        if (draft.end.isBefore(draft.start)) {
            Text(stringResource(R.string.rules_window_wrong), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            val count = RuleTimes.Every(draft.start, draft.end, draft.minutes).list.size
            Text(
                pluralStringResource(R.plurals.rules_times_count, count, count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Своё время по дням недели: день — время или «как обычно». */
@Composable
private fun WeekdayTimes(times: Map<DayOfWeek, LocalTime>, onPick: (DayOfWeek) -> Unit, onClear: (DayOfWeek) -> Unit) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    Column {
        for (day in orderedDays(locale)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(day.getDisplayName(TextStyle.FULL_STANDALONE, locale), modifier = Modifier.weight(1f))
                val time = times[day]
                TextButton(onClick = { onPick(day) }) {
                    Text(time?.let { TimeText.time(context, it.hour, it.minute) } ?: stringResource(R.string.rules_as_usual))
                }
                if (time != null) {
                    IconButton(onClick = { onClear(day) }) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.rules_as_usual)) }
                } else {
                    Spacer(Modifier.size(48.dp))
                }
            }
        }
    }
}

@Composable
private fun PeriodRow(title: Int, date: LocalDate?, onPick: () -> Unit, onClear: () -> Unit) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(title), modifier = Modifier.weight(1f))
        TextButton(onClick = onPick) { Text(date?.let { TimeText.date(context, it, withYear = true) } ?: stringResource(R.string.rules_no_limit)) }
        if (date != null) {
            IconButton(onClick = onClear) { Icon(AppIcons.Close, contentDescription = stringResource(R.string.rules_no_limit)) }
        } else {
            Spacer(Modifier.size(48.dp))
        }
    }
}

// Подписи

/** Сложный повтор одной строкой: правила через «;», в конце — «кроме». */
fun rulesText(context: Context, rules: Repeat.Rules): String {
    val text = rules.rules.joinToString("; ") { ruleText(context, it) }
    if (rules.except.isEmpty()) return text
    return text + "; " + context.getString(R.string.rules_except_short, rules.except.joinToString(", ") { patternText(context, it) })
}

fun ruleText(context: Context, rule: RepeatRule): String = buildList {
    add(patternText(context, rule.dates))
    if (rule.weekdays.isNotEmpty()) add(context.getString(R.string.rules_only_days, daysText(context, rule.weekdays)))
    add(timesText(context, rule.times))
    val locale = context.resources.configuration.locales[0]
    for (day in orderedDays(locale).filter { it in rule.weekdayTimes }) {
        val time = rule.weekdayTimes.getValue(day)
        add(day.getDisplayName(TextStyle.SHORT, locale) + " — " + TimeText.time(context, time.hour, time.minute))
    }
    rule.from?.let { add(context.getString(R.string.rules_from, TimeText.shortDate(context, it))) }
    rule.until?.let { add(context.getString(R.string.rules_until, TimeText.shortDate(context, it))) }
}.joinToString(" · ")

fun patternText(context: Context, pattern: DatePattern): String {
    val resources = context.resources
    val locale = resources.configuration.locales[0]
    return when (pattern) {
        is DatePattern.Weeks -> {
            val days = daysText(context, pattern.days)
            if (pattern.everyWeeks == 1) days else resources.getQuantityString(R.plurals.rules_every_weeks, pattern.everyWeeks, pattern.everyWeeks, days)
        }
        is DatePattern.MonthDays -> {
            val numbers = pattern.days.filter { it != DatePattern.LAST }.sorted().map { it.toString() } +
                listOfNotNull(resources.getString(R.string.rules_last_day).takeIf { DatePattern.LAST in pattern.days })
            resources.getString(R.string.rules_month_days, numbers.joinToString(", "))
        }
        is DatePattern.MonthWeekday -> {
            val day = pattern.day.getDisplayName(TextStyle.SHORT, locale)
            if (pattern.week == DatePattern.LAST) resources.getString(R.string.rules_last_weekday, day)
            else resources.getString(R.string.rules_nth_weekday, day, pattern.week)
        }
        is DatePattern.Yearly -> resources.getString(R.string.rules_yearly, dayMonth(context, pattern.month, pattern.day))
        is DatePattern.Shifts -> resources.getQuantityString(R.plurals.rules_shifts, pattern.on.size, pattern.on.count { it }, pattern.on.size)
        is DatePattern.Dates -> {
            val sorted = pattern.dates.sorted()
            val shown = sorted.take(3).joinToString(", ") { TimeText.shortDate(context, it) }
            if (sorted.size > 3) resources.getQuantityString(R.plurals.rules_dates_more, sorted.size - 3, shown, sorted.size - 3) else shown
        }
        DatePattern.Workdays -> resources.getString(R.string.repeat_workdays)
    }
}

private fun timesText(context: Context, times: RuleTimes): String = when (times) {
    is RuleTimes.At -> times.list.joinToString(", ") { TimeText.time(context, it.hour, it.minute) }
    is RuleTimes.Every -> context.getString(
        R.string.rules_every,
        leadText(context, times.minutes),
        TimeText.time(context, times.start.hour, times.start.minute),
        TimeText.time(context, times.end.hour, times.end.minute),
    )
}

private fun daysText(context: Context, days: Set<DayOfWeek>): String {
    val locale = context.resources.configuration.locales[0]
    return when (days) {
        DayOfWeek.entries.toSet() -> context.getString(R.string.repeat_daily)
        DayOfWeek.entries.take(5).toSet() -> context.getString(R.string.repeat_weekdays)
        else -> orderedDays(locale).filter { it in days }.joinToString(", ") { it.getDisplayName(TextStyle.SHORT, locale) }
    }
}

/** «29 февраля» — день и месяц без года. */
private fun dayMonth(context: Context, month: Int, day: Int): String {
    val locale = context.resources.configuration.locales[0]
    val pattern = DateFormat.getBestDateTimePattern(locale, "dMMMM")
    return DateTimeFormatter.ofPattern(pattern, locale).format(LocalDate.of(PatternDraft.LEAP_YEAR, month, day))
}
