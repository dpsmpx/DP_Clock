package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarRulesTest {
    private val zone = ZoneId.of("Europe/Moscow")
    private val day = 86_400_000L

    private fun at(date: String, time: String): Long = LocalDateTime.of(LocalDate.parse(date), LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    /** Событие на весь день — как в Android: полночи UTC. */
    private fun allDay(from: String, days: Int, title: String = ""): CalendarInstance {
        val begin = LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarInstance(begin, begin + days * day, allDay = true, title = title)
    }

    private fun timed(date: String, time: String, minutes: Long = 60, title: String = ""): CalendarInstance {
        val begin = at(date, time)
        return CalendarInstance(begin, begin + minutes * 60_000L, allDay = false, title = title)
    }

    @Test
    fun allDayEventsCoverTheirDaysAndShortEventsDoNot() {
        val dates = CalendarRules.exceptionDates(
            listOf(
                allDay("2026-11-04", 1),
                allDay("2026-12-29", 3),
                timed("2026-11-10", "10:00"),
            ),
            zone,
        )
        assertEquals(
            setOf("2026-11-04", "2026-12-29", "2026-12-30", "2026-12-31").map(LocalDate::parse).toSet(),
            dates,
        )
    }

    @Test
    fun timedEventLongerThanADayCoversEveryLocalDay() {
        // Командировка: с 18:00 пятницы до полуночи понедельника — пятница, суббота, воскресенье.
        val begin = at("2026-11-06", "18:00")
        val end = at("2026-11-09", "00:00")
        val dates = CalendarRules.exceptionDates(listOf(CalendarInstance(begin, end, allDay = false)), zone)
        assertEquals(setOf("2026-11-06", "2026-11-07", "2026-11-08").map(LocalDate::parse).toSet(), dates)
    }

    @Test
    fun calendarDatesSilenceAlarmsThatHonorExceptions() {
        val alarm = Alarm("a", 7, 0, Repeat.Weekly(DayOfWeek.entries.toSet()), enabled = true, createdAt = 0)
        val exceptions = Exceptions(calendarDates = setOf(LocalDate.parse("2026-11-04")))
        val next = AlarmRules.nextTrigger(alarm, java.time.Instant.ofEpochMilli(at("2026-11-03", "08:00")), zone, exceptions)
        assertEquals(at("2026-11-05", "07:00"), next?.toEpochMilli())
    }

    @Test
    fun earlyEventGetsAWakeBeforeItWhenTheUsualAlarmIsLater() {
        val now = java.time.Instant.ofEpochMilli(at("2026-11-02", "22:00"))
        val usual = Alarm("u", 8, 0, Repeat.Weekly(DayOfWeek.entries.toSet()), enabled = true, createdAt = 0)
        val wakes = CalendarRules.earlyWakes(
            listOf(timed("2026-11-03", "09:00"), timed("2026-11-03", "11:00"), timed("2026-11-04", "08:30")),
            listOf(usual),
            Exceptions.NONE,
            leadMinutes = 90,
            now = now,
            zone = zone,
        )
        // 3 ноября подъём в 7:30 — раньше обычного 8:00; 4 ноября 7:00 — тоже раньше.
        assertEquals(
            listOf(
                CalendarWake(LocalDate.parse("2026-11-03"), LocalTime.of(7, 30), at("2026-11-03", "09:00")),
                CalendarWake(LocalDate.parse("2026-11-04"), LocalTime.of(7, 0), at("2026-11-04", "08:30")),
            ),
            wakes,
        )
    }

    @Test
    fun noWakeWhenTheUsualAlarmIsEarlyEnoughOrTheEventIsAfterNoon() {
        val now = java.time.Instant.ofEpochMilli(at("2026-11-02", "22:00"))
        val usual = Alarm("u", 7, 0, Repeat.Weekly(DayOfWeek.entries.toSet()), enabled = true, createdAt = 0)
        val wakes = CalendarRules.earlyWakes(
            listOf(timed("2026-11-03", "09:00"), timed("2026-11-04", "13:00")),
            listOf(usual),
            Exceptions.NONE,
            leadMinutes = 60,
            now = now,
            zone = zone,
        )
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun dayOffWithoutAlarmStillGetsAWake() {
        // В выходной без будильника раннее событие будит само.
        val now = java.time.Instant.ofEpochMilli(at("2026-11-06", "23:00"))
        val weekdays = Alarm("w", 7, 0, Repeat.Weekly(DayOfWeek.entries.take(5).toSet()), enabled = true, createdAt = 0)
        val wakes = CalendarRules.earlyWakes(listOf(timed("2026-11-07", "10:00")), listOf(weekdays), Exceptions.NONE, 60, now, zone)
        assertEquals(listOf(CalendarWake(LocalDate.parse("2026-11-07"), LocalTime.of(9, 0), at("2026-11-07", "10:00"))), wakes)
    }

    @Test
    fun wakeThatAlreadyPassedOrFallsOnTheDayBeforeIsDropped() {
        val now = java.time.Instant.ofEpochMilli(at("2026-11-03", "08:00"))
        val wakes = CalendarRules.earlyWakes(
            listOf(timed("2026-11-03", "09:00"), timed("2026-11-04", "00:30")),
            emptyList(),
            Exceptions.NONE,
            leadMinutes = 90,
            now = now,
            zone = zone,
        )
        assertTrue(wakes.isEmpty())
    }

    @Test
    fun calendarAlarmsDoNotCoverOtherEvents() {
        val now = java.time.Instant.ofEpochMilli(at("2026-11-02", "22:00"))
        val earlier = Alarm("c", 6, 0, Repeat.OnDate(LocalDate.parse("2026-11-03")), enabled = true, createdAt = 0, calendarEventAt = 1L)
        val wakes = CalendarRules.earlyWakes(listOf(timed("2026-11-03", "09:00")), listOf(earlier), Exceptions.NONE, 60, now, zone)
        assertEquals(1, wakes.size)
    }

    @Test
    fun nextEventIsTheFirstTimedOneLaterToday() {
        val now = java.time.Instant.ofEpochMilli(at("2026-11-03", "09:30"))
        val events = listOf(
            allDay("2026-11-03", 1, "Отпуск"),
            timed("2026-11-03", "09:00", title = "Прошло"),
            timed("2026-11-03", "15:00", title = "Позже"),
            timed("2026-11-03", "10:00", title = "Планёрка"),
            timed("2026-11-04", "08:00", title = "Завтра"),
        )
        assertEquals("Планёрка", CalendarRules.nextEvent(events, now, zone)?.title)
        assertNull(CalendarRules.nextEvent(events.take(2), now, zone))
    }

    @Test
    fun dayEventsListAllDayFirstThenByTime() {
        val events = listOf(
            timed("2026-11-03", "13:00", title = "Обед"),
            allDay("2026-11-02", 3, "Командировка"),
            timed("2026-11-03", "09:00", title = "Планёрка"),
            timed("2026-11-04", "09:00", title = "Другой день"),
        )
        assertEquals(
            listOf("Командировка", "Планёрка", "Обед"),
            CalendarRules.dayEvents(events, LocalDate.parse("2026-11-03"), zone).map { it.title },
        )
    }
}
