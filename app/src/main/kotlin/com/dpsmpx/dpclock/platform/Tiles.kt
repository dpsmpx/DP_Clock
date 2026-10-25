package com.dpsmpx.dpclock.platform

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.MainActivity
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.Section
import com.dpsmpx.dpclock.data.SurfaceData
import com.dpsmpx.dpclock.model.AlarmRules
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.isRepeating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Плитки быстрых настроек DP_Clock (D-020). */
object Tiles {
    fun requestAll(context: Context) {
        for (service in listOf(SleepTimerTile::class.java, NextAlarmTile::class.java, LastTimerTile::class.java, StopwatchTile::class.java)) {
            Surfaces.requestTile(context, service)
        }
    }
}

/**
 * Общее у плиток: при открытии шторки — свежие данные из базы; нажатие —
 * действие в области процесса, потом плитка перерисовывается.
 */
abstract class DpClockTile : TileService() {
    protected val repository get() = DpClockApp.graph(this).repository

    override fun onStartListening() = refresh()

    override fun onTileAdded() = refresh()

    override fun onClick() {
        DpClockApp.scope.launch {
            click(repository.surfaceData())
            refresh()
        }
    }

    protected abstract fun render(tile: Tile, data: SurfaceData)

    protected abstract suspend fun click(data: SurfaceData)

    protected fun refresh() {
        DpClockApp.scope.launch {
            val data = repository.surfaceData()
            withContext(Dispatchers.Main) {
                val tile = qsTile ?: return@withContext
                render(tile, data)
                tile.updateTile()
            }
        }
    }

    /** Открыть раздел приложения и свернуть шторку. */
    protected suspend fun openSection(section: Section) = withContext(Dispatchers.Main) {
        val intent = MainActivity.intent(this@DpClockTile, section)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this@DpClockTile, 300 + section.ordinal, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            startOld(intent)
        }
    }

    /** До Android 14 у плитки есть только этот способ открыть приложение. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun startOld(intent: Intent) {
        startActivityAndCollapse(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    protected fun Tile.show(state: Int, subtitle: String?) {
        this.state = state
        this.subtitle = subtitle
        stateDescription = subtitle
    }
}

/** «Таймер засыпания»: нажатие — пошёл на прошлую длину; ещё раз — стоп (выбор автора). */
class SleepTimerTile : DpClockTile() {
    override fun render(tile: Tile, data: SurfaceData) {
        val run = data.runs.firstOrNull { it.program.sleep }
        if (run == null) {
            tile.show(Tile.STATE_INACTIVE, getString(R.string.minutes_value, Surfaces.sleepMinutes(this)))
        } else {
            val left = (run.remainingAt(Clocks.moment()) + 59_999L) / 60_000L
            tile.show(Tile.STATE_ACTIVE, getString(R.string.tile_minutes_left, left.toInt()))
        }
    }

    override suspend fun click(data: SurfaceData) {
        if (data.runs.any { it.program.sleep }) {
            repository.stopSleepTimers()
        } else {
            repository.startTimer(TimerProgram.sleep(Surfaces.sleepMinutes(this) * 60_000L), getString(R.string.sleep_timer), null)
        }
    }
}

/** «Ближайший будильник»: видно его время; нажатие — пропустить, ещё раз — вернуть. */
class NextAlarmTile : DpClockTile() {
    private val zone get() = ZoneId.systemDefault()

    /** Будильник, пропущенный с плитки, — если пропуск ещё в силе. */
    private fun skipped(data: SurfaceData): Pair<String, Long>? {
        val (id, at) = Surfaces.skipped(this) ?: return null
        val alarm = data.alarms.firstOrNull { it.id == id }
        val valid = alarm != null && at > System.currentTimeMillis() && if (alarm.repeat.isRepeating) {
            alarm.skipDate == Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        } else {
            !alarm.enabled
        }
        if (!valid) Surfaces.setSkipped(this, null)
        return if (valid) id to at else null
    }

    override fun render(tile: Tile, data: SurfaceData) {
        val skipped = skipped(data)
        when {
            skipped != null -> tile.show(Tile.STATE_INACTIVE, getString(R.string.tile_alarm_skipped, TimeText.time(this, skipped.second)))
            else -> {
                val next = Widgets.nextAlarmText(this, data)
                tile.show(if (next == null) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE, next ?: getString(R.string.widget_no_alarms))
            }
        }
    }

    override suspend fun click(data: SurfaceData) {
        val skipped = skipped(data)
        if (skipped != null) {
            val alarm = data.alarms.first { it.id == skipped.first }
            if (alarm.repeat.isRepeating) repository.saveAlarm(alarm.copy(skipDate = null)) else repository.setAlarmEnabled(alarm.id, true)
            Surfaces.setSkipped(this, null)
            return
        }
        val (alarm, at) = AlarmRules.nearestAlarm(data.alarms, Instant.now(), zone, data.exceptions) ?: return openSection(Section.ALARMS)
        if (alarm.repeat.isRepeating) {
            repository.saveAlarm(alarm.copy(skipDate = at.atZone(zone).toLocalDate()))
        } else {
            repository.setAlarmEnabled(alarm.id, false)
        }
        Surfaces.setSkipped(this, alarm.id, at.toEpochMilli())
    }
}

/** «Последний таймер»: запустить его ещё раз одним нажатием; идёт — открыть «Таймеры». */
class LastTimerTile : DpClockTile() {
    override fun render(tile: Tile, data: SurfaceData) {
        val running = data.runs.firstOrNull { !it.program.sleep && it.state != TimerState.RINGING }
        val last = Surfaces.lastTimer(this)
        when {
            running != null -> tile.show(Tile.STATE_ACTIVE, running.name.ifBlank { TimeText.countdown(running.remainingAt(Clocks.moment())) })
            last != null -> tile.show(
                Tile.STATE_INACTIVE,
                listOf(last.name, TimeText.duration(last.program.totalMillis)).filter { it.isNotBlank() }.joinToString(" · "),
            )
            else -> tile.show(Tile.STATE_INACTIVE, null)
        }
    }

    override suspend fun click(data: SurfaceData) {
        val last = Surfaces.lastTimer(this)
        if (data.runs.any { !it.program.sleep && it.state != TimerState.RINGING } || last == null) {
            openSection(Section.TIMERS)
        } else {
            repository.startTimer(last.program, last.name, last.presetId)
        }
    }
}

/** «Секундомер»: старт и пауза из шторки. */
class StopwatchTile : DpClockTile() {
    override fun render(tile: Tile, data: SurfaceData) {
        val stopwatch = data.stopwatch
        when (stopwatch.state) {
            StopwatchState.RUNNING -> tile.show(Tile.STATE_ACTIVE, getString(R.string.tile_stopwatch_running))
            StopwatchState.PAUSED -> tile.show(
                Tile.STATE_INACTIVE,
                getString(R.string.tile_stopwatch_paused, TimeText.stopwatch(stopwatch.elapsedAt(Clocks.moment()))),
            )
            StopwatchState.RESET -> tile.show(Tile.STATE_INACTIVE, null)
        }
    }

    override suspend fun click(data: SurfaceData) {
        repository.updateStopwatch { if (it.state == StopwatchState.RUNNING) it.pause(Clocks.moment()) else it.start(Clocks.moment()) }
    }
}
