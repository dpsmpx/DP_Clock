package com.dpsmpx.dpclock.platform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Собственные звуки DP_Clock (D-013, п. 2): мелодии из нот, синтезированные
 * кодом, — файлов и чужих записей нет (D-003). Звук получается из памяти,
 * поэтому звучит и до первой разблокировки после перезагрузки.
 */
object Synth {
    const val SAMPLE_RATE = 22_050

    /** Тембр ноты: обертоны (кратность частоты и громкость) и огибающая. */
    enum class Timbre(val partials: List<Pair<Double, Double>>, val decay: Double?, val attack: Double) {
        /** Колокольчик: негармонические обертоны, долгое затухание. */
        BELL(listOf(1.0 to 1.0, 2.0 to 0.5, 2.76 to 0.3, 5.4 to 0.12), decay = 0.9, attack = 0.002),

        /** Деревянная пластина: короткий яркий удар. */
        MARIMBA(listOf(1.0 to 1.0, 4.0 to 0.25, 10.0 to 0.06), decay = 0.28, attack = 0.002),

        /** Пищалка электронных часов: нечётные гармоники, ровная громкость. */
        BEEP(listOf(1.0 to 1.0, 3.0 to 0.33, 5.0 to 0.2), decay = null, attack = 0.004),

        /** Мягкий тон: основной и немного второй гармоники, плавное вступление. */
        SOFT(listOf(1.0 to 1.0, 2.0 to 0.15), decay = 1.6, attack = 0.06),
    }

    data class Note(val start: Double, val hz: Double, val length: Double, val timbre: Timbre, val gain: Double = 1.0)

    /** Звук DP_Clock: ноты одного круга длиной [loop] с; круг повторяется без шва. */
    data class Builtin(val id: String, val loop: Double, val notes: List<Note>)

    private fun arpeggio(timbre: Timbre, step: Double, length: Double, vararg hz: Double): List<Note> =
        hz.mapIndexed { i, f -> Note(i * step, f, length, timbre) }

    /** Каталог; порядок — порядок в списке выбора. id хранится в базе и не меняется. */
    val catalog: List<Builtin> = listOf(
        // До-ми-соль-до колокольчиками.
        Builtin("chime", 2.6, arpeggio(Timbre.BELL, 0.28, 1.6, 1046.5, 1318.5, 1568.0, 2093.0)),
        // Ля-мажор, мягко и вверх — для спокойного пробуждения.
        Builtin("dawn", 3.4, arpeggio(Timbre.SOFT, 0.42, 1.2, 440.0, 554.4, 659.3, 880.0)),
        // Перекличка пластин.
        Builtin(
            "marimba",
            2.2,
            arpeggio(Timbre.MARIMBA, 0.18, 0.5, 784.0, 659.3, 523.3, 659.3, 784.0, 1046.5) +
                Note(1.08, 1046.5, 0.5, Timbre.MARIMBA, 0.7),
        ),
        // Четыре писка и пауза — как у электронных часов.
        Builtin("classic", 1.2, (0 until 4).map { Note(it * 0.15, 1975.5, 0.08, Timbre.BEEP, 0.6) }),
        // Два тона попеременно, настойчиво.
        Builtin("pulse", 1.0, listOf(Note(0.0, 880.0, 0.22, Timbre.SOFT), Note(0.5, 1318.5, 0.22, Timbre.SOFT))),
    )

    val DEFAULT_ID = catalog.first().id

    fun find(id: String): Builtin = catalog.firstOrNull { it.id == id } ?: catalog.first()

    /** Один круг звука: 16 бит, моно, [SAMPLE_RATE]. Хвосты нот за концом круга переносятся в начало. */
    fun render(sound: Builtin): ShortArray {
        val frames = (sound.loop * SAMPLE_RATE).roundToInt()
        val mix = DoubleArray(frames)
        val nyquist = SAMPLE_RATE / 2.0
        for (note in sound.notes) {
            val first = (note.start * SAMPLE_RATE).roundToInt()
            val count = (note.length * SAMPLE_RATE).roundToInt()
            // Обертоны выше половины частоты дискретизации дали бы ложные тоны.
            val partials = note.timbre.partials.filter { (ratio, _) -> note.hz * ratio < nyquist * 0.9 }
            for (n in 0 until count) {
                val t = n.toDouble() / SAMPLE_RATE
                val envelope = envelope(note, t)
                var sample = 0.0
                for ((ratio, amp) in partials) {
                    // Высокие обертоны затухают быстрее — так звучат настоящие колокола и пластины.
                    val partialDecay = note.timbre.decay?.let { exp(-t * (ratio - 1.0) * 0.8 / it) } ?: 1.0
                    sample += amp * partialDecay * sin(2 * PI * note.hz * ratio * t)
                }
                mix[(first + n) % frames] += sample * envelope * note.gain
            }
        }
        val peak = mix.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = 0.89 * Short.MAX_VALUE / peak
        return ShortArray(frames) { (mix[it] * scale).roundToInt().toShort() }
    }

    /**
     * Бой курантов (D-025, п. 4): [count] ударов низкого колокола через [step] с и
     * затухание [tail] с после последнего. Не круг — звучит один раз.
     */
    fun strikes(count: Int, step: Double, tail: Double): ShortArray {
        val notes = (0 until count).map { Note(it * step, 523.3, step + tail, Timbre.BELL) }
        // Круг длиннее всех нот: хвостам переноситься некуда.
        return render(Builtin("strikes", (count - 1) * step + step + tail + 0.05, notes))
    }

    private fun envelope(note: Note, t: Double): Double {
        val attack = min(1.0, t / note.timbre.attack)
        // Короткий спад в конце ноты убирает щелчок.
        val release = min(1.0, (note.length - t) / 0.01).coerceAtLeast(0.0)
        val decay = note.timbre.decay?.let { exp(-t / it) } ?: 1.0
        return attack * decay * release
    }
}
