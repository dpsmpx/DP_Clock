package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Сменные графики, рабочие дни, исключения и шаблоны (D-014). */
class SchedulesTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(moscow).toInstant()

    private fun alarm(repeat: Repeat, hour: Int = 7, minute: Int = 0, honor: Boolean = true) =
        Alarm("a", hour, minute, repeat, enabled = true, createdAt = 0, honorExceptions = honor)

    private fun times(list: List<Instant>) = list.map { it.atZone(moscow).toLocalDateTime().toString() }

    @Test
    fun twoByTwoCycleFollowsAnchor() {
        val cycle = Repeat.Cycle(LocalDate.parse("2026-10-24"), CyclePreset.TWO_BY_TWO.days(LocalTime.of(7, 0), LocalTime.of(19, 0)))
        val got = AlarmRules.upcoming(alarm(cycle), at("2026-10-24T06:00"), moscow, Exceptions.NONE, 4)
        assertEquals(listOf("2026-10-24T07:00", "2026-10-25T07:00", "2026-10-28T07:00", "2026-10-29T07:00"), times(got))
    }

    @Test
    fun dayNightCycleUsesItsOwnTimeEachDay() {
        val cycle = Repeat.Cycle(LocalDate.parse("2026-10-20"), CyclePreset.DAY_NIGHT.days(LocalTime.of(6, 30), LocalTime.of(17, 0)))
        // Опорная дата в прошлом: цикл идёт и до неё, и после.
        val got = AlarmRules.upcoming(alarm(cycle), at("2026-10-24T00:00"), moscow, Exceptions.NONE, 3)
        assertEquals(listOf("2026-10-24T06:30", "2026-10-25T17:00", "2026-10-28T06:30"), times(got))
        assertEquals(LocalTime.of(17, 0), cycle.timeOn(LocalDate.parse("2026-10-17")))
        assertEquals(LocalTime.of(6, 30), cycle.firstTime)
    }

    @Test
    fun cycleDaysRoundTrip() {
        val days = listOf(LocalTime.of(7, 5), null, LocalTime.of(23, 59), null)
        assertEquals(days, Repeat.Cycle.decodeDays(Repeat.Cycle(LocalDate.EPOCH, days).encodeDays()))
    }

    @Test
    fun workdaysSkipHolidaysAndRingOnWorkingSaturday() {
        val got = AlarmRules.upcoming(alarm(Repeat.Workdays), at("2027-02-19T08:00"), moscow, Exceptions.NONE, 3)
        // 20.02.2027 — рабочая суббота; 22-е и 23-е — выходные.
        assertEquals(listOf("2027-02-20T07:00", "2027-02-24T07:00", "2027-02-25T07:00"), times(got))
    }

    @Test
    fun exceptionsSkipPeriodsAndHolidaysForRepeatingAlarmsOnly() {
        val weekdays = alarm(Repeat.Weekly(DayOfWeek.entries.take(5).toSet()))
        val vacation = ExceptionPeriod("v", "Отпуск", LocalDate.parse("2026-10-26"), LocalDate.parse("2026-11-06"))
        val exceptions = Exceptions(listOf(vacation), holidays = true)
        // Отпуск до пятницы 6 ноября; 4 ноября — праздник внутри него.
        assertEquals(at("2026-11-09T07:00"), AlarmRules.nextTrigger(weekdays, at("2026-10-24T12:00"), moscow, exceptions))
        assertEquals(at("2026-10-26T07:00"), AlarmRules.nextTrigger(weekdays.copy(honorExceptions = false), at("2026-10-24T12:00"), moscow, exceptions))
        // Однократный звонит, когда сказано.
        val once = alarm(Repeat.Once)
        assertEquals(at("2026-10-27T07:00"), AlarmRules.nextTrigger(once, at("2026-10-26T12:00"), moscow, exceptions))
        // Только праздники: 4 ноября 2026 (среда) пропускается.
        val wednesdays = alarm(Repeat.Weekly(setOf(DayOfWeek.WEDNESDAY)))
        assertEquals(at("2026-11-11T07:00"), AlarmRules.nextTrigger(wednesdays, at("2026-11-01T00:00"), moscow, Exceptions(holidays = true)))
        assertEquals(vacation, exceptions.periodOf(LocalDate.parse("2026-11-04")))
    }

    @Test
    fun exceptionsCoveringEverythingMeanNoTrigger() {
        val daily = alarm(Repeat.Weekly(DayOfWeek.entries.toSet()))
        val forever = ExceptionPeriod("f", "", LocalDate.parse("2026-01-01"), LocalDate.parse("2030-12-31"))
        assertEquals(null, AlarmRules.nextTrigger(daily, at("2026-10-24T12:00"), moscow, Exceptions(listOf(forever))))
    }

    @Test
    fun templateKeepsEverythingButIdentity() {
        val group = AlarmGroup("g", "Работа", 1)
        val alarms = listOf(
            Alarm("1", 6, 30, Repeat.Workdays, enabled = false, createdAt = 5, label = "Подъём & кофе", sound = AlarmSound.Builtin("dawn"), groupId = "g"),
            Alarm(
                "2", 6, 40, Repeat.Cycle(LocalDate.parse("2026-10-24"), listOf(LocalTime.of(6, 40), null)), enabled = true, createdAt = 6,
                sound = AlarmSound.Ringtone("content://x?a=1&b=2", "Утро"), vibrate = false, rampSeconds = 30, snoozeMinutes = 5,
                skipDate = LocalDate.parse("2026-10-26"), honorExceptions = false, groupId = "g",
            ),
        )
        val template = AlarmTemplate.from(group, alarms, id = "t", now = 9)
        val decoded = AlarmTemplate.decodeAlarms(template.encodeAlarms())
        assertEquals(template.alarms, decoded)
        assertEquals("Работа", template.name)
        assertTrue(decoded.all { it.enabled && it.groupId == null && it.skipDate == null && it.id.isEmpty() })
        for (repeat in listOf(Repeat.Once, Repeat.fromBits(0b1010101), Repeat.OnDate(LocalDate.parse("2027-01-01")), Repeat.Workdays)) {
            assertEquals(repeat, RepeatCode.decode(RepeatCode.encode(repeat)))
        }
    }
}
