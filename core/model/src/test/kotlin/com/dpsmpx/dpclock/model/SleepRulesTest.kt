package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class SleepRulesTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(moscow).toInstant()

    @Test
    fun reminderFromTheNearestAlarm() {
        val bedtime = Bedtime(BedtimeMode.FROM_ALARM, sleepMinutes = 8 * 60, leadMinutes = 15)
        val alarm = at("2026-10-27T07:00")
        val reminder = SleepRules.nextReminder(bedtime, alarm, at("2026-10-26T20:00"), moscow)!!
        assertEquals(at("2026-10-26T23:00"), reminder.bedAt)
        assertEquals(at("2026-10-26T22:45"), reminder.remindAt)
        assertEquals(alarm, reminder.alarmAt)
        // Время напомнить прошло — до следующего будильника напоминания нет; будильника нет — тоже.
        assertEquals(null, SleepRules.nextReminder(bedtime, alarm, at("2026-10-26T22:45"), moscow))
        assertEquals(null, SleepRules.nextReminder(bedtime, null, at("2026-10-26T20:00"), moscow))
        assertEquals(null, SleepRules.nextReminder(bedtime.copy(mode = BedtimeMode.OFF), alarm, at("2026-10-26T20:00"), moscow))
    }

    @Test
    fun reminderBySchedule() {
        // 26.10.2026 — понедельник; ложиться в 23:00 по будням.
        val bedtime = Bedtime(BedtimeMode.SCHEDULE, hour = 23, minute = 0, days = DayOfWeek.entries.take(5).toSet(), leadMinutes = 30)
        val monday = SleepRules.nextReminder(bedtime, null, at("2026-10-26T12:00"), moscow)!!
        assertEquals(at("2026-10-26T22:30"), monday.remindAt)
        assertEquals(at("2026-10-26T23:00"), monday.bedAt)
        // В 22:40 напоминать о сегодняшнем поздно — следующее во вторник.
        assertEquals(at("2026-10-27T22:30"), SleepRules.nextReminder(bedtime, null, at("2026-10-26T22:40"), moscow)!!.remindAt)
        // После пятницы — понедельник.
        assertEquals(at("2026-11-02T22:30"), SleepRules.nextReminder(bedtime, null, at("2026-10-30T23:30"), moscow)!!.remindAt)
        assertEquals(null, SleepRules.nextReminder(bedtime.copy(days = emptySet()), null, at("2026-10-26T12:00"), moscow))
    }

    @Test
    fun sleepIfLyingDownNow() {
        assertEquals((7 * 60 + 45) * 60_000L, SleepRules.sleepIfNow(at("2026-10-26T23:00"), at("2026-10-27T07:00")))
        assertEquals(0L, SleepRules.sleepIfNow(at("2026-10-27T06:50"), at("2026-10-27T07:00")))
    }

    @Test
    fun sunriseGoesFromDarkRedToWarmWhite() {
        val start = SleepRules.sunrise(0.0)
        val end = SleepRules.sunrise(1.0)
        assertEquals(Sunrise(0.01f, 40, 0, 0), start)
        assertEquals(255, end.red)
        assertTrue(end.green > 200 && end.blue > 150 && end.brightness == 1f)
        var last = -1f
        for (i in 0..20) {
            val b = SleepRules.sunrise(i / 20.0).brightness
            assertTrue(b >= last)
            last = b
        }
    }

    @Test
    fun templateKeepsGentleWakeAndAfterAlarm() {
        val after = AfterAlarm(presetId = "p", reminder = "Лекарство & вода", reminderMinutes = 15, app = "org.example.news")
        val alarm = Alarm("", 7, 0, Repeat.Once, enabled = true, createdAt = 0, gentleMinutes = 20, after = after)
        val decoded = AlarmTemplate.decodeAlarms(AlarmTemplate("t", "т", listOf(alarm), 0).encodeAlarms()).single()
        assertEquals(20, decoded.gentleMinutes)
        assertEquals(after, decoded.after)
    }
}
