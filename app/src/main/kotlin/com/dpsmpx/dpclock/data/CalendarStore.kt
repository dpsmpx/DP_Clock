package com.dpsmpx.dpclock.data

import android.content.Context
import androidx.core.content.edit
import com.dpsmpx.dpclock.model.CalendarSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/**
 * Календарь телефона (D-023, п. 6): выбор календарей, кэш дат-исключений и
 * отменённые будильники перед событиями — в хранилище устройства, не в базе.
 * Номера календарей — этого телефона; даты нужны и до первой разблокировки,
 * когда календарь телефона ещё недоступен. В системную копию и перенос файлом
 * это не входит: копия берёт только базу.
 */
class CalendarStore(context: Context) {
    private val prefs = context.deviceStorage().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val settingsFlow = MutableStateFlow(readSettings())
    private val datesFlow = MutableStateFlow(readDays(EXCEPTION_DATES))

    val settings: StateFlow<CalendarSettings> = settingsFlow

    /** Дни событий календарей-исключений — последнее прочитанное. */
    val exceptionDates: StateFlow<Set<LocalDate>> = datesFlow

    @Synchronized
    fun update(change: (CalendarSettings) -> CalendarSettings): CalendarSettings {
        val new = change(settingsFlow.value)
        prefs.edit {
            putString(EXCEPTION_CALENDARS, new.exceptionCalendars.joinToString(","))
            putString(EVENT_CALENDARS, new.eventCalendars.joinToString(","))
            putInt(EARLY_LEAD, new.earlyLeadMinutes)
            putBoolean(SHOW_NEXT, new.showNext)
            val task = new.taskCalendar
            if (task != null) putLong(TASK_CALENDAR, task) else remove(TASK_CALENDAR)
        }
        settingsFlow.value = new
        return new
    }

    /** Новые даты исключений; true — изменились, будильники пересчитать. */
    @Synchronized
    fun setExceptionDates(dates: Set<LocalDate>): Boolean {
        if (dates == datesFlow.value) return false
        writeDays(EXCEPTION_DATES, dates)
        datesFlow.value = dates
        return true
    }

    /** Даты, в которые будильник перед событием удалили или переделали в обычный: календарь его не возвращает. */
    @Synchronized
    fun cancelledDates(): Set<LocalDate> = readDays(CANCELLED)

    @Synchronized
    fun setCancelled(date: LocalDate, cancelled: Boolean) {
        val dates = readDays(CANCELLED)
        writeDays(CANCELLED, if (cancelled) dates + date else dates - date)
    }

    /** Прошедшие отмены больше ничего не значат. */
    @Synchronized
    fun pruneCancelled(today: LocalDate) {
        val dates = readDays(CANCELLED)
        val kept = dates.filterTo(mutableSetOf()) { !it.isBefore(today) }
        if (kept.size != dates.size) writeDays(CANCELLED, kept)
    }

    private fun readSettings() = CalendarSettings(
        exceptionCalendars = readIds(EXCEPTION_CALENDARS),
        eventCalendars = readIds(EVENT_CALENDARS),
        earlyLeadMinutes = prefs.getInt(EARLY_LEAD, 0).coerceAtLeast(0),
        showNext = prefs.getBoolean(SHOW_NEXT, true),
        taskCalendar = if (prefs.contains(TASK_CALENDAR)) prefs.getLong(TASK_CALENDAR, 0L) else null,
    )

    private fun readIds(key: String): Set<Long> =
        prefs.getString(key, null).orEmpty().split(',').mapNotNullTo(mutableSetOf()) { it.trim().toLongOrNull() }

    private fun readDays(key: String): Set<LocalDate> = readIds(key).mapTo(mutableSetOf(), LocalDate::ofEpochDay)

    private fun writeDays(key: String, dates: Set<LocalDate>) {
        prefs.edit { putString(key, dates.map { it.toEpochDay() }.sorted().joinToString(",")) }
    }

    private companion object {
        const val PREFS = "calendar"
        const val EXCEPTION_CALENDARS = "exceptionCalendars"
        const val EVENT_CALENDARS = "eventCalendars"
        const val EARLY_LEAD = "earlyLead"
        const val SHOW_NEXT = "showNext"
        const val TASK_CALENDAR = "taskCalendar"
        const val EXCEPTION_DATES = "exceptionDates"
        const val CANCELLED = "cancelled"
    }
}
