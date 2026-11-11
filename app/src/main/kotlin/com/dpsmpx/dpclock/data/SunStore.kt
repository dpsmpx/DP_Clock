package com.dpsmpx.dpclock.data

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherPlace
import com.dpsmpx.dpclock.model.Zones
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.ZoneId

/** Откуда место для восхода и заката (D-035, п. 3). */
enum class SunPlaceSource {
    /** Приблизительная геолокация телефона. */
    LOCATION,

    /** Город погоды из «Мировых часов». */
    WEATHER,

    /** Главный город часового пояса телефона. */
    ZONE,
}

/** Место, для которого считаются восход и закат; [name] пустое — названия нет. */
data class SunPlace(val name: String, val latitude: Double, val longitude: Double, val source: SunPlaceSource, val updatedAt: Long = 0L)

/**
 * Где телефон — для восхода и заката (D-035, п. 3): последнее место по геолокации
 * в хранилище устройства, не в базе. Оно своё у каждого телефона — в перенос файлом и
 * синхронизацию не входит — и нужно до первой разблокировки: сигнал после
 * перезагрузки ставится по нему.
 */
class SunStore(context: Context) {
    private val prefs = context.deviceStorage().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val locationFlow = MutableStateFlow(read())

    /** Последнее место по геолокации; null — ещё не находилось или разрешение забрали. */
    val location: StateFlow<SunPlace?> = locationFlow

    /** Новое место; true — изменилось так, что время восхода и заката другое. */
    @Synchronized
    fun setLocation(name: String, latitude: Double, longitude: Double, at: Long): Boolean {
        val old = locationFlow.value
        prefs.edit {
            putString(NAME, name)
            putString(LATITUDE, latitude.toString())
            putString(LONGITUDE, longitude.toString())
            putLong(UPDATED_AT, at)
        }
        locationFlow.value = SunPlace(name, latitude, longitude, SunPlaceSource.LOCATION, at)
        return old == null || old.latitude != latitude || old.longitude != longitude
    }

    /** Геолокацию запретили: прежнее место больше не «текущее». true — место было. */
    @Synchronized
    fun clearLocation(): Boolean {
        if (locationFlow.value == null) return false
        prefs.edit { clear() }
        locationFlow.value = null
        return true
    }

    /** Место для расчёта сейчас — по настройкам погоды и поясу телефона. */
    fun place(settings: Settings): SunPlace? = resolve(
        locationFlow.value,
        settings.weather.place.takeIf { settings.weather.mode == WeatherMode.CITY || settings.weather.mode == WeatherMode.LOCATION },
        ZoneId.systemDefault().id,
    )

    private fun read(): SunPlace? {
        val latitude = prefs.getString(LATITUDE, null)?.toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
        val longitude = prefs.getString(LONGITUDE, null)?.toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
        return SunPlace(prefs.getString(NAME, null).orEmpty(), latitude, longitude, SunPlaceSource.LOCATION, prefs.getLong(UPDATED_AT, 0L))
    }

    companion object {
        private const val PREFS = "sun"
        private const val NAME = "name"
        private const val LATITUDE = "latitude"
        private const val LONGITUDE = "longitude"
        private const val UPDATED_AT = "updatedAt"

        /**
         * Место для расчёта: геолокация телефона, иначе город погоды, иначе главный
         * город часового пояса [zoneId]; null — пояса нет в таблице (например, `Etc/UTC`).
         */
        fun resolve(location: SunPlace?, weather: WeatherPlace?, zoneId: String): SunPlace? =
            location
                ?: weather?.let { SunPlace(it.name, it.latitude, it.longitude, SunPlaceSource.WEATHER) }
                ?: Zones.find(zoneId)?.let { SunPlace("", it.latitude, it.longitude, SunPlaceSource.ZONE) }
    }
}
