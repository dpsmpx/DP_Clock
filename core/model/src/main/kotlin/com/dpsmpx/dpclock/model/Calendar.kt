package com.dpsmpx.dpclock.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Одно наступление события календаря телефона (D-023): [begin] и [end] — мс UTC.
 * У события на весь день — полночи UTC, как их хранит Android. Название — только
 * для экрана: в базу приложения оно не попадает.
 */
data class CalendarInstance(val begin: Long, val end: Long, val allDay: Boolean, val title: String = "")

/**
 * Календарь телефона (D-023, п. 2–5): какие календари дают исключения, какие —
 * события для раннего будильника, экрана и редактора. Номера календарей — этого
 * телефона: в перенос и системную копию настройки не входят.
 */
data class CalendarSettings(
    val exceptionCalendars: Set<Long> = emptySet(),
    val eventCalendars: Set<Long> = emptySet(),
    /** Будить за столько минут до раннего события; 0 — не будить. */
    val earlyLeadMinutes: Int = 0,
    /** Ближайшее событие в «Часах», ночных часах и виджете. */
    val showNext: Boolean = true,
    /** Календарь для своих задач с флажком «В календарь» (D-032, п. 6); null — не выбран. */
    val taskCalendar: Long? = null,
) {
    val isEnabled: Boolean get() = exceptionCalendars.isNotEmpty() || eventCalendars.isNotEmpty()

    companion object {
        val LEAD_CHOICES = listOf(0, 30, 45, 60, 90, 120)

        /** Раннее событие — начинается до полудня. */
        val EARLY_BEFORE: LocalTime = LocalTime.NOON
    }
}

/** Подъём перед ранним событием (D-023, п. 3): в дату [date] в [wakeAt]; событие — в [eventAt], мс UTC. */
data class CalendarWake(val date: LocalDate, val wakeAt: LocalTime, val eventAt: Long)

/** Правила календаря телефона (D-023). Чистые функции. */
object CalendarRules {
    private const val DAY_MILLIS = 86_400_000L

    /** Сколько дней вперёд ставятся будильники перед ранними событиями. */
    const val WAKE_DAYS = 3L

    /** На сколько дней вперёд читаются исключения. */
    const val EXCEPTION_DAYS = 400L

    /**
     * Даты исключений (D-023, п. 2): дни событий на весь день и события
     * длиннее суток — каждый их день. Короткие события дня не закрывают.
     */
    fun exceptionDates(instances: List<CalendarInstance>, zone: ZoneId): Set<LocalDate> = buildSet {
        for (event in instances) {
            val (first, last) = if (event.allDay) {
                // Конец события на весь день — следующая полночь UTC, она не входит.
                val start = LocalDate.ofEpochDay(Math.floorDiv(event.begin, DAY_MILLIS))
                val end = LocalDate.ofEpochDay(Math.floorDiv(event.end, DAY_MILLIS)).minusDays(1)
                start to if (end.isBefore(start)) start else end
            } else {
                if (event.end - event.begin < DAY_MILLIS) continue
                localDate(event.begin, zone) to localDate(event.end - 1, zone)
            }
            var date = first
            while (!date.isAfter(last)) {
                add(date)
                date = date.plusDays(1)
            }
        }
    }

    /**
     * Будильники перед ранними событиями (D-023, п. 3) на [WAKE_DAYS] дней с
     * сегодняшнего: первое событие дня начинается до полудня — подъём за
     * [leadMinutes] до него. Не нужен, если обычный будильник этого дня звонит
     * не позже, если подъём уже прошёл или пришёлся бы на вчера.
     */
    fun earlyWakes(
        events: List<CalendarInstance>,
        alarms: List<Alarm>,
        exceptions: Exceptions,
        leadMinutes: Int,
        now: Instant,
        zone: ZoneId,
    ): List<CalendarWake> {
        if (leadMinutes <= 0) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        val regular = alarms.filter { it.enabled && it.calendarEventAt == null }
        return (0 until WAKE_DAYS).mapNotNull { offset ->
            val date = today.plusDays(offset)
            val first = events.filter { !it.allDay && localDate(it.begin, zone) == date }.minByOrNull { it.begin } ?: return@mapNotNull null
            val start = Instant.ofEpochMilli(first.begin).atZone(zone)
            if (!start.toLocalTime().isBefore(CalendarSettings.EARLY_BEFORE)) return@mapNotNull null
            val wake = start.minusMinutes(leadMinutes.toLong()).truncatedTo(ChronoUnit.MINUTES)
            if (wake.toLocalDate() != date || !wake.toInstant().isAfter(now)) return@mapNotNull null
            // Обычный будильник этого дня звонит не позже — второй не нужен.
            val dayStart = date.atStartOfDay(zone).toInstant().minusMillis(1)
            val from = if (dayStart.isAfter(now)) dayStart else now
            val covered = regular.any { alarm ->
                val at = AlarmRules.nextTrigger(alarm, from, zone, exceptions) ?: return@any false
                at.atZone(zone).toLocalDate() == date && !at.isAfter(wake.toInstant())
            }
            if (covered) null else CalendarWake(date, wake.toLocalTime(), first.begin)
        }
    }

    /** Ближайшее событие сегодня (D-023, п. 4): начинается не раньше [now]; на весь день — не в счёт. */
    fun nextEvent(events: List<CalendarInstance>, now: Instant, zone: ZoneId): CalendarInstance? {
        val today = now.atZone(zone).toLocalDate()
        return events
            .filter { !it.allDay && it.begin >= now.toEpochMilli() && localDate(it.begin, zone) == today }
            .minByOrNull { it.begin }
    }

    /** События дня (D-023, п. 5): сначала на весь день, потом по времени начала. */
    fun dayEvents(events: List<CalendarInstance>, date: LocalDate, zone: ZoneId): List<CalendarInstance> {
        val allDay = events.filter { event ->
            event.allDay && date in exceptionDates(listOf(event), zone)
        }
        val timed = events.filter { !it.allDay && localDate(it.begin, zone) == date }.sortedBy { it.begin }
        return allDay + timed
    }

    private fun localDate(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
}
