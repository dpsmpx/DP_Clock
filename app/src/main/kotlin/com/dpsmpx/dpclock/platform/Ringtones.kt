package com.dpsmpx.dpclock.platform

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns

/** Мелодии телефона и выбранные файлы (D-013, п. 2). */
object Ringtones {
    data class Entry(val uri: String, val title: String)

    /** Мелодии телефона вида [type] (`RingtoneManager.TYPE_ALARM`, `TYPE_RINGTONE`); при ошибке — пусто. */
    fun list(context: Context, type: Int): List<Entry> = runCatching {
        val manager = RingtoneManager(context).apply { setType(type) }
        // Курсор принадлежит RingtoneManager: закрывать его не нужно.
        val cursor = manager.cursor
        buildList {
            while (cursor.moveToNext()) {
                add(Entry(manager.getRingtoneUri(cursor.position).toString(), cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX)))
            }
        }.distinctBy { it.uri }
    }.getOrDefault(emptyList())

    /** Доступ к файлу сохраняется после перезапуска телефона; название — для списка. */
    fun keepFile(context: Context, uri: Uri): String {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()
    }
}
