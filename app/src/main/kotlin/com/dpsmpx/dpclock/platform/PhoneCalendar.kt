package com.dpsmpx.dpclock.platform

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserManager
import android.provider.CalendarContract
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.CalendarInstance
import com.dpsmpx.dpclock.model.CalendarRules
import com.dpsmpx.dpclock.model.Task
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Календарь телефона в списке выбора (D-023, п. 1). */
data class PhoneCalendarInfo(val id: Long, val name: String, val account: String, val color: Int)

/**
 * Календарь телефона (D-023): только чтение через `CalendarContract`, без
 * разрешения — пусто. Название события живёт только в памяти: на экран и в
 * виджет, в базу приложения оно не пишется.
 */
object PhoneCalendar {
    fun granted(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun calendars(context: Context): List<PhoneCalendarInfo> {
        if (!granted(context)) return emptyList()
        return runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR),
                null,
                null,
                "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(PhoneCalendarInfo(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getInt(3)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /**
     * Наступления событий календарей [calendars] между [from] и [to], мс UTC;
     * отменённые и отклонённые — без них. null — прочитать не удалось: прежнее
     * прочитанное остаётся в силе.
     */
    fun instances(context: Context, calendars: Set<Long>, from: Long, to: Long): List<CalendarInstance>? {
        if (calendars.isEmpty() || !granted(context)) return emptyList()
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        // Номера — из своих настроек, числа: подставлять в запрос безопасно.
        val selection = "${Instances.CALENDAR_ID} IN (${calendars.joinToString(",")})"
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    Instances.BEGIN,
                    Instances.END,
                    Instances.ALL_DAY,
                    Instances.TITLE,
                    Instances.STATUS,
                    Instances.SELF_ATTENDEE_STATUS,
                    Instances.CUSTOM_APP_PACKAGE,
                    Instances.DESCRIPTION,
                ),
                selection,
                null,
                Instances.BEGIN,
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        if (cursor.getInt(4) == Events.STATUS_CANCELED) continue
                        if (cursor.getInt(5) == Attendees.ATTENDEE_STATUS_DECLINED) continue
                        // Свои задачи в календаре (D-032) — не событие: ни исключения, ни подъёма перед ним.
                        if (TaskCalendar.isOwn(context, cursor.getString(6), cursor.getString(7))) continue
                        add(CalendarInstance(cursor.getLong(0), cursor.getLong(1), cursor.getInt(2) == 1, cursor.getString(3).orEmpty()))
                    }
                }
            }
        }.getOrNull()
    }

    /** События календарей событий в дату [date] — для редактора и экрана. */
    fun eventsOn(context: Context, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<CalendarInstance> {
        val calendars = DpClockApp.graph(context).calendar.settings.value.eventCalendars
        // С запасом в сутки: события на весь день хранятся в UTC.
        val from = date.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = date.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        return CalendarRules.dayEvents(instances(context, calendars, from, to).orEmpty(), date, zone)
    }

    /** Ближайшее событие сегодня; выключено в настройках или нет доступа — null. */
    fun nextEvent(context: Context, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): CalendarInstance? {
        val settings = DpClockApp.graph(context).calendar.settings.value
        if (!settings.showNext) return null
        val today = now.atZone(zone).toLocalDate()
        val events = instances(
            context,
            settings.eventCalendars,
            now.toEpochMilli(),
            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        ).orEmpty()
        return CalendarRules.nextEvent(events, now, zone)
    }
}

/** Что вышло с событием задачи в календаре. */
sealed interface CalendarWrite {
    data class Written(val eventId: Long) : CalendarWrite

    /** Событие удалили в календаре — флажок снимается, нового события нет. */
    data object Gone : CalendarWrite

    /** Нет разрешения, календаря или запись не удалась — попробовать позже. */
    data object Failed : CalendarWrite
}

/**
 * Свои задачи в календаре телефона (D-032; выбор автора — флажок у задачи). Задача
 * без времени — событие на весь день, со временем — полчаса со срока. Сделанная —
 * «✓» в названии. Событие помечено пакетом DP Clock и строкой [MARKER] в описании:
 * так его узнаёт и этот телефон, и другой, куда событие пришло через облако
 * календаря, — и не считает ни исключением, ни ранним событием.
 */
object TaskCalendar {
    const val MARKER = "[DP Clock]"
    private const val TIMED_MILLIS = 30 * 60_000L
    private const val DONE = "✓ "

    fun granted(context: Context): Boolean = PhoneCalendar.granted(context) &&
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun isOwn(context: Context, packageName: String?, description: String?): Boolean =
        packageName == context.packageName || description?.trimEnd()?.endsWith(MARKER) == true

    /** Календари, в которые можно писать. */
    fun writable(context: Context): List<PhoneCalendarInfo> {
        if (!granted(context)) return emptyList()
        return runCatching {
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_COLOR),
                "${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR} AND ${Calendars.VISIBLE} = 1",
                null,
                "${Calendars.ACCOUNT_NAME}, ${Calendars.CALENDAR_DISPLAY_NAME}",
            )?.use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(PhoneCalendarInfo(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getInt(3)))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /** Записать задачу [task] со сроком: новое событие или правка прежнего [Task.eventId]. */
    fun write(context: Context, calendarId: Long, task: Task, zone: ZoneId = ZoneId.systemDefault()): CalendarWrite {
        val date = task.date ?: return CalendarWrite.Failed
        if (!granted(context)) return CalendarWrite.Failed
        val values = ContentValues().apply {
            put(Events.TITLE, (if (task.done) DONE else "") + task.title)
            put(Events.DESCRIPTION, if (task.note.isBlank()) MARKER else task.note.trimEnd() + "\n\n" + MARKER)
            put(Events.CUSTOM_APP_PACKAGE, context.packageName)
            val time = task.time
            if (time == null) {
                // Событие на весь день — полночь UTC по правилам календаря Android.
                val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(Events.ALL_DAY, 1)
                put(Events.DTSTART, start)
                put(Events.DTEND, start + 86_400_000L)
                put(Events.EVENT_TIMEZONE, "UTC")
            } else {
                val start = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
                put(Events.ALL_DAY, 0)
                put(Events.DTSTART, start)
                put(Events.DTEND, start + TIMED_MILLIS)
                put(Events.EVENT_TIMEZONE, zone.id)
            }
        }
        return try {
            val eventId = task.eventId
            if (eventId != null) {
                val rows = context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null)
                if (rows > 0) CalendarWrite.Written(eventId) else CalendarWrite.Gone
            } else {
                values.put(Events.CALENDAR_ID, calendarId)
                val uri = context.contentResolver.insert(Events.CONTENT_URI, values)
                uri?.let { CalendarWrite.Written(ContentUris.parseId(it)) } ?: CalendarWrite.Failed
            }
        } catch (e: SecurityException) {
            CalendarWrite.Failed
        } catch (e: IllegalArgumentException) {
            CalendarWrite.Failed
        }
    }

    fun delete(context: Context, eventId: Long) {
        if (!granted(context)) return
        try {
            context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
        } catch (e: SecurityException) {
            // Разрешение отняли — событие останется, его удалят в календаре.
        } catch (e: IllegalArgumentException) {
            // Календаря больше нет.
        }
    }
}

/**
 * Перечитать календарь телефона (D-023, п. 6): даты исключений, будильники
 * перед ранними событиями. Потом — обновление виджета, когда начнётся
 * ближайшее событие, и в полночь. До первой разблокировки календарь
 * недоступен, а не пуст: тогда остаётся прочитанное раньше.
 */
object CalendarSync {
    suspend fun run(context: Context) {
        if (context.getSystemService(UserManager::class.java)?.isUserUnlocked == false) return
        val graph = DpClockApp.graph(context)
        val settings = graph.calendar.settings.value
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        fun startOf(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val exceptions = PhoneCalendar.instances(
            context,
            settings.exceptionCalendars,
            startOf(today.minusDays(1)),
            startOf(today.plusDays(CalendarRules.EXCEPTION_DAYS)),
        ) ?: return
        val events = PhoneCalendar.instances(context, settings.eventCalendars, startOf(today), startOf(today.plusDays(CalendarRules.WAKE_DAYS))) ?: return
        graph.repository.syncCalendar(CalendarRules.exceptionDates(exceptions, zone), events) { wake ->
            context.getString(R.string.calendar_alarm_label, TimeText.time(context, wake.eventAt))
        }
        val refreshAt = listOfNotNull(
            CalendarRules.nextEvent(events, now.plusSeconds(1), zone)?.begin?.plus(1_000L),
            startOf(today.plusDays(1)) + 1_000L,
        ).min()
        if (settings.isEnabled) graph.scheduler.setCalendarRefresh(refreshAt) else graph.scheduler.cancelCalendarRefresh()
    }

    /** Перечитать и следить за изменениями — после запуска, загрузки, смены настроек. */
    suspend fun refresh(context: Context) {
        run(context)
        CalendarJobs.schedule(context)
    }
}

/**
 * Задания Android (D-023, п. 6): календарь изменился — перечитать через
 * несколько секунд; раз в 12 часов — сдвинуть окно дат. Без выбранных
 * календарей или разрешения заданий нет.
 */
object CalendarJobs {
    private const val CHANGED = 7_001
    private const val PERIODIC = 7_002
    private const val PERIOD_MILLIS = 12 * 3_600_000L

    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java) ?: return
        val enabled = DpClockApp.graph(context).calendar.settings.value.isEnabled && PhoneCalendar.granted(context)
        if (!enabled) {
            jobs.cancel(CHANGED)
            jobs.cancel(PERIODIC)
            return
        }
        val service = ComponentName(context, CalendarJob::class.java)
        runCatching {
            // Задание по изменению содержимого не переживает перезагрузку и срабатывает один раз:
            // ставится заново после каждого прочтения и после загрузки.
            jobs.schedule(
                JobInfo.Builder(CHANGED, service)
                    .addTriggerContentUri(JobInfo.TriggerContentUri(CalendarContract.CONTENT_URI, JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS))
                    .setTriggerContentUpdateDelay(5_000L)
                    .setTriggerContentMaxDelay(60_000L)
                    .build(),
            )
            if (jobs.getPendingJob(PERIODIC) == null) {
                jobs.schedule(JobInfo.Builder(PERIODIC, service).setPeriodic(PERIOD_MILLIS).setPersisted(true).build())
            }
        }
    }
}

/** Задание Android: перечитать календарь и снова следить за ним. */
class CalendarJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val context = applicationContext
        DpClockApp.scope.launch {
            try {
                CalendarSync.run(context)
            } finally {
                jobFinished(params, false)
                CalendarJobs.schedule(context)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
