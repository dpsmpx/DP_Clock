package com.dpsmpx.dpclock.platform

import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.model.MetNorway
import com.dpsmpx.dpclock.model.WeatherRules
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Запрос прогноза к MET Norway (D-019, п. 4): HTTPS, имя приложения в
 * User-Agent, `If-Modified-Since` по прошлому ответу. Ответ сжат gzip —
 * распаковывает сам `HttpURLConnection`.
 */
object WeatherClient {
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 15_000

    /** Ответ прогноза — около 40 КБ; больше мегабайта не бывает. */
    private const val MAX_BYTES = 1024 * 1024

    sealed interface Result {
        data class Fresh(val text: String, val expiresAt: Long, val lastModified: String) : Result

        /** 304: прогноз не изменился. */
        data class NotModified(val expiresAt: Long) : Result

        /** [code] — код HTTP; 0 — нет связи. */
        data class Failed(val code: Int) : Result
    }

    /** Блокирует поток — вызывать в `Dispatchers.IO`. */
    fun fetch(latitude: Double, longitude: Double, lastModified: String?, now: Long): Result {
        val connection = try {
            URL(MetNorway.url(latitude, longitude)).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            return Result.Failed(0)
        }
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", MetNorway.userAgent(BuildConfig.VERSION_NAME))
            connection.setRequestProperty("Accept", "application/json")
            if (!lastModified.isNullOrEmpty()) connection.setRequestProperty("If-Modified-Since", lastModified)
            val code = connection.responseCode
            // Нет Expires — не раньше чем через час (выбор автора).
            val expiresAt = connection.getHeaderFieldDate("Expires", now + WeatherRules.REFRESH_MILLIS)
            when (code) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_NOT_AUTHORITATIVE -> Result.Fresh(
                    connection.inputStream.use { read(it) },
                    expiresAt,
                    connection.getHeaderField("Last-Modified").orEmpty(),
                )
                HttpURLConnection.HTTP_NOT_MODIFIED -> Result.NotModified(expiresAt)
                else -> Result.Failed(code)
            }
        } catch (_: IOException) {
            Result.Failed(0)
        } finally {
            connection.disconnect()
        }
    }

    private fun read(input: java.io.InputStream): String {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) break
            out.write(chunk, 0, read)
            if (out.size() > MAX_BYTES) throw IOException("слишком большой ответ")
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
