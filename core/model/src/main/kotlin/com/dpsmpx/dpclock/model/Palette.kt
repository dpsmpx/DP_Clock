package com.dpsmpx.dpclock.model

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Цвета Material 3 из любого цвета (D-021, п. 3): тональные палитры по светлоте
 * CIELAB, как тона Material, — оттенок от выбранного цвета, насыщенность
 * ограничена тем, что умеет экран (sRGB). Цвет — ARGB `Int`.
 */
object Palette {
    /** Готовые цвета в настройках (выбор автора — и любой свой на круге). */
    val PRESETS: List<Int> = listOf(
        0xFF00677D, 0xFF3F5AA9, 0xFF6750A4, 0xFF8E4585, 0xFFB3261E, 0xFFC4511C,
        0xFF8B6914, 0xFF4E7D2A, 0xFF1F7A5A, 0xFF5D6B73, 0xFF7A5C4F, 0xFF3D3D3D,
    ).map { it.toInt() }

    /** Тональная палитра: оттенок и насыщенность Lab; тон 0–100 — светлота L*. */
    class Tonal(private val hue: Double, private val chroma: Double) {
        fun tone(tone: Int): Int = fromLch(tone.toDouble(), chroma, hue)
    }

    /** Роли схемы Material 3, ARGB. */
    data class Scheme(
        val primary: Int, val onPrimary: Int, val primaryContainer: Int, val onPrimaryContainer: Int, val inversePrimary: Int,
        val secondary: Int, val onSecondary: Int, val secondaryContainer: Int, val onSecondaryContainer: Int,
        val tertiary: Int, val onTertiary: Int, val tertiaryContainer: Int, val onTertiaryContainer: Int,
        val error: Int, val onError: Int, val errorContainer: Int, val onErrorContainer: Int,
        val background: Int, val onBackground: Int,
        val surface: Int, val onSurface: Int, val surfaceVariant: Int, val onSurfaceVariant: Int,
        val inverseSurface: Int, val inverseOnSurface: Int, val outline: Int, val outlineVariant: Int, val scrim: Int,
        val surfaceDim: Int, val surfaceBright: Int,
        val surfaceContainerLowest: Int, val surfaceContainerLow: Int, val surfaceContainer: Int,
        val surfaceContainerHigh: Int, val surfaceContainerHighest: Int,
        /** «Fixed» — одинаковые в светлой и тёмной теме. */
        val primaryFixed: Int, val primaryFixedDim: Int, val onPrimaryFixed: Int, val onPrimaryFixedVariant: Int,
        val secondaryFixed: Int, val secondaryFixedDim: Int, val onSecondaryFixed: Int, val onSecondaryFixedVariant: Int,
        val tertiaryFixed: Int, val tertiaryFixedDim: Int, val onTertiaryFixed: Int, val onTertiaryFixedVariant: Int,
    )

    /** Схема из [seed]: светлая, тёмная или [black] — тёмная на чистом чёрном для AMOLED. */
    fun scheme(seed: Int, dark: Boolean, black: Boolean = false): Scheme {
        val (_, a, b) = toLab(seed)
        val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
        // Серый цвет даёт серую тему, яркий — не ярче, чем читается текст.
        val chroma = sqrt(a * a + b * b).coerceIn(6.0, 64.0)
        val p = Tonal(hue, chroma)
        val s = Tonal(hue, chroma / 2.6)
        val t = Tonal((hue + 60) % 360, chroma / 1.6)
        val n = Tonal(hue, min(5.0, chroma / 8))
        val nv = Tonal(hue, min(10.0, chroma / 5))
        val e = Tonal(30.0, 70.0)
        val fixed = listOf(
            p.tone(90), p.tone(80), p.tone(10), p.tone(30),
            s.tone(90), s.tone(80), s.tone(10), s.tone(30),
            t.tone(90), t.tone(80), t.tone(10), t.tone(30),
        )
        return if (!dark) {
            Scheme(
                p.tone(40), p.tone(100), p.tone(90), p.tone(10), p.tone(80),
                s.tone(40), s.tone(100), s.tone(90), s.tone(10),
                t.tone(40), t.tone(100), t.tone(90), t.tone(10),
                e.tone(40), e.tone(100), e.tone(90), e.tone(10),
                n.tone(98), n.tone(10),
                n.tone(98), n.tone(10), nv.tone(90), nv.tone(30),
                n.tone(20), n.tone(95), nv.tone(50), nv.tone(80), BLACK,
                n.tone(87), n.tone(98),
                n.tone(100), n.tone(96), n.tone(94), n.tone(92), n.tone(90),
                fixed[0], fixed[1], fixed[2], fixed[3], fixed[4], fixed[5], fixed[6], fixed[7], fixed[8], fixed[9], fixed[10], fixed[11],
            )
        } else {
            val base = Scheme(
                p.tone(80), p.tone(20), p.tone(30), p.tone(90), p.tone(40),
                s.tone(80), s.tone(20), s.tone(30), s.tone(90),
                t.tone(80), t.tone(20), t.tone(30), t.tone(90),
                e.tone(80), e.tone(20), e.tone(30), e.tone(90),
                n.tone(6), n.tone(90),
                n.tone(6), n.tone(90), nv.tone(30), nv.tone(80),
                n.tone(90), n.tone(20), nv.tone(60), nv.tone(30), BLACK,
                n.tone(6), n.tone(24),
                n.tone(4), n.tone(10), n.tone(12), n.tone(17), n.tone(22),
                fixed[0], fixed[1], fixed[2], fixed[3], fixed[4], fixed[5], fixed[6], fixed[7], fixed[8], fixed[9], fixed[10], fixed[11],
            )
            if (black) black(base, n) else base
        }
    }

    /** Чистый чёрный фон: пиксели OLED не светятся; карточки — чуть светлее, чтобы их было видно. */
    private fun black(scheme: Scheme, n: Tonal): Scheme = scheme.copy(
        background = BLACK,
        surface = BLACK,
        surfaceDim = BLACK,
        surfaceContainerLowest = BLACK,
        surfaceContainerLow = n.tone(4),
        surfaceContainer = n.tone(7),
        surfaceContainerHigh = n.tone(11),
        surfaceContainerHighest = n.tone(15),
        surfaceBright = n.tone(18),
    )

    /** Контраст по WCAG 2: от 1 до 21; тексту нужно 4,5 и больше. */
    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** Цвет из оттенка 0–360 и насыщенности 0–1 — для круга выбора; светлота средняя. */
    fun fromWheel(hue: Float, saturation: Float): Int =
        fromLch(52.0, 8.0 + 72.0 * saturation.coerceIn(0f, 1f), hue.toDouble().mod(360.0))

    /** Оттенок и насыщенность цвета для круга выбора — обратно к [fromWheel]. */
    fun toWheel(color: Int): Pair<Float, Float> {
        val (_, a, b) = toLab(color)
        val hue = Math.toDegrees(atan2(b, a)).let { if (it < 0) it + 360 else it }
        return hue.toFloat() to ((sqrt(a * a + b * b) - 8.0) / 72.0).coerceIn(0.0, 1.0).toFloat()
    }

    private const val BLACK = 0xFF000000.toInt()

    // Преобразования: sRGB ↔ линейный RGB ↔ XYZ (D65) ↔ Lab.

    private fun luminance(color: Int): Double {
        val (r, g, b) = linear(color)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun linear(color: Int): Triple<Double, Double, Double> {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return Triple(ch(color shr 16 and 0xFF), ch(color shr 8 and 0xFF), ch(color and 0xFF))
    }

    private fun toLab(color: Int): Triple<Double, Double, Double> {
        val (r, g, b) = linear(color)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / WHITE_X
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / WHITE_Z
        fun f(t: Double) = if (t > EPSILON) cbrt(t) else (KAPPA * t + 16) / 116
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** Цвет LCh; насыщенность уменьшается, пока цвет не влезет в sRGB. */
    private fun fromLch(lightness: Double, chroma: Double, hue: Double): Int {
        val l = lightness.coerceIn(0.0, 100.0)
        val h = Math.toRadians(hue)
        var low = 0.0
        var high = chroma
        if (inGamut(l, high * cos(h), high * sin(h))) return rgb(l, high * cos(h), high * sin(h))
        repeat(16) {
            val mid = (low + high) / 2
            if (inGamut(l, mid * cos(h), mid * sin(h))) low = mid else high = mid
        }
        return rgb(l, low * cos(h), low * sin(h))
    }

    private fun linearRgb(l: Double, a: Double, b: Double): Triple<Double, Double, Double> {
        val fy = (l + 16) / 116
        val fx = fy + a / 500
        val fz = fy - b / 200
        fun inv(f: Double): Double = if (f * f * f > EPSILON) f * f * f else (116 * f - 16) / KAPPA
        val x = inv(fx) * WHITE_X
        val y = if (l > KAPPA * EPSILON) fy * fy * fy else l / KAPPA
        val z = inv(fz) * WHITE_Z
        return Triple(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
    }

    private fun inGamut(l: Double, a: Double, b: Double): Boolean {
        val (r, g, bl) = linearRgb(l, a, b)
        val slack = 1e-4
        return r in -slack..1 + slack && g in -slack..1 + slack && bl in -slack..1 + slack
    }

    private fun rgb(l: Double, a: Double, b: Double): Int {
        val (r, g, bl) = linearRgb(l, a, b)
        fun ch(v: Double): Int {
            val c = v.coerceIn(0.0, 1.0)
            val s = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055
            return (s * 255).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(bl)
    }

    private const val WHITE_X = 0.95047
    private const val WHITE_Z = 1.08883
    private const val EPSILON = 216.0 / 24389
    private const val KAPPA = 24389.0 / 27
}
