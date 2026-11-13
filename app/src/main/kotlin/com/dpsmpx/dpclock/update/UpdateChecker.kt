package com.dpsmpx.dpclock.update

import android.content.Context
import android.util.Log
import com.dpsmpx.dpclock.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка новой версии в GitHub Releases.
 *
 * Запрос к api.github.com не чаще раза в сутки (и по кнопке). Никаких сведений
 * о пользователе в запросе нет. APK скачивает [UpdateDownloader], устанавливает система.
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    const val REPOSITORY = "dpsmpx/DP_Clock"
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases/latest"
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private const val DOWNLOAD_PREFIX = "https://github.com/$REPOSITORY/releases/"
    /** Имя APK выпуска: `dp-clock-0.29.0.apk` (не debug). */
    private const val ASSET_PREFIX = "dp-clock-"
    private const val ASSET_DEBUG_MARK = "-debug"
    private const val CHECK_INTERVAL_MS = 24 * 3600_000L
    private const val MAX_RESPONSE_BYTES = 512 * 1024
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    enum class Result { UPDATE_AVAILABLE, UP_TO_DATE, FAILED }

    private val mutex = Mutex()

    /** Плановая проверка при открытии: пропускается, если выключена или с прошлой прошло меньше суток. */
    suspend fun checkIfDue(context: Context) {
        val prefs = UpdatePrefs.get(context)
        if (!prefs.enabled.value) return
        val elapsed = System.currentTimeMillis() - prefs.lastCheckAt
        if (elapsed in 0 until CHECK_INTERVAL_MS) return
        check(context, manual = false)
    }

    /** Проверка сейчас. [manual] — по кнопке: показать и ранее скрытую версию. */
    suspend fun check(context: Context, manual: Boolean = true): Result = mutex.withLock {
        val prefs = UpdatePrefs.get(context)
        val latest = withContext(Dispatchers.IO) { fetchLatest() }
        prefs.lastCheckAt = System.currentTimeMillis()
        if (latest == null) return Result.FAILED
        val installed = installedVersion(context)
        return if (isNewer(latest.version, installed)) {
            Log.i(TAG, "update available: ${latest.version} (installed $installed)")
            prefs.setAvailable(latest, showAgain = manual)
            Result.UPDATE_AVAILABLE
        } else {
            prefs.setAvailable(null)
            Result.UP_TO_DATE
        }
    }

    fun pending(context: Context, update: UpdateInfo?): UpdateInfo? =
        update?.takeIf { isNewer(it.version, installedVersion(context)) }

    fun installedVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (_: Exception) {
        BuildConfig.VERSION_NAME
    }

    private fun fetchLatest(): UpdateInfo? = try {
        val connection = URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "DP-Clock/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "update check failed: HTTP ${connection.responseCode}")
                return null
            }
            val body = connection.inputStream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_RESPONSE_BYTES) throw IOException("response too large")
                bytes.toString(Charsets.UTF_8)
            }
            parseRelease(body)
        } finally {
            connection.disconnect()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Update check failed", e)
        null
    }

    /**
     * Разбирает ответ GitHub «последний выпуск» без org.json (в unit-тестах JVM stub
     * JSONObject ненадёжен). Черновики и pre-release не предлагаются.
     */
    internal fun parseRelease(body: String): UpdateInfo? {
        if (jsonFlag(body, "draft") || jsonFlag(body, "prerelease")) return null
        val tag = jsonString(body, "tag_name") ?: return null
        val version = tag.removePrefix("v").takeIf { parseVersion(it) != null } ?: return null
        var apkUrl: String? = null
        val urlRegex = Regex("\"""browser_download_url\"\\s*:\\s*\"([^\"]+)\"\""")
        for (match in urlRegex.findAll(body)) {
            val url = match.groupValues[1]
            if (!url.startsWith(DOWNLOAD_PREFIX) || !url.endsWith(".apk")) continue
            val name = url.substringAfterLast('/')
            if (name.startsWith(ASSET_PREFIX) && !name.contains(ASSET_DEBUG_MARK)) {
                apkUrl = url
                break
            }
        }
        return UpdateInfo(version, apkUrl ?: RELEASES_PAGE)
    }

    /** true, если в JSON на верхнем уровне поле [name] — true. */
    private fun jsonFlag(body: String, name: String): Boolean =
        Regex("\"""$name\"\\s*:\\s*true\""").containsMatchIn(body)

    private fun jsonString(body: String, name: String): String? =
        Regex("\"""$name\"\\s*:\\s*\"([^\"]*)\"\""").find(body)?.groupValues?.get(1)

    /** «1.2.10» → [1, 2, 10]; суффиксы и мусор не разбираются. */
    internal fun parseVersion(version: String): List<Int>? {
        val parts = version.trim().removePrefix("v").split('.')
        if (parts.isEmpty() || parts.size > 4) return null
        return parts.map { part ->
            part.takeIf { it.length in 1..6 && it.all(Char::isDigit) }?.toInt() ?: return null
        }
    }

    /** Промежуточная сборка (1.2.0-dev) считается своей базой: 1.2.0 её не «обновляет». */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        val new = parseVersion(candidate) ?: return false
        val old = parseVersion(installed.substringBefore('-')) ?: return false
        for (i in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrElse(i) { 0 }
            val b = old.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
