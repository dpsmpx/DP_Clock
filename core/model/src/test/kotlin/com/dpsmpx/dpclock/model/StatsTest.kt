package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class StatsTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private var ids = 0

    private fun at(date: String, time: String): Long = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun alarm(date: String, fired: String, outcome: SignalOutcome, ended: String? = null, scheduled: String = fired, id: String = "a") =
        SignalEvent((ids++).toString(), SignalSource.ALARM, id, "", at(date, scheduled), at(date, fired), outcome, ended?.let { at(date, it) })

    private fun activity(kind: ActivityKind, start: Long, end: Long = start, title: String = "", amount: Long = 0, count: Int = 0) =
        ActivityEvent((ids++).toString(), kind, start, end, title, null, amount, count)

    private val now = Instant.ofEpochMilli(at("2026-11-08", "20:00"))

    @Test
    fun wakeIsTheFirstDismissOfTheMorningWithSnoozesBeforeIt() {
        val signals = listOf(
            alarm("2026-11-02", "07:00:00", SignalOutcome.SNOOZED, ended = "07:00:20"),
            alarm("2026-11-02", "07:10:00", SignalOutcome.SNOOZED, ended = "07:10:40"),
            alarm("2026-11-02", "07:20:00", SignalOutcome.DISMISSED, ended = "07:21:00"),
            // «Не спите?» после подъёма — не второй подъём.
            alarm("2026-11-02", "07:26:00", SignalOutcome.DISMISSED, ended = "07:26:05"),
            // Вечерний будильник — напоминание, не подъём.
            alarm("2026-11-02", "21:00:00", SignalOutcome.DISMISSED, ended = "21:00:10"),
        )
        val days = StatsRules.wakes(signals, zone)
        assertEquals(1, days.size)
        assertEquals(at("2026-11-02", "07:21:00"), days[0].wakeAt)
        assertEquals(2, days[0].snoozes)
        assertEquals(20_000L + 40_000L + 60_000L, days[0].ringMillis)
    }

    @Test
    fun averagesSplitWeekdaysAndWeekendsAndCountOversleeping() {
        val signals = listOf(
            alarm("2026-11-02", "07:00", SignalOutcome.DISMISSED, ended = "07:00"),
            alarm("2026-11-03", "07:30", SignalOutcome.DISMISSED, ended = "07:30"),
            alarm("2026-11-07", "09:00", SignalOutcome.DISMISSED, ended = "09:00"),
            // Стих сам — проспали.
            alarm("2026-11-04", "07:00", SignalOutcome.AUTO_STOPPED, ended = "07:10"),
        )
        val wakes = StatsRules.report(signals, emptyList(), StatsPeriod.WEEK, now, zone, 480).wakes
        // (7:00 + 7:30 + 9:00) / 3 = 7:50.
        assertEquals(7 * 60 + 50, wakes.averageMinute)
        assertEquals(7 * 60 + 15, wakes.weekdayAverageMinute)
        assertEquals(9 * 60, wakes.weekendAverageMinute)
        assertEquals(7 * 60, wakes.byDayOfWeek[DayOfWeek.MONDAY])
        assertEquals(1, wakes.overslept)
    }

    @Test
    fun sleepRunsFromTheLatestBedSignalToTheWake() {
        val signals = listOf(alarm("2026-11-03", "07:00", SignalOutcome.DISMISSED, ended = "07:05"))
        val activities = listOf(
            activity(ActivityKind.BEDTIME, at("2026-11-02", "22:30")),
            activity(ActivityKind.NIGHT_CLOCK, at("2026-11-02", "23:10"), at("2026-11-03", "06:59")),
            // Ночью проснулись и включили заставку — это не «лёг».
            activity(ActivityKind.NIGHT_CLOCK, at("2026-11-03", "05:00"), at("2026-11-03", "05:10")),
        )
        // Заставка на зарядке днём — тоже не «лёг».
        val daytime = activity(ActivityKind.NIGHT_CLOCK, at("2026-11-04", "16:00"), at("2026-11-04", "17:00"))
        val nextMorning = alarm("2026-11-05", "07:00", SignalOutcome.DISMISSED)
        assertEquals(
            1,
            StatsRules.report(signals + nextMorning, activities + daytime, StatsPeriod.WEEK, now, zone, 480).sleep.nights.size,
        )
        val sleep = StatsRules.report(signals, activities, StatsPeriod.WEEK, now, zone, 480).sleep
        assertEquals(listOf(SleepNight(LocalDate.parse("2026-11-03"), at("2026-11-02", "23:10"), at("2026-11-03", "07:05"))), sleep.nights)
        assertEquals((7 * 60 + 55) * 60_000L, sleep.averageMillis)
        assertEquals(480 * 60_000L, sleep.goalMillis)
    }

    @Test
    fun noBedSignalMeansNoSleepEstimate() {
        val signals = listOf(alarm("2026-11-03", "07:00", SignalOutcome.DISMISSED))
        val sleep = StatsRules.report(signals, emptyList(), StatsPeriod.WEEK, now, zone, 480).sleep
        assertEquals(emptyList<SleepNight>(), sleep.nights)
        assertNull(sleep.averageMillis)
    }

    @Test
    fun timersAndStopwatchAreCountedByTitle() {
        val t = at("2026-11-05", "12:00")
        val activities = listOf(
            activity(ActivityKind.TIMER, t, t + 180_000, "Чай", amount = 180_000, count = 1),
            activity(ActivityKind.TIMER, t, t + 180_000, "Чай", amount = 180_000, count = 1),
            activity(ActivityKind.TIMER, t, t + 600_000, "Паста", amount = 600_000, count = 1),
            activity(ActivityKind.SLEEP_TIMER, t, t, amount = 1_800_000),
            activity(ActivityKind.STOPWATCH, t, t + 90_000, amount = 90_000, count = 4),
        )
        val timers = StatsRules.report(emptyList(), activities, StatsPeriod.WEEK, now, zone, 480).timers
        assertEquals(3, timers.count)
        assertEquals(960_000L, timers.totalMillis)
        assertEquals(listOf(TimerUse("Чай", 2, 360_000), TimerUse("Паста", 1, 600_000)), timers.top)
        assertEquals(1, timers.sleepTimers)
        assertEquals(1, timers.stopwatchSessions)
        assertEquals(4, timers.laps)
    }

    @Test
    fun reliabilityCountsLatenessAndMisses() {
        val signals = listOf(
            alarm("2026-11-02", "07:00:02", SignalOutcome.DISMISSED, scheduled = "07:00:00"),
            alarm("2026-11-03", "07:02:00", SignalOutcome.DISMISSED, scheduled = "07:00:00"),
            alarm("2026-11-04", "07:30:00", SignalOutcome.MISSED, scheduled = "07:00:00"),
        )
        val reliability = StatsRules.report(signals, emptyList(), StatsPeriod.WEEK, now, zone, 480).reliability
        assertEquals(3, reliability.signals)
        assertEquals(1, reliability.onTime)
        assertEquals(1, reliability.missed)
        assertEquals(120_000L, reliability.maxLateMillis)
        assertEquals(61_000L, reliability.averageLateMillis)
        assertEquals(listOf(LocalDate.parse("2026-11-02") to 2_000L, LocalDate.parse("2026-11-03") to 120_000L), reliability.maxLateByDay)
    }

    @Test
    fun periodLeavesOlderEventsOutButAllTimeKeepsThem() {
        val signals = listOf(
            alarm("2026-10-01", "07:00", SignalOutcome.DISMISSED),
            alarm("2026-11-06", "07:00", SignalOutcome.DISMISSED),
        )
        assertEquals(1, StatsRules.report(signals, emptyList(), StatsPeriod.WEEK, now, zone, 480).wakes.days.size)
        assertEquals(2, StatsRules.report(signals, emptyList(), StatsPeriod.ALL, now, zone, 480).wakes.days.size)
    }
}
