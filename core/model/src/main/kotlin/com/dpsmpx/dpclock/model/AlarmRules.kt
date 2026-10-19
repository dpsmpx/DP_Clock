package com.dpsmpx.dpclock.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Правила времени будильника (DATA_MODEL.md, п. 2; D-009, п. 2). Чистые функции. */
object AlarmRules {
    /** Опоздание больше этого — не звонить задним числом, показать пропуск. */
    const val MISSED_AFTER_MILLIS: Long = 10 * 60_000L

    /**
     * Следующее срабатывание: первая подходящая дата, у которой время строго
     * позже [after], в поясе [zone].
     *
     * - Время пропущено переходом на летнее — сигнал в первый существующий момент после пропуска.
     * - Время наступает дважды переходом на зимнее — один сигнал, в первое наступление.
     */
    fun nextTrigger(hour: Int, minute: Int, repeat: Repeat, after: Instant, zone: ZoneId): Instant {
        val time = LocalTime.of(hour, minute)
        val start = after.atZone(zone).toLocalDate()
        // Восемь дней покрывают и «тот же день недели, но время уже прошло».
        for (offset in 0L..7L) {
            val date = start.plusDays(offset)
            if (repeat is Repeat.Weekly && date.dayOfWeek !in repeat.days) continue
            val candidate = resolve(LocalDateTime.of(date, time), zone)
            if (candidate.isAfter(after)) return candidate
        }
        error("нет срабатывания за 8 дней: $repeat")
    }

    fun nextTrigger(alarm: Alarm, after: Instant, zone: ZoneId): Instant =
        nextTrigger(alarm.hour, alarm.minute, alarm.repeat, after, zone)

    /** Местное время — в момент: пропуск — к концу пропуска, повтор — к первому наступлению. */
    internal fun resolve(local: LocalDateTime, zone: ZoneId): Instant {
        val transition = zone.rules.getTransition(local)
        if (transition != null && transition.isGap) return transition.instant
        // Для повторившегося времени ofLocal без подсказки берёт более раннее смещение.
        return ZonedDateTime.ofLocal(local, zone, null).toInstant()
    }

    /** Сигнал опоздал так, что звонить уже нельзя (телефон был выключен, часы переведены вперёд). */
    fun isMissed(scheduledAt: Long, now: Long): Boolean = now - scheduledAt > MISSED_AFTER_MILLIS

    /** Ближайшее срабатывание среди включённых будильников. */
    fun nearest(alarms: List<Alarm>, after: Instant, zone: ZoneId): Instant? =
        alarms.filter { it.enabled }.minOfOrNull { nextTrigger(it, after, zone) }

    /** Список в выбранном порядке (D-010, п. 6); при равенстве — по созданию. */
    fun sorted(alarms: List<Alarm>, order: AlarmOrder, now: Instant, zone: ZoneId): List<Alarm> =
        when (order) {
            AlarmOrder.TIME_OF_DAY -> alarms.sortedWith(compareBy({ it.minuteOfDay }, { it.createdAt }))
            AlarmOrder.CREATED -> alarms.sortedWith(compareBy({ it.createdAt }, { it.id }))
            AlarmOrder.NEXT -> alarms.sortedWith(
                compareBy<Alarm>({ !it.enabled }, { if (it.enabled) nextTrigger(it, now, zone) else null })
                    .thenBy { it.minuteOfDay }
                    .thenBy { it.createdAt },
            )
        }
}

/** Промежуток до момента — для строк «через 7 ч 20 мин». Минуты округляются вверх. */
data class Countdown(val days: Long, val hours: Long, val minutes: Long) {
    companion object {
        fun of(millis: Long): Countdown {
            val totalMinutes = Math.ceilDiv(millis.coerceAtLeast(0L), 60_000L)
            return Countdown(totalMinutes / (24 * 60), totalMinutes / 60 % 24, totalMinutes % 60)
        }
    }
}
