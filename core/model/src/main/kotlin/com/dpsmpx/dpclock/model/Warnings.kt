package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Предупреждения до сигнала (D-025, п. 5): за сколько минут до срабатывания будильника. */
object Warnings {
    /** Готовые: за день, за час, за 30 и за 5 минут. */
    val CHOICES = listOf(24 * 60, 60, 30, 5)
    const val MAX = 6

    /** Не раньше чем за неделю. */
    const val MAX_MINUTES = 7 * 24 * 60

    /** Без повторов, в пределах, от дальнего к ближнему. */
    fun normalize(minutes: Collection<Int>): List<Int> =
        minutes.filter { it in 1..MAX_MINUTES }.distinct().sortedDescending().take(MAX)

    /**
     * Ближайшее после [now] предупреждение о срабатывании [at], мс UTC: момент и
     * за сколько минут. null — все предупреждения уже прошли.
     */
    fun next(minutes: List<Int>, at: Long, now: Long): Pair<Long, Int>? =
        minutes.map { at - it * 60_000L to it }.filter { it.first > now }.minByOrNull { it.first }

    /** Для базы: минуты через запятую. */
    fun encode(minutes: List<Int>): String = minutes.joinToString(",")

    fun decode(text: String): List<Int> = normalize(text.split(',').mapNotNull { it.trim().toIntOrNull() })
}

/** Куранты (D-025, п. 4): бой каждый час или полчаса. */
enum class ChimeEvery(val minutes: Int) { OFF(0), HOUR(60), HALF_HOUR(30), QUARTER(15) }

/**
 * Куранты: как часто, звуком или вибрацией, бой по числу часов, тихие часы
 * [quietFrom]–[quietTo] (могут переходить через полночь) и дни недели.
 */
data class Chimes(
    val every: ChimeEvery = ChimeEvery.OFF,
    val vibrate: Boolean = false,
    /** В начале часа — столько ударов, сколько часов (1–12); иначе один. */
    val countHours: Boolean = true,
    val quiet: Boolean = true,
    val quietFrom: LocalTime = LocalTime.of(22, 0),
    val quietTo: LocalTime = LocalTime.of(8, 0),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
) {
    val enabled: Boolean get() = every != ChimeEvery.OFF && days.isNotEmpty()

    /** Время в тихих часах: граница [quietFrom] — тихо, [quietTo] — уже нет. */
    fun isQuiet(time: LocalTime): Boolean = quiet && quietFrom != quietTo && if (quietFrom.isBefore(quietTo)) {
        !time.isBefore(quietFrom) && time.isBefore(quietTo)
    } else {
        !time.isBefore(quietFrom) || time.isBefore(quietTo)
    }

    /** Для базы: `<every>;<v|->;<c|->;<q|->;<с>;<до>;<дни битами>`. */
    fun encode(): String = listOf(
        every.name,
        if (vibrate) "v" else "-",
        if (countHours) "c" else "-",
        if (quiet) "q" else "-",
        (quietFrom.hour * 60 + quietFrom.minute).toString(),
        (quietTo.hour * 60 + quietTo.minute).toString(),
        days.fold(0) { acc, day -> acc or (1 shl day.ordinal) }.toString(),
    ).joinToString(";")

    companion object {
        fun decode(text: String): Chimes {
            val f = text.split(';')
            if (f.size < 7) return Chimes()
            fun time(value: String) = value.toIntOrNull()?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) }
            val bits = f[6].toIntOrNull() ?: 0x7f
            return Chimes(
                every = ChimeEvery.entries.firstOrNull { it.name == f[0] } ?: ChimeEvery.OFF,
                vibrate = f[1] == "v",
                countHours = f[2] == "c",
                quiet = f[3] == "q",
                quietFrom = time(f[4]) ?: LocalTime.of(22, 0),
                quietTo = time(f[5]) ?: LocalTime.of(8, 0),
                days = DayOfWeek.entries.filterTo(mutableSetOf()) { bits and (1 shl it.ordinal) != 0 },
            )
        }
    }
}

/** Когда и сколько бить (D-025, п. 4). Чистые функции. */
object ChimeRules {
    /** Ближайший бой строго позже [after] в поясе [zone]; null — куранты выключены. */
    fun next(chimes: Chimes, after: Instant, zone: ZoneId): Instant? {
        if (!chimes.enabled) return null
        val step = chimes.every.minutes.toLong()
        val local = after.atZone(zone).truncatedTo(ChronoUnit.MINUTES)
        var candidate = local.withMinute(((local.minute / step) * step).toInt())
        // Шаг — по настоящему времени: перевод часов сдвигает местное на целый час, бой остаётся в начале часа.
        // Неделя вперёд — дальше искать нечего: дни недели не пусты.
        repeat((7 * 24 * 60 / step + 2).toInt()) {
            val time = candidate.toLocalTime()
            if (candidate.toInstant().isAfter(after) && candidate.dayOfWeek in chimes.days && !chimes.isQuiet(time)) {
                return candidate.toInstant()
            }
            candidate = candidate.plusMinutes(step)
        }
        return null
    }

    /** Ударов в [time]: в начале часа — по числу часов (12-часовой счёт), иначе один. */
    fun strikes(chimes: Chimes, time: LocalTime): Int =
        if (chimes.countHours && time.minute == 0) (time.hour % 12).let { if (it == 0) 12 else it } else 1
}
