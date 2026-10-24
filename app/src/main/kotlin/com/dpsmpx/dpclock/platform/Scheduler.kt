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
    }

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
