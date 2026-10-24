package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Сверка с производственными календарями (КонсультантПлюс, 24.10.2026):
 * нерабочие будние дни и рабочие выходные, число рабочих дней.
 */
class RuCalendarTest {
    private fun d(text: String) = LocalDate.parse(text)

    private fun weekdaysOff(year: Int): List<LocalDate> {
        val start = LocalDate.of(year, 1, 1)
        return (0 until start.lengthOfYear()).map { start.plusDays(it.toLong()) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY && !RuCalendar.isWorkingDay(it) }
    }

    private fun workingDays(year: Int): Int {
        val start = LocalDate.of(year, 1, 1)
        return (0 until start.lengthOfYear()).count { RuCalendar.isWorkingDay(start.plusDays(it.toLong())) }
    }

    @Test
    fun year2026MatchesDecree1466AndLabourCode() {
        val expected = listOf(
            "2026-01-01", "2026-01-02", "2026-01-05", "2026-01-06", "2026-01-07", "2026-01-08", "2026-01-09",
            "2026-02-23", "2026-03-09", "2026-05-01", "2026-05-11", "2026-06-12", "2026-11-04", "2026-12-31",
        ).map(::d)
        assertEquals(expected, weekdaysOff(2026))
        assertEquals(247, workingDays(2026))
    }

    @Test
    fun year2027MatchesDecree1187AndLabourCode() {
        val expected = listOf(
            "2027-01-01", "2027-01-04", "2027-01-05", "2027-01-06", "2027-01-07", "2027-01-08",
            "2027-02-22", "2027-02-23", "2027-03-08", "2027-05-03", "2027-05-10", "2027-06-14",
            "2027-11-04", "2027-11-05", "2027-12-31",
        ).map(::d)
        assertEquals(expected, weekdaysOff(2027))
        // Суббота 20 февраля — рабочая по переносу.
        assertTrue(RuCalendar.isWorkingDay(d("2027-02-20")))
        assertEquals(247, workingDays(2027))
    }

    @Test
    fun holidaysOnWeekendsAreDaysOffButNotWorkingWeekends() {
        assertTrue(RuCalendar.isDayOff(d("2026-01-03")))
        assertTrue(RuCalendar.isDayOff(d("2026-03-08")))
        assertTrue(RuCalendar.isDayOff(d("2026-03-09")))
        assertFalse(RuCalendar.isDayOff(d("2026-03-10")))
        assertFalse(RuCalendar.isDayOff(d("2027-02-20")))
        assertFalse(RuCalendar.isDayOff(d("2026-10-24")))
    }

    @Test
    fun yearWithoutDecreeUsesLabourCodeOnly() {
        // 2028: 1 января — суббота; январские выходные без постановления не переносятся.
        assertFalse(2028 in RuCalendar.decreedYears)
        assertTrue(RuCalendar.isDayOff(d("2028-01-03")))
        assertTrue(RuCalendar.isWorkingDay(d("2028-01-10")))
        // 23 февраля 2028 — среда; 8 марта — среда; 9 мая — вторник; 12 июня — понедельник.
        assertFalse(RuCalendar.isWorkingDay(d("2028-02-23")))
        assertTrue(RuCalendar.isWorkingDay(d("2028-02-24")))
        // 4 ноября 2028 — суббота: выходной переносится на понедельник, 6 ноября.
        assertFalse(RuCalendar.isWorkingDay(d("2028-11-06")))
        assertTrue(RuCalendar.isWorkingDay(d("2028-11-07")))
    }
}
