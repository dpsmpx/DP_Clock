package com.dpsmpx.dpclock.model

import java.time.Instant
import kotlin.math.roundToLong

/** Откуда место для погоды (D-019, п. 3). [NOT_SET] — ещё не выбрано: в шапке — предложение. */
enum class WeatherMode { NOT_SET, OFF, CITY, LOCATION }

/** Место погоды: название для шапки и точка для запроса. */
data class WeatherPlace(val name: String, val latitude: Double, val longitude: Double) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "место $latitude, $longitude" }
    }
}

/** Погода вашего города (D-019): способ и последнее место — свой город или найденное по геолокации. */
data class WeatherSettings(val mode: WeatherMode = WeatherMode.NOT_SET, val place: WeatherPlace? = null)

enum class Sky { CLEAR, FAIR, PARTLY_CLOUDY, CLOUDY, FOG }

enum class Precipitation { NONE, RAIN, SLEET, SNOW }

enum class Intensity { LIGHT, MODERATE, HEAVY }

/** Погода для картинки и подписи — из кода символа MET Norway (`partlycloudy_night`, `lightrainshowers_day`…). */
data class WeatherSymbol(
    val sky: Sky,
    val precipitation: Precipitation = Precipitation.NONE,
    val intensity: Intensity = Intensity.MODERATE,
    val thunder: Boolean = false,
    val night: Boolean = false,
) {
    companion object {
        /** Неизвестный код — облачно: картинка спокойная и не обманывает солнцем. */
        fun parse(code: String): WeatherSymbol {
            val parts = code.split('_')
            val night = parts.getOrNull(1).let { it == "night" || it == "polartwilight" }
            var base = parts[0]
            val thunder = base.endsWith("andthunder")
            base = base.removeSuffix("andthunder")
            val showers = base.endsWith("showers")
            base = base.removeSuffix("showers")
            val intensity = when {
                base.startsWith("light") -> Intensity.LIGHT
                base.startsWith("heavy") -> Intensity.HEAVY
                else -> Intensity.MODERATE
            }
            // В кодах MET есть «lightssleet…» и «lightssnow…» — лишняя «s».
            base = base.removePrefix("light").removePrefix("heavy")
            if (base.startsWith("ssleet") || base.startsWith("ssnow")) base = base.drop(1)
            val precipitation = when (base) {
                "rain" -> Precipitation.RAIN
                "sleet" -> Precipitation.SLEET
                "snow" -> Precipitation.SNOW
                else -> Precipitation.NONE
            }
            val sky = when {
                precipitation != Precipitation.NONE -> if (showers) Sky.PARTLY_CLOUDY else Sky.CLOUDY
                base == "clearsky" -> Sky.CLEAR
                base == "fair" -> Sky.FAIR
                base == "partlycloudy" -> Sky.PARTLY_CLOUDY
                base == "fog" -> Sky.FOG
                else -> Sky.CLOUDY
            }
            return WeatherSymbol(sky, precipitation, if (precipitation == Precipitation.NONE) Intensity.MODERATE else intensity, thunder, night)
        }
    }
}

/** Погода сейчас: температура, °C, и символ на час, к которому она относится. */
data class Weather(val temperature: Double, val symbol: WeatherSymbol, val at: Instant)

/** Последний ответ сети и правила повторного запроса (условия MET Norway, D-019, п. 4). */
data class WeatherCache(
    val latitude: Double,
    val longitude: Double,
    val weather: Weather,
    /** Когда получен ответ, мс UTC. */
    val fetchedAt: Long,
    /** Заголовок `Expires`: раньше спрашивать нельзя. */
    val expiresAt: Long,
    /** Заголовок `Last-Modified` — для `If-Modified-Since`. */
    val lastModified: String,
) {
    fun encode(): String = Json.write(
        mapOf(
            "latitude" to latitude,
            "longitude" to longitude,
            "temperature" to weather.temperature,
            "symbol" to weather.symbol.let {
                mapOf("sky" to it.sky.name, "precipitation" to it.precipitation.name, "intensity" to it.intensity.name, "thunder" to it.thunder, "night" to it.night)
            },
            "at" to weather.at.toEpochMilli(),
            "fetchedAt" to fetchedAt,
            "expiresAt" to expiresAt,
            "lastModified" to lastModified,
        ),
    )

    companion object {
        /** Испорченный кэш — null: погода просто спросится заново. */
        fun decode(text: String): WeatherCache? = runCatching {
            val root = Json.parse(text) as Map<*, *>
            val symbol = root["symbol"] as Map<*, *>
            WeatherCache(
                latitude = (root["latitude"] as Number).toDouble(),
                longitude = (root["longitude"] as Number).toDouble(),
                weather = Weather(
                    temperature = (root["temperature"] as Number).toDouble(),
                    symbol = WeatherSymbol(
                        Sky.valueOf(symbol["sky"] as String),
                        Precipitation.valueOf(symbol["precipitation"] as String),
                        Intensity.valueOf(symbol["intensity"] as String),
                        symbol["thunder"] as Boolean,
                        symbol["night"] as Boolean,
                    ),
                    at = Instant.ofEpochMilli(root["at"] as Long),
                ),
                fetchedAt = root["fetchedAt"] as Long,
                expiresAt = root["expiresAt"] as Long,
                lastModified = root["lastModified"] as String,
            )
        }.getOrNull()
    }
}

/** Прогноз MET Norway, Locationforecast 2.0 compact (D-019, п. 4). */
object MetNorway {
    const val BASE = "https://api.met.no/weatherapi/locationforecast/2.0/compact"
    const val HOME = "github.com/dpsmpx/DP_Clock"

    /** Условия MET: приложение называет себя и даёт адрес для связи; «Dalvik» и «okhttp» запрещены. */
    fun userAgent(version: String): String = "DP_Clock/$version $HOME"

    fun url(latitude: Double, longitude: Double): String =
        "$BASE?lat=${WeatherRules.coordinate(latitude)}&lon=${WeatherRules.coordinate(longitude)}"

    /** Погода сейчас: последняя запись прогноза не позже [now], иначе первая. Ошибка формата — [IllegalArgumentException]. */
    fun parse(text: String, now: Instant): Weather {
        val root = Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("не объект")
        val series = ((root["properties"] as? Map<*, *>)?.get("timeseries") as? List<*>)
            ?.mapNotNull { it as? Map<*, *> }
            ?.mapNotNull { entry -> (entry["time"] as? String)?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { it to entry } }
            ?.sortedBy { it.first }
            .orEmpty()
        require(series.isNotEmpty()) { "пустой прогноз" }
        val (at, entry) = series.lastOrNull { !it.first.isAfter(now) } ?: series.first()
        val data = entry["data"] as? Map<*, *> ?: throw IllegalArgumentException("нет data")
        val details = (data["instant"] as? Map<*, *>)?.get("details") as? Map<*, *>
        val temperature = (details?.get("air_temperature") as? Number)?.toDouble() ?: throw IllegalArgumentException("нет температуры")
        val code = listOf("next_1_hours", "next_6_hours", "next_12_hours").firstNotNullOfOrNull { key ->
            ((data[key] as? Map<*, *>)?.get("summary") as? Map<*, *>)?.get("symbol_code") as? String
        } ?: "cloudy"
        return Weather(temperature, WeatherSymbol.parse(code), at)
    }
}

/** Когда спрашивать погоду и как её показывать (D-019) — чистые функции. */
object WeatherRules {
    /** Выбор автора — при открытии раздела и не чаще раза в час. */
    const val REFRESH_MILLIS = 60 * 60_000L

    /** Координаты в запросе — до 0,1° (около 11 км): погоде хватает, точное место в сеть не уходит. */
    fun rounded(value: Double): Double = (value * 10).roundToLong() / 10.0

    /** Координата строкой для запроса: точка, одна цифра после неё. */
    fun coordinate(value: Double): String = rounded(value).toString()

    /** Пора спросить сеть: кэша нет, место другое или прошёл час и наступил срок `Expires`. */
    fun needsFetch(cache: WeatherCache?, place: WeatherPlace, now: Long): Boolean =
        cache == null ||
            rounded(cache.latitude) != rounded(place.latitude) ||
            rounded(cache.longitude) != rounded(place.longitude) ||
            now >= maxOf(cache.fetchedAt + REFRESH_MILLIS, cache.expiresAt)

    fun fahrenheit(celsius: Double): Double = celsius * 9 / 5 + 32
}
