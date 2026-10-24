package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WorldClockTest {
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test
    fun zonesComeFromTheTzTable() {
        assertTrue(Zones.all.size > 300)
        val zone = Zones.find("Europe/Moscow")!!
        assertEquals("RU", zone.country)
        assertEquals(55.76, zone.latitude, 0.01)
        assertEquals(Zones.all.size, Zones.all.map { it.id }.toSet().size)
        // Ближайший главный город: Химки — к Москве; посреди океана — никого.
        assertEquals("Europe/Moscow", Zones.nearest(55.89, 37.43)?.id)
        assertNull(Zones.nearest(0.0, -140.0))
    }

    @Test
    fun offsetsFollowSummerTimeOfBothCities() {
        val london = ZoneId.of("Europe/London")
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val summer = Instant.parse("2026-07-15T12:00:00Z")
        assertEquals(-180, WorldClockRules.offsetMinutes(london, moscow, winter))
        assertEquals(-120, WorldClockRules.offsetMinutes(london, moscow, summer))
        assertEquals(150, WorldClockRules.offsetMinutes(ZoneId.of("Asia/Kolkata"), moscow, winter))
        assertEquals(0, WorldClockRules.offsetMinutes(moscow, moscow, winter))
    }

    @Test
    fun dayShiftAcrossMidnight() {
        // 23:30 в Москве — в Токио уже завтра, в Нью-Йорке ещё сегодня.
        val late = Instant.parse("2026-10-24T20:30:00Z")
        assertEquals(1, WorldClockRules.dayShift(ZoneId.of("Asia/Tokyo"), moscow, late))
        assertEquals(0, WorldClockRules.dayShift(ZoneId.of("America/New_York"), moscow, late))
        // 01:00 в Москве — в Нью-Йорке ещё вчера.
        assertEquals(-1, WorldClockRules.dayShift(ZoneId.of("America/New_York"), moscow, Instant.parse("2026-10-24T22:00:00Z")))
    }

    @Test
    fun daylightBySunNotByClock() {
        // Москва: полдень — день, полночь — ночь.
        assertTrue(WorldClockRules.isDaylight(55.76, 37.62, Instant.parse("2026-06-21T09:00:00Z")))
        assertFalse(WorldClockRules.isDaylight(55.76, 37.62, Instant.parse("2026-06-21T21:30:00Z")))
        // Шпицберген: в июне полночное солнце, в декабре полярная ночь даже в полдень.
        assertTrue(WorldClockRules.isDaylight(78.0, 16.0, Instant.parse("2026-06-21T23:00:00Z")))
        assertFalse(WorldClockRules.isDaylight(78.0, 16.0, Instant.parse("2026-12-21T11:00:00Z")))
        // Высота солнца в полдень равноденствия на экваторе — почти 90°.
        assertEquals(90.0, Sun.elevation(0.0, 0.0, Instant.parse("2026-03-20T12:07:00Z")), 1.0)
    }

    @Test
    fun sunriseInMoscowAtTheRightMinute() {
        // Восход 24 октября 2026 в Москве — около 07:18 по местному, 04:18 UTC (r-astro.com: 25 октября — 07:20).
        assertFalse(WorldClockRules.isDaylight(55.76, 37.62, Instant.parse("2026-10-24T04:11:00Z")))
        assertTrue(WorldClockRules.isDaylight(55.76, 37.62, Instant.parse("2026-10-24T04:26:00Z")))
        assertNotNull(Zones.find("Arctic/Longyearbyen"))
    }
}
