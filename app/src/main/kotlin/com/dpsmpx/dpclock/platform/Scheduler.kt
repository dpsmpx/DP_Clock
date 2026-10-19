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

    fun cancelAlarm(alarmId: String) = alarmManager.cancel(alarmIntent(alarmId, 0L))

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
