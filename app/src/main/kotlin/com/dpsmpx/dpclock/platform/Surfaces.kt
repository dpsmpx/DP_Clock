package com.dpsmpx.dpclock.platform

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import androidx.core.content.edit
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.data.deviceStorage
import com.dpsmpx.dpclock.model.TimerProgram
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Последний обычный таймер — для плитки «Последний таймер». */
data class LastTimer(val program: TimerProgram, val name: String, val presetId: String?)

/**
 * Виджеты и плитки (D-020): после изменений данных — одно обновление через
 * мгновение, а не по каждому изменению. Тут же — мелочи плиток, которых нет в
 * базе: последняя длина таймера засыпания, последний таймер, будильник,
 * пропущенный с плитки. Они в хранилище устройства и не переносятся.
 */
object Surfaces {
    private const val DEBOUNCE_MILLIS = 400L
    private const val PREFS = "surfaces"
    private const val SLEEP_MINUTES = "sleepMinutes"
    private const val LAST_PROGRAM = "lastProgram"
    private const val LAST_NAME = "lastName"
    private const val LAST_PRESET = "lastPreset"
    private const val SKIPPED_ALARM = "skippedAlarm"
    private const val SKIPPED_AT = "skippedAt"

    /** Длина таймера засыпания с плитки, пока его ни разу не запускали. */
    const val DEFAULT_SLEEP_MINUTES = 30

    private var pending: Job? = null

    @Synchronized
    fun changed(context: Context) {
        val app = context.applicationContext
        pending?.cancel()
        pending = DpClockApp.scope.launch {
            delay(DEBOUNCE_MILLIS)
            Widgets.updateAll(app)
            Tiles.requestAll(app)
        }
    }

    private fun prefs(context: Context) = context.deviceStorage().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun rememberTimer(context: Context, program: TimerProgram, name: String, presetId: String?) {
        prefs(context).edit {
            if (program.sleep) {
                putInt(SLEEP_MINUTES, (program.totalMillis / 60_000L).toInt().coerceAtLeast(1))
            } else {
                putString(LAST_PROGRAM, program.encode()).putString(LAST_NAME, name).putString(LAST_PRESET, presetId)
            }
        }
    }

    fun sleepMinutes(context: Context): Int = prefs(context).getInt(SLEEP_MINUTES, DEFAULT_SLEEP_MINUTES)

    fun lastTimer(context: Context): LastTimer? {
        val prefs = prefs(context)
        val program = prefs.getString(LAST_PROGRAM, null)?.let { runCatching { TimerProgram.decode(it) }.getOrNull() } ?: return null
        return LastTimer(program, prefs.getString(LAST_NAME, null).orEmpty(), prefs.getString(LAST_PRESET, null))
    }

    /** Будильник, пропущенный с плитки, и срабатывание, которое пропущено. */
    fun skipped(context: Context): Pair<String, Long>? {
        val prefs = prefs(context)
        val id = prefs.getString(SKIPPED_ALARM, null) ?: return null
        return id to prefs.getLong(SKIPPED_AT, 0L)
    }

    fun setSkipped(context: Context, alarmId: String?, at: Long = 0L) {
        prefs(context).edit { putString(SKIPPED_ALARM, alarmId).putLong(SKIPPED_AT, at) }
    }

    /** Плитку перерисовать: система вызовет её onStartListening. */
    fun requestTile(context: Context, service: Class<out TileService>) {
        runCatching { TileService.requestListeningState(context, ComponentName(context, service)) }
    }
}
