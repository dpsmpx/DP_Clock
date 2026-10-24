package com.dpsmpx.dpclock.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dpsmpx.dpclock.MainActivity
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.RingActivity
import com.dpsmpx.dpclock.Section
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.Moment
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState

/** Уведомления (SCREEN_MAP.md, п. 11). */
class Notifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        manager.createNotificationChannels(
            listOf(
                // Звук и вибрацию даёт служба сигнала через поток будильника, не канал.
                NotificationChannel(CHANNEL_RING, text(R.string.channel_ring), NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(null, null)
                    enableVibration(false)
                },
                NotificationChannel(CHANNEL_MISSED, text(R.string.channel_missed), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_ONGOING, text(R.string.channel_ongoing), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun show(id: Int, notification: Notification, tag: String? = null) {
        // Без разрешения система уведомление молча не покажет; плашка надёжности это объясняет.
        if (manager.areNotificationsEnabled()) manager.notify(tag, id, notification)
    }

    // Сигнал

    /** [fullScreen] — по настройке «Вид сигнала»; иначе только всплывающее уведомление с кнопками. */
    fun ringing(item: RingItem, fullScreen: Boolean): Notification {
        val screen = PendingIntent.getActivity(
            context,
            0,
            Intent(context, RingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(context, CHANNEL_RING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(screen)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (fullScreen) builder.setFullScreenIntent(screen, true)
        when (item) {
            is RingItem.AlarmRing -> builder
                .setContentTitle(alarmTitle(item.label))
                .setContentText(TimeText.time(context, item.hour, item.minute))
                .addAction(action(context.getString(R.string.snooze_for, item.snoozeMinutes), ringAction(RingService.ACTION_SNOOZE, item.alarmId)))
                .addAction(action(text(R.string.dismiss), ringAction(RingService.ACTION_DISMISS, item.alarmId)))
            is RingItem.TimerRing -> builder
                .setContentTitle(timerTitle(item.name))
                .setContentText(text(if (item.finished) R.string.ring_timer_finished else R.string.ring_step_finished))
                .addAction(action(text(R.string.stop), ringAction(RingService.ACTION_TIMER_STOP, item.runId)))
                .addAction(action(text(R.string.add_minute), ringAction(RingService.ACTION_TIMER_ADD_MINUTE, item.runId)))
        }
        return builder.build()
    }

    /** Уведомление службы, пока она разбирает, звонить ли. */
    fun ringPlaceholder(): Notification = Notification.Builder(context, CHANNEL_RING)
        .setSmallIcon(R.drawable.ic_notification)
        .setCategory(Notification.CATEGORY_ALARM)
        .setContentTitle(text(R.string.app_name))
        .build()

    /** Служба сигнала не запустилась: хотя бы сказать об этом. */
    fun missedStart() = show(
        ID_RING,
        Notification.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.signal_blocked_title))
            .setContentText(text(R.string.signal_blocked_text))
            .setContentIntent(openApp(Section.ALARMS))
            .setAutoCancel(true)
            .build(),
    )

    // Будильники

    fun snoozed(alarm: Alarm, until: Long) = show(
        ID_ALARM,
        Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (alarm.label.isBlank()) text(R.string.snoozed_title) else alarm.label)
            .setContentText(context.getString(R.string.snoozed_until, TimeText.time(context, until)))
            .setOngoing(true)
            .setContentIntent(openApp(Section.ALARMS))
            .addAction(
                action(
                    text(R.string.dismiss),
                    broadcast(ActionReceiver.ACTION_DISMISS_SNOOZED, alarm.id),
                ),
            )
            .build(),
        tag = "snoozed:${alarm.id}",
    )

    fun cancelSnoozed(alarmId: String) = manager.cancel("snoozed:$alarmId", ID_ALARM)

    fun missedAlarm(alarm: Alarm, scheduledAt: Long, autoStopped: Boolean) = show(
        ID_ALARM,
        Notification.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (alarm.label.isBlank()) text(R.string.missed_title) else text(R.string.missed_title) + " · " + alarm.label)
            .setContentText(
                context.getString(
                    if (autoStopped) R.string.missed_auto_stop else R.string.missed_phone_off,
                    TimeText.time(context, scheduledAt),
                ),
            )
            .setWhen(scheduledAt)
            .setShowWhen(true)
            .setContentIntent(openApp(Section.ALARMS))
            .setAutoCancel(true)
            .build(),
        tag = "missed:${alarm.id}",
    )

    // Таймеры

    fun timer(run: TimerRun, now: Moment) {
        val remaining = run.remainingAt(now)
        val running = run.state == TimerState.RUNNING
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentTitle(timerTitle(run.name))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(Section.TIMERS))
            .setWhen(System.currentTimeMillis() + remaining)
            .setShowWhen(running)
            .setUsesChronometer(running)
            .setChronometerCountDown(true)
        if (run.program.isChain) {
            builder.setSubText(
                context.getString(R.string.step_of, run.stepIndex + 1, run.program.steps.size) +
                    if (run.program.cycles > 1) " · " + context.getString(R.string.cycle_of, run.cycleIndex + 1, run.program.cycles) else "",
            )
        }
        if (running) {
            builder.addAction(action(text(R.string.pause), broadcast(ActionReceiver.ACTION_TIMER_PAUSE, run.id)))
        } else {
            builder.setContentText(text(R.string.paused) + " · " + TimeText.countdown(remaining))
            builder.addAction(action(text(R.string.resume), broadcast(ActionReceiver.ACTION_TIMER_RESUME, run.id)))
        }
        builder.addAction(action(text(R.string.stop), broadcast(ActionReceiver.ACTION_TIMER_DELETE, run.id)))
        show(ID_TIMER, builder.build(), tag = "timer:${run.id}")
    }

    fun cancelTimer(runId: String) = manager.cancel("timer:$runId", ID_TIMER)

    /** Таймер закончился, но сигнала не было: телефон был выключен или сигнал стих сам. */
    fun timerFinished(run: TimerRun) = show(
        ID_TIMER,
        Notification.Builder(context, CHANNEL_MISSED)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(timerTitle(run.name))
            .setContentText(text(R.string.ring_timer_finished))
            .setContentIntent(openApp(Section.TIMERS))
            .setAutoCancel(true)
            .build(),
        tag = "timer-finished:${run.id}",
    )

    // Секундомер

    fun stopwatch(stopwatch: Stopwatch) {
        if (stopwatch.state == StopwatchState.RESET) {
            manager.cancel(ID_STOPWATCH)
            return
        }
        val elapsed = stopwatch.elapsedAt(Clocks.moment())
        val running = stopwatch.state == StopwatchState.RUNNING
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_STOPWATCH)
            .setContentTitle(text(R.string.section_stopwatch))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(Section.STOPWATCH))
            .setWhen(System.currentTimeMillis() - elapsed)
            .setShowWhen(running)
            .setUsesChronometer(running)
        if (running) {
            builder.addAction(action(text(R.string.pause), broadcast(ActionReceiver.ACTION_STOPWATCH_PAUSE, "")))
            builder.addAction(action(text(R.string.lap), broadcast(ActionReceiver.ACTION_STOPWATCH_LAP, "")))
        } else {
            builder.setContentText(text(R.string.paused) + " · " + TimeText.stopwatch(elapsed))
            builder.addAction(action(text(R.string.resume), broadcast(ActionReceiver.ACTION_STOPWATCH_RESUME, "")))
            builder.addAction(action(text(R.string.reset), broadcast(ActionReceiver.ACTION_STOPWATCH_RESET, "")))
        }
        show(ID_STOPWATCH, builder.build())
    }

    private fun timerTitle(name: String): String = name.ifBlank { text(R.string.timer_default_name) }

    private fun alarmTitle(label: String): String = label.ifBlank { text(R.string.ring_alarm_title) }

    private fun text(id: Int): String = context.getString(id)

    private fun action(title: String, intent: PendingIntent): Notification.Action =
        Notification.Action.Builder(null as Icon?, title, intent).build()

    private fun ringAction(action: String, id: String): PendingIntent = PendingIntent.getService(
        context,
        action.hashCode() xor id.hashCode(),
        RingService.intent(context, action, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun broadcast(action: String, id: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        action.hashCode() xor id.hashCode(),
        Intent(context, ActionReceiver::class.java).setAction(action).putExtra(ActionReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openApp(section: Section): PendingIntent = PendingIntent.getActivity(
        context,
        section.ordinal,
        MainActivity.intent(context, section),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ID_RING = 1
        private const val ID_ALARM = 2
        private const val ID_TIMER = 3
        private const val ID_STOPWATCH = 4
        private const val CHANNEL_RING = "ring"
        private const val CHANNEL_MISSED = "missed"
        private const val CHANNEL_ONGOING = "ongoing"
    }
}
