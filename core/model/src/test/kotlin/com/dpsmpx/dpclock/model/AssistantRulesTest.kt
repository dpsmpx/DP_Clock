package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AssistantRulesTest {
    @Test
    fun calendarDaysMapToDaysOfWeek() {
        // java.util.Calendar: SUNDAY = 1, MONDAY = 2, …, SATURDAY = 7.
        assertEquals(DayOfWeek.SUNDAY, AssistantRules.dayOf(1))
        assertEquals(DayOfWeek.MONDAY, AssistantRules.dayOf(2))
        assertEquals(DayOfWeek.SATURDAY, AssistantRules.dayOf(7))
        assertNull(AssistantRules.dayOf(0))
        assertNull(AssistantRules.dayOf(8))
    }

    @Test
    fun alarmFromCommand() {
        val weekdays = AssistantRules.alarm("a", 7, 30, listOf(2, 3, 4, 5, 6), "  Работа ", vibrate = false, createdAt = 5L)!!
        assertEquals(Repeat.Weekly(DayOfWeek.entries.take(5).toSet()), weekdays.repeat)
        assertEquals("Работа", weekdays.label)
        assertEquals(false, weekdays.vibrate)
        assertEquals(Repeat.Once, AssistantRules.alarm("b", 6, 0, emptyList(), null, true, 0L)!!.repeat)
        assertNull(AssistantRules.alarm("c", 24, 0, emptyList(), null, true, 0L))
        assertNull(AssistantRules.alarm("c", 7, 60, emptyList(), null, true, 0L))
    }

    @Test
    fun sameAlarmIsReusedNotCopied() {
        val existing = Alarm("old", 7, 0, Repeat.Once, enabled = false, createdAt = 1L, label = "Подъём")
        val wanted = AssistantRules.alarm("new", 7, 0, emptyList(), "Подъём", true, 2L)!!
        assertEquals(existing, AssistantRules.matching(listOf(existing), wanted))
        assertNull(AssistantRules.matching(listOf(existing), wanted.copy(label = "")))
        assertNull(AssistantRules.matching(listOf(existing), wanted.copy(minute = 5)))
    }

    @Test
    fun timerLength() {
        assertEquals(600_000L, AssistantRules.timerMillis(600))
        assertEquals(86_400_000L, AssistantRules.timerMillis(86_400))
        assertNull(AssistantRules.timerMillis(0))
        assertNull(AssistantRules.timerMillis(86_401))
    }

    @Test
    fun nearestAlarmComesWithItsTime() {
        val zone = ZoneId.of("Europe/Moscow")
        val now = LocalDateTime.parse("2026-10-24T22:00").atZone(zone).toInstant()
        val early = Alarm("e", 6, 0, Repeat.Once, enabled = true, createdAt = 1L)
        val late = Alarm("l", 7, 0, Repeat.Once, enabled = true, createdAt = 2L)
        val off = Alarm("o", 5, 0, Repeat.Once, enabled = false, createdAt = 3L)
        val (alarm, at) = AlarmRules.nearestAlarm(listOf(late, early, off), now, zone)!!
        assertEquals("e", alarm.id)
        assertEquals(Instant.parse("2026-10-25T03:00:00Z"), at)
        assertNull(AlarmRules.nearestAlarm(listOf(off), now, zone))
    }
}
