package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Что человек делал в приложении (D-023, п. 7): основа статистики сна, таймеров и секундомера. */
enum class ActivityKind {
    /** Ночные часы или заставка: с открытия до закрытия. */
    NIGHT_CLOCK,

    /** Запущен таймер засыпания; [ActivityEvent.amountMillis] — его длина. */
    SLEEP_TIMER,

    /** Напоминание «пора спать» пришло; [ActivityEvent.startedAt] — когда ложиться. */
    BEDTIME,

    /** Таймер кончился или убран; длина программы, [ActivityEvent.count] — 1, если дошёл до конца. */
    TIMER,

    /** Секундомер сброшен: сколько шёл и сколько кругов. */
    STOPWATCH,
}

/** Запись о действии (D-023, п. 7), мс UTC. Только на телефоне: в перенос не входит. */
data class ActivityEvent(
    val id: String,
    val kind: ActivityKind,
    val startedAt: Long,
    val endedAt: Long,
    val title: String = "",
    val refId: String? = null,
    val amountMillis: Long = 0L,
    val count: Int = 0,
)

/** Период статистики; [days] — null: всё время. */
enum class StatsPeriod(val days: Long?) { WEEK(7), MONTH(30), YEAR(365), ALL(null) }

/** Утро (D-023, п. 7): во сколько выключили будильник, сколько раз отложили, сколько звенел; [wakeAt] — null, если проспали. */
data class WakeDay(val date: LocalDate, val wakeAt: Long?, val snoozes: Int, val ringMillis: Long)

/** Ночь: от «лёг» (последнее из «пора спать», ночных часов, таймера засыпания) до подъёма. */
data class SleepNight(val date: LocalDate, val bedAt: Long, val wakeAt: Long) {
    val millis: Long get() = wakeAt - bedAt
}

/** Таймер по названию: сколько раз и сколько всего. */
data class TimerUse(val title: String, val count: Int, val totalMillis: Long)

data class WakeStats(
    val days: List<WakeDay>,
    /** Средний подъём, минута суток; null — подъёмов нет. */
    val averageMinute: Int?,
    val weekdayAverageMinute: Int?,
    val weekendAverageMinute: Int?,
    /** Средний подъём по дням недели, минута суток. */
    val byDayOfWeek: Map<DayOfWeek, Int>,
    val snoozes: Int,
    val averageRingMillis: Long?,
    /** Утра, когда будильник стих сам или пропущен, а выключить его никто не выключил. */
    val overslept: Int,
)

data class SleepStats(val nights: List<SleepNight>, val averageMillis: Long?, val goalMillis: Long)

data class TimerStats(
    val count: Int,
    val totalMillis: Long,
    val top: List<TimerUse>,
    val sleepTimers: Int,
    val stopwatchSessions: Int,
    val stopwatchMillis: Long,
    val laps: Int,
)

data class ReliabilityStats(
    val signals: Int,
    /** Опоздали не больше чем на [StatsRules.ON_TIME_MILLIS]. */
    val onTime: Int,
    val averageLateMillis: Long?,
    val maxLateMillis: Long?,
    val missed: Int,
    /** Самое большое опоздание каждого дня с сигналами. */
    val maxLateByDay: List<Pair<LocalDate, Long>>,
)

data class StatsReport(val wakes: WakeStats, val sleep: SleepStats, val timers: TimerStats, val reliability: ReliabilityStats)

/** Статистика (D-023, п. 7): считается на телефоне из журнала срабатываний и действий. Чистые функции. */
object StatsRules {
    /** Опоздание не больше минуты — вовремя: столько уходит на пробуждение телефона. */
    const val ON_TIME_MILLIS = 60_000L

    /** Подъём — будильник, зазвеневший с 3:00 до 14:00; днём и вечером — напоминания, не подъёмы. */
    private val MORNING_FROM: LocalTime = LocalTime.of(3, 0)
    private val MORNING_TO: LocalTime = LocalTime.of(14, 0)

    /** «Лёг» ищется за 3–16 часов до подъёма: позже — проснулись ночью, раньше — вчерашний день. */
    private const val BED_MIN_MILLIS = 3 * 3_600_000L
    private const val BED_MAX_MILLIS = 16 * 3_600_000L

    /** «Лёг» — только вечером и ночью, с 19:00 до 5:00: заставка на зарядке днём — не сон. */
    private val BED_FROM: LocalTime = LocalTime.of(19, 0)
    private val BED_TO: LocalTime = LocalTime.of(5, 0)

    private const val TOP_TIMERS = 3

    fun report(
        signals: List<SignalEvent>,
        activities: List<ActivityEvent>,
        period: StatsPeriod,
        now: Instant,
        zone: ZoneId,
        sleepGoalMinutes: Int,
    ): StatsReport {
        val from = period.days?.let { now.atZone(zone).toLocalDate().minusDays(it - 1).atStartOfDay(zone).toInstant().toEpochMilli() } ?: Long.MIN_VALUE
        val to = now.toEpochMilli()
        val inSignals = signals.filter { it.firedAt in from..to }
        val inActivities = activities.filter { it.endedAt in from..to || it.startedAt in from..to }
        val wakes = wakes(inSignals, zone)
        return StatsReport(
            wakes = wakeStats(wakes, zone),
            sleep = sleepStats(wakes, activities, sleepGoalMinutes, zone),
            timers = timerStats(inActivities),
            reliability = reliability(inSignals, zone),
        )
    }

    /** Утра по датам: первое «Выключить» утреннего будильника — подъём; отложенное до него — в счёт. */
    fun wakes(signals: List<SignalEvent>, zone: ZoneId): List<WakeDay> =
        signals
            .filter { it.source == SignalSource.ALARM }
            .filter { event ->
                val time = Instant.ofEpochMilli(event.firedAt).atZone(zone).toLocalTime()
                !time.isBefore(MORNING_FROM) && time.isBefore(MORNING_TO)
            }
            .groupBy { Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate() }
            .map { (date, events) ->
                val sorted = events.sortedBy { it.firedAt }
                val wake = sorted.firstOrNull { it.outcome == SignalOutcome.DISMISSED }
                val before = if (wake == null) sorted else sorted.filter { it.firedAt <= wake.firedAt }
                WakeDay(
                    date = date,
                    wakeAt = wake?.let { it.endedAt ?: it.firedAt },
                    snoozes = before.count { it.outcome == SignalOutcome.SNOOZED },
                    ringMillis = before.filter { it.outcome != SignalOutcome.MISSED }.sumOf { ((it.endedAt ?: it.firedAt) - it.firedAt).coerceAtLeast(0L) },
                )
            }
            .sortedBy { it.date }

    private fun wakeStats(days: List<WakeDay>, zone: ZoneId): WakeStats {
        val woke = days.filter { it.wakeAt != null }
        fun minuteOf(day: WakeDay): Int = Instant.ofEpochMilli(day.wakeAt!!).atZone(zone).toLocalTime().let { it.hour * 60 + it.minute }
        fun average(list: List<WakeDay>): Int? = list.takeIf { it.isNotEmpty() }?.map(::minuteOf)?.average()?.let { Math.round(it).toInt() }
        val weekend = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        return WakeStats(
            days = days,
            averageMinute = average(woke),
            weekdayAverageMinute = average(woke.filter { it.date.dayOfWeek !in weekend }),
            weekendAverageMinute = average(woke.filter { it.date.dayOfWeek in weekend }),
            byDayOfWeek = woke.groupBy { it.date.dayOfWeek }.mapValues { (_, list) -> average(list)!! },
            snoozes = days.sumOf { it.snoozes },
            averageRingMillis = woke.takeIf { it.isNotEmpty() }?.map { it.ringMillis }?.average()?.let { Math.round(it) },
            overslept = days.count { it.wakeAt == null },
        )
    }

    /** Ночи: подъём и самое позднее «лёг» за 3–16 часов до него, вечером или ночью; без «лёг» ночи нет. */
    private fun sleepStats(wakes: List<WakeDay>, activities: List<ActivityEvent>, goalMinutes: Int, zone: ZoneId): SleepStats {
        val bedKinds = setOf(ActivityKind.NIGHT_CLOCK, ActivityKind.SLEEP_TIMER, ActivityKind.BEDTIME)
        val beds = activities.filter { it.kind in bedKinds }.map { it.startedAt }.filter { at ->
            val time = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
            !time.isBefore(BED_FROM) || time.isBefore(BED_TO)
        }
        val nights = wakes.mapNotNull { day ->
            val wakeAt = day.wakeAt ?: return@mapNotNull null
            val bedAt = beds.filter { it in (wakeAt - BED_MAX_MILLIS)..(wakeAt - BED_MIN_MILLIS) }.maxOrNull() ?: return@mapNotNull null
            SleepNight(day.date, bedAt, wakeAt)
        }
        return SleepStats(
            nights = nights,
            averageMillis = nights.takeIf { it.isNotEmpty() }?.map { it.millis }?.average()?.let { Math.round(it) },
            goalMillis = goalMinutes * 60_000L,
        )
    }

    private fun timerStats(activities: List<ActivityEvent>): TimerStats {
        val timers = activities.filter { it.kind == ActivityKind.TIMER }
        val stopwatch = activities.filter { it.kind == ActivityKind.STOPWATCH }
        return TimerStats(
            count = timers.size,
            totalMillis = timers.sumOf { it.amountMillis },
            top = timers.groupBy { it.title.trim() }
                .map { (title, list) -> TimerUse(title, list.size, list.sumOf { it.amountMillis }) }
                .sortedWith(compareByDescending<TimerUse> { it.count }.thenByDescending { it.totalMillis }.thenBy { it.title })
                .take(TOP_TIMERS),
            sleepTimers = activities.count { it.kind == ActivityKind.SLEEP_TIMER },
            stopwatchSessions = stopwatch.size,
            stopwatchMillis = stopwatch.sumOf { it.amountMillis },
            laps = stopwatch.sumOf { it.count },
        )
    }

    /** Надёжность сигналов будильников и таймеров: опоздания и пропуски. */
    fun reliability(signals: List<SignalEvent>, zone: ZoneId): ReliabilityStats {
        val sounded = signals.filter { it.outcome != SignalOutcome.MISSED }
        val lates = sounded.mapNotNull { it.lateMillis }
        return ReliabilityStats(
            signals = signals.size,
            onTime = lates.count { it <= ON_TIME_MILLIS },
            averageLateMillis = lates.takeIf { it.isNotEmpty() }?.average()?.let { Math.round(it) },
            maxLateMillis = lates.maxOrNull(),
            missed = signals.count { it.outcome == SignalOutcome.MISSED },
            maxLateByDay = sounded
                .groupBy { Instant.ofEpochMilli(it.firedAt).atZone(zone).toLocalDate() }
                .map { (date, list) -> date to (list.mapNotNull { it.lateMillis }.maxOrNull() ?: 0L) }
                .sortedBy { it.first },
        )
    }
}
