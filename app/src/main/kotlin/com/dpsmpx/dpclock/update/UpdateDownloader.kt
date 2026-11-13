package com.dpsmpx.dpclock.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание APK выпуска во внутренний кэш и запуск установщика системы.
 * Подпись сверяет Android с уже установленным приложением.
 */
object UpdateDownloader {
    private const val TAG = "UpdateDownloader"
    private const val DIR = "updates"
    private const val FILE_NAME = "dp-clock-update.apk"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 120_000
    private const val MAX_BYTES = 80L * 1024 * 1024

    sealed interface Outcome {
        data object Ready : Outcome
        data class Failed(val reason: String) : Outcome
        data object NeedInstallPermission : Outcome
    }

    /**
     * Скачивает [url] в кэш и открывает установщик.
     * [onProgress] — 0…100, вызывается с фонового потока.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit = {},
    ): Outcome = withContext(Dispatchers.IO) {
        if (!url.startsWith("https://github.com/${UpdateChecker.REPOSITORY}/releases/")) {
            return@withContext Outcome.Failed("bad_url")
        }
        val app = context.applicationContext
        val dir = File(app.cacheDir, DIR).also { it.mkdirs() }
        val file = File(dir, FILE_NAME)
        try {
            download(url, file, onProgress)
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            file.delete()
            return@withContext Outcome.Failed("download")
        }
        withContext(Dispatchers.Main) { install(app, file) }
    }

    private fun download(url: String, file: File, onProgress: (Int) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "DP-Clock")
            connection.setRequestProperty("Accept", "application/octet-stream")
            if (connection.responseCode !in 200..299) {
                throw IOException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            if (total > MAX_BYTES) throw IOException("too large")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (read > MAX_BYTES) throw IOException("too large")
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                    output.flush()
                }
            }
            onProgress(100)
        } finally {
            connection.disconnect()
        }
    }

    private fun install(context: Context, file: File): Outcome {
        if (!context.packageManager.canRequestPackageInstalls()) {
            return Outcome.NeedInstallPermission
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Outcome.Ready
        } catch (e: Exception) {
            Log.w(TAG, "install intent failed", e)
            Outcome.Failed("install")
        }
    }

    /** Открыть системный экран «Установка неизвестных приложений» для DP Clock. */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
