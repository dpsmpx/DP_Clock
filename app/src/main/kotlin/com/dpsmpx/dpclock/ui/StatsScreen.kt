package com.dpsmpx.dpclock.ui

import android.content.Context
import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.ReliabilityStats
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SleepStats
import com.dpsmpx.dpclock.model.StatsPeriod
import com.dpsmpx.dpclock.model.StatsRules
import com.dpsmpx.dpclock.model.TimerStats
import com.dpsmpx.dpclock.model.WakeStats
import com.dpsmpx.dpclock.persistLater
import com.dpsmpx.dpclock.platform.TimeText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** Сколько последних ночей и дней показывают графики. */
private const val CHART_DAYS = 14

/**
 * Статистика (D-023, п. 7): подъёмы, сон, таймеры и секундомер, надёжность
 * сигналов — за неделю, месяц, год или всё время. Считается на телефоне из
 * журнала срабатываний и действий; наружу ничего не уходит.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = DpClockApp.graph(context).repository
    val events by repository.events.collectAsStateWithLifecycle(null)
    val activities by repository.activities.collectAsStateWithLifecycle(null)
    val settings by repository.settings.collectAsStateWithLifecycle(Settings())
    var period by rememberSaveable { mutableStateOf(StatsPeriod.WEEK) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val now = rememberNowMillis(60_000L)
    val zone = ZoneId.systemDefault()
    val signals = events
    val actions = activities
    val report = remember(signals, actions, period, now, settings.bedtime.sleepMinutes) {
        if (signals == null || actions == null) null else StatsRules.report(signals, actions, period, Instant.ofEpochMilli(now), zone, settings.bedtime.sleepMinutes)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { TitleText(stringResource(R.string.stats)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(AppIcons.Back, contentDescription = stringResource(R.string.back)) } },
            )
        },
    ) { padding ->
        val cards = if (report == null) {
            emptyList()
        } else {
            listOf<@Composable () -> Unit>(
                { WakeCard(report.wakes) },
                { SleepCard(report.sleep) },
                { TimersCard(report.timers) },
                { ReliabilityCard(report.reliability) },
            )
        }
        val periods: @Composable () -> Unit = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                for (choice in StatsPeriod.entries) {
                    FilterChip(selected = choice == period, onClick = { period = choice }, label = { Text(stringResource(periodTitle(choice))) })
                }
            }
        }
        val clear: @Composable () -> Unit = {
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.stats_clear))
            }
        }
        if (rememberWindowLayout().twoPane) {
            LazyVerticalStaggeredGrid(
                columns = StaggeredGridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding,
                verticalItemSpacing = 12.dp,
            ) {
                item(span = StaggeredGridItemSpan.FullLine) { periods() }
                items(cards.size) { index -> cards[index]() }
                item(span = StaggeredGridItemSpan.FullLine) { clear() }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { periods() }
                items(cards.size) { index -> cards[index]() }
                item { clear() }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.stats_clear_title)) },
            text = { Text(stringResource(R.string.stats_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    persistLater { repository.clearHistory() }
                }) { Text(stringResource(R.string.stats_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

private fun periodTitle(period: StatsPeriod): Int = when (period) {
    StatsPeriod.WEEK -> R.string.stats_week
    StatsPeriod.MONTH -> R.string.stats_month
    StatsPeriod.YEAR -> R.string.stats_year
    StatsPeriod.ALL -> R.string.stats_all
}

/** Карточка раздела: заголовок — для TalkBack заголовок. */
@Composable
private fun StatsCard(title: Int, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

/** Главное число карточки и подпись под ним. */
@Composable
private fun Hero(value: String, label: String) {
    Column(Modifier.semantics(mergeDescendants = true) {}) {
        FitText(value, style = MaterialTheme.typography.displaySmall)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(text: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
}

@Composable
private fun Empty(text: Int) {
    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun minuteText(context: Context, minute: Int): String = TimeText.time(context, minute / 60, minute % 60)

@Composable
private fun WakeCard(stats: WakeStats) {
    val context = LocalContext.current
    val resources = LocalResources.current
    StatsCard(R.string.stats_wakes) {
        val average = stats.averageMinute
        if (stats.days.isEmpty() || average == null && stats.overslept == 0) {
            Empty(R.string.stats_wakes_empty)
            return@StatsCard
        }
        if (average != null) Hero(minuteText(context, average), stringResource(R.string.stats_wake_average))
        val parts = listOfNotNull(
            stats.weekdayAverageMinute?.let { stringResource(R.string.stats_weekdays, minuteText(context, it)) },
            stats.weekendAverageMinute?.let { stringResource(R.string.stats_weekends, minuteText(context, it)) },
        )
        if (parts.isNotEmpty()) Line(parts.joinToString(" · "))
        if (stats.byDayOfWeek.isNotEmpty()) WeekdayDots(stats.byDayOfWeek)
        Line(stringResource(R.string.stats_snoozes, stats.snoozes))
        stats.averageRingMillis?.let { Line(stringResource(R.string.stats_ring_average, durationSecondsText(resources, it))) }
        if (stats.overslept > 0) Line(stringResource(R.string.stats_overslept, stats.overslept), MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SleepCard(stats: SleepStats) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    StatsCard(R.string.stats_sleep) {
        val average = stats.averageMillis
        if (average == null) {
            Empty(R.string.stats_sleep_empty)
            return@StatsCard
        }
        Hero(durationText(resources, average), stringResource(R.string.stats_sleep_average, durationText(resources, stats.goalMillis)))
        Line(stringResource(R.string.stats_nights, stats.nights.size))
        val nights = stats.nights.takeLast(CHART_DAYS)
        BarChart(
            values = nights.map { it.millis / 3_600_000f },
            labels = nights.map { dayLabel(it.date, nights.size, locale) },
            descriptions = nights.map { night ->
                stringResource(
                    R.string.stats_night_detail,
                    TimeText.date(context, night.date),
                    durationText(resources, night.millis),
                    TimeText.time(context, night.bedAt),
                    TimeText.time(context, night.wakeAt),
                )
            },
            reference = stats.goalMillis / 3_600_000f,
            referenceLabel = stringResource(R.string.stats_goal),
        )
        Text(stringResource(R.string.stats_sleep_how), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TimersCard(stats: TimerStats) {
    val resources = LocalResources.current
    StatsCard(R.string.stats_timers) {
        if (stats.count == 0 && stats.sleepTimers == 0 && stats.stopwatchSessions == 0 && stats.pomodoros == 0) {
            Empty(R.string.stats_timers_empty)
            return@StatsCard
        }
        if (stats.count > 0) {
            Hero(stats.count.toString(), stringResource(R.string.stats_timers_total, durationText(resources, stats.totalMillis)))
            for (use in stats.top) {
                Line(stringResource(R.string.stats_timer_use, use.title.ifBlank { stringResource(R.string.timer_default_name) }, use.count))
            }
        }
        // Помидоры (D-025, п. 1): сколько, сколько работы, в скольких днях.
        if (stats.pomodoros > 0) {
            Line(
                stringResource(
                    R.string.stats_pomodoros,
                    stats.pomodoros,
                    durationText(resources, stats.focusMillis),
                    resources.getQuantityString(R.plurals.stats_days, stats.pomodoroDays, stats.pomodoroDays),
                ),
            )
        }
        if (stats.sleepTimers > 0) Line(stringResource(R.string.stats_sleep_timers, stats.sleepTimers))
        if (stats.stopwatchSessions > 0) {
            Line(stringResource(R.string.stats_stopwatch_sessions, stats.stopwatchSessions))
            Line(stringResource(R.string.stats_stopwatch_total, durationSecondsText(resources, stats.stopwatchMillis)))
            if (stats.laps > 0) Line(stringResource(R.string.stats_laps, stats.laps))
        }
    }
}

@Composable
private fun ReliabilityCard(stats: ReliabilityStats) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    StatsCard(R.string.stats_reliability) {
        if (stats.signals == 0) {
            Empty(R.string.stats_reliability_empty)
            return@StatsCard
        }
        val sounded = stats.signals - stats.missed
        Hero("${stats.onTime}/$sounded", stringResource(R.string.stats_on_time))
        stats.averageLateMillis?.let { Line(stringResource(R.string.stats_late_average, lateText(resources, it))) }
        stats.maxLateMillis?.let { Line(stringResource(R.string.stats_late_max, lateText(resources, it))) }
        Line(
            stringResource(R.string.stats_missed, stats.missed),
            if (stats.missed > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        val days = stats.maxLateByDay.takeLast(CHART_DAYS)
        if (days.isNotEmpty()) {
            val limit = StatsRules.ON_TIME_MILLIS / 1000f
            BarChart(
                values = days.map { it.second / 1000f },
                labels = days.map { dayLabel(it.first, days.size, locale) },
                descriptions = days.map { (date, late) -> stringResource(R.string.stats_late_detail, TimeText.date(context, date), lateText(resources, late)) },
                reference = limit,
                referenceLabel = stringResource(R.string.stats_late_limit),
                highlightAbove = limit,
            )
        }
    }
}

/** Подпись дня под столбцом: до недели — день недели, дальше — число месяца. */
private fun dayLabel(date: LocalDate, count: Int, locale: Locale): String =
    if (count <= 7) date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) else date.dayOfMonth.toString()

/** «1 мин 20 с», «45 с», «2 ч 5 мин» — для коротких длительностей. */
private fun durationSecondsText(resources: Resources, millis: Long): String {
    val seconds = millis / 1000
    return when {
        seconds < 60 -> resources.getString(R.string.duration_seconds, seconds.toInt())
        seconds < 3600 -> resources.getString(R.string.duration_minutes_seconds, (seconds / 60).toInt(), (seconds % 60).toInt())
        else -> durationText(resources, millis)
    }
}

/**
 * Средний подъём по дням недели (D-023, п. 7): точка на общей шкале времени —
 * время дня не величина, столбец его исказил бы. Касание дня — его значение.
 */
@Composable
private fun WeekdayDots(byDay: Map<DayOfWeek, Int>) {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val days = orderedDays(locale)
    val minutes = byDay.values
    // Шкала — целые часы вокруг значений, не меньше часа.
    val from = minutes.min() / 60 * 60
    val to = maxOf(from + 60, (minutes.max() + 59) / 60 * 60)
    val track = MaterialTheme.colorScheme.outlineVariant
    val dot = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.surfaceContainerLow
    val description = days.filter { it in byDay }.joinToString(", ") { day ->
        day.getDisplayName(TextStyle.FULL, locale) + " " + minuteText(context, byDay.getValue(day))
    }
    Column(Modifier.padding(vertical = 4.dp).semantics(mergeDescendants = true) { contentDescription = description }) {
        for (day in days) {
            val minute = byDay[day]
            Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    day.getDisplayName(TextStyle.SHORT, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                )
                Canvas(Modifier.weight(1f).height(28.dp)) {
                    val y = size.height / 2
                    drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    if (minute != null) {
                        val x = (minute - from).toFloat() / (to - from).coerceAtLeast(1) * size.width
                        drawCircle(ring, radius = 7.dp.toPx(), center = Offset(x, y))
                        drawCircle(dot, radius = 5.dp.toPx(), center = Offset(x, y))
                    }
                }
                Text(
                    minute?.let { minuteText(context, it) }.orEmpty(),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 52.dp)) {
            Text(minuteText(context, from), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                minuteText(context, to.coerceAtMost(24 * 60 - 1)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Столбцы от нуля (D-023, п. 7): одна серия — без легенды, цвет темы; пунктир —
 * ориентир с подписью (цель сна, минута опоздания). Касание столбца — строка с
 * его значением над графиком; TalkBack читает все значения разом.
 * [highlightAbove] — столбцы выше него цветом ошибки: подпись ориентира объясняет цвет.
 */
@Composable
private fun BarChart(
    values: List<Float>,
    labels: List<String>,
    descriptions: List<String>,
    reference: Float?,
    referenceLabel: String?,
    highlightAbove: Float? = null,
) {
    if (values.isEmpty()) return
    var selected by remember(values) { mutableIntStateOf(values.lastIndex) }
    val bar = MaterialTheme.colorScheme.primary
    val alert = MaterialTheme.colorScheme.error
    val grid = MaterialTheme.colorScheme.outlineVariant
    val guide = MaterialTheme.colorScheme.outline
    val top = maxOf(values.max(), reference ?: 0f) * 1.1f
    Column(Modifier.padding(top = 8.dp).semantics(mergeDescendants = true) { contentDescription = descriptions.joinToString("; ") }) {
        Text(
            descriptions.getOrNull(selected).orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .padding(top = 8.dp)
                .pointerInput(values) {
                    detectTapGestures { offset ->
                        selected = (offset.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)
                    }
                },
        ) {
            val gap = 2.dp.toPx()
            val slot = size.width / values.size
            val width = (slot - gap).coerceAtLeast(1f).coerceAtMost(24.dp.toPx())
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            values.forEachIndexed { index, value ->
                if (value <= 0f) return@forEachIndexed
                val height = (value / top * size.height).coerceAtLeast(2.dp.toPx())
                val x = index * slot + (slot - width) / 2
                val color = if (highlightAbove != null && value > highlightAbove) alert else bar
                // Скругление — только у вершины: столбец стоит на нуле.
                drawRoundRect(color, Offset(x, size.height - height), Size(width, height), CornerRadius(4.dp.toPx()))
                drawRect(color, Offset(x, size.height - minOf(height, 4.dp.toPx())), Size(width, minOf(height, 4.dp.toPx())))
                if (index == selected) {
                    drawRect(guide, Offset(x, size.height + 2.dp.toPx()), Size(width, 2.dp.toPx()))
                }
            }
            if (reference != null) {
                val y = size.height - reference / top * size.height
                drawLine(guide, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            labels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (referenceLabel != null) {
            Text(
                "- - " + referenceLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
