package com.dpsmpx.dpclock.model

import java.time.DayOfWeek

/**
 * Стандартные команды Android (`AlarmClock.ACTION_SET_ALARM`, `ACTION_SET_TIMER`)
 * от ассистента и других приложений (D-020, п. 5) — чистые правила.
 */
object AssistantRules {
    /** `AlarmClock.EXTRA_LENGTH` — от 1 с до суток. */
    const val MAX_TIMER_SECONDS = 24 * 60 * 60

    /** День `java.util.Calendar` (воскресенье — 1, суббота — 7) — в `DayOfWeek`; иное — null. */
    fun dayOf(calendarDay: Int): DayOfWeek? =
        if (calendarDay in 1..7) DayOfWeek.of((calendarDay + 5) % 7 + 1) else null

    /**
     * Будильник по команде: время, дни (`Calendar`), подпись, вибрация. Без дней —
     * однократный. Время вне суток — null: команда неверна.
     */
    fun alarm(
        id: String,
        hour: Int,
        minute: Int,
        calendarDays: List<Int>,
        message: String?,
        vibrate: Boolean,
        createdAt: Long,
    ): Alarm? {
        if (hour !in 0..23 || minute !in 0..59) return null
        val days = calendarDays.mapNotNull(::dayOf).toSet()
        return Alarm(
            id = id,
            hour = hour,
            minute = minute,
            repeat = Repeat.of(days),
            enabled = true,
            createdAt = createdAt,
            label = message.orEmpty().trim().take(Alarm.MAX_LABEL),
            vibrate = vibrate,
        )
    }

    /** Такой же будильник уже есть — то же время, повтор и подпись: его включают, а не плодят копию. */
    fun matching(alarms: List<Alarm>, wanted: Alarm): Alarm? = alarms.firstOrNull {
        it.hour == wanted.hour && it.minute == wanted.minute && it.repeat == wanted.repeat && it.label == wanted.label
    }

    /** Длина таймера по команде, мс; вне 1 с – сутки — null. */
    fun timerMillis(seconds: Int): Long? = seconds.takeIf { it in 1..MAX_TIMER_SECONDS }?.let { it * 1000L }
}
