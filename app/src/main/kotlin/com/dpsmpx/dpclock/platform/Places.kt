package com.dpsmpx.dpclock.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.dpsmpx.dpclock.model.WeatherPlace
import com.dpsmpx.dpclock.model.Zones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume

/** Место из поиска: точка с коротким названием для шапки и подробности для списка — область, страна. */
data class PlaceMatch(val place: WeatherPlace, val details: String)

/**
 * Где вы для погоды (D-019, п. 3): приблизительная геолокация и геокодер
 * Android. Геокодер ходит в сеть сам (обычно — сервис Google на телефоне);
 * его может не быть — тогда остаются главные города поясов.
 */
object Places {
    private const val LOCATION_TIMEOUT_MILLIS = 20_000L

    /** Последнее известное место не старше этого — годится, если текущее не нашлось. */
    private const val LAST_KNOWN_MAX_AGE_MILLIS = 6 * 60 * 60_000L

    private const val RESULTS = 5

    /** Геокодер не ответил за это время — считаем, что не нашёл: погода не ждёт его вечно. */
    private const val GEOCODER_TIMEOUT_MILLIS = 10_000L

    fun hasLocationPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasGeocoder(): Boolean = Geocoder.isPresent()

    /** Где телефон сейчас, приблизительно; null — нет разрешения, геолокация выключена или место не нашлось. */
    suspend fun current(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            val location = withTimeoutOrNull(LOCATION_TIMEOUT_MILLIS) { currentFrom(context, manager, provider) }
            if (location != null) return location
        }
        val now = System.currentTimeMillis()
        return providers.mapNotNull { lastKnown(context, manager, it) }
            .filter { now - it.time < LAST_KNOWN_MAX_AGE_MILLIS }
            .maxByOrNull { it.time }
    }

    private suspend fun currentFrom(context: Context, manager: LocationManager, provider: String): Location? {
        if (!hasLocationPermission(context)) return null
        return suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            } catch (_: SecurityException) {
                continuation.resume(null)
            } catch (_: IllegalArgumentException) {
                continuation.resume(null)
            }
        }
    }

    private fun lastKnown(context: Context, manager: LocationManager, provider: String): Location? {
        if (!hasLocationPermission(context)) return null
        return try {
            manager.getLastKnownLocation(provider)
        } catch (_: SecurityException) {
            null
        }
    }

    /** Название места по точке: город по геокодеру, иначе ближайший главный город пояса, иначе пусто. */
    suspend fun name(context: Context, latitude: Double, longitude: Double): String {
        val address = if (hasGeocoder()) reverse(context, latitude, longitude) else null
        return address?.let(::shortName)?.takeIf { it.isNotBlank() }
            ?: Zones.nearest(latitude, longitude)?.let { ZoneNames.city(context, it.id) }
            ?: ""
    }

    /** Поиск города по названию через геокодер; нет геокодера или сети — пусто. */
    suspend fun search(context: Context, query: String): List<PlaceMatch> {
        if (!hasGeocoder() || query.isBlank()) return emptyList()
        val geocoder = Geocoder(context, locale(context))
        val addresses: List<Address> = withTimeoutOrNull(GEOCODER_TIMEOUT_MILLIS) { forward(geocoder, query) }.orEmpty()
        return addresses.filter { it.hasLatitude() && it.hasLongitude() }.mapNotNull { address ->
            val name = shortName(address).ifBlank { query.trim() }
            runCatching {
                PlaceMatch(
                    WeatherPlace(name, address.latitude, address.longitude),
                    listOfNotNull(address.adminArea, address.countryName).filter { it != name }.distinct().joinToString(", "),
                )
            }.getOrNull()
        }.distinctBy { it.place.name to it.details }
    }

    private suspend fun forward(geocoder: Geocoder, query: String): List<Address> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocationName(
                    query,
                    RESULTS,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses)
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(emptyList())
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocationName(query, RESULTS).orEmpty()
                } catch (_: IOException) {
                    emptyList()
                }
            }
        }

    private suspend fun reverse(context: Context, latitude: Double, longitude: Double): Address? {
        val geocoder = Geocoder(context, locale(context))
        return withTimeoutOrNull(GEOCODER_TIMEOUT_MILLIS) { backward(geocoder, latitude, longitude) }
    }

    private suspend fun backward(geocoder: Geocoder, latitude: Double, longitude: Double): Address? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { continuation ->
                geocoder.getFromLocation(
                    latitude,
                    longitude,
                    1,
                    object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            }
        } else {
            withContext(Dispatchers.IO) {
                try {
                    @Suppress("DEPRECATION")
                    geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull()
                } catch (_: IOException) {
                    null
                }
            }
        }

    private fun shortName(address: Address): String =
        address.locality ?: address.subAdminArea ?: address.featureName ?: address.adminArea ?: ""

    private fun locale(context: Context): Locale = context.resources.configuration.locales[0]
}
