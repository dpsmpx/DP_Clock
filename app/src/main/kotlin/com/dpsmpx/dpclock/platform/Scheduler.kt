package com.dpsmpx.dpclock.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import com.dpsmpx.dpclock.MainActivity

/**
 * Будильники Android (D-008): будильник — `setAlarmClock()`, конец шага
 * таймера — точный `setExactAndAllowWhileIdle()` по монотонным часам.
 */
class Scheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    /** Точные будильники разрешены. С USE_EXACT_ALARM — всегда; на Android 12–12L решает человек. */
    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    fun setAlarm(alarmId: String, at: Long) {
        val operation = alarmIntent(alarmId, at)
        if (canScheduleExact()) {
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), operation)
        } else {
            // Без разрешения — лучшее из доступного; плашка надёжности просит его выдать.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    fun cancelAlarm(alarmId: String) {
        alarmManager.cancel(alarmIntent(alarmId, 0L))
        cancelGentle(alarmId)
        cancelWarning(alarmId)
    }

    /** Предупреждение (D-025, п. 5) в [at] о срабатывании [alarmAt]; одно на будильник — следующее ставится, когда придёт это. */
    fun setWarning(alarmId: String, at: Long, alarmAt: Long) = setExact(at, warningIntent(alarmId, alarmAt))

    fun cancelWarning(alarmId: String) = alarmManager.cancel(warningIntent(alarmId, 0L))

    private fun warningIntent(alarmId: String, alarmAt: Long): PendingIntent = broadcast(
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_WARNING)
            .setData("dpclock://warning/$alarmId".toUri())
            .putExtra(AlarmReceiver.EXTRA_ID, alarmId)
            .putExtra(AlarmReceiver.EXTRA_ALARM_AT, alarmAt),
    )

    /** Бой курантов в [at] (D-025, п. 4); один на приложение. Точный: бой в начале часа, а не когда-нибудь. */
    fun setChime(at: Long) = setExact(at, chimeIntent(at))

    fun cancelChime() = alarmManager.cancel(chimeIntent(0L))

    private fun chimeIntent(at: Long): PendingIntent = broadcast(
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_CHIME)
            .setData("dpclock://chime".toUri())
            .putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, at),
    )

    /** Мягкое пробуждение (D-017, п. 3): в [at] — свет и тихий звук до будильника в [alarmAt]. */
    fun setGentle(alarmId: String, at: Long, alarmAt: Long) = setExact(at, gentleIntent(alarmId, alarmAt))

    fun cancelGentle(alarmId: String) = alarmManager.cancel(gentleIntent(alarmId, 0L))

    /** Напоминание ложиться (D-017, п. 2); одно на приложение. */
    fun setBedtime(at: Long, bedAt: Long, alarmAt: Long?) = setExact(
        at,
        broadcast(
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_BEDTIME)
                .setData("dpclock://bedtime".toUri())
                .putExtra(AlarmReceiver.EXTRA_BED_AT, bedAt)
                .putExtra(AlarmReceiver.EXTRA_ALARM_AT, alarmAt ?: 0L),
        ),
    )

    fun cancelBedtime() = alarmManager.cancel(
        broadcast(Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_BEDTIME).setData("dpclock://bedtime".toUri())),
    )

    /** Напоминание после будильника [alarmId] (D-017, п. 5). */
    fun setReminder(alarmId: String, at: Long, text: String) = setExact(
        at,
        broadcast(
            Intent(context, AlarmReceiver::class.java)
                .setAction(AlarmReceiver.ACTION_REMINDER)
                .setData("dpclock://reminder/$alarmId".toUri())
                .putExtra(AlarmReceiver.EXTRA_ID, alarmId)
                .putExtra(AlarmReceiver.EXTRA_TEXT, text),
        ),
    )

    /**
     * Перечитать календарь телефона в [at] (D-023, п. 4): ближайшее событие началось
     * или наступила полночь. Не будит телефон — виджет обновится, когда экран включат.
     */
    fun setCalendarRefresh(at: Long) = alarmManager.set(AlarmManager.RTC, at, calendarIntent())

    fun cancelCalendarRefresh() = alarmManager.cancel(calendarIntent())

    private fun calendarIntent(): PendingIntent = broadcast(
        Intent(context, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_CALENDAR).setData("dpclock://calendar".toUri()),
    )

    private fun setExact(at: Long, operation: PendingIntent) {
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
        }
    }

    private fun gentleIntent(alarmId: String, alarmAt: Long): PendingIntent = broadcast(
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_GENTLE)
            .setData("dpclock://gentle/$alarmId".toUri())
            .putExtra(AlarmReceiver.EXTRA_ID, alarmId)
            .putExtra(AlarmReceiver.EXTRA_ALARM_AT, alarmAt),
    )

    private fun broadcast(intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun setTimer(runId: String, atElapsed: Long, fullSignal: Boolean) {
        val operation = timerIntent(runId, fullSignal)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, operation)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsed, operation)
        }
    }

    fun cancelTimer(runId: String) = alarmManager.cancel(timerIntent(runId, false))

    // Адрес данных делает интент своим у каждого будильника и таймера:
    // extras в сравнении PendingIntent не участвуют, поэтому отмена находит его по адресу.
    private fun alarmIntent(alarmId: String, at: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_ALARM)
            .setData("dpclock://alarm/$alarmId".toUri())
            .putExtra(AlarmReceiver.EXTRA_ID, alarmId)
            .putExtra(AlarmReceiver.EXTRA_SCHEDULED_AT, at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun timerIntent(runId: String, fullSignal: Boolean): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, AlarmReceiver::class.java)
            .setAction(AlarmReceiver.ACTION_TIMER)
            .setData("dpclock://timer/$runId".toUri())
            .putExtra(AlarmReceiver.EXTRA_ID, runId)
            .putExtra(AlarmReceiver.EXTRA_FULL, fullSignal),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
