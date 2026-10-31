package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Даты правила сложного повтора (D-025, п. 3). */
sealed interface DatePattern {
    fun matches(date: LocalDate): Boolean

    /**
     * Дни недели раз в [everyWeeks] недель; неделя (с понедельника), где [anchor], — первая.
     * Чётные и нечётные недели — каждые две с разных недель.
     */
    data class Weeks(val days: Set<DayOfWeek>, val everyWeeks: Int = 1, val anchor: LocalDate = EPOCH_MONDAY) : DatePattern {
        init {
            require(days.isNotEmpty()) { "недели без дней" }
            require(everyWeeks in 1..MAX_EVERY_WEEKS) { "каждые $everyWeeks нед." }
        }

        override fun matches(date: LocalDate): Boolean {
            if (date.dayOfWeek !in days) return false
            if (everyWeeks == 1) return true
            val weeks = ChronoUnit.WEEKS.between(monday(anchor), monday(date))
            return Math.floorMod(weeks, everyWeeks.toLong()) == 0L
        }
    }

    /** Числа месяца 1–31 и [LAST] — последний день. Числа нет в месяце (31 апреля) — в этом месяце не звонит. */
    data class MonthDays(val days: Set<Int>) : DatePattern {
        init {
            require(days.isNotEmpty() && days.all { it == LAST || it in 1..31 }) { "числа $days" }
        }

        override fun matches(date: LocalDate): Boolean =
            date.dayOfMonth in days || (LAST in days && date.dayOfMonth == date.lengthOfMonth())
    }

    /** [week]-й день недели [day] месяца — 1–5 — или последний ([LAST]): «второй вторник». */
    data class MonthWeekday(val week: Int, val day: DayOfWeek) : DatePattern {
        init {
            require(week == LAST || week in 1..5) { "неделя месяца $week" }
        }

        override fun matches(date: LocalDate): Boolean {
            if (date.dayOfWeek != day) return false
            return if (week == LAST) date.plusWeeks(1).month != date.month else (date.dayOfMonth - 1) / 7 + 1 == week
        }
    }

    /** Каждый год [day] числа месяца [month]; 29 февраля — только в високосные годы. */
    data class Yearly(val month: Int, val day: Int) : DatePattern {
        init {
            require(month in 1..12) { "месяц $month" }
            require(day in 1..Month.of(month).maxLength()) { "день $day" }
        }

        override fun matches(date: LocalDate): Boolean = date.monthValue == month && date.dayOfMonth == day
    }

    /** Смены: цикл из [on].size дней от [anchor] — первого дня цикла; true — рабочий день. */
    data class Shifts(val anchor: LocalDate, val on: List<Boolean>) : DatePattern {
        init {
            require(on.size in 2..Repeat.Cycle.MAX_DAYS) { "дней в цикле ${on.size}" }
            require(on.any { it }) { "в цикле нет рабочих дней" }
        }

        override fun matches(date: LocalDate): Boolean =
            on[Math.floorMod(ChronoUnit.DAYS.between(anchor, date), on.size.toLong()).toInt()]
    }

    /** Отдельные даты. */
    data class Dates(val dates: Set<LocalDate>) : DatePattern {
        init {
            require(dates.size in 1..MAX_DATES) { "дат ${dates.size}" }
        }

        override fun matches(date: LocalDate): Boolean = date in dates
    }

    /** Рабочие дни по производственному календарю России (D-014, п. 3). */
    data object Workdays : DatePattern {
        override fun matches(date: LocalDate): Boolean = RuCalendar.isWorkingDay(date)
    }

    companion object {
        /** «Последний»: день месяца или неделя месяца. */
        const val LAST = -1
        const val MAX_EVERY_WEEKS = 8
        const val MAX_DATES = 60

        /** Понедельник для правил «каждую неделю», где первая неделя не важна. */
        val EPOCH_MONDAY: LocalDate = LocalDate.of(1970, 1, 5)

        fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }
}

/** Время правила (D-025, п. 4): несколько раз за день или каждые N минут в окне. */
sealed interface RuleTimes {
    /** Моменты дня по порядку, без повторов. */
    val list: List<LocalTime>

    data class At(val times: List<LocalTime>) : RuleTimes {
        init {
            require(times.size in 1..MAX_TIMES) { "времён ${times.size}" }
        }

        override val list: List<LocalTime> get() = times.map { it.withSecond(0).withNano(0) }.distinct().sorted()
    }

    /** Каждые [minutes] минут от [start] до [end] включительно, в пределах одних суток. */
    data class Every(val start: LocalTime, val end: LocalTime, val minutes: Int) : RuleTimes {
        init {
            require(minutes in 1..MAX_EVERY_MINUTES) { "каждые $minutes мин" }
            require(!end.isBefore(start)) { "окно $start–$end" }
        }

        override val list: List<LocalTime>
            get() {
                val first = start.hour * 60 + start.minute
                val last = end.hour * 60 + end.minute
                return (first..last step minutes).map { LocalTime.of(it / 60, it % 60) }
            }
    }

    companion object {
        const val MAX_TIMES = 24
        const val MAX_EVERY_MINUTES = 12 * 60
    }
}

/**
 * Правило сложного повтора (D-025, п. 3): даты [dates] в пределах [from]–[until],
 * только по дням недели [weekdays] (пусто — по любым), время — [times] или
 * своё по дню недели из [weekdayTimes].
 */
data class RepeatRule(
    val dates: DatePattern,
    val times: RuleTimes,
    val weekdays: Set<DayOfWeek> = emptySet(),
    val weekdayTimes: Map<DayOfWeek, LocalTime> = emptyMap(),
    val from: LocalDate? = null,
    val until: LocalDate? = null,
) {
    init {
        require(from == null || until == null || !until.isBefore(from)) { "период $from–$until" }
    }

    /** Времена в дату [date]; пусто — правило в эту дату молчит. */
    fun timesOn(date: LocalDate): List<LocalTime> {
        if (from != null && date.isBefore(from)) return emptyList()
        if (until != null && date.isAfter(until)) return emptyList()
        if (weekdays.isNotEmpty() && date.dayOfWeek !in weekdays) return emptyList()
        if (!dates.matches(date)) return emptyList()
        return weekdayTimes[date.dayOfWeek]?.let { listOf(it.withSecond(0).withNano(0)) } ?: times.list
    }

    /** Самое раннее время правила — для сортировки списка. */
    val firstTime: LocalTime get() = (times.list + weekdayTimes.values).min()
}

/** Сложный повтор строкой JSON — для базы и шаблонов; его же объект — в файле переноса (D-018). */
object RulesCode {
    fun encode(rules: Repeat.Rules): String = Json.write(toMap(rules)).trimEnd()

    fun decode(text: String): Repeat.Rules = fromMap(Json.parse(text) as? Map<*, *> ?: throw IllegalArgumentException("правила: $text"))

    fun toMap(rules: Repeat.Rules): Map<String, Any?> = linkedMapOf(
        "rules" to rules.rules.map(::rule),
        "except" to rules.except.map(::pattern),
    )

    fun fromMap(map: Map<*, *>): Repeat.Rules = Repeat.Rules(
        rules = list(map, "rules").map { rule(it as? Map<*, *> ?: bad("rules")) },
        except = list(map, "except").map { pattern(it as? Map<*, *> ?: bad("except")) },
    )

    private fun rule(rule: RepeatRule): Map<String, Any?> = linkedMapOf(
        "dates" to pattern(rule.dates),
        "times" to when (val t = rule.times) {
            is RuleTimes.At -> mapOf("type" to "at", "times" to t.list.map(::time))
            is RuleTimes.Every -> mapOf("type" to "every", "start" to time(t.start), "end" to time(t.end), "minutes" to t.minutes)
        },
        "weekdays" to rule.weekdays.sorted().map { it.name },
        "weekdayTimes" to rule.weekdayTimes.toSortedMap().entries.associate { (day, at) -> day.name to time(at) },
        "from" to rule.from?.toString(),
        "until" to rule.until?.toString(),
    )

    private fun pattern(pattern: DatePattern): Map<String, Any?> = when (pattern) {
        is DatePattern.Weeks -> mapOf(
            "type" to "weeks",
            "days" to pattern.days.sorted().map { it.name },
            "every" to pattern.everyWeeks,
            "anchor" to pattern.anchor.toString(),
        )
        is DatePattern.MonthDays -> mapOf("type" to "monthDays", "days" to pattern.days.sorted())
        is DatePattern.MonthWeekday -> mapOf("type" to "monthWeekday", "week" to pattern.week, "day" to pattern.day.name)
        is DatePattern.Yearly -> mapOf("type" to "yearly", "month" to pattern.month, "day" to pattern.day)
        is DatePattern.Shifts -> mapOf("type" to "shifts", "anchor" to pattern.anchor.toString(), "on" to pattern.on)
        is DatePattern.Dates -> mapOf("type" to "dates", "dates" to pattern.dates.sorted().map { it.toString() })
        DatePattern.Workdays -> mapOf("type" to "workdays")
    }

    private fun rule(map: Map<*, *>): RepeatRule {
        val times = map["times"] as? Map<*, *> ?: bad("times")
        return RepeatRule(
            dates = pattern(map["dates"] as? Map<*, *> ?: bad("dates")),
            times = when (val type = times["type"]) {
                "at" -> RuleTimes.At(list(times, "times").map { time(it) })
                "every" -> RuleTimes.Every(time(times["start"]), time(times["end"]), int(times, "minutes"))
                else -> bad("times $type")
            },
            weekdays = list(map, "weekdays").mapTo(mutableSetOf()) { day(it) },
            weekdayTimes = (map["weekdayTimes"] as? Map<*, *>).orEmpty().entries.associate { (day, at) -> day(day) to time(at) },
            from = (map["from"] as? String)?.let(LocalDate::parse),
            until = (map["until"] as? String)?.let(LocalDate::parse),
        )
    }

    private fun pattern(map: Map<*, *>): DatePattern = when (val type = map["type"]) {
        "weeks" -> DatePattern.Weeks(
            list(map, "days").mapTo(mutableSetOf()) { day(it) },
            int(map, "every"),
            (map["anchor"] as? String)?.let(LocalDate::parse) ?: DatePattern.EPOCH_MONDAY,
        )
        "monthDays" -> DatePattern.MonthDays(list(map, "days").mapTo(mutableSetOf()) { (it as? Long ?: bad("days")).toInt() })
        "monthWeekday" -> DatePattern.MonthWeekday(int(map, "week"), day(map["day"]))
        "yearly" -> DatePattern.Yearly(int(map, "month"), int(map, "day"))
        "shifts" -> DatePattern.Shifts(
            LocalDate.parse(map["anchor"] as? String ?: bad("anchor")),
            list(map, "on").map { it as? Boolean ?: bad("on") },
        )
        "dates" -> DatePattern.Dates(list(map, "dates").mapTo(mutableSetOf()) { LocalDate.parse(it as? String ?: bad("dates")) })
        "workdays" -> DatePattern.Workdays
        else -> bad("даты $type")
    }

    private fun time(at: LocalTime): String = at.hour.toString().padStart(2, '0') + ":" + at.minute.toString().padStart(2, '0')

    private fun time(value: Any?): LocalTime = LocalTime.parse(value as? String ?: bad("время"))

    private fun day(value: Any?): DayOfWeek = DayOfWeek.valueOf(value as? String ?: bad("день недели"))

    private fun int(map: Map<*, *>, key: String): Int = (map[key] as? Long)?.toInt() ?: bad(key)

    private fun list(map: Map<*, *>, key: String): List<Any?> = map[key] as? List<*> ?: emptyList<Any?>()

    private fun bad(what: String): Nothing = throw IllegalArgumentException("правила: $what")
}
