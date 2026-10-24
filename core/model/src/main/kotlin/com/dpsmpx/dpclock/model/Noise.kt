package com.dpsmpx.dpclock.model

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Шум для ночных часов (D-021, п. 5): считается кодом, файлов нет. Круг в
 * несколько секунд, конец плавно переходит в начало — повтор не щёлкает.
 * - белый — ровный по всем частотам, шипящий;
 * - розовый — тише к высоким (−3 дБ на октаву), мягче, как дождь;
 * - коричневый — ещё глуше (−6 дБ на октаву), как прибой или гул.
 */
object Noise {
    const val SAMPLE_RATE = 44_100
    const val SECONDS = 8
    private const val CROSSFADE_SECONDS = 0.5

    /** Пик — половина шкалы: шум не громче голоса и без искажений. */
    private const val PEAK = 0.5

    fun render(kind: NoiseKind, sampleRate: Int = SAMPLE_RATE, seconds: Int = SECONDS, seed: Int = 7): ShortArray {
        require(kind != NoiseKind.NONE) { "шум не выбран" }
        val fade = (sampleRate * CROSSFADE_SECONDS).roundToInt()
        val total = sampleRate * seconds
        val raw = DoubleArray(total + fade)
        val random = Random(seed)
        // Розовый — фильтр Пола Келлета, коричневый — белый с утечкой интеграла.
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        var brown = 0.0
        for (i in raw.indices) {
            val white = random.nextDouble() * 2 - 1
            raw[i] = when (kind) {
                NoiseKind.WHITE -> white
                NoiseKind.PINK -> {
                    b0 = 0.99886 * b0 + white * 0.0555179
                    b1 = 0.99332 * b1 + white * 0.0750759
                    b2 = 0.96900 * b2 + white * 0.1538520
                    b3 = 0.86650 * b3 + white * 0.3104856
                    b4 = 0.55000 * b4 + white * 0.5329522
                    b5 = -0.7616 * b5 - white * 0.0168980
                    val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
                    b6 = white * 0.115926
                    pink
                }
                NoiseKind.BROWN -> {
                    brown = (brown + 0.02 * white) / 1.02
                    brown
                }
                NoiseKind.NONE -> 0.0
            }
        }
        // Хвост круга смешивается с его началом: последний отсчёт переходит в первый.
        val looped = DoubleArray(total) { raw[it] }
        for (i in 0 until fade) {
            val t = i.toDouble() / fade
            looped[i] = raw[total + i] * (1 - t) + raw[i] * t
        }
        val mean = looped.average()
        val peak = looped.maxOf { abs(it - mean) }.coerceAtLeast(1e-9)
        val gain = PEAK * Short.MAX_VALUE / peak
        return ShortArray(total) { ((looped[it] - mean) * gain).roundToInt().toShort() }
    }
}
