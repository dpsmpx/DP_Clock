package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WeatherTest {
    @Test
    fun symbolCodesOfMetNorway() {
        assertEquals(WeatherSymbol(Sky.CLEAR, night = true), WeatherSymbol.parse("clearsky_night"))
        assertEquals(WeatherSymbol(Sky.PARTLY_CLOUDY), WeatherSymbol.parse("partlycloudy_day"))
        assertEquals(WeatherSymbol(Sky.FAIR, night = true), WeatherSymbol.parse("fair_polartwilight"))
        assertEquals(WeatherSymbol(Sky.FOG), WeatherSymbol.parse("fog"))
        assertEquals(WeatherSymbol(Sky.CLOUDY, Precipitation.RAIN, Intensity.LIGHT), WeatherSymbol.parse("lightrain"))
        assertEquals(WeatherSymbol(Sky.PARTLY_CLOUDY, Precipitation.RAIN, Intensity.HEAVY), WeatherSymbol.parse("heavyrainshowers_day"))
        assertEquals(WeatherSymbol(Sky.CLOUDY, Precipitation.SNOW, thunder = true), WeatherSymbol.parse("snowandthunder"))
        // Опечатка самого MET: «lightssleet…».
        assertEquals(
            WeatherSymbol(Sky.PARTLY_CLOUDY, Precipitation.SLEET, Intensity.LIGHT, thunder = true, night = true),
            WeatherSymbol.parse("lightssleetshowersandthunder_night"),
        )
        assertEquals(WeatherSymbol(Sky.CLOUDY), WeatherSymbol.parse("hologram"))
    }

    @Test
    fun currentHourFromARealResponse() {
        val text = WeatherTest::class.java.getResourceAsStream("met-compact.json")!!.readBytes().toString(Charsets.UTF_8)
        // 18:44 UTC — запись за 18:00.
        val now = MetNorway.parse(text, Instant.parse("2026-10-24T18:44:00Z"))
        assertEquals(8.2, now.temperature, 0.001)
        assertEquals(WeatherSymbol(Sky.PARTLY_CLOUDY, night = true), now.symbol)
        assertEquals(Instant.parse("2026-10-24T18:00:00Z"), now.at)
        // Через два часа — запись за 20:00; до начала прогноза — первая.
        assertEquals(7.1, MetNorway.parse(text, Instant.parse("2026-10-24T20:10:00Z")).temperature, 0.001)
        assertEquals(8.2, MetNorway.parse(text, Instant.parse("2026-10-24T10:00:00Z")).temperature, 0.001)
        assertThrows(IllegalArgumentException::class.java) { MetNorway.parse("""{"properties": {"timeseries": []}}""", Instant.EPOCH) }
        assertThrows(IllegalArgumentException::class.java) { MetNorway.parse("<html>", Instant.EPOCH) }
    }

    @Test
    fun requestKeepsOnlyARegion() {
        assertEquals(
            "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=55.8&lon=37.6",
            MetNorway.url(55.755826, 37.6173),
        )
        assertEquals("lat=-33.9&lon=18.4", MetNorway.url(-33.9249, 18.4241).substringAfter('?'))
        assertEquals("DP_Clock/0.8.0 github.com/dpsmpx/DP_Clock", MetNorway.userAgent("0.8.0"))
    }

    @Test
    fun askTheNetworkAtMostHourlyAndNotBeforeExpires() {
        val place = WeatherPlace("Москва", 55.76, 37.62)
        val cache = WeatherCache(55.8, 37.6, Weather(8.2, WeatherSymbol(Sky.FAIR), Instant.EPOCH), fetchedAt = 0L, expiresAt = 30 * 60_000L, lastModified = "x")
        assertTrue(WeatherRules.needsFetch(null, place, 0L))
        assertFalse(WeatherRules.needsFetch(cache, place, 59 * 60_000L))
        assertTrue(WeatherRules.needsFetch(cache, place, 60 * 60_000L))
        // Сервер просит подождать дольше часа — ждём.
        assertFalse(WeatherRules.needsFetch(cache.copy(expiresAt = 90 * 60_000L), place, 60 * 60_000L))
        // Другой город — сразу.
        assertTrue(WeatherRules.needsFetch(cache, WeatherPlace("Тула", 54.19, 37.62), 60_000L))
        // Те же 0,1° — то же место.
        assertFalse(WeatherRules.needsFetch(cache, place.copy(latitude = 55.81), 60_000L))
    }

    @Test
    fun cacheRoundTripsAndBrokenCacheIsIgnored() {
        val cache = WeatherCache(
            55.8, 37.6,
            Weather(-3.5, WeatherSymbol(Sky.CLOUDY, Precipitation.SNOW, Intensity.HEAVY, thunder = false, night = true), Instant.parse("2026-12-01T03:00:00Z")),
            fetchedAt = 1L, expiresAt = 2L, lastModified = "Tue, 01 Dec 2026 03:00:00 GMT",
        )
        assertEquals(cache, WeatherCache.decode(cache.encode()))
        assertNull(WeatherCache.decode("{}"))
        assertNull(WeatherCache.decode("мусор"))
        assertEquals(26.6, WeatherRules.fahrenheit(-3.0), 0.001)
    }
}
