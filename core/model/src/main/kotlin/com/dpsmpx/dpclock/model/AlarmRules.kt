package com.dpsmpx.dpclock.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Правила времени будильника (DATA_MODEL.md, п. 2; D-009, п. 2). Чистые функции. */
object AlarmRules {
    /** Опоздание больше этого — не звонить задним числом, показать пропуск. */
    const val MISSED_AFTER_MILLIS: Long = 10 * 60_000L

    /** Дальше этого срабатывание не ищется: пропуски и исключения не закрывают годы. */
    private const val SEARCH_DAYS = 800L

    /**
     * Следующее срабатывание: первая подходящая дата, у которой время строго
     * позже [after], в поясе [zone]; дата [skipDate] пропускается. null — больше
     * не сработает (дата будильника прошла).
     *
     * - Время пропущено переходом на летнее — сигнал в первый существующий момент после пропуска.
     * - Время наступает дважды переходом на зимнее — один сигнал, в первое наступление.
     */
    fun nextTrigger(
        hour: Int,
        minute: Int,
        repeat: Repeat,
        after: Instant,
        zone: ZoneId,
        skipDate: LocalDate? = null,
    ): Instant? {
        val time = LocalTime.of(hour, minute)
        val start = after.atZone(zone).toLocalDate()
        if (repeat is Repeat.OnDate) {
            if (repeat.date.isBefore(start)) return null
            return resolve(LocalDateTime.of(repeat.date, time), zone).takeIf { it.isAfter(after) }
        }
        for (offset in 0L..SEARCH_DAYS) {
            val date = start.plusDays(offset)
            if (repeat is Repeat.Weekly && date.dayOfWeek !in repeat.days) continue
            if (date == skipDate) continue
            val candidate = resolve(LocalDateTime.of(date, time), zone)
            if (candidate.isAfter(after)) return candidate
        }
        return null
    }

    fun nextTrigger(alarm: Alarm, after: Instant, zone: ZoneId): Instant? =
        nextTrigger(alarm.hour, alarm.minute, alarm.repeat, after, zone, alarm.skipDate.takeIf { alarm.repeat.isRepeating })

    /** Ближайшее срабатывание без учёта пропуска — его дата и становится пропущенной (D-013, п. 3). */
    fun nextIgnoringSkip(alarm: Alarm, after: Instant, zone: ZoneId): Instant? =
        nextTrigger(alarm.copy(skipDate = null), after, zone)

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
        alarms.filter { it.enabled }.mapNotNull { nextTrigger(it, after, zone) }.minOrNull()

    /** Список в выбранном порядке (D-010, п. 6); при равенстве — по созданию. */
    fun sorted(alarms: List<Alarm>, order: AlarmOrder, now: Instant, zone: ZoneId): List<Alarm> =
        when (order) {
            AlarmOrder.TIME_OF_DAY -> alarms.sortedWith(compareBy({ it.minuteOfDay }, { it.createdAt }))
            AlarmOrder.CREATED -> alarms.sortedWith(compareBy({ it.createdAt }, { it.id }))
            AlarmOrder.NEXT -> {
                val next = alarms.associate { it.id to if (it.enabled) nextTrigger(it, now, zone) else null }
                alarms.sortedWith(
                    compareBy<Alarm>({ next[it.id] == null }, { next[it.id] })
                        .thenBy { it.minuteOfDay }
                        .thenBy { it.createdAt },
                )
            }
        }

    /** Пропуск устарел: его дата уже прошла, больше он ничего не пропустит. */
    fun isSkipStale(alarm: Alarm, now: Instant, zone: ZoneId): Boolean =
        alarm.skipDate != null && alarm.skipDate.isBefore(now.atZone(zone).toLocalDate())
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
