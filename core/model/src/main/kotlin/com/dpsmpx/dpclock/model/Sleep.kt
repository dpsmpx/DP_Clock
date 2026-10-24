package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt

/** Как считается время ложиться (D-017, п. 2; выбор автора — оба способа). */
enum class BedtimeMode {
    OFF,

    /** От ближайшего будильника: будильник минус [Bedtime.sleepMinutes]. */
    FROM_ALARM,

    /** По своему расписанию: время и дни недели. */
    SCHEDULE,
}

/** Время сна (D-007, п. 5; D-017, п. 2). */
data class Bedtime(
    val mode: BedtimeMode = BedtimeMode.OFF,
    /** Сколько хочется спать. */
    val sleepMinutes: Int = 8 * 60,
    /** Расписание: во сколько ложиться и по каким дням. */
    val hour: Int = 23,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    /** Напомнить за столько минут до времени ложиться; 0 — в само время. */
    val leadMinutes: Int = 15,
) {
    init {
        require(sleepMinutes in 60..16 * 60) { "сон $sleepMinutes" }
        require(hour in 0..23 && minute in 0..59) { "время $hour:$minute" }
        require(leadMinutes >= 0) { "заранее $leadMinutes" }
    }

    companion object {
        val SLEEP_CHOICES = (12..20).map { it * 30 }
        val LEAD_CHOICES = listOf(0, 15, 30, 60)
    }
}

/** Напоминание ложиться: когда напомнить, когда ложиться и будильник, от которого посчитано. */
data class BedtimeReminder(val remindAt: Instant, val bedAt: Instant, val alarmAt: Instant?)

/**
 * Что сделать после выключения будильника (D-007, п. 5; D-017, п. 5; выбор
 * автора — все три): запустить пресет таймера, напомнить текстом, открыть приложение.
 */
data class AfterAlarm(
    val presetId: String? = null,
    val reminder: String = "",
    /** Напоминание через столько минут; 0 — сразу. */
    val reminderMinutes: Int = 0,
    /** Пакет приложения, которое открыть. */
    val app: String = "",
) {
    val isEmpty: Boolean get() = presetId == null && reminder.isBlank() && app.isEmpty()

    companion object {
        const val MAX_REMINDER = 80
        val REMINDER_DELAYS = listOf(0, 5, 10, 15, 30, 60)
    }
}

/** Цвет и яркость экрана мягкого пробуждения. Каналы — 0..255. */
data class Sunrise(val brightness: Float, val red: Int, val green: Int, val blue: Int)

/** Правила сна и пробуждения (D-017) — чистые функции. */
object SleepRules {
    /** Минут на засыпание: в «если лечь сейчас» сон начинается не сразу. */
    const val FALL_ASLEEP_MINUTES = 15

    /** Мягкое пробуждение: за столько минут до будильника; 0 — выключено. */
    val GENTLE_CHOICES = listOf(0, 10, 15, 20, 30)

    /** Тихий звук — последние столько минут мягкого пробуждения (D-017, п. 3). */
    const val GENTLE_SOUND_MINUTES = 3

    /**
     * Следующее напоминание ложиться позже [now]. От будильника — от [nearestAlarm];
     * его нет или время уже прошло — напоминания нет.
     */
    fun nextReminder(bedtime: Bedtime, nearestAlarm: Instant?, now: Instant, zone: ZoneId): BedtimeReminder? {
        val lead = bedtime.leadMinutes * 60_000L
        return when (bedtime.mode) {
            BedtimeMode.OFF -> null
            BedtimeMode.FROM_ALARM -> {
                val alarm = nearestAlarm ?: return null
                val bed = alarm.minusMillis(bedtime.sleepMinutes * 60_000L)
                val remind = bed.minusMillis(lead)
                if (remind.isAfter(now)) BedtimeReminder(remind, bed, alarm) else null
            }
            BedtimeMode.SCHEDULE -> {
                if (bedtime.days.isEmpty()) return null
                // Ближайшее время ложиться, до которого ещё можно напомнить.
                val bed = AlarmRules.nextTrigger(bedtime.hour, bedtime.minute, Repeat.Weekly(bedtime.days), now.plusMillis(lead), zone)
                    ?: return null
                BedtimeReminder(bed.minusMillis(lead), bed, null)
            }
        }
    }

    /** Сколько удастся поспать, если лечь в [now], до будильника [alarmAt]; 0 — некогда. */
    fun sleepIfNow(now: Instant, alarmAt: Instant): Long =
        (alarmAt.toEpochMilli() - now.toEpochMilli() - FALL_ASLEEP_MINUTES * 60_000L).coerceAtLeast(0L)

    /**
     * Рассвет на экране: [progress] от 0 (начало) до 1 (будильник). Цвет — от
     * тёмно-красного через оранжевый к тёплому белому, яркость — по степени:
     * в начале почти темно, свет прибывает к концу.
     */
    fun sunrise(progress: Double): Sunrise {
        val p = progress.coerceIn(0.0, 1.0)
        fun mix(from: Int, to: Int, t: Double) = (from + (to - from) * t).roundToInt()
        val (r, g, b) = if (p < 0.5) {
            val t = p / 0.5
            Triple(mix(40, 255, t), mix(0, 110, t), mix(0, 20, t))
        } else {
            val t = (p - 0.5) / 0.5
            Triple(255, mix(110, 236, t), mix(20, 200, t))
        }
        return Sunrise(brightness = (0.01 + 0.99 * p.pow(2.2)).toFloat(), red = r, green = g, blue = b)
    }
}
