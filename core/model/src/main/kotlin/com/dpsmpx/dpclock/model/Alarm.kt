package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.LocalDate

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

/** Правило повтора. В версии 1.0 добавится сменный график (DATA_MODEL.md). */
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
        Repeat.Once, is Repeat.OnDate -> emptySet()
    }

val Repeat.bits: Int
    get() = days.fold(0) { acc, day -> acc or (1 shl day.ordinal) }

/** Звонит больше одного раза: пропуск одного срабатывания имеет смысл. */
val Repeat.isRepeating: Boolean
    get() = this is Repeat.Weekly

/** Вид повтора для подписи в списке: «Однократно», «Ежедневно», «Пн–Пт», перечень дней или дата. */
enum class RepeatKind { ONCE, DAILY, WEEKDAYS, DAYS, DATE }

val Repeat.kind: RepeatKind
    get() = when {
        this == Repeat.Once -> RepeatKind.ONCE
        this is Repeat.OnDate -> RepeatKind.DATE
        days.size == 7 -> RepeatKind.DAILY
        days == WORKDAYS -> RepeatKind.WEEKDAYS
        else -> RepeatKind.DAYS
    }

private val WORKDAYS = DayOfWeek.entries.take(5).toSet()

/** Порядок списка будильников (D-010, п. 6). */
enum class AlarmOrder {
    /** По времени суток; по умолчанию. */
    TIME_OF_DAY,

    /** По ближайшему срабатыванию; выключенные внизу. */
    NEXT,

    /** В порядке создания. */
    CREATED,
}
