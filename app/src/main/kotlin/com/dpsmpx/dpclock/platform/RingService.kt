package com.dpsmpx.dpclock.platform

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.data.AlarmFire
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.SignalStyle
import com.dpsmpx.dpclock.model.SleepRules
import com.dpsmpx.dpclock.model.WakeCheck
import com.dpsmpx.dpclock.model.StepSignal
import com.dpsmpx.dpclock.model.TimerState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Что сейчас звонит. */
sealed interface RingItem {
    val key: String
    val since: Long
    val sound: RingSound

    data class AlarmRing(
        val alarmId: String,
        val hour: Int,
        val minute: Int,
        val label: String,
        val snoozeMinutes: Int,
        override val sound: RingSound,
        override val since: Long,
        /** Без проверки «Выключить» не выключает (D-015). */
        val wakeCheck: WakeCheck = WakeCheck(),
        /** Приложение, которое открыть после выключения (D-017, п. 5). */
        val afterApp: String = "",
    ) : RingItem {
        override val key: String get() = "alarm:$alarmId"
    }

    /**
     * Мягкое пробуждение (D-017, п. 3): с [startsAt] до будильника в [alarmAt]
     * экран светлеет, в последние минуты — тихий звук. [canDismiss] — «Я уже
     * проснулся» выключает это срабатывание (без проверки пробуждения).
     */
    data class GentleRing(
        val alarmId: String,
        val label: String,
        val startsAt: Long,
        val alarmAt: Long,
        val canDismiss: Boolean,
        override val since: Long,
        override val sound: RingSound = RingSound(AlarmSound.Silent, vibrate = false),
    ) : RingItem {
        override val key: String get() = "gentle:$alarmId"
    }

    /** «Не спите?» через несколько минут после выключения (D-011, п. 3): без ответа — снова сигнал. */
    data class RecheckRing(val alarmId: String, val hour: Int, val minute: Int, val label: String, override val since: Long) : RingItem {
        override val key: String get() = "recheck:$alarmId"
        // Мягкий звук и вибрация: спящего разбудит повторный сигнал, а не этот вопрос.
        override val sound: RingSound get() = RingSound(AlarmSound.Builtin("dawn"), vibrate = true)
    }

    /** [finished] — таймер закончился; иначе — полный сигнал промежуточного шага. */
    data class TimerRing(
        val runId: String,
        val name: String,
        val finished: Boolean,
        override val since: Long,
        /** Звук закончившегося шага или пресета (D-016, п. 2). */
        override val sound: RingSound = RingSound(),
        /** Расчётный конец шага, мс UTC; для журнала. */
        val endedAt: Long? = null,
        /** Таймер ждёт «Дальше» (D-025, п. 1): вместо «+1 мин» — «Дальше». */
        val waiting: Boolean = false,
    ) : RingItem {
        override val key: String get() = "timer:$runId"
    }
}

/** Звонящие сигналы процесса; последний — сверху на экране сигнала. */
object Ringing {
    private val mutableItems = MutableStateFlow<List<RingItem>>(emptyList())
    val items: StateFlow<List<RingItem>> = mutableItems

    fun isAlarmRinging(alarmId: String): Boolean =
        mutableItems.value.any { it is RingItem.AlarmRing && it.alarmId == alarmId }

    fun isRecheckShowing(alarmId: String): Boolean =
        mutableItems.value.any { it is RingItem.RecheckRing && it.alarmId == alarmId }

    internal fun add(item: RingItem) = mutableItems.update { list -> list.filterNot { it.key == item.key } + item }

    internal fun remove(key: String) = mutableItems.update { list -> list.filterNot { it.key == key } }
}

/**
 * Служба переднего плана типа `systemExempted` на время звучания (D-008, п. 2):
 * мелодия, вибрация, полноэкранное уведомление, автовыключение.
 */
class RingService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val player by lazy { AlarmPlayer(this) }
    private val autoStops = mutableMapOf<String, Runnable>()
    private val gentleTasks = mutableMapOf<String, List<Runnable>>()
    private var pending = 0
    /** Вид сигнала из настроек на момент последнего сигнала (D-007, п. 2). */
    private var fullScreen = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Каждый запуск через startForegroundService обязан сразу показать уведомление.
        // «Тише» шлёт открытый экран сигнала обычным startService на каждое нажатие —
        // уведомление сигнала заново не публикуется.
        if (intent?.action != ACTION_QUIET) goForeground()
        pending++
        DpClockApp.scope.launch(Dispatchers.Main) {
            try {
                if (intent != null) handle(intent)
            } finally {
                pending--
                stopIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun handle(intent: Intent) {
        val repository = DpClockApp.graph(this).repository
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        when (intent.action) {
            ACTION_FIRE_ALARM -> when (val fire = repository.onAlarmFired(id, intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L))) {
                is AlarmFire.Ring -> ring(alarmRing(fire))
                is AlarmFire.Recheck -> ring(
                    RingItem.RecheckRing(id, fire.alarm.hour, fire.alarm.minute, fire.alarm.label, System.currentTimeMillis()),
                )
                AlarmFire.Skip -> Unit
            }
            ACTION_FIRE_GENTLE -> {
                val alarmAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L)
                val alarm = repository.onGentleFired(id, alarmAt) ?: return
                ring(
                    RingItem.GentleRing(
                        alarmId = id,
                        label = alarm.label,
                        startsAt = System.currentTimeMillis(),
                        alarmAt = alarmAt,
                        canDismiss = !alarm.wakeCheck.required,
                        since = System.currentTimeMillis(),
                    ),
                )
            }
            ACTION_GENTLE_CLOSE -> silence("gentle:$id")
            ACTION_GENTLE_AWAKE -> {
                repository.dismissEarly(id)
                silence("gentle:$id")
            }
            ACTION_RECHECK_OK -> {
                repository.recheckPassed(id, Ringing.items.value.firstOrNull { it.key == "recheck:$id" }?.since)
                silence("recheck:$id")
            }
            ACTION_QUIET -> player.quiet(QUIET_MILLIS)
            ACTION_FIRE_TIMER -> {
                val fire = repository.onTimerStepEnded(id) ?: return
                when (fire.signal) {
                    StepSignal.FULL -> ring(
                        RingItem.TimerRing(
                            id,
                            fire.run.name,
                            fire.run.state == TimerState.RINGING,
                            System.currentTimeMillis(),
                            RingSound(fire.sound),
                            fire.endedAt,
                            fire.run.isWaiting,
                        ),
                    )
                    StepSignal.SHORT -> Sounds.shortSignal(this, fire.sound)
                    StepSignal.SLEEP -> MediaStop.fadeAndPause(this)
                    StepSignal.NONE -> Unit
                }
            }
            ACTION_RING_TIMER -> ring(
                RingItem.TimerRing(
                    id,
                    intent.getStringExtra(EXTRA_NAME).orEmpty(),
                    intent.getBooleanExtra(EXTRA_FINISHED, true),
                    System.currentTimeMillis(),
                    RingSound(AlarmSound.decode(intent.getStringExtra(EXTRA_SOUND).orEmpty(), intent.getStringExtra(EXTRA_SOUND_TITLE).orEmpty())),
                    intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L).takeIf { it > 0 },
                    intent.getBooleanExtra(EXTRA_WAITING, false),
                ),
            )
            ACTION_SNOOZE -> {
                repository.snoozeAlarm(id)
                silence("alarm:$id")
            }
            ACTION_DISMISS -> {
                val item = Ringing.items.value.firstOrNull { it.key == "alarm:$id" } as? RingItem.AlarmRing
                repository.stopAlarm(id, SignalOutcome.DISMISSED)
                silence("alarm:$id")
                // Утром — задачи на сегодня (D-031).
                val graph = DpClockApp.graph(this)
                DpClockApp.scope.launch { graph.tasks.afterAlarm() }
                // Выключен не на экране сигнала — приложение открывается из уведомления (D-017, п. 5).
                if (item != null && item.afterApp.isNotEmpty() && !intent.getBooleanExtra(EXTRA_FROM_SCREEN, false)) {
                    DpClockApp.graph(this).notifier.openAfterAlarm(item.afterApp)
                }
            }
            ACTION_TIMER_STOP -> {
                val item = Ringing.items.value.firstOrNull { it.key == "timer:$id" } as? RingItem.TimerRing
                repository.stopTimerSignal(id, SignalOutcome.DISMISSED, item?.endedAt, item?.since)
                silence("timer:$id")
            }
            ACTION_TIMER_ADD_MINUTE -> {
                repository.addMinute(id)
                silence("timer:$id")
            }
            ACTION_TIMER_NEXT -> {
                val item = Ringing.items.value.firstOrNull { it.key == "timer:$id" } as? RingItem.TimerRing
                repository.stopTimerSignal(id, SignalOutcome.DISMISSED, item?.endedAt, item?.since)
                repository.resumeTimer(id)
                silence("timer:$id")
            }
        }
    }

    private fun alarmRing(fire: AlarmFire.Ring): RingItem.AlarmRing {
        val alarm = fire.alarm
        return RingItem.AlarmRing(
            alarmId = alarm.id,
            hour = alarm.hour,
            minute = alarm.minute,
            label = alarm.label,
            snoozeMinutes = fire.snoozeMinutes,
            sound = RingSound(alarm.sound, alarm.vibrate, alarm.rampSeconds),
            since = System.currentTimeMillis(),
            wakeCheck = alarm.wakeCheck,
            afterApp = alarm.after.app,
        )
    }

    private suspend fun ring(item: RingItem) {
        val settings = DpClockApp.graph(this).repository.currentSettings()
        fullScreen = settings.signalStyle == SignalStyle.FULL_SCREEN
        // Будильник зазвонил — его мягкое пробуждение закончилось.
        if (item is RingItem.AlarmRing) silence("gentle:${item.alarmId}")
        Ringing.add(item)
        player.play(item.sound)
        goForeground()
        if (item is RingItem.GentleRing) {
            scheduleGentle(item)
            return
        }
        if (item is RingItem.RecheckRing) {
            // Минута на ответ; потом — снова будильник с той же проверкой.
            val timeout = Runnable {
                DpClockApp.scope.launch(Dispatchers.Main) {
                    val fire = DpClockApp.graph(this@RingService).repository.recheckFailed(item.alarmId)
                    silence(item.key)
                    if (fire != null) ring(alarmRing(fire))
                    stopIfIdle()
                }
            }
            autoStops.put(item.key, timeout)?.let(main::removeCallbacks)
            main.postDelayed(timeout, RECHECK_MILLIS)
            return
        }
        val minutes = settings.autoStopMinutes ?: return
        val autoStop = Runnable {
            DpClockApp.scope.launch(Dispatchers.Main) {
                val repository = DpClockApp.graph(this@RingService).repository
                when (item) {
                    is RingItem.AlarmRing -> repository.stopAlarm(item.alarmId, SignalOutcome.AUTO_STOPPED)
                    is RingItem.TimerRing -> repository.stopTimerSignal(item.runId, SignalOutcome.AUTO_STOPPED, item.endedAt, item.since)
                    is RingItem.RecheckRing, is RingItem.GentleRing -> Unit
                }
                silence(item.key)
                stopIfIdle()
            }
        }
        autoStops.put(item.key, autoStop)?.let(main::removeCallbacks)
        main.postDelayed(autoStop, minutes * 60_000L)
    }

    /** Тихий звук — в последние минуты; не пришёл будильник — через 2 мин после его времени рассвет гаснет сам. */
    private fun scheduleGentle(item: RingItem.GentleRing) {
        val now = System.currentTimeMillis()
        val soundAt = item.alarmAt - SleepRules.GENTLE_SOUND_MINUTES * 60_000L
        val withSound = item.copy(sound = RingSound(AlarmSound.Builtin("dawn"), vibrate = false, rampSeconds = SleepRules.GENTLE_SOUND_MINUTES * 60, volume = 0.35f))
        val startSound = Runnable {
            if (Ringing.items.value.any { it.key == item.key }) {
                Ringing.add(withSound)
                if (Ringing.items.value.lastOrNull()?.key == item.key) player.play(withSound.sound)
            }
        }
        val end = Runnable {
            silence(item.key)
            stopIfIdle()
        }
        main.postDelayed(startSound, (soundAt - now).coerceAtLeast(0L))
        main.postDelayed(end, item.alarmAt - now + 2 * 60_000L)
        gentleTasks[item.key] = listOf(startSound, end)
    }

    private fun silence(key: String) {
        gentleTasks.remove(key)?.forEach(main::removeCallbacks)
        autoStops.remove(key)?.let(main::removeCallbacks)
        Ringing.remove(key)
        // Остался другой сигнал — звучит его звук.
        val next = Ringing.items.value.lastOrNull()
        if (next == null) {
            player.stop()
        } else {
            player.play(next.sound)
            goForeground()
        }
    }

    private fun stopIfIdle() {
        if (pending == 0 && Ringing.items.value.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun goForeground() {
        val notifier = DpClockApp.graph(this).notifier
        val notification = Ringing.items.value.lastOrNull()?.let { notifier.ringing(it, fullScreen) } ?: notifier.ringPlaceholder()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(Notifier.ID_RING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            } else {
                startForeground(Notifier.ID_RING, notification)
            }
        }.onFailure {
            // Система не дала службу переднего плана — хотя бы уведомление с полноэкранным сигналом.
            notifier.show(Notifier.ID_RING, notification)
        }
    }

    override fun onDestroy() {
        autoStops.values.forEach(main::removeCallbacks)
        autoStops.clear()
        gentleTasks.values.flatten().forEach(main::removeCallbacks)
        gentleTasks.clear()
        player.stop()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_FIRE_ALARM = "com.dpsmpx.dpclock.ring.FIRE_ALARM"
        private const val ACTION_FIRE_TIMER = "com.dpsmpx.dpclock.ring.FIRE_TIMER"
        private const val ACTION_RING_TIMER = "com.dpsmpx.dpclock.ring.RING_TIMER"
        const val ACTION_SNOOZE = "com.dpsmpx.dpclock.ring.SNOOZE"
        const val ACTION_DISMISS = "com.dpsmpx.dpclock.ring.DISMISS"
        const val ACTION_TIMER_STOP = "com.dpsmpx.dpclock.ring.TIMER_STOP"
        const val ACTION_TIMER_ADD_MINUTE = "com.dpsmpx.dpclock.ring.TIMER_ADD_MINUTE"
        const val ACTION_TIMER_NEXT = "com.dpsmpx.dpclock.ring.TIMER_NEXT"
        const val ACTION_RECHECK_OK = "com.dpsmpx.dpclock.ring.RECHECK_OK"
        private const val ACTION_FIRE_GENTLE = "com.dpsmpx.dpclock.ring.FIRE_GENTLE"
        const val ACTION_GENTLE_CLOSE = "com.dpsmpx.dpclock.ring.GENTLE_CLOSE"
        const val ACTION_GENTLE_AWAKE = "com.dpsmpx.dpclock.ring.GENTLE_AWAKE"

        /** «Выключить» нажато на экране сигнала: приложение после будильника он открывает сам. */
        const val EXTRA_FROM_SCREEN = "fromScreen"

        /** Человек решает проверку пробуждения: сигнал молчит [QUIET_MILLIS] с последнего действия (D-015, п. 2). */
        const val ACTION_QUIET = "com.dpsmpx.dpclock.ring.QUIET"
        private const val QUIET_MILLIS = 20_000L
        private const val RECHECK_MILLIS = 60_000L
        private const val EXTRA_ID = "id"
        private const val EXTRA_SCHEDULED_AT = "scheduledAt"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_FINISHED = "finished"
        private const val EXTRA_SOUND = "sound"
        private const val EXTRA_WAITING = "waiting"
        private const val EXTRA_SOUND_TITLE = "soundTitle"

        fun fireAlarm(context: Context, alarmId: String, scheduledAt: Long) = startForeground(
            context,
            intent(context, ACTION_FIRE_ALARM, alarmId).putExtra(EXTRA_SCHEDULED_AT, scheduledAt),
        )

        fun fireGentle(context: Context, alarmId: String, alarmAt: Long) = startForeground(
            context,
            intent(context, ACTION_FIRE_GENTLE, alarmId).putExtra(EXTRA_SCHEDULED_AT, alarmAt),
        )

        fun fireTimer(context: Context, runId: String) =
            startForeground(context, intent(context, ACTION_FIRE_TIMER, runId))

        fun ringTimer(context: Context, runId: String, name: String, finished: Boolean, sound: AlarmSound, endedAt: Long?, waiting: Boolean) = startForeground(
            context,
            intent(context, ACTION_RING_TIMER, runId)
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_FINISHED, finished)
                .putExtra(EXTRA_WAITING, waiting)
                .putExtra(EXTRA_SOUND, sound.encode())
                .putExtra(EXTRA_SOUND_TITLE, sound.title)
                .putExtra(EXTRA_SCHEDULED_AT, endedAt ?: 0L),
        )

        /** Интент кнопки сигнала: экран сигнала и уведомление. */
        fun intent(context: Context, action: String, id: String): Intent =
            Intent(context, RingService::class.java).setAction(action).putExtra(EXTRA_ID, id)

        private fun startForeground(context: Context, intent: Intent) {
            runCatching { context.startForegroundService(intent) }.onFailure {
                // Запуск из фона запрещён (неточный будильник без разрешения): уведомление без звука службы.
                DpClockApp.graph(context).notifier.missedStart()
            }
        }
    }
}
