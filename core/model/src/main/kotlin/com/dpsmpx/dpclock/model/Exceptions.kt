package com.dpsmpx.dpclock.model

import java.time.LocalDate

/** Период исключения — отпуск, своя дата (D-014, п. 4); [start] и [end] входят. */
data class ExceptionPeriod(val id: String, val name: String, val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "период $start–$end" }
    }

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    companion object {
        const val MAX_NAME = 40
    }
}

/**
 * Общий список исключений приложения (D-014, п. 4): свои периоды и, если
 * включено, праздники и переносы по производственному календарю России.
 * [calendarDates] — дни событий выбранных календарей телефона (D-023, п. 2): их
 * приложение не хранит в базе и не переносит.
 */
data class Exceptions(
    val periods: List<ExceptionPeriod> = emptyList(),
    val holidays: Boolean = false,
    val calendarDates: Set<LocalDate> = emptySet(),
) {
    fun excludes(date: LocalDate): Boolean =
        (holidays && RuCalendar.isDayOff(date)) || date in calendarDates || periods.any { date in it }

    /** Период, в который попадает [date]; праздник — null. */
    fun periodOf(date: LocalDate): ExceptionPeriod? = periods.firstOrNull { date in it }

    companion object {
        val NONE = Exceptions()
    }
}
