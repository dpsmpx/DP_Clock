package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmCountdownTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(text: String, zone: ZoneId = moscow): Instant =
        LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun parts(from: String, to: String, zone: ZoneId = moscow) =
        AlarmCountdown.parts(at(from, zone), at(to, zone), zone)

    @Test
    fun roundsSubSecondDurationsUpAndUsesSecondsBelowAMinute() {
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.SECONDS)),
            parts("2026-10-19T06:59:59", "2026-10-19T06:59:59.001"),
        )
        assertEquals(
            listOf(CountdownPart(59, CountdownUnit.SECONDS)),
            parts("2026-10-19T06:59:00", "2026-10-19T06:59:58.001"),
        )
    }

    @Test
    fun changesUnitsAtMinuteAndHourBoundaries() {
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.MINUTES)),
            parts("2026-10-19T06:00", "2026-10-19T06:01"),
        )
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.HOURS), CountdownPart(1, CountdownUnit.MINUTES)),
            parts("2026-10-19T06:00", "2026-10-19T07:01"),
        )
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.HOURS), CountdownPart(2, CountdownUnit.SECONDS)),
            parts("2026-10-19T06:00", "2026-10-19T07:00:02"),
        )
    }

    @Test
    fun usesCalendarMonthsInsteadOfFixedThirtyDayMonths() {
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.MONTHS)),
            parts("2026-01-31T12:00", "2026-02-28T12:00"),
        )
        assertEquals(
            listOf(CountdownPart(27, CountdownUnit.DAYS)),
            parts("2026-01-31T12:00", "2026-02-27T12:00"),
        )
    }

    @Test
    fun treatsLeapDayToNextFebruaryAsOneCalendarYear() {
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.YEARS)),
            parts("2024-02-29T09:00", "2025-02-28T09:00"),
        )
    }

    @Test
    fun displaysYearsMonthsAndDaysTogetherWhenUseful() {
        assertEquals(
            listOf(
                CountdownPart(2, CountdownUnit.YEARS),
                CountdownPart(3, CountdownUnit.MONTHS),
                CountdownPart(3, CountdownUnit.DAYS),
            ),
            parts("2025-01-31T12:00", "2027-05-03T12:00"),
        )
    }

    @Test
    fun aLocalCalendarDayStaysOneDayAcrossDaylightSavingChange() {
        assertEquals(
            listOf(CountdownPart(1, CountdownUnit.DAYS)),
            parts("2026-03-28T12:00", "2026-03-29T12:00", berlin),
        )
    }

    @Test
    fun emptyForMomentsThatHaveAlreadyPassed() {
        assertTrue(parts("2026-10-19T07:00", "2026-10-19T06:59").isEmpty())
        assertTrue(parts("2026-10-19T07:00", "2026-10-19T07:00").isEmpty())
    }
}
