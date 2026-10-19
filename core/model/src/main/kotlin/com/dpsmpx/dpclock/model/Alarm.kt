package com.dpsmpx.dpclock.model

import java.time.DayOfWeek

/**
 * Будильник MVP (DATA_MODEL.md, п. 2). Время — местное: 7:00 остаётся 7:00
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
) {
    init {
        require(hour in 0..23) { "час $hour" }
        require(minute in 0..59) { "минута $minute" }
    }

    /** Минута суток: ключ сортировки «по времени суток». */
    val minuteOfDay: Int get() = hour * 60 + minute
}

/** Правило повтора. В версии 1.0 добавятся дата и сменный график (DATA_MODEL.md). */
sealed interface Repeat {
    /** Ближайшее наступление времени; после срабатывания будильник выключается. */
    data object Once : Repeat

    /** По дням недели; пустого набора не бывает — это [Once]. */
    data class Weekly(val days: Set<DayOfWeek>) : Repeat {
        init {
            require(days.isNotEmpty()) { "повтор без дней — это Once" }
        }
    }

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
        Repeat.Once -> emptySet()
        is Repeat.Weekly -> days
    }

val Repeat.bits: Int
    get() = days.fold(0) { acc, day -> acc or (1 shl day.ordinal) }

/** Вид повтора для подписи в списке: «Однократно», «Ежедневно», «Пн–Пт» или перечень дней. */
enum class RepeatKind { ONCE, DAILY, WEEKDAYS, DAYS }

val Repeat.kind: RepeatKind
    get() = when {
        this == Repeat.Once -> RepeatKind.ONCE
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
