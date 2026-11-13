package com.dpsmpx.dpclock.platform

import android.content.Context
import android.os.Build
import com.dpsmpx.dpclock.BuildConfig
import com.dpsmpx.dpclock.data.deviceStorage
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

/**
 * Журнал сбоев в файле (D-036, п. 2): записи от новых к старым не хранятся — файл
 * дописывается, лишнее с начала отрезается. Без Android: проверяется на JVM.
 */
class CrashLog(private val file: File, private val maxEntries: Int = MAX_ENTRIES) {
    /** Записать сбой: [header] — когда и где, под ним трасса [error]. Никогда не бросает. */
    fun append(header: String, error: Throwable) {
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().trim().take(MAX_TRACE)
            val entries = (entries() + (header.trim() + "\n" + trace)).takeLast(maxEntries)
            file.parentFile?.mkdirs()
            file.writeText(entries.joinToString("") { SEPARATOR + it + "\n" })
        }
    }

    /** Записи по порядку, последняя — самая свежая; файла нет или он испорчен — пусто. */
    fun entries(): List<String> = runCatching {
        file.readText().split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    fun clear() {
        runCatching { file.delete() }
    }

    companion object {
        const val MAX_ENTRIES = 20

        /** Трасса длиннее (бесконечная рекурсия) обрезается. */
        private const val MAX_TRACE = 12_000
        private const val SEPARATOR = "\u001E\n"
    }
}

/**
 * Сбои приложения (D-036, п. 2): необработанное исключение записывается в файл
 * в хранилище устройства — сбой до первой разблокировки тоже — и уходит дальше
 * системе, как раньше. Журнал остаётся на телефоне; им делится человек сам.
 */
object Crashes {
    fun log(context: Context): CrashLog = CrashLog(File(context.deviceStorage().noBackupFilesDir, "crashes.log"))

    fun install(context: Context) {
        val log = log(context.applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            log.append(header(thread.name), error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** «2026-11-11T16:40:00Z · 0.23.0 (29) · Xiaomi 23124RA7EO · Android 15 · main». */
    private fun header(thread: String): String = listOf(
        Instant.now().toString().substringBefore('.').removeSuffix("Z") + "Z",
        "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})",
        "${Build.MANUFACTURER} ${Build.MODEL}",
        "Android ${Build.VERSION.RELEASE}",
        thread,
    ).joinToString(" · ")
}
