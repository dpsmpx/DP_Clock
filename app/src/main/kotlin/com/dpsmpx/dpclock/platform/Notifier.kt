package com.dpsmpx.dpclock.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.dpsmpx.dpclock.MainActivity
import com.dpsmpx.dpclock.NightClockActivity
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.RingActivity
import com.dpsmpx.dpclock.Section
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.DayTask
import com.dpsmpx.dpclock.model.Moment
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskStats
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.ui.elapsedText
import com.dpsmpx.dpclock.ui.leadText

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
                NotificationChannel(CHANNEL_SLEEP, text(R.string.channel_sleep), NotificationManager.IMPORTANCE_DEFAULT),
                // Предупреждения до сигнала (D-025, п. 5): звук у канала, поэтому каналов два.
                NotificationChannel(CHANNEL_WARNING, text(R.string.channel_warning), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_WARNING_SOUND, text(R.string.channel_warning_sound), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_SYNC, text(R.string.channel_sync), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_TASKS, text(R.string.channel_tasks), NotificationManager.IMPORTANCE_LOW),
                NotificationChannel(CHANNEL_TASK_REMINDERS, text(R.string.channel_task_reminders), NotificationManager.IMPORTANCE_HIGH),
                // Постоянные задачи (D-033; выбор автора — мягко, без звука).
                NotificationChannel(CHANNEL_TASK_NUDGES, text(R.string.channel_task_nudges), NotificationManager.IMPORTANCE_LOW),
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
            is RingItem.AlarmRing -> {
                builder
                    .setContentTitle(alarmTitle(item.label))
                    .setContentText(TimeText.time(context, item.hour, item.minute))
                if (item.wakeCheck.snoozeAllowed) {
                    builder.addAction(action(context.getString(R.string.snooze_for, item.snoozeMinutes), ringAction(RingService.ACTION_SNOOZE, item.alarmId)))
                }
                // С проверкой пробуждения выключить можно только на экране сигнала (D-015).
                if (item.wakeCheck.required) {
                    builder.addAction(action(text(R.string.wake_open_check), screen))
                } else {
                    builder.addAction(action(text(R.string.dismiss), ringAction(RingService.ACTION_DISMISS, item.alarmId)))
                }
            }
            is RingItem.GentleRing -> {
                builder
                    .setContentTitle(text(R.string.gentle_title))
                    .setContentText(context.getString(R.string.gentle_text, alarmTitle(item.label), TimeText.time(context, item.alarmAt)))
                    .addAction(action(text(R.string.gentle_close), ringAction(RingService.ACTION_GENTLE_CLOSE, item.alarmId)))
                if (item.canDismiss) {
                    builder.addAction(action(text(R.string.gentle_awake), ringAction(RingService.ACTION_GENTLE_AWAKE, item.alarmId)))
                }
            }
            is RingItem.RecheckRing -> builder
                .setContentTitle(text(R.string.recheck_title))
                .setContentText(alarmTitle(item.label) + " · " + TimeText.time(context, item.hour, item.minute))
                .addAction(action(text(R.string.recheck_awake), ringAction(RingService.ACTION_RECHECK_OK, item.alarmId)))
            is RingItem.TimerRing -> builder
                .setContentTitle(timerTitle(item.name))
                .setContentText(text(if (item.finished) R.string.ring_timer_finished else R.string.ring_step_finished))
                .addAction(action(text(R.string.stop), ringAction(RingService.ACTION_TIMER_STOP, item.runId)))
                .addAction(
                    // Таймер ждёт «Дальше» (D-025, п. 1): одна кнопка глушит сигнал и начинает следующий шаг.
                    if (item.waiting) action(text(R.string.timer_next), ringAction(RingService.ACTION_TIMER_NEXT, item.runId))
                    else action(text(R.string.add_minute), ringAction(RingService.ACTION_TIMER_ADD_MINUTE, item.runId)),
                )
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
            .apply {
                // С проверкой пробуждения отложенный не выключается из уведомления.
                if (!alarm.wakeCheck.required) {
                    addAction(action(text(R.string.dismiss), broadcast(ActionReceiver.ACTION_DISMISS_SNOOZED, alarm.id)))
                }
            }
            .build(),
        tag = "snoozed:${alarm.id}",
    )

    fun cancelSnoozed(alarmId: String) = manager.cancel("snoozed:$alarmId", ID_ALARM)

    /**
     * Предупреждение до сигнала (D-025, п. 5): полная дата и время, обратный отсчёт;
     * «Пропустить этот раз», а за два часа и меньше — «Выключить сейчас» (если пробуждение
     * не нужно подтверждать). Исчезает само к сроку будильника.
     */
    fun warning(alarm: Alarm, alarmAt: Long, minutes: Int, sound: Boolean) {
        val now = System.currentTimeMillis()
        val builder = Notification.Builder(context, if (sound) CHANNEL_WARNING_SOUND else CHANNEL_WARNING)
            .setSmallIcon(R.drawable.ic_notification)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentTitle(context.getString(R.string.warning_title, alarmTitle(alarm.label), leadText(context, minutes)))
            .setContentText(TimeText.fullDateTime(context, alarmAt))
            .setContentIntent(openApp(Section.ALARMS))
            .setWhen(alarmAt)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((alarmAt - now).coerceAtLeast(1_000L))
            .addAction(action(text(R.string.warning_skip), broadcast(ActionReceiver.ACTION_WARNING_SKIP, alarm.id)))
        if (alarmAt - now <= DISMISS_NOW_MILLIS && !alarm.wakeCheck.required) {
            builder.addAction(action(text(R.string.warning_dismiss), broadcast(ActionReceiver.ACTION_WARNING_DISMISS, alarm.id)))
        }
        show(ID_WARNING, builder.build(), tag = "warning:${alarm.id}")
    }

    fun cancelWarning(alarmId: String) = manager.cancel("warning:$alarmId", ID_WARNING)


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

    // Синхронизация (D-030)

    /** Одно и то же поправили на двух телефонах — спросить, какую версию оставить. */
    fun syncConflicts(count: Int) = show(
        ID_SYNC,
        Notification.Builder(context, CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.sync_conflicts_title))
            .setContentText(context.resources.getQuantityString(R.plurals.sync_conflicts_count, count, count))
            .setContentIntent(openApp(Section.ALARMS))
            .setAutoCancel(true)
            .build(),
    )

    fun cancelSyncConflicts() = manager.cancel(ID_SYNC)

    // Задачи (D-031, D-032)

    /** Напоминание своей задачи: название, срок, «Сделано». */
    fun taskReminder(task: Task) {
        val due = listOfNotNull(
            task.date?.let { TimeText.date(context, it) },
            task.time?.let { TimeText.time(context, it.hour, it.minute) },
        ).joinToString(", ")
        show(
            ID_TASK,
            Notification.Builder(context, CHANNEL_TASK_REMINDERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(task.title)
                .setContentText(listOf(due, task.note.lineSequence().firstOrNull().orEmpty()).filter { it.isNotBlank() }.joinToString(" · "))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setContentIntent(openApp(Section.CLOCK))
                .addAction(action(text(R.string.tasks_done), broadcast(ActionReceiver.ACTION_TASK_DONE, task.id)))
                .setAutoCancel(true)
                .build(),
            tag = "task:${task.id}",
        )
    }

    fun cancelTaskReminder(taskId: String) = manager.cancel("task:$taskId", ID_TASK)

    /** Тихое напоминание постоянной задачи: «прошло 5 д 3 ч · обычно раз в 3 д», «Сделано». */
    fun taskNudge(task: Task, stats: TaskStats) {
        val last = stats.last
        val text = listOfNotNull(
            if (last == null) text(R.string.tasks_never_done) else context.getString(R.string.tasks_ago, elapsedText(context, System.currentTimeMillis() - last)),
            stats.average?.let { context.getString(R.string.tasks_usually, elapsedText(context, it)) },
        ).joinToString(" · ")
        show(
            ID_TASK,
            Notification.Builder(context, CHANNEL_TASK_NUDGES)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(task.title)
                .setContentText(text)
                .setContentIntent(openApp(Section.TASKS))
                .addAction(action(text(R.string.tasks_done), broadcast(ActionReceiver.ACTION_TASK_DONE, task.id)))
                .setAutoCancel(true)
                .build(),
            tag = "task:${task.id}",
        )
    }

    /**
     * «Задачи на сегодня» после утреннего будильника: первые задачи строками. На экране
     * блокировки — только число, без названий.
     */
    fun tasks(tasks: List<DayTask>) {
        val count = context.resources.getQuantityString(R.plurals.tasks_count, tasks.size, tasks.size)
        val style = Notification.InboxStyle()
        tasks.take(TASK_LINES).forEach { style.addLine(it.title) }
        if (tasks.size > TASK_LINES) style.setSummaryText(context.getString(R.string.tasks_more, tasks.size - TASK_LINES))
        val public = Notification.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.tasks_today))
            .setContentText(count)
            .build()
        show(
            ID_TASKS,
            Notification.Builder(context, CHANNEL_TASKS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text(R.string.tasks_today))
                .setContentText(count)
                .setStyle(style)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(openApp(Section.CLOCK))
                .setAutoCancel(true)
                .build(),
        )
    }

    // Сон и после будильника (D-017)

    /** «Пора ложиться»: время ложиться, будильник, если посчитано от него, и сколько выйдет сна. */
    fun bedtime(bedAt: Long, alarmAt: Long?, sleepMinutes: Int) = show(
        ID_SLEEP,
        Notification.Builder(context, CHANNEL_SLEEP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.bedtime_title))
            .setContentText(
                if (alarmAt != null) {
                    context.getString(R.string.bedtime_text_alarm, TimeText.time(context, bedAt), TimeText.time(context, alarmAt), sleepMinutes / 60, sleepMinutes % 60)
                } else {
                    context.getString(R.string.bedtime_text, TimeText.time(context, bedAt))
                },
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    ID_SLEEP,
                    Intent(context, NightClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setAutoCancel(true)
            .build(),
    )

    /** Напоминание после будильника — текст человека. */
    fun reminder(alarmId: String, message: String) = show(
        ID_SLEEP,
        Notification.Builder(context, CHANNEL_SLEEP)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text(R.string.reminder_title))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(openApp(Section.ALARMS))
            .setAutoCancel(true)
            .build(),
        tag = "reminder:$alarmId",
    )

    /** Будильник выключен не на экране сигнала: приложение после него — по нажатию на уведомление. */
    fun openAfterAlarm(packageName: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
        show(
            ID_SLEEP,
            Notification.Builder(context, CHANNEL_SLEEP)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.after_open_app, label))
                .setContentIntent(PendingIntent.getActivity(context, ID_SLEEP, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build(),
            tag = "open:$packageName",
        )
    }

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
        if (run.program.isChain) builder.setSubText(stepText(run))
        when {
            running -> builder.addAction(action(text(R.string.pause), broadcast(ActionReceiver.ACTION_TIMER_PAUSE, run.id)))
            // Ждёт «Дальше» (D-025, п. 1): что будет следующим и сколько.
            run.isWaiting -> {
                builder.setContentText(context.getString(R.string.timer_waiting, run.step.name.ifBlank { stepText(run) }, TimeText.countdown(remaining)))
                builder.addAction(action(text(R.string.timer_next), broadcast(ActionReceiver.ACTION_TIMER_NEXT, run.id)))
            }
            else -> {
                builder.setContentText(text(R.string.paused) + " · " + TimeText.countdown(remaining))
                builder.addAction(action(text(R.string.resume), broadcast(ActionReceiver.ACTION_TIMER_RESUME, run.id)))
            }
        }
        if (running && run.step.name.isNotBlank()) builder.setContentText(run.step.name)
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
            .setContentIntent(openStopwatch())
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

    /** «Шаг 2 из 8 · круг 1 из 3»; у бесконечного — «круг 5». */
    private fun stepText(run: TimerRun): String {
        val step = context.getString(R.string.step_of, run.stepIndex + 1, run.program.steps.size)
        val cycle = when {
            run.program.forever -> " · " + context.getString(R.string.cycle_number, run.cycleIndex + 1)
            run.program.cycles > 1 -> " · " + context.getString(R.string.cycle_of, run.cycleIndex + 1, run.program.cycles)
            else -> ""
        }
        return if (run.program.steps.size > 1) step + cycle else cycle.removePrefix(" · ")
    }

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

    /** Секундомер — вкладка раздела «Таймеры» (D-033). */
    private fun openStopwatch(): PendingIntent = PendingIntent.getActivity(
        context,
        STOPWATCH_REQUEST,
        MainActivity.intent(context, Section.TIMERS, stopwatch = true),
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
        private const val ID_SLEEP = 5
        private const val ID_WARNING = 6
        private const val ID_SYNC = 7
        private const val CHANNEL_SYNC = "sync"
        private const val ID_TASKS = 8
        private const val CHANNEL_TASKS = "tasks"
        private const val ID_TASK = 9
        private const val CHANNEL_TASK_REMINDERS = "task-reminders"
        private const val CHANNEL_TASK_NUDGES = "task-nudges"
        private const val STOPWATCH_REQUEST = 100
        private const val TASK_LINES = 5
        private const val CHANNEL_WARNING = "warning"
        private const val CHANNEL_WARNING_SOUND = "warning-sound"

        /** «Выключить сейчас» — только когда до будильника не больше двух часов. */
        private const val DISMISS_NOW_MILLIS = 2 * 3_600_000L
        private const val CHANNEL_SLEEP = "sleep"
        private const val CHANNEL_RING = "ring"
        private const val CHANNEL_MISSED = "missed"
        private const val CHANNEL_ONGOING = "ongoing"
    }
}
