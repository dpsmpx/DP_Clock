package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month

/**
 * Производственный календарь России (D-014, п. 3): нерабочие праздничные дни
 * по статье 112 Трудового кодекса и переносы выходных.
 *
 * - Праздник, выпавший на выходной, переносит выходной на следующий рабочий
 *   день — по закону, кроме январских праздников (ст. 112, ч. 2).
 * - Январские выходные и другие переносы — постановления Правительства на
 *   каждый год ([decrees]). Для года без постановления — только закон.
 *
 * Источники: ТК РФ, ст. 112; постановления Правительства РФ от 24.09.2025
 * № 1466 «О переносе выходных дней в 2026 году» и от 17.09.2026 № 1187
 * «О переносе выходных дней в 2027 году». Сверено с производственными
 * календарями КонсультантПлюс на 2026 и 2027 годы 24.10.2026. Официальные
 * документы не охраняются авторским правом (ГК РФ, ст. 1259, п. 6).
 */
object RuCalendar {
    /** Перенос выходного: [from] становится рабочим (если это не праздник), [to] — выходным. */
    data class Move(val from: LocalDate, val to: LocalDate)

    /** Переносы по постановлениям Правительства, по годам. Новый год — новая строка и тест. */
    val decrees: Map<Int, List<Move>> = mapOf(
        // Постановление от 24.09.2025 № 1466.
        2026 to listOf(
            Move(LocalDate.of(2026, 1, 3), LocalDate.of(2026, 1, 9)),
            Move(LocalDate.of(2026, 1, 4), LocalDate.of(2026, 12, 31)),
        ),
        // Постановление от 17.09.2026 № 1187.
        2027 to listOf(
            Move(LocalDate.of(2027, 1, 2), LocalDate.of(2027, 11, 5)),
            Move(LocalDate.of(2027, 1, 3), LocalDate.of(2027, 12, 31)),
            Move(LocalDate.of(2027, 2, 20), LocalDate.of(2027, 2, 22)),
        ),
    )

    /** Годы, переносы которых утверждены. */
    val decreedYears: Set<Int> get() = decrees.keys

    private val cache = mutableMapOf<Int, YearDays>()

    private class YearDays(val daysOff: Set<LocalDate>, val workingWeekends: Set<LocalDate>)

    /** Нерабочий праздничный день по ст. 112: 1–8 января, 23 февраля, 8 марта, 1 и 9 мая, 12 июня, 4 ноября. */
    fun isHoliday(date: LocalDate): Boolean = when (date.month) {
        Month.JANUARY -> date.dayOfMonth <= 8
        Month.FEBRUARY -> date.dayOfMonth == 23
        Month.MARCH -> date.dayOfMonth == 8
        Month.MAY -> date.dayOfMonth == 1 || date.dayOfMonth == 9
        Month.JUNE -> date.dayOfMonth == 12
        Month.NOVEMBER -> date.dayOfMonth == 4
        else -> false
    }

    /** Официальный выходной сверх обычных субботы и воскресенья: праздник или день отдыха по переносу. */
    fun isDayOff(date: LocalDate): Boolean = date in year(date.year).daysOff

    /** Рабочий день: будний, кроме праздников и переносов, и рабочие субботы и воскресенья по переносу. */
    fun isWorkingDay(date: LocalDate): Boolean {
        val days = year(date.year)
        if (date in days.workingWeekends) return true
        if (date in days.daysOff) return false
        return !date.isWeekend
    }

    private fun year(year: Int): YearDays = synchronized(cache) { cache.getOrPut(year) { compute(year) } }

    private fun compute(year: Int): YearDays {
        val daysOff = mutableSetOf<LocalDate>()
        val start = LocalDate.of(year, 1, 1)
        val holidays = (0 until start.lengthOfYear()).map { start.plusDays(it.toLong()) }.filter(::isHoliday)
        daysOff += holidays
        val moves = decrees[year].orEmpty()
        val decreedTo = moves.map { it.to }.toSet()
        val workingWeekends = moves.map { it.from }.filterNot(::isHoliday).toSet()
        // Закон: выходной, совпавший с праздником (кроме январских), — на следующий рабочий день.
        for (holiday in holidays) {
            if (holiday.month == Month.JANUARY || !holiday.isWeekend) continue
            var next = holiday.plusDays(1)
            while (next.isWeekend || isHoliday(next) || next in daysOff || next in decreedTo) next = next.plusDays(1)
            daysOff += next
        }
        daysOff += decreedTo
        return YearDays(daysOff, workingWeekends)
    }

    private val LocalDate.isWeekend: Boolean get() = dayOfWeek == DayOfWeek.SATURDAY || dayOfWeek == DayOfWeek.SUNDAY
}
