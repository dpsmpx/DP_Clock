package com.dpsmpx.dpclock.platform

import android.content.Context
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.model.Zones
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Текущее место для восхода и заката (D-035, п. 3): приблизительная геолокация,
 * пока приложение открыто, — в фоне Android место не даёт без отдельного
 * разрешения, и его приложение не просит. Сигналы идут по последнему найденному.
 */
object SunLocation {
    /** Чаще место не спрашивается: за полчаса восход не сдвинется. */
    private const val REFRESH_MILLIS = 30 * 60_000L

    /** Сдвинулись дальше — место называется заново. */
    private const val MOVED_KM = 5.0

    private val mutex = Mutex()

    /**
     * Обновить место, если сигналы включены (или [force] — открыт их раздел настроек).
     * Разрешения нет — прежнее место забывается: считать его текущим больше нельзя.
     * Геолокация выключена или место не нашлось — остаётся прежнее.
     */
    suspend fun refresh(context: Context, force: Boolean = false) = mutex.withLock {
        val graph = DpClockApp.graph(context)
        if (!force && !graph.repository.currentSettings().sun.enabled) return@withLock
        if (!Places.hasLocationPermission(context)) {
            if (graph.sun.clearLocation()) graph.repository.syncSun()
            return@withLock
        }
        val previous = graph.sun.location.value
        val now = System.currentTimeMillis()
        if (!force && previous != null && now - previous.updatedAt in 0 until REFRESH_MILLIS) return@withLock
        val location = Places.current(context) ?: return@withLock
        val changed = if (previous == null || Zones.distanceKm(previous.latitude, previous.longitude, location.latitude, location.longitude) > MOVED_KM) {
            graph.sun.setLocation(Places.name(context, location.latitude, location.longitude), location.latitude, location.longitude, now)
        } else {
            // Мелкие сдвиги (шум приблизительной геолокации) время восхода не меняют — точка остаётся прежней.
            val name = previous.name.ifBlank { Places.name(context, location.latitude, location.longitude) }
            graph.sun.setLocation(name, previous.latitude, previous.longitude, now)
        }
        if (changed) graph.repository.syncSun()
    }
}
