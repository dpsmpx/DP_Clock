package com.dpsmpx.dpclock.model

import java.util.Locale

/**
 * Ввод длительности клавиатурой 0–9 (D-010, п. 4): цифры заполняют ЧЧ:ММ:СС
 * справа налево, как на микроволновке. «90» — это 1 мин 30 с.
 */
data class DurationInput(val digits: String = "") {
    init {
        require(digits.length <= MAX_DIGITS && digits.all { it in '0'..'9' }) { "цифры «$digits»" }
    }

    /** Часы, минуты, секунды — как набраны, без переноса через 60. */
    val hours: Int get() = padded.substring(0, 2).toInt()
    val minutes: Int get() = padded.substring(2, 4).toInt()
    val seconds: Int get() = padded.substring(4, 6).toInt()

    private val padded: String get() = digits.padStart(MAX_DIGITS, '0')

    val millis: Long get() = ((hours * 60L + minutes) * 60L + seconds) * 1000L

    val isEmpty: Boolean get() = millis == 0L

    fun press(digit: Int): DurationInput {
        require(digit in 0..9)
        if (digits.isEmpty() && digit == 0) return this
        if (digits.length == MAX_DIGITS) return this
        return DurationInput(digits + digit)
    }

    fun backspace(): DurationInput = DurationInput(digits.dropLast(1))

    /** Быстрые кнопки «+1/+5/+10 мин»: длительность нормализуется в ЧЧ:ММ:СС. */
    fun plusMinutes(minutes: Int): DurationInput = ofMillis(millis + minutes * 60_000L)

    companion object {
        const val MAX_DIGITS = 6
        private const val MAX_MILLIS = ((99 * 60L + 59) * 60L + 59) * 1000L

        fun ofMillis(millis: Long): DurationInput {
            val totalSeconds = millis.coerceIn(0L, MAX_MILLIS) / 1000L
            // Locale.ROOT: цифры — ASCII при любом языке системы.
            val text = String.format(Locale.ROOT, "%02d%02d%02d", totalSeconds / 3600, totalSeconds / 60 % 60, totalSeconds % 60)
            return DurationInput(text.trimStart('0'))
        }
    }
}

/** Части длительности для показа ЧЧ:ММ:СС (или ММ:СС, когда часов нет). */
data class Hms(val hours: Long, val minutes: Long, val seconds: Long, val centis: Long) {
    companion object {
        fun of(millis: Long): Hms {
            val m = millis.coerceAtLeast(0L)
            return Hms(m / 3_600_000L, m / 60_000L % 60, m / 1000L % 60, m / 10L % 100)
        }

        /** Для обратного отсчёта: секунды вверх — «0:01» до самого конца, «0:00» — только в конце. */
        fun ofCountdown(millis: Long): Hms = of(Math.ceilDiv(millis.coerceAtLeast(0L), 1000L) * 1000L)
    }
}
