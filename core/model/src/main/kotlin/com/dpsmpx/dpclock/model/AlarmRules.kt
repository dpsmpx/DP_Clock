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

    /**
     * Дальше этого срабатывание не ищется: пропуски и исключения не закрывают годы.
     * Четыре года — правило «каждый год 29 февраля» (D-025, п. 3).
     */
    private const val SEARCH_DAYS = 1500L

    /**
     * Следующее срабатывание: первая подходящая дата, у которой время строго
     * позже [after], в поясе [zone]; дата [skipDate] (при [skipTime] — только это
     * срабатывание в неё) и даты [exceptions] пропускаются. null — больше не
     * сработает (дата будильника прошла, исключения закрыли все даты).
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
        exceptions: Exceptions = Exceptions.NONE,
        skipTime: LocalTime? = null,
    ): Instant? {
        val time = LocalTime.of(hour, minute)
        val start = after.atZone(zone).toLocalDate()
        if (repeat is Repeat.OnDate) {
            if (repeat.date.isBefore(start)) return null
            return resolve(LocalDateTime.of(repeat.date, time), zone).takeIf { it.isAfter(after) }
        }
        for (offset in 0L..SEARCH_DAYS) {
            val date = start.plusDays(offset)
            if (exceptions.excludes(date)) continue
            val times = repeat.timesOn(date, time)
            for (at in times) {
                if (date == skipDate && (skipTime == null || at == skipTime)) continue
                val candidate = resolve(LocalDateTime.of(date, at), zone)
                if (candidate.isAfter(after)) return candidate
            }
        }
        return null
    }

    /**
     * Следующее срабатывание будильника. Пропуск и исключения — только у
     * повторяющегося: однократный и на дату звонят, когда сказано (D-014, п. 4).
     */
    fun nextTrigger(alarm: Alarm, after: Instant, zone: ZoneId, exceptions: Exceptions = Exceptions.NONE): Instant? {
        val repeating = alarm.repeat.isRepeating
        return nextTrigger(
            alarm.hour,
            alarm.minute,
            alarm.repeat,
            after,
            zone,
            skipDate = alarm.skipDate.takeIf { repeating },
            exceptions = if (repeating && alarm.honorExceptions) exceptions else Exceptions.NONE,
            skipTime = alarm.skipTime.takeIf { repeating },
        )
    }

    /** Ближайшее срабатывание без учёта пропуска — его дата и становится пропущенной (D-013, п. 3). */
    fun nextIgnoringSkip(alarm: Alarm, after: Instant, zone: ZoneId, exceptions: Exceptions = Exceptions.NONE): Instant? =
        nextTrigger(alarm.copy(skipDate = null, skipTime = null), after, zone, exceptions)

    /**
     * Будильник с пропуском срабатывания [at]: дата, а у сложного повтора, где в
     * день бывает несколько срабатываний, — и время (D-025, п. 3).
     */
    fun skipping(alarm: Alarm, at: Instant, zone: ZoneId): Alarm {
        val local = at.atZone(zone)
        return alarm.copy(skipDate = local.toLocalDate(), skipTime = if (alarm.repeat is Repeat.Rules) local.toLocalTime().withSecond(0).withNano(0) else null)
    }

    /** Срабатывание [at] пропускается — для плитки «Ближайший будильник» (D-020). */
    fun isSkipped(alarm: Alarm, at: Instant, zone: ZoneId): Boolean {
        val local = at.atZone(zone)
        return alarm.skipDate == local.toLocalDate() && (alarm.skipTime == null || alarm.skipTime == local.toLocalTime().withSecond(0).withNano(0))
    }

    /** Несколько ближайших срабатываний — для предпросмотра графика. */
    fun upcoming(alarm: Alarm, after: Instant, zone: ZoneId, exceptions: Exceptions, count: Int): List<Instant> = buildList {
        var from = after
        while (size < count) {
            val next = nextTrigger(alarm, from, zone, exceptions) ?: break
            add(next)
            from = next
        }
    }

    /** Местное время — в момент: пропуск — к концу пропуска, повтор — к первому наступлению. */
    internal fun resolve(local: LocalDateTime, zone: ZoneId): Instant {
        val transition = zone.rules.getTransition(local)
        if (transition != null && transition.isGap) return transition.instant
        // Для повторившегося времени ofLocal без подсказки берёт более раннее смещение.
        return ZonedDateTime.ofLocal(local, zone, null).toInstant()
    }

    /** Сигнал опоздал так, что звонить уже нельзя (телефон был выключен, часы переведены вперёд). */
    fun isMissed(scheduledAt: Long, now: Long): Boolean = now - scheduledAt > MISSED_AFTER_MILLIS

    /**
     * Будильники, по которым считается сон (D-037): включённые ключевые; ни одного
     * включённого ключевого нет — все, как до 0.25.0.
     */
    fun forSleep(alarms: List<Alarm>): List<Alarm> =
        alarms.filter { it.enabled && it.key }.ifEmpty { alarms }

    /** Ближайшее срабатывание среди включённых будильников. */
    fun nearest(alarms: List<Alarm>, after: Instant, zone: ZoneId, exceptions: Exceptions = Exceptions.NONE): Instant? =
        nearestAlarm(alarms, after, zone, exceptions)?.second

    /** Ближайший включённый будильник и его срабатывание — для виджета и плитки (D-020). */
    fun nearestAlarm(alarms: List<Alarm>, after: Instant, zone: ZoneId, exceptions: Exceptions = Exceptions.NONE): Pair<Alarm, Instant>? =
        alarms.filter { it.enabled }
            .mapNotNull { alarm -> nextTrigger(alarm, after, zone, exceptions)?.let { alarm to it } }
            .minWithOrNull(compareBy<Pair<Alarm, Instant>> { it.second }.thenBy { it.first.createdAt })

    /** Список в выбранном порядке (D-010, п. 6); при равенстве — по созданию. */
    fun sorted(
        alarms: List<Alarm>,
        order: AlarmOrder,
        now: Instant,
        zone: ZoneId,
        exceptions: Exceptions = Exceptions.NONE,
    ): List<Alarm> =
        when (order) {
            AlarmOrder.TIME_OF_DAY -> alarms.sortedWith(compareBy({ it.minuteOfDay }, { it.createdAt }))
            AlarmOrder.CREATED -> alarms.sortedWith(compareBy({ it.createdAt }, { it.id }))
            AlarmOrder.NEXT -> {
                val next = alarms.associate { it.id to if (it.enabled) nextTrigger(it, now, zone, exceptions) else null }
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

    /** У сложного повтора и сменного графика время будильника — самое раннее: по нему сортируется список. */
    fun normalized(alarm: Alarm): Alarm {
        val time = when (val repeat = alarm.repeat) {
            is Repeat.Cycle -> repeat.firstTime
            is Repeat.Rules -> repeat.firstTime
            else -> return alarm
        }
        return alarm.copy(hour = time.hour, minute = time.minute)
    }
}

/** Промежуток до момента — для строк «через 7 ч 20 мин». Минуты округляются вверх. */
data class Countdown(val days: Long, val hours: Long, val minutes: Long) {
    companion object {
        fun of(millis: Long): Countdown {
            val totalMinutes = ceilDiv(millis.coerceAtLeast(0L), 60_000L)
            return Countdown(totalMinutes / (24 * 60), totalMinutes / 60 % 24, totalMinutes % 60)
        }
    }
}
