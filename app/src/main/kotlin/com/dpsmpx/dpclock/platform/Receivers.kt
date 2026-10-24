package com.dpsmpx.dpclock.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.StepSignal
import com.dpsmpx.dpclock.model.TimerState
import kotlinx.coroutines.launch

/** Будильник Android пришёл: конец будильника, «Отложить» или шага таймера. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_BEDTIME -> {
                val bedAt = intent.getLongExtra(EXTRA_BED_AT, 0L)
                val alarmAt = intent.getLongExtra(EXTRA_ALARM_AT, 0L).takeIf { it > 0 }
                runAsync {
                    val graph = DpClockApp.graph(context)
                    graph.notifier.bedtime(bedAt, alarmAt, graph.repository.currentSettings().bedtime.sleepMinutes)
                    graph.repository.syncBedtime()
                }
                return
            }
            ACTION_REMINDER -> {
                DpClockApp.graph(context).notifier.reminder(intent.getStringExtra(EXTRA_ID).orEmpty(), intent.getStringExtra(EXTRA_TEXT).orEmpty())
                return
            }
        }
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_GENTLE -> RingService.fireGentle(context, id, intent.getLongExtra(EXTRA_ALARM_AT, 0L))
            // Службу сигнала — сразу, пока действует разрешение точного будильника
            // на запуск службы переднего плана; разбор — уже в ней.
            ACTION_ALARM -> RingService.fireAlarm(context, id, intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L))
            ACTION_TIMER ->
                if (intent.getBooleanExtra(EXTRA_FULL, true)) {
                    RingService.fireTimer(context, id)
                } else {
                    runAsync {
                        val fire = DpClockApp.graph(context).repository.onTimerStepEnded(id)
                        when (fire?.signal) {
                            StepSignal.SHORT -> Sounds.shortSignal(context, fire.sound)
                            // Шаг стал последним после «+1 мин» на паузе — сигнал полный.
                            StepSignal.FULL -> RingService.ringTimer(context, id, fire.run.name, fire.run.state == TimerState.RINGING, fire.sound, fire.endedAt)
                            StepSignal.SLEEP -> MediaStop.fadeAndPause(context)
                            StepSignal.NONE, null -> Unit
                        }
                    }
                }
        }
    }

    companion object {
        const val ACTION_ALARM = "com.dpsmpx.dpclock.action.ALARM"
        const val ACTION_TIMER = "com.dpsmpx.dpclock.action.TIMER"
        const val ACTION_GENTLE = "com.dpsmpx.dpclock.action.GENTLE"
        const val ACTION_BEDTIME = "com.dpsmpx.dpclock.action.BEDTIME"
        const val ACTION_REMINDER = "com.dpsmpx.dpclock.action.REMINDER"
        const val EXTRA_ALARM_AT = "alarmAt"
        const val EXTRA_BED_AT = "bedAt"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ID = "id"
        const val EXTRA_SCHEDULED_AT = "scheduledAt"
        const val EXTRA_FULL = "full"
    }
}

/**
 * События системы, после которых расписания пересчитываются (D-008, п. 5):
 * загрузка (и до разблокировки), смена времени и пояса, обновление
 * приложения, выдача разрешения на точные будильники.
 */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            android.app.AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync { DpClockApp.graph(context).repository.rescheduleAll() }
        }
    }
}

/** Кнопки уведомлений и виджетов без экрана: отложенный будильник, будильники, таймеры, пресеты, секундомер. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repository = DpClockApp.graph(context).repository
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        runAsync {
            when (intent.action) {
                ACTION_DISMISS_SNOOZED -> repository.stopAlarm(id, SignalOutcome.DISMISSED)
                ACTION_TIMER_PAUSE -> repository.pauseTimer(id)
                ACTION_TIMER_RESUME -> repository.resumeTimer(id)
                ACTION_TIMER_DELETE -> repository.deleteTimer(id)
                ACTION_STOPWATCH_PAUSE -> repository.updateStopwatch { it.pause(Clocks.moment()) }
                ACTION_STOPWATCH_RESUME -> repository.updateStopwatch { it.start(Clocks.moment()) }
                ACTION_STOPWATCH_LAP -> repository.updateStopwatch { it.lap(Clocks.moment()) }
                ACTION_STOPWATCH_RESET -> repository.updateStopwatch { it.reset() }
                // Виджеты (D-020): переключатель будильника, пресет одним нажатием.
                ACTION_ALARM_TOGGLE -> repository.setAlarmEnabled(id, intent.getBooleanExtra(RemoteViews.EXTRA_CHECKED, false))
                ACTION_PRESET_START -> repository.startPreset(id)
            }
        }
    }

    companion object {
        const val ACTION_DISMISS_SNOOZED = "com.dpsmpx.dpclock.action.DISMISS_SNOOZED"
        const val ACTION_TIMER_PAUSE = "com.dpsmpx.dpclock.action.TIMER_PAUSE"
        const val ACTION_TIMER_RESUME = "com.dpsmpx.dpclock.action.TIMER_RESUME"
        const val ACTION_TIMER_DELETE = "com.dpsmpx.dpclock.action.TIMER_DELETE"
        const val ACTION_STOPWATCH_PAUSE = "com.dpsmpx.dpclock.action.STOPWATCH_PAUSE"
        const val ACTION_STOPWATCH_RESUME = "com.dpsmpx.dpclock.action.STOPWATCH_RESUME"
        const val ACTION_STOPWATCH_LAP = "com.dpsmpx.dpclock.action.STOPWATCH_LAP"
        const val ACTION_STOPWATCH_RESET = "com.dpsmpx.dpclock.action.STOPWATCH_RESET"
        const val ACTION_ALARM_TOGGLE = "com.dpsmpx.dpclock.action.ALARM_TOGGLE"
        const val ACTION_PRESET_START = "com.dpsmpx.dpclock.action.PRESET_START"
        const val EXTRA_ID = "id"
    }
}

/** Работа приёмника в фоне приложения; система ждёт её до ~10 с. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    DpClockApp.scope.launch {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}
