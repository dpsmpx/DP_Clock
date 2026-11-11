package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SunSignalsTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val lat = 55.7558
    private val lon = 37.6173

    private fun at(text: String, zone: ZoneId = moscow): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun assertNear(expected: Instant, actual: Instant?, minutes: Long = 3) {
        assertNotNull(actual)
        assertTrue("ожидалось $expected, вышло $actual", Duration.between(expected, actual).abs() <= Duration.ofMinutes(minutes))
    }

    @Test
    fun sunriseAndSunsetInMoscow() {
        // Справочные времена: 21 июня — 03:44 и 21:18, 21 декабря — 08:57 и 15:58.
        val summer = SunTimes.on(LocalDate.of(2026, 6, 21), lat, lon, moscow)
        assertNear(at("2026-06-21T03:44"), summer.sunrise)
        assertNear(at("2026-06-21T21:18"), summer.sunset)
        val winter = SunTimes.on(LocalDate.of(2026, 12, 21), lat, lon, moscow)
        assertNear(at("2026-12-21T08:57"), winter.sunrise)
        assertNear(at("2026-12-21T15:58"), winter.sunset)
        // До целой минуты.
        assertEquals(0L, summer.sunrise!!.epochSecond % 60)
    }

    @Test
    fun sunriseOnTheEquatorAndInTheSouth() {
        // Кито: день — около 12 часов круглый год, восход около 06:10.
        val quito = ZoneId.of("America/Guayaquil")
        val day = SunTimes.on(LocalDate.of(2026, 3, 20), -0.18, -78.47, quito)
        assertNear(at("2026-03-20T06:17", quito), day.sunrise, minutes = 6)
        assertNear(at("2026-03-20T18:24", quito), day.sunset, minutes = 6)
        // Сидней: в декабре день длинный — восход около 05:40, закат около 20:05.
        val sydney = ZoneId.of("Australia/Sydney")
        val south = SunTimes.on(LocalDate.of(2026, 12, 21), -33.87, 151.21, sydney)
        assertNear(at("2026-12-21T05:41", sydney), south.sunrise, minutes = 5)
        assertNear(at("2026-12-21T20:05", sydney), south.sunset, minutes = 5)
    }

    @Test
    fun polarDayAndNightHaveNoEvents() {
        val oslo = ZoneId.of("Arctic/Longyearbyen")
        assertEquals(SunDay(null, null), SunTimes.on(LocalDate.of(2026, 6, 21), 78.22, 15.65, oslo))
        assertEquals(SunDay(null, null), SunTimes.on(LocalDate.of(2026, 12, 21), 78.22, 15.65, oslo))
        // Сигнал ждёт первого восхода после полярной ночи — в середине февраля.
        val signals = SunSignals(sunrise = SunSignal(enabled = true, moments = listOf(SunMoment(0))))
        val fire = SunRules.next(signals, 78.22, 15.65, at("2026-12-21T12:00", oslo), oslo).single()
        assertEquals(2027, fire.at.atZone(oslo).year)
        assertEquals(2, fire.at.atZone(oslo).monthValue)
    }

    @Test
    fun defaultIsFiveMinutesBeforeAndAtTheMoment() {
        assertEquals(listOf(SunMoment(5), SunMoment(0)), SunSignal().moments)
        assertFalse(SunSignals().enabled)
        val signals = SunSignals(sunrise = SunSignal(enabled = true))
        val sunrise = SunTimes.on(LocalDate.of(2026, 6, 21), lat, lon, moscow).sunrise!!

        val first = SunRules.next(signals, lat, lon, at("2026-06-21T00:00"), moscow).single()
        assertEquals(SunFire(sunrise.minusSeconds(300), SunEvent.SUNRISE, sunrise, 5, SunSignalKind.NOTIFICATION), first)
        val second = SunRules.next(signals, lat, lon, first.at, moscow).single()
        assertEquals(SunFire(sunrise, SunEvent.SUNRISE, sunrise, 0, SunSignalKind.NOTIFICATION), second)
        // После восхода — завтрашний.
        val third = SunRules.next(signals, lat, lon, second.at, moscow).single()
        assertEquals(LocalDate.of(2026, 6, 22), third.eventAt.atZone(moscow).toLocalDate())
        assertEquals(5, third.minutes)
    }

    @Test
    fun sunriseAndSunsetAlternate() {
        val signals = SunSignals(
            sunrise = SunSignal(enabled = true, moments = listOf(SunMoment(0, SunSignalKind.ALARM))),
            sunset = SunSignal(enabled = true, moments = listOf(SunMoment(30), SunMoment(0))),
        )
        val day = SunTimes.on(LocalDate.of(2026, 10, 20), lat, lon, moscow)
        var after = at("2026-10-20T00:00")
        val fires = (1..4).map { SunRules.next(signals, lat, lon, after, moscow).single().also { after = it.at } }
        assertEquals(listOf(SunEvent.SUNRISE, SunEvent.SUNSET, SunEvent.SUNSET, SunEvent.SUNRISE), fires.map { it.event })
        assertEquals(listOf(0, 30, 0, 0), fires.map { it.minutes })
        assertEquals(SunSignalKind.ALARM, fires[0].kind)
        assertEquals(day.sunset!!.minusSeconds(1800), fires[1].at)
        assertEquals(day.sunset, fires[2].at)
        // Выключенное событие молчит.
        val onlySunset = signals.copy(sunrise = signals.sunrise.copy(enabled = false))
        assertEquals(SunEvent.SUNSET, SunRules.next(onlySunset, lat, lon, at("2026-10-20T00:00"), moscow).single().event)
        assertTrue(SunRules.next(SunSignals(), lat, lon, at("2026-10-20T00:00"), moscow).isEmpty())
    }

    @Test
    fun longLeadOfTomorrowComesBeforeTodaysSunset() {
        // За 12 часов до заката — раннее утро, до сегодняшнего восхода; за 12 часов до
        // завтрашнего восхода — сегодняшний вечер, уже после заката.
        val signals = SunSignals(
            sunrise = SunSignal(enabled = true, moments = listOf(SunMoment(720))),
            sunset = SunSignal(enabled = true, moments = listOf(SunMoment(720))),
        )
        val first = SunRules.next(signals, lat, lon, at("2026-10-20T00:00"), moscow).single()
        assertEquals(SunEvent.SUNSET, first.event)
        val second = SunRules.next(signals, lat, lon, first.at, moscow).single()
        assertEquals(SunEvent.SUNRISE, second.event)
        assertEquals(LocalDate.of(2026, 10, 21), second.eventAt.atZone(moscow).toLocalDate())
        assertEquals(LocalDate.of(2026, 10, 20), second.at.atZone(moscow).toLocalDate())
    }

    @Test
    fun dueAtFindsExactlyTheScheduledSignals() {
        val signals = SunSignals(sunset = SunSignal(enabled = true))
        val fire = SunRules.next(signals, lat, lon, at("2026-10-20T12:00"), moscow).single()
        assertEquals(listOf(fire), SunRules.dueAt(signals, lat, lon, fire.at, moscow))
        // Настройки сменились, пока будильник Android ждал: старый момент ничего не значит.
        val changed = SunSignals(sunset = SunSignal(enabled = true, moments = listOf(SunMoment(15))))
        assertTrue(SunRules.dueAt(changed, lat, lon, fire.at, moscow).isEmpty())
        // Место сменилось — тоже.
        assertTrue(SunRules.dueAt(signals, 59.94, 30.31, fire.at, moscow).isEmpty())
        assertTrue(SunRules.isLate(fire.at.toEpochMilli(), fire.at.toEpochMilli() + SunRules.LATE_MILLIS + 1))
        assertFalse(SunRules.isLate(fire.at.toEpochMilli(), fire.at.toEpochMilli() + 60_000))
    }

    @Test
    fun nextEventSkipsToTomorrow() {
        val today = SunTimes.on(LocalDate.of(2026, 10, 20), lat, lon, moscow)
        assertEquals(today.sunset, SunRules.nextEvent(SunEvent.SUNSET, lat, lon, at("2026-10-20T12:00"), moscow))
        val tomorrow = SunRules.nextEvent(SunEvent.SUNRISE, lat, lon, at("2026-10-20T12:00"), moscow)
        assertEquals(LocalDate.of(2026, 10, 21), tomorrow!!.atZone(moscow).toLocalDate())
    }

    @Test
    fun momentsAreNormalizedAndSurviveEncoding() {
        val messy = listOf(SunMoment(5), SunMoment(0), SunMoment(5, SunSignalKind.ALARM), SunMoment(-1), SunMoment(721), SunMoment(60))
        assertEquals(listOf(SunMoment(60), SunMoment(5), SunMoment(0)), SunSignal.normalize(messy))
        assertEquals(SunSignal.MAX, SunSignal.normalize((0..20).map { SunMoment(it) }).size)

        val signals = SunSignals(
            sunrise = SunSignal(enabled = true, moments = listOf(SunMoment(10, SunSignalKind.ALARM), SunMoment(0))),
            sunset = SunSignal(enabled = false, moments = emptyList()),
            sound = AlarmSound.Ringtone("content://media/1", "Утро"),
            vibrate = false,
        )
        assertEquals(signals, SunSignals.decode(signals.encode()))
        assertTrue(signals.hasAlarm)
        assertFalse(signals.copy(sunrise = signals.sunrise.copy(enabled = false)).hasAlarm)
        // Включено, но без моментов — сигналить нечем.
        assertFalse(SunSignals(sunset = SunSignal(enabled = true, moments = emptyList())).enabled)
        assertEquals("", SunSignals().encode())
        assertEquals(SunSignals(), SunSignals.decode(""))
        assertEquals(SunSignals(), SunSignals.decode("не JSON"))
    }
}
