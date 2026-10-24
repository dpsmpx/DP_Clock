package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Будильник (DATA_MODEL.md, п. 2; D-013). Время — местное: 7:00 остаётся 7:00
 * после смены часового пояса.
 */
data class Alarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val repeat: Repeat,
    val enabled: Boolean,
    /** Момент создания, мс UTC: порядок в списке при равном времени. */
    val createdAt: Long,
    /** Название; пустое — без названия. */
    val label: String = "",
    val sound: AlarmSound = AlarmSound.Default,
    val vibrate: Boolean = true,
    /** Нарастание громкости, с; 0 — сразу полная громкость. */
    val rampSeconds: Int = 0,
    /** Свой интервал «Отложить», мин; null — общий из настроек. */
    val snoozeMinutes: Int? = null,
    /** Дата одного пропускаемого срабатывания; null — без пропуска. */
    val skipDate: LocalDate? = null,
    /** Не звонить в даты исключений (D-014, п. 4); действует только у повторяющегося. */
    val honorExceptions: Boolean = true,
    /** Группа (D-014, п. 5); null — без группы. */
    val groupId: String? = null,
) {
    init {
        require(hour in 0..23) { "час $hour" }
        require(minute in 0..59) { "минута $minute" }
        require(rampSeconds >= 0) { "нарастание $rampSeconds" }
        require(snoozeMinutes == null || snoozeMinutes > 0) { "«Отложить» $snoozeMinutes" }
    }

    /** Минута суток: ключ сортировки «по времени суток». */
    val minuteOfDay: Int get() = hour * 60 + minute

    /** Ни звука, ни вибрации — сигнал никто не заметит. */
    val isUnnoticeable: Boolean get() = sound == AlarmSound.Silent && !vibrate

    companion object {
        const val MAX_LABEL = 40
    }
}

/** Правило повтора (DATA_MODEL.md, п. 2). */
sealed interface Repeat {
    /** Ближайшее наступление времени; после срабатывания будильник выключается. */
    data object Once : Repeat

    /** По дням недели; пустого набора не бывает — это [Once]. */
    data class Weekly(val days: Set<DayOfWeek>) : Repeat {
        init {
            require(days.isNotEmpty()) { "повтор без дней — это Once" }
        }
    }

    /** Один раз в указанную дату; после срабатывания будильник выключается. */
    data class OnDate(val date: LocalDate) : Repeat

    /**
     * Сменный график (D-014, п. 2): цикл из [days].size дней от [anchor] — первого
     * дня цикла; у каждого дня своё время или null — выходной.
     */
    data class Cycle(val anchor: LocalDate, val days: List<LocalTime?>) : Repeat {
        init {
            require(days.size in 2..MAX_DAYS) { "дней в цикле ${days.size}" }
            require(days.any { it != null }) { "в цикле нет рабочих дней" }
        }

        /** Время в дату [date]; null — выходной по графику. */
        fun timeOn(date: LocalDate): LocalTime? =
            days[Math.floorMod(ChronoUnit.DAYS.between(anchor, date), days.size.toLong()).toInt()]

        /** Время первого рабочего дня цикла — для сортировки и показа. */
        val firstTime: LocalTime get() = days.first { it != null }!!

        /** Дни для базы: минуты суток через запятую, «-» — выходной. */
        fun encodeDays(): String = days.joinToString(",") { it?.let { t -> (t.hour * 60 + t.minute).toString() } ?: "-" }

        companion object {
            const val MAX_DAYS = 28

            fun decodeDays(text: String): List<LocalTime?> =
                text.split(',').map { if (it == "-") null else LocalTime.of(it.toInt() / 60, it.toInt() % 60) }
        }
    }

    /** Рабочие дни по производственному календарю России (D-014, п. 3). */
    data object Workdays : Repeat

    companion object {
        /** Ни одного дня — однократно (SCREEN_MAP.md, п. 2). */
        fun of(days: Set<DayOfWeek>): Repeat = if (days.isEmpty()) Once else Weekly(days)

        /** Дни битами: понедельник — бит 0, воскресенье — бит 6; 0 — однократно. */
        fun fromBits(bits: Int): Repeat =
            of(DayOfWeek.entries.filterTo(mutableSetOf()) { bits and (1 shl it.ordinal) != 0 })
    }
}

val Repeat.days: Set<DayOfWeek>
    get() = when (this) {
        is Repeat.Weekly -> days
        Repeat.Once, is Repeat.OnDate, is Repeat.Cycle, Repeat.Workdays -> emptySet()
    }

val Repeat.bits: Int
    get() = days.fold(0) { acc, day -> acc or (1 shl day.ordinal) }

/** Звонит больше одного раза: пропуск одного срабатывания и исключения имеют смысл. */
val Repeat.isRepeating: Boolean
    get() = this is Repeat.Weekly || this is Repeat.Cycle || this == Repeat.Workdays

/** Время срабатывания в дату [date] при времени будильника [time]; null — в эту дату не звонит. */
fun Repeat.timeOn(date: LocalDate, time: LocalTime): LocalTime? = when (this) {
    Repeat.Once -> time
    is Repeat.Weekly -> time.takeIf { date.dayOfWeek in days }
    is Repeat.OnDate -> time.takeIf { date == this.date }
    is Repeat.Cycle -> timeOn(date)
    Repeat.Workdays -> time.takeIf { RuCalendar.isWorkingDay(date) }
}

/** Вид повтора для подписи в списке: «Однократно», «Ежедневно», «Пн–Пт», перечень дней, дата, график, рабочие дни. */
enum class RepeatKind { ONCE, DAILY, WEEKDAYS, DAYS, DATE, CYCLE, WORKDAYS }

val Repeat.kind: RepeatKind
    get() = when {
        this == Repeat.Once -> RepeatKind.ONCE
        this is Repeat.OnDate -> RepeatKind.DATE
        this is Repeat.Cycle -> RepeatKind.CYCLE
        this == Repeat.Workdays -> RepeatKind.WORKDAYS
        days.size == 7 -> RepeatKind.DAILY
        days == WORKDAYS -> RepeatKind.WEEKDAYS
        else -> RepeatKind.DAYS
    }

private val WORKDAYS = DayOfWeek.entries.take(5).toSet()

/** Готовые сменные графики редактора (D-014, п. 2); [day] — время дневной смены, [night] — ночной. */
enum class CyclePreset {
    /** 2/2: два рабочих, два выходных. */
    TWO_BY_TWO,

    /** Сутки через трое. */
    DAY_THREE_OFF,

    /** День, ночь, отсыпной, выходной. */
    DAY_NIGHT;

    fun days(day: LocalTime, night: LocalTime): List<LocalTime?> = when (this) {
        TWO_BY_TWO -> listOf(day, day, null, null)
        DAY_THREE_OFF -> listOf(day, null, null, null)
        DAY_NIGHT -> listOf(day, night, null, null)
    }
}

/** Порядок списка будильников (D-010, п. 6). */
enum class AlarmOrder {
    /** По времени суток; по умолчанию. */
    TIME_OF_DAY,

    /** По ближайшему срабатыванию; выключенные внизу. */
    NEXT,

    /** В порядке создания. */
    CREATED,
}
