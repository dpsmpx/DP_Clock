package com.dpsmpx.dpclock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.widget.Toast
import com.dpsmpx.dpclock.model.AssistantRules
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.platform.RingItem
import com.dpsmpx.dpclock.platform.RingService
import com.dpsmpx.dpclock.platform.Ringing
import com.dpsmpx.dpclock.platform.TimeText
import com.dpsmpx.dpclock.ui.untilText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Стандартные команды Android от ассистента и других приложений (D-020, п. 5):
 * поставить будильник или таймер, показать их, отложить или выключить звонящий.
 * Выбор автора — сразу создавать, если команда просит не спрашивать
 * (`EXTRA_SKIP_UI`); иначе — создать и открыть, чтобы поправить. Окна у
 * активности нет; она закрывается, когда сделала своё: пока она открыта, Android
 * разрешает ей открыть приложение.
 */
class AssistantActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = intent
        when (intent?.action) {
            AlarmClock.ACTION_SET_ALARM -> setAlarm(intent)
            AlarmClock.ACTION_SET_TIMER -> setTimer(intent)
            AlarmClock.ACTION_SHOW_ALARMS -> open(MainActivity.intent(this, Section.ALARMS))
            AlarmClock.ACTION_SHOW_TIMERS -> open(MainActivity.intent(this, Section.TIMERS))
            AlarmClock.ACTION_SNOOZE_ALARM -> {
                ringing()?.let { startService(RingService.intent(this, RingService.ACTION_SNOOZE, it.alarmId)) }
                finish()
            }
            AlarmClock.ACTION_DISMISS_ALARM -> dismiss()
            else -> finish()
        }
    }

    private fun open(target: Intent) {
        startActivity(target)
        finish()
    }

    /** Работа с базой — в области процесса; открыть приложение и закрыться — после неё, здесь же. */
    private fun afterSaving(work: suspend () -> Intent?) {
        DpClockApp.scope.launch(Dispatchers.Main) {
            val target = runCatching { persist { work() } }.getOrNull()
            if (target != null && !isFinishing) startActivity(target)
            finish()
        }
    }

    private fun ringing(): RingItem.AlarmRing? = Ringing.items.value.filterIsInstance<RingItem.AlarmRing>().lastOrNull()

    /** Выключить звонящий: с проверкой пробуждения — только через экран сигнала (D-015). */
    private fun dismiss() {
        val item = ringing()
        when {
            item == null -> Unit
            item.wakeCheck.required -> startActivity(Intent(this, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else -> startService(RingService.intent(this, RingService.ACTION_DISMISS, item.alarmId))
        }
        finish()
    }

    private fun setAlarm(intent: Intent) {
        val hour = intent.getIntExtra(AlarmClock.EXTRA_HOUR, -1)
        if (hour < 0) {
            // Время не названо — выбрать его в приложении.
            open(MainActivity.quickIntent(this, QuickAction.NEW_ALARM))
            return
        }
        val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
        val wanted = AssistantRules.alarm(
            id = UUID.randomUUID().toString(),
            hour = hour,
            minute = intent.getIntExtra(AlarmClock.EXTRA_MINUTES, 0),
            calendarDays = intent.getIntegerArrayListExtra(AlarmClock.EXTRA_DAYS).orEmpty(),
            message = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE),
            vibrate = intent.getBooleanExtra(AlarmClock.EXTRA_VIBRATE, true),
            createdAt = System.currentTimeMillis(),
        ) ?: return finish()
        val app = applicationContext
        afterSaving {
            val repository = DpClockApp.graph(app).repository
            val existing = AssistantRules.matching(repository.surfaceData().alarms, wanted)
            val alarm = existing?.copy(enabled = true) ?: wanted
            val at = repository.saveAlarm(alarm)
            if (skipUi) {
                val text = app.getString(R.string.assistant_alarm_set, TimeText.time(app, alarm.hour, alarm.minute)) +
                    if (at > 0) " · " + untilText(app.resources, at - System.currentTimeMillis()) else ""
                toast(app, text)
                null
            } else {
                MainActivity.quickIntent(app, QuickAction.EDIT_ALARM, alarm.id)
            }
        }
    }

    private fun setTimer(intent: Intent) {
        val millis = AssistantRules.timerMillis(intent.getIntExtra(AlarmClock.EXTRA_LENGTH, 0))
        if (millis == null) {
            open(MainActivity.intent(this, Section.TIMERS))
            return
        }
        val skipUi = intent.getBooleanExtra(AlarmClock.EXTRA_SKIP_UI, false)
        val name = intent.getStringExtra(AlarmClock.EXTRA_MESSAGE).orEmpty().trim()
        val app = applicationContext
        afterSaving {
            DpClockApp.graph(app).repository.startTimer(TimerProgram.single(millis, name), name, null)
            if (skipUi) {
                toast(app, app.getString(R.string.assistant_timer_set, TimeText.duration(millis)))
                null
            } else {
                MainActivity.intent(app, Section.TIMERS)
            }
        }
    }
}

/** Итог команды — коротким сообщением: окна у команды нет. */
private fun toast(app: Context, text: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(app, text, Toast.LENGTH_LONG).show() }
}
