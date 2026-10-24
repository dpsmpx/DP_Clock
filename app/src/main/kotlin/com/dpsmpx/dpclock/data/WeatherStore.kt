package com.dpsmpx.dpclock.data

import android.content.Context
import com.dpsmpx.dpclock.model.MetNorway
import com.dpsmpx.dpclock.model.WeatherCache
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherPlace
import com.dpsmpx.dpclock.model.WeatherRules
import com.dpsmpx.dpclock.model.Zones
import com.dpsmpx.dpclock.platform.Places
import com.dpsmpx.dpclock.platform.WeatherClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/** Почему погода не свежая. */
enum class WeatherProblem {
    /** Нет сети или сервер не ответил: показана прошлая погода, если есть. */
    OFFLINE,

    /** Ответ не разобрать или сервер отказал. */
    SERVICE,

    /** Геолокация: нет разрешения. */
    NO_PERMISSION,

    /** Геолокация выключена или место не нашлось: показано прошлое место, если было. */
    NO_LOCATION,
}

/** Что показать в шапке «Мировых часов». */
data class WeatherState(
    val mode: WeatherMode = WeatherMode.NOT_SET,
    val place: WeatherPlace? = null,
    val cache: WeatherCache? = null,
    val loading: Boolean = false,
    val problem: WeatherProblem? = null,
)

/**
 * Погода вашего города (D-019, п. 4): при открытии раздела и не чаще раза в
 * час, не раньше срока `Expires`. Последний ответ — в кэше приложения: без
 * сети видна прошлая погода, а повторный запрос идёт с `If-Modified-Since`.
 */
class WeatherStore(private val context: Context, private val repository: ClockRepository) {
    private val mutex = Mutex()
    private val cacheFile get() = File(context.cacheDir, "weather.json")

    /** Весь прогноз последнего ответа: из него берётся текущий час, пока прогноз не обновился. */
    private val forecastFile get() = File(context.cacheDir, "weather-forecast.json")

    private val mutableState = MutableStateFlow(WeatherState())
    val state: StateFlow<WeatherState> = mutableState

    /** Обновить, если пора: другое место — сразу, то же — раз в час и не раньше `Expires`. */
    suspend fun refresh() = mutex.withLock {
        val settings = repository.currentSettings().weather
        if (settings.mode == WeatherMode.NOT_SET || settings.mode == WeatherMode.OFF) {
            mutableState.value = WeatherState(settings.mode)
            return@withLock
        }
        var cache: WeatherCache? = withContext(Dispatchers.IO) { loadCache() }
        var problem: WeatherProblem? = null
        var place = settings.place
        // Место ищется, только когда погода и так пора спросить: геолокация не дёргается при каждом открытии раздела.
        val stale = cache == null || System.currentTimeMillis() >= maxOf(cache.fetchedAt + WeatherRules.REFRESH_MILLIS, cache.expiresAt)
        if (settings.mode == WeatherMode.LOCATION && (place == null || stale)) {
            mutableState.value = WeatherState(settings.mode, place, cache, loading = true)
            when {
                !Places.hasLocationPermission(context) -> problem = WeatherProblem.NO_PERMISSION
                else -> {
                    val location = Places.current(context)
                    if (location == null) {
                        problem = WeatherProblem.NO_LOCATION
                    } else {
                        val previous = place
                        val name = if (
                            previous == null || previous.name.isBlank() ||
                            Zones.distanceKm(previous.latitude, previous.longitude, location.latitude, location.longitude) > MOVED_KM
                        ) {
                            Places.name(context, location.latitude, location.longitude)
                        } else {
                            previous.name
                        }
                        val found = WeatherPlace(name, location.latitude, location.longitude)
                        if (found != previous) {
                            place = found
                            repository.updateSettings { it.copy(weather = it.weather.copy(place = found)) }
                        }
                    }
                }
            }
        }
        val target = place
        if (target == null) {
            mutableState.value = WeatherState(settings.mode, null, null, problem = problem)
            return@withLock
        }
        val now = System.currentTimeMillis()
        // Кэш другого места не годится; кэш этого — на текущий час из сохранённого прогноза.
        val known = cache
            ?.takeIf {
                WeatherRules.rounded(it.latitude) == WeatherRules.rounded(target.latitude) &&
                    WeatherRules.rounded(it.longitude) == WeatherRules.rounded(target.longitude)
            }
            ?.let { current(it) }
        val due = WeatherRules.needsFetch(known, target, now)
        mutableState.value = WeatherState(settings.mode, target, known, loading = due, problem = problem)
        if (!due) return@withLock

        val result = withContext(Dispatchers.IO) { WeatherClient.fetch(target.latitude, target.longitude, known?.lastModified, now) }
        cache = when (result) {
            is WeatherClient.Result.Fresh -> {
                val weather = runCatching { MetNorway.parse(result.text, Instant.now()) }.getOrNull()
                if (weather == null) {
                    problem = problem ?: WeatherProblem.SERVICE
                    known
                } else {
                    val fresh = WeatherCache(
                        WeatherRules.rounded(target.latitude),
                        WeatherRules.rounded(target.longitude),
                        weather,
                        fetchedAt = now,
                        expiresAt = result.expiresAt,
                        lastModified = result.lastModified,
                    )
                    withContext(Dispatchers.IO) {
                        forecastFile.writeText(result.text)
                        cacheFile.writeText(fresh.encode())
                    }
                    fresh
                }
            }
            is WeatherClient.Result.NotModified -> known?.copy(fetchedAt = now, expiresAt = result.expiresAt)?.also { updated ->
                withContext(Dispatchers.IO) { cacheFile.writeText(updated.encode()) }
            }
            is WeatherClient.Result.Failed -> {
                problem = problem ?: if (result.code == 0) WeatherProblem.OFFLINE else WeatherProblem.SERVICE
                known
            }
        }
        mutableState.value = WeatherState(settings.mode, target, cache, loading = false, problem = problem)
    }

    /** Режим сменили в настройках: шапка сразу показывает новый, погода — после [refresh]. */
    fun onModeChanged(mode: WeatherMode) = mutableState.update { it.copy(mode = mode) }

    private fun loadCache(): WeatherCache? = runCatching { WeatherCache.decode(cacheFile.readText()) }.getOrNull()

    /** Текущий час из сохранённого прогноза; прогноза нет или он испорчен — как было. */
    private suspend fun current(cache: WeatherCache): WeatherCache = withContext(Dispatchers.IO) {
        val text = runCatching { forecastFile.readText() }.getOrNull() ?: return@withContext cache
        runCatching { cache.copy(weather = MetNorway.parse(text, Instant.now())) }.getOrDefault(cache)
    }

    private companion object {
        /** Сдвинулись дальше — место называется заново. */
        const val MOVED_KM = 5.0
    }
}
