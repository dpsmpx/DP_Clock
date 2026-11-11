package com.dpsmpx.dpclock.model

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Событие солнца, к которому привязан сигнал (D-035). */
enum class SunEvent { SUNRISE, SUNSET }

/** Чем сигналить: уведомлением или будильником — со звуком и экраном сигнала. */
enum class SunSignalKind { NOTIFICATION, ALARM }

/** Один сигнал: за [minutes] минут до события; 0 — в его момент. */
data class SunMoment(val minutes: Int, val kind: SunSignalKind = SunSignalKind.NOTIFICATION)

/** Сигналы одного события: включены ли и когда. По умолчанию — за 5 минут и в момент события. */
data class SunSignal(val enabled: Boolean = false, val moments: List<SunMoment> = DEFAULT_MOMENTS) {
    /** Сигналить нечем — как выключено. */
    val active: Boolean get() = enabled && moments.isNotEmpty()

    companion object {
        const val MAX = 6

        /** Не раньше чем за 12 часов: дальше сигнал относился бы к другому событию. */
        const val MAX_MINUTES = 12 * 60
        const val DEFAULT_LEAD = 5
        val DEFAULT_MOMENTS = listOf(SunMoment(DEFAULT_LEAD), SunMoment(0))

        /** Готовые «за сколько» в окне добавления. */
        val CHOICES = listOf(0, 5, 10, 15, 30, 60)

        /** В пределах, одно на каждое «за сколько», от дальнего к ближнему. */
        fun normalize(moments: Collection<SunMoment>): List<SunMoment> =
            moments.filter { it.minutes in 0..MAX_MINUTES }.distinctBy { it.minutes }.sortedByDescending { it.minutes }.take(MAX)
    }
}

/**
 * Уведомления и будильники на восходе и закате (D-035): сигналы каждого события и
 * звук будильника — один на оба. Место — у телефона, не здесь.
 */
data class SunSignals(
    val sunrise: SunSignal = SunSignal(),
    val sunset: SunSignal = SunSignal(),
    val sound: AlarmSound = AlarmSound.Default,
    val vibrate: Boolean = true,
) {
    val enabled: Boolean get() = sunrise.active || sunset.active

    /** Есть хоть один будильник — звук и вибрация что-то значат. */
    val hasAlarm: Boolean
        get() = SunEvent.entries.any { event -> of(event).let { it.active && it.moments.any { m -> m.kind == SunSignalKind.ALARM } } }

    fun of(event: SunEvent): SunSignal = if (event == SunEvent.SUNRISE) sunrise else sunset

    fun with(event: SunEvent, signal: SunSignal): SunSignals = if (event == SunEvent.SUNRISE) copy(sunrise = signal) else copy(sunset = signal)

    /** Для файла и синхронизации. */
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "sunrise" to signalMap(sunrise),
        "sunset" to signalMap(sunset),
        "sound" to sound.encode(),
        "soundTitle" to sound.title,
        "vibrate" to vibrate,
    )

    /** Для базы. */
    fun encode(): String = if (this == SunSignals()) "" else Json.write(toMap())

    companion object {
        /** Пустая или испорченная строка — выключено. */
        fun decode(text: String): SunSignals =
            if (text.isBlank()) SunSignals() else runCatching { fromMap(Json.parse(text) as Map<*, *>) }.getOrDefault(SunSignals())

        /** Чего нет или что не разобрать — по умолчанию. */
        fun fromMap(map: Map<*, *>): SunSignals = SunSignals(
            sunrise = signalFrom(map["sunrise"]),
            sunset = signalFrom(map["sunset"]),
            sound = AlarmSound.decode(map["sound"] as? String ?: "", map["soundTitle"] as? String ?: ""),
            vibrate = map["vibrate"] as? Boolean ?: true,
        )

        private fun signalMap(signal: SunSignal): Map<String, Any?> = linkedMapOf(
            "enabled" to signal.enabled,
            "moments" to signal.moments.map { linkedMapOf("minutes" to it.minutes, "kind" to it.kind.name) },
        )

        private fun signalFrom(value: Any?): SunSignal {
            val map = value as? Map<*, *> ?: return SunSignal()
            val moments = (map["moments"] as? List<*>)?.mapNotNull { item ->
                val moment = item as? Map<*, *> ?: return@mapNotNull null
                val minutes = (moment["minutes"] as? Number)?.toInt() ?: return@mapNotNull null
                SunMoment(minutes, SunSignalKind.entries.firstOrNull { it.name == moment["kind"] } ?: SunSignalKind.NOTIFICATION)
            }
            return SunSignal(map["enabled"] as? Boolean ?: false, moments?.let(SunSignal::normalize) ?: SunSignal.DEFAULT_MOMENTS)
        }
    }
}

/** Восход и закат в одну дату; null — в эту дату солнце не всходит или не заходит (полярные день и ночь). */
data class SunDay(val sunrise: Instant?, val sunset: Instant?) {
    fun of(event: SunEvent): Instant? = if (event == SunEvent.SUNRISE) sunrise else sunset
}

/** Когда восход и закат в точке — по высоте солнца [Sun.elevation]; точность — около минуты. */
object SunTimes {
    private const val STEP_SECONDS = 300L

    /**
     * Восход и закат в местные сутки [date] пояса [zone]: первый подъём центра солнца
     * выше [WorldClockRules.SUNRISE_ELEVATION] и первый спуск ниже, до целой минуты.
     */
    fun on(date: LocalDate, latitude: Double, longitude: Double, zone: ZoneId): SunDay {
        val start = date.atStartOfDay(zone).toEpochSecond()
        val end = date.plusDays(1).atStartOfDay(zone).toEpochSecond()
        var sunrise: Instant? = null
        var sunset: Instant? = null
        var from = start
        var wasUp = isUp(latitude, longitude, from)
        while (from < end && (sunrise == null || sunset == null)) {
            val to = minOf(from + STEP_SECONDS, end)
            val up = isUp(latitude, longitude, to)
            if (up != wasUp) {
                val at = crossing(latitude, longitude, from, to, wasUp)
                if (up && sunrise == null) sunrise = at
                if (!up && sunset == null) sunset = at
            }
            wasUp = up
            from = to
        }
        return SunDay(sunrise, sunset)
    }

    private fun isUp(latitude: Double, longitude: Double, second: Long): Boolean =
        Sun.elevation(latitude, longitude, Instant.ofEpochSecond(second)) > WorldClockRules.SUNRISE_ELEVATION

    /** Момент смены между [from] и [to] делением пополам — до секунды, потом до ближайшей минуты. */
    private fun crossing(latitude: Double, longitude: Double, from: Long, to: Long, wasUp: Boolean): Instant {
        var low = from
        var high = to
        while (high - low > 1) {
            val middle = (low + high) / 2
            if (isUp(latitude, longitude, middle) == wasUp) low = middle else high = middle
        }
        return Instant.ofEpochSecond(Math.floorDiv(high + 30, 60L) * 60)
    }
}

/** Сигнал пора подать в [at]: [kind] за [minutes] минут до события [event] в [eventAt]. */
data class SunFire(val at: Instant, val event: SunEvent, val eventAt: Instant, val minutes: Int, val kind: SunSignalKind)

/** Когда сигналить о восходе и закате (D-035). Чистые функции. */
object SunRules {
    /** Дальше не ищется: полярная ночь на полюсе — около полугода. */
    private const val SEARCH_DAYS = 370L

    /** Сигнал опоздал больше этого (телефон был выключен) — о прошедшем восходе не сообщать. */
    const val LATE_MILLIS: Long = 10 * 60_000L

    /**
     * Ближайшие сигналы строго позже [after] в точке и поясе [zone]: все, что приходятся
     * на один и тот же самый ранний момент. Пусто — сигналы выключены или солнце не
     * всходит и не заходит весь год.
     */
    fun next(signals: SunSignals, latitude: Double, longitude: Double, after: Instant, zone: ZoneId): List<SunFire> {
        if (!signals.enabled) return emptyList()
        val start = after.atZone(zone).toLocalDate()
        var found = emptyList<SunFire>()
        var foundOffset = -1L
        for (offset in 0L..SEARCH_DAYS) {
            // Сигнал завтрашнего события с большим «за сколько» бывает раньше сегодняшнего — день после найденного тоже смотрим.
            if (foundOffset >= 0 && offset > foundOffset + 1) break
            val day = SunTimes.on(start.plusDays(offset), latitude, longitude, zone)
            val fires = SunEvent.entries.flatMap { event ->
                val eventAt = day.of(event)
                val signal = signals.of(event)
                if (eventAt == null || !signal.active) {
                    emptyList()
                } else {
                    signal.moments.map { SunFire(eventAt.minusSeconds(it.minutes * 60L), event, eventAt, it.minutes, it.kind) }
                }
            }.filter { it.at.isAfter(after) }
            if (fires.isEmpty()) continue
            if (foundOffset < 0) foundOffset = offset
            found = found + fires
        }
        val earliest = found.minOfOrNull { it.at } ?: return emptyList()
        return found.filter { it.at == earliest }
    }

    /** Сигналы, назначенные ровно на [at]: настройки или место сменились — пусто. */
    fun dueAt(signals: SunSignals, latitude: Double, longitude: Double, at: Instant, zone: ZoneId): List<SunFire> =
        next(signals, latitude, longitude, at.minusMillis(1), zone).filter { it.at == at }

    /** Ближайшее событие [event] позже [after]; null — не наступит весь год. */
    fun nextEvent(event: SunEvent, latitude: Double, longitude: Double, after: Instant, zone: ZoneId): Instant? {
        val start = after.atZone(zone).toLocalDate()
        for (offset in 0L..SEARCH_DAYS) {
            val at = SunTimes.on(start.plusDays(offset), latitude, longitude, zone).of(event)
            if (at != null && at.isAfter(after)) return at
        }
        return null
    }

    fun isLate(at: Long, now: Long): Boolean = now - at > LATE_MILLIS
}

/**
 * Время будильника по солнцу (D-038): [event] со сдвигом [offsetMinutes] — меньше
 * нуля раньше события, больше — позже.
 */
data class SunTime(val event: SunEvent, val offsetMinutes: Int = 0) {
    init {
        require(offsetMinutes in -MAX_OFFSET..MAX_OFFSET) { "сдвиг $offsetMinutes" }
    }

    /** Время, которое пишется в файл и синхронизацию и действует, пока место неизвестно. */
    val nominal: LocalTime get() = if (event == SunEvent.SUNRISE) LocalTime.of(6, 0) else LocalTime.of(18, 0)

    companion object {
        /** Не дальше трёх часов от события. */
        const val MAX_OFFSET = 180
        val OFFSET_CHOICES = listOf(-120, -90, -60, -45, -30, -20, -15, -10, -5, 0, 5, 10, 15, 20, 30, 45, 60, 90, 120)
    }
}

/** Когда звонит будильник со временем по солнцу (D-038). Чистые функции. */
object SunAlarmRules {
    /** Как у обычного будильника: пропуски и исключения не закрывают годы. */
    private const val SEARCH_DAYS = 1500L

    /** У будильника время по солнцу, и повтор его допускает. */
    fun applies(alarm: Alarm): Boolean = alarm.sun != null && alarm.repeat !is Repeat.Cycle && alarm.repeat !is Repeat.Rules

    /**
     * Следующее срабатывание строго позже [after] в точке и поясе [zone]: первая
     * подходящая повтору дата, в которую событие есть (в полярные день и ночь его
     * нет), — событие плюс сдвиг. Пропуск и исключения — как у обычного будильника.
     * null — больше не сработает или время у будильника не по солнцу.
     */
    fun next(alarm: Alarm, latitude: Double, longitude: Double, after: Instant, zone: ZoneId, exceptions: Exceptions = Exceptions.NONE): Instant? {
        val sun = alarm.sun?.takeIf { applies(alarm) } ?: return null
        val repeating = alarm.repeat.isRepeating
        val start = after.atZone(zone).toLocalDate()
        for (offset in 0L..SEARCH_DAYS) {
            val date = start.plusDays(offset)
            if (alarm.repeat is Repeat.OnDate && date.isAfter(alarm.repeat.date)) return null
            if (repeating && alarm.honorExceptions && exceptions.excludes(date)) continue
            if (repeating && date == alarm.skipDate) continue
            if (alarm.repeat.timeOn(date, sun.nominal) == null) continue
            val eventAt = SunTimes.on(date, latitude, longitude, zone).of(sun.event) ?: continue
            val candidate = eventAt.plusSeconds(sun.offsetMinutes * 60L)
            if (candidate.isAfter(after)) return candidate
        }
        return null
    }

    /** Будильник с временем срабатывания [at] в полях часа и минуты — так его видят список и виджеты. */
    fun withTime(alarm: Alarm, at: Instant, zone: ZoneId): Alarm {
        val local = at.atZone(zone)
        return if (alarm.hour == local.hour && alarm.minute == local.minute) alarm else alarm.copy(hour = local.hour, minute = local.minute)
    }
}
