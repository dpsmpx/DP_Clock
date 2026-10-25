package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Сложные и интервальные повторы, предупреждения, куранты (D-025). */
class RepeatRulesTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String, zone: ZoneId = moscow): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun times(vararg text: String) = RuleTimes.At(text.map(LocalTime::parse))

    private fun alarm(repeat: Repeat, skipDate: LocalDate? = null, skipTime: LocalTime? = null) =
        AlarmRules.normalized(Alarm("a", 0, 0, repeat, enabled = true, createdAt = 0L, skipDate = skipDate, skipTime = skipTime))

    /** Несколько ближайших срабатываний местным временем. */
    private fun upcoming(repeat: Repeat, from: String, count: Int, zone: ZoneId = moscow, exceptions: Exceptions = Exceptions.NONE) =
        AlarmRules.upcoming(alarm(repeat), at(from, zone), zone, exceptions, count).map { it.atZone(zone).toLocalDateTime().toString() }

    @Test
    fun everyOtherWeekCountsFromTheAnchorWeek() {
        // 19.10.2026 — понедельник; эта неделя первая, следующая — мимо.
        val weeks = DatePattern.Weeks(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), everyWeeks = 2, anchor = LocalDate.of(2026, 10, 21))
        val repeat = Repeat.Rules(listOf(RepeatRule(weeks, times("07:00"))))
        assertEquals(
            listOf("2026-10-19T07:00", "2026-10-21T07:00", "2026-11-02T07:00", "2026-11-04T07:00"),
            upcoming(repeat, "2026-10-18T12:00", 4),
        )
        // Неделя до первой — тоже через одну.
        assertTrue(weeks.matches(LocalDate.of(2026, 10, 5)))
        assertFalse(weeks.matches(LocalDate.of(2026, 10, 12)))
    }

    @Test
    fun monthDaysSkipMissingDaysAndKnowTheLastOne() {
        val repeat = Repeat.Rules(listOf(RepeatRule(DatePattern.MonthDays(setOf(31, DatePattern.LAST)), times("09:00"))))
        // В ноябре нет 31-го — только последний день; в декабре 31-е и есть последний — одно срабатывание.
        assertEquals(
            listOf("2026-10-31T09:00", "2026-11-30T09:00", "2026-12-31T09:00", "2027-01-31T09:00", "2027-02-28T09:00"),
            upcoming(repeat, "2026-10-20T00:00", 5),
        )
    }

    @Test
    fun nthAndLastWeekdayOfMonth() {
        val second = DatePattern.MonthWeekday(2, DayOfWeek.TUESDAY)
        val last = DatePattern.MonthWeekday(DatePattern.LAST, DayOfWeek.FRIDAY)
        assertTrue(second.matches(LocalDate.of(2026, 10, 13)))
        assertFalse(second.matches(LocalDate.of(2026, 10, 6)))
        assertTrue(last.matches(LocalDate.of(2026, 10, 30)))
        assertFalse(last.matches(LocalDate.of(2026, 10, 23)))
        // Пятого вторника в ноябре 2026 нет — следующий в декабре.
        val fifth = Repeat.Rules(listOf(RepeatRule(DatePattern.MonthWeekday(5, DayOfWeek.TUESDAY), times("10:00"))))
        assertEquals(listOf("2026-12-29T10:00"), upcoming(fifth, "2026-11-01T00:00", 1))
    }

    @Test
    fun leapDayYearlyIsFoundFourYearsAhead() {
        val repeat = Repeat.Rules(listOf(RepeatRule(DatePattern.Yearly(2, 29), times("12:00"))))
        assertEquals(listOf("2028-02-29T12:00", "2032-02-29T12:00"), upcoming(repeat, "2026-10-20T00:00", 2))
    }

    @Test
    fun severalTimesADayAndEveryNMinutesInAWindow() {
        val daily = DatePattern.Weeks(DayOfWeek.entries.toSet())
        val repeat = Repeat.Rules(
            listOf(
                RepeatRule(daily, times("13:00", "08:00", "08:00")),
                RepeatRule(daily, RuleTimes.Every(LocalTime.of(9, 0), LocalTime.of(10, 0), 25)),
            ),
        )
        assertEquals(
            listOf("2026-10-20T08:00", "2026-10-20T09:00", "2026-10-20T09:25", "2026-10-20T09:50", "2026-10-20T13:00", "2026-10-21T08:00"),
            upcoming(repeat, "2026-10-20T07:00", 6),
        )
        assertEquals(LocalTime.of(8, 0), repeat.firstTime)
    }

    @Test
    fun shiftsFilteredByWeekdaysWithTheirOwnTime() {
        // 2/2 с 1 октября, но только в будни; в субботу смена позже.
        val rule = RepeatRule(
            DatePattern.Shifts(LocalDate.of(2026, 10, 1), listOf(true, true, false, false)),
            times("07:00"),
            weekdays = DayOfWeek.entries.take(6).toSet(),
            weekdayTimes = mapOf(DayOfWeek.SATURDAY to LocalTime.of(9, 0)),
        )
        // Рабочие дни цикла: 1, 2, 5, 6, 9, 10 октября; 4-е — воскресенье, но выходное и по циклу.
        assertEquals(
            listOf("2026-10-01T07:00", "2026-10-02T07:00", "2026-10-05T07:00", "2026-10-06T07:00", "2026-10-09T07:00", "2026-10-10T09:00"),
            upcoming(Repeat.Rules(listOf(rule)), "2026-09-30T12:00", 6),
        )
    }

    @Test
    fun exceptPeriodAndUntilCloseDates() {
        val repeat = Repeat.Rules(
            listOf(RepeatRule(DatePattern.Weeks(DayOfWeek.entries.toSet()), times("07:00"), until = LocalDate.of(2026, 10, 24))),
            except = listOf(DatePattern.Weeks(setOf(DayOfWeek.WEDNESDAY)), DatePattern.Dates(setOf(LocalDate.of(2026, 10, 23)))),
        )
        assertEquals(
            listOf("2026-10-20T07:00", "2026-10-22T07:00", "2026-10-24T07:00"),
            upcoming(repeat, "2026-10-19T12:00", 5),
        )
        assertNull(AlarmRules.nextTrigger(alarm(repeat), at("2026-10-24T08:00"), moscow))
    }

    @Test
    fun skipTimeSkipsOnlyThatOccurrence() {
        val repeat = Repeat.Rules(listOf(RepeatRule(DatePattern.Weeks(DayOfWeek.entries.toSet()), times("08:00", "12:00"))))
        val now = at("2026-10-20T07:00")
        val first = AlarmRules.nextTrigger(alarm(repeat), now, moscow)!!
        val skipped = AlarmRules.skipping(alarm(repeat), first, moscow)
        assertEquals(LocalTime.of(8, 0), skipped.skipTime)
        assertTrue(AlarmRules.isSkipped(skipped, first, moscow))
        assertEquals(at("2026-10-20T12:00"), AlarmRules.nextTrigger(skipped, now, moscow))
        assertEquals(first, AlarmRules.nextIgnoringSkip(skipped, now, moscow))
        // У простого повтора пропускается вся дата, как раньше.
        val weekly = alarm(Repeat.Weekly(DayOfWeek.entries.toSet())).copy(hour = 8)
        assertNull(AlarmRules.skipping(weekly, first, moscow).skipTime)
    }

    @Test
    fun globalExceptionsAndDstStillApply() {
        // Берлин, 29.03.2026: 2:00 → 3:00 — срабатывания 2:00–2:45 сливаются в одно в 3:00.
        val repeat = Repeat.Rules(listOf(RepeatRule(DatePattern.Weeks(DayOfWeek.entries.toSet()), RuleTimes.Every(LocalTime.of(1, 30), LocalTime.of(3, 15), 15))))
        assertEquals(
            listOf("2026-03-29T01:30", "2026-03-29T01:45", "2026-03-29T03:00", "2026-03-29T03:15", "2026-03-30T01:30"),
            upcoming(repeat, "2026-03-29T00:00", 5, berlin),
        )
        val holiday = Exceptions(listOf(ExceptionPeriod("e", "", LocalDate.of(2026, 3, 30), LocalDate.of(2026, 3, 30))), holidays = false)
        assertEquals("2026-03-31T01:30", upcoming(repeat, "2026-03-29T04:00", 1, berlin, holiday).single())
    }

    @Test
    fun rulesCodeRoundTripsThroughTemplates() {
        val repeat = Repeat.Rules(
            listOf(
                RepeatRule(DatePattern.MonthWeekday(1, DayOfWeek.MONDAY), RuleTimes.Every(LocalTime.of(8, 0), LocalTime.of(12, 0), 60), from = LocalDate.of(2026, 1, 1)),
                RepeatRule(DatePattern.Workdays, times("06:45"), weekdayTimes = mapOf(DayOfWeek.FRIDAY to LocalTime.of(7, 30))),
            ),
            except = listOf(DatePattern.Yearly(1, 7)),
        )
        assertEquals(repeat, RulesCode.decode(RulesCode.encode(repeat)))
        assertEquals(repeat, RepeatCode.decode(RepeatCode.encode(repeat)))
        val template = AlarmTemplate("t", "x", listOf(alarm(repeat).copy(id = "", warnings = listOf(60, 5))), 0L)
        assertEquals(template.alarms, AlarmTemplate.decodeAlarms(template.encodeAlarms()))
    }

    @Test
    fun warningsComeOneByOneBeforeTheAlarm() {
        val alarmAt = at("2026-10-21T07:00").toEpochMilli()
        val list = Warnings.normalize(listOf(5, 1440, 60, 5, 0, 99_999))
        assertEquals(listOf(1440, 60, 5), list)
        assertEquals(at("2026-10-20T07:00").toEpochMilli() to 1440, Warnings.next(list, alarmAt, at("2026-10-19T12:00").toEpochMilli()))
        // День назад уже прошёл — следующее за час.
        assertEquals(at("2026-10-21T06:00").toEpochMilli() to 60, Warnings.next(list, alarmAt, at("2026-10-20T07:00").toEpochMilli()))
        assertNull(Warnings.next(list, alarmAt, at("2026-10-21T06:55").toEpochMilli()))
        assertEquals(list, Warnings.decode(Warnings.encode(list)))
    }

    @Test
    fun chimesStrikeOnTheHourOutsideQuietTime() {
        val hourly = Chimes(ChimeEvery.HOUR, quietFrom = LocalTime.of(22, 0), quietTo = LocalTime.of(8, 0))
        assertEquals(at("2026-10-20T15:00"), ChimeRules.next(hourly, at("2026-10-20T14:00"), moscow))
        // Ночью тихо — следующий бой в 8:00.
        assertEquals(at("2026-10-21T08:00"), ChimeRules.next(hourly, at("2026-10-20T21:30"), moscow))
        val half = Chimes(ChimeEvery.HALF_HOUR, quiet = false, days = setOf(DayOfWeek.SATURDAY))
        assertEquals(at("2026-10-24T00:00"), ChimeRules.next(half, at("2026-10-20T14:10"), moscow))
        assertEquals(at("2026-10-24T00:30"), ChimeRules.next(half, at("2026-10-24T00:00"), moscow))
        assertNull(ChimeRules.next(Chimes(), at("2026-10-20T14:00"), moscow))
        assertEquals(3, ChimeRules.strikes(hourly, LocalTime.of(15, 0)))
        assertEquals(12, ChimeRules.strikes(hourly, LocalTime.of(0, 0)))
        assertEquals(1, ChimeRules.strikes(hourly, LocalTime.of(15, 30)))
        assertEquals(1, ChimeRules.strikes(hourly.copy(countHours = false), LocalTime.of(15, 0)))
        assertEquals(half, Chimes.decode(half.encode()))
    }

    @Test
    fun chimesStayOnTheHourAcrossDst() {
        // Берлин, 25.10.2026: 3:00 летнего → 2:00 зимнего; бой каждый час по местному.
        val hourly = Chimes(ChimeEvery.HOUR, quiet = false)
        val first = ChimeRules.next(hourly, at("2026-10-25T01:10", berlin), berlin)!!
        val second = ChimeRules.next(hourly, first, berlin)!!
        val third = ChimeRules.next(hourly, second, berlin)!!
        assertEquals(listOf(0, 0, 0), listOf(first, second, third).map { it.atZone(berlin).minute })
        assertEquals(3_600_000L, second.toEpochMilli() - first.toEpochMilli())
        assertEquals(3_600_000L, third.toEpochMilli() - second.toEpochMilli())
    }
}
