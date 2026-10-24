package com.dpsmpx.dpclock.model

import java.time.Instant
import java.time.ZoneId
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Пояс мировых часов (D-019): пояс tz и его главный город — страна и точка,
 * по которой видно, день там или ночь. Название города — из языка системы.
 */
data class Zone(val id: String, val country: String, val latitude: Double, val longitude: Double)

/** Пояса с главными городами — `zones.tsv` из zone.tab базы tz (`tools/make-zones.py`). */
object Zones {
    val all: List<Zone> by lazy { load() }

    private val byId: Map<String, Zone> by lazy { all.associateBy { it.id } }

    fun find(id: String): Zone? = byId[id]

    /** Ближайший главный город к точке — не дальше [maxKm]; для названия места по геолокации. */
    fun nearest(latitude: Double, longitude: Double, maxKm: Double = 100.0): Zone? =
        all.map { it to distanceKm(latitude, longitude, it.latitude, it.longitude) }
            .filter { it.second <= maxKm }
            .minByOrNull { it.second }?.first

    private fun load(): List<Zone> {
        val text = Zones::class.java.getResourceAsStream("zones.tsv")?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("нет zones.tsv")
        return text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val (id, country, lat, lon) = line.split('\t')
                Zone(id, country, lat.toDouble(), lon.toDouble())
            }
            // Пояс, которого нет в базе tz телефона, пропускается.
            .filter { runCatching { ZoneId.of(it.id) }.isSuccess }
            .toList()
    }

    /** Расстояние по большому кругу, км. */
    fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 6371.0 * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}

/** Правила мировых часов (D-019) — чистые функции от момента. */
object WorldClockRules {
    /** Разница пояса [zone] с [home] в момент [at], мин; учитывает летнее время обоих. */
    fun offsetMinutes(zone: ZoneId, home: ZoneId, at: Instant): Int =
        (zone.rules.getOffset(at).totalSeconds - home.rules.getOffset(at).totalSeconds) / 60

    /** Какой там день относительно [home]: −1 — вчера, 0 — сегодня, 1 — завтра. */
    fun dayShift(zone: ZoneId, home: ZoneId, at: Instant): Int =
        (at.atZone(zone).toLocalDate().toEpochDay() - at.atZone(home).toLocalDate().toEpochDay()).toInt()

    /** День — солнце выше горизонта с поправкой на преломление; полярный день и ночь — как есть. */
    fun isDaylight(latitude: Double, longitude: Double, at: Instant): Boolean = Sun.elevation(latitude, longitude, at) > SUNRISE_ELEVATION

    /** Высота центра солнца на восходе и закате, градусы. */
    const val SUNRISE_ELEVATION = -0.833
}

/** Положение солнца по формулам NOAA: точность — доли градуса, этого хватает на «день или ночь». */
object Sun {
    /** Высота солнца над горизонтом в точке и момент, градусы. */
    fun elevation(latitude: Double, longitude: Double, at: Instant): Double {
        val millis = at.toEpochMilli()
        val julianDay = millis / 86_400_000.0 + 2_440_587.5
        val t = (julianDay - 2_451_545.0) / 36_525.0
        val l0 = Math.toRadians(normalize(280.46646 + t * (36_000.76983 + t * 0.0003032)))
        val m = Math.toRadians(357.52911 + t * (35_999.05029 - 0.0001537 * t))
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * m) * (0.019993 - 0.000101 * t) + sin(3 * m) * 0.000289
        val omega = Math.toRadians(125.04 - 1934.136 * t)
        val lambda = Math.toRadians(Math.toDegrees(l0) + center - 0.00569 - 0.00478 * sin(omega))
        val obliquity = Math.toRadians(23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0 + 0.00256 * cos(omega))
        val declination = asin(sin(obliquity) * sin(lambda))
        val y = tan(obliquity / 2).pow(2)
        val equationOfTime = 4 * Math.toDegrees(
            y * sin(2 * l0) - 2 * e * sin(m) + 4 * e * y * sin(m) * cos(2 * l0) - 0.5 * y * y * sin(4 * l0) - 1.25 * e * e * sin(2 * m),
        )
        val minutesUtc = Math.floorMod(millis, 86_400_000L) / 60_000.0
        val solarMinutes = normalize(minutesUtc + equationOfTime + 4 * longitude, 1440.0)
        val hourAngle = Math.toRadians(solarMinutes / 4 - 180)
        val lat = Math.toRadians(latitude)
        val cosZenith = (sin(lat) * sin(declination) + cos(lat) * cos(declination) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        return 90 - Math.toDegrees(acos(cosZenith))
    }

    private fun normalize(value: Double, period: Double = 360.0): Double = ((value % period) + period) % period
}
