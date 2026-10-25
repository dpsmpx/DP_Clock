package com.dpsmpx.dpclock.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** Единица обратного отсчёта до будильника. */
enum class CountdownUnit { YEARS, MONTHS, DAYS, HOURS, MINUTES, SECONDS }

/** Часть форматированного обратного отсчёта; нулевые части не выдаются. */
data class CountdownPart(val value: Long, val unit: CountdownUnit) {
    init {
        require(value > 0L) { "обратный отсчёт должен быть положительным" }
    }
}

/**
 * Автоформат времени до следующего срабатывания.
 *
 * Годы, месяцы и дни считаются календарно в часовом поясе будильника, поэтому
 * конец месяца, високосный день и переход на летнее время не превращают
 * календарный месяц или день в условные 30 или 24 часа. Меньшие единицы
 * считаются по фактически оставшейся длительности. Метод не зависит от Android
 * и предназначен для детерминированных unit-тестов.
 */
object AlarmCountdown {
    fun parts(from: Instant, target: Instant, zone: ZoneId): List<CountdownPart> {
        if (!target.isAfter(from)) return emptyList()

        val start = from.atZone(zone)
        val end = target.atZone(zone)
        var cursor = start
        val result = mutableListOf<CountdownPart>()

        val years = wholeUnits(
            cursor,
            end,
            (end.year.toLong() - cursor.year.toLong()).coerceAtLeast(0L),
        ) { base, amount -> base.plusYears(amount) }
        if (years > 0L) {
            result += CountdownPart(years, CountdownUnit.YEARS)
            cursor = cursor.plusYears(years)
        }

        val startMonth = cursor.year.toLong() * 12L + cursor.monthValue
        val endMonth = end.year.toLong() * 12L + end.monthValue
        val months = wholeUnits(cursor, end, (endMonth - startMonth).coerceAtLeast(0L)) { base, amount ->
            base.plusMonths(amount)
        }
        if (months > 0L) {
            result += CountdownPart(months, CountdownUnit.MONTHS)
            cursor = cursor.plusMonths(months)
        }

        val dayDifference = end.toLocalDate().toEpochDay() - cursor.toLocalDate().toEpochDay()
        val days = wholeUnits(cursor, end, dayDifference.coerceAtLeast(0L)) { base, amount ->
            base.plusDays(amount)
        }
        if (days > 0L) {
            result += CountdownPart(days, CountdownUnit.DAYS)
            cursor = cursor.plusDays(days)
        }

        // Не перегружаем строку: до трёх частей, например «2 г. 3 мес. 5 д.».
        val maxParts = if (result.isEmpty()) 2 else 3
        if (result.size >= maxParts) return result.take(maxParts)

        val remainder = Duration.between(cursor.toInstant(), target)
        var seconds = (remainder.seconds + if (remainder.nano > 0) 1L else 0L).coerceAtLeast(0L)
        if (seconds >= 3_600L && result.size < maxParts) {
            val hours = seconds / 3_600L
            result += CountdownPart(hours, CountdownUnit.HOURS)
            seconds %= 3_600L
        }
        if (seconds >= 60L && result.size < maxParts) {
            val minutes = seconds / 60L
            result += CountdownPart(minutes, CountdownUnit.MINUTES)
            seconds %= 60L
        }
        if (seconds > 0L && result.size < maxParts) {
            result += CountdownPart(seconds, CountdownUnit.SECONDS)
        }
        return result
    }

    private fun wholeUnits(
        start: ZonedDateTime,
        target: ZonedDateTime,
        estimate: Long,
        add: (ZonedDateTime, Long) -> ZonedDateTime,
    ): Long {
        var count = estimate.coerceAtLeast(0L)

        fun candidate(amount: Long): ZonedDateTime? = runCatching { add(start, amount) }.getOrNull()

        while (count > 0L && candidate(count)?.isAfter(target) != false) count--
        while (count < Long.MAX_VALUE) {
            val next = candidate(count + 1L) ?: break
            if (next.isAfter(target)) break
            count++
        }
        return count
    }
}
