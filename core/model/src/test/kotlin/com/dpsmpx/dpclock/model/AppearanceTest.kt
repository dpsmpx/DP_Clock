package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AppearanceTest {
    private val seeds = Palette.PRESETS + listOf(0xFFFFEB3B, 0xFF00FF00, 0xFF0000FF, 0xFFFF00FF, 0xFF808080, 0xFFFFFFFF, 0xFF000000).map { it.toInt() }

    @Test
    fun textIsReadableOnEverySchemeFromAnyColor() {
        for (seed in seeds) {
            for ((dark, black) in listOf(false to false, true to false, true to true)) {
                val s = Palette.scheme(seed, dark, black)
                val where = "цвет #${Integer.toHexString(seed)}, тёмная $dark, чёрная $black"
                for ((name, fg, bg) in listOf(
                    Triple("onPrimary", s.onPrimary, s.primary),
                    Triple("onPrimaryContainer", s.onPrimaryContainer, s.primaryContainer),
                    Triple("onSecondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
                    Triple("onTertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
                    Triple("onSurface", s.onSurface, s.surface),
                    Triple("onSurfaceVariant", s.onSurfaceVariant, s.surfaceContainerHighest),
                    Triple("onError", s.onError, s.error),
                )) {
                    assertTrue("$name: ${Palette.contrast(fg, bg)} — $where", Palette.contrast(fg, bg) >= 4.5)
                }
                // Выбранный цвет и правда главный: оттенок кнопок совпадает с ним.
                assertEquals(0xFF, s.primary ushr 24)
            }
        }
    }

    @Test
    fun blackThemeIsPureBlack() {
        val s = Palette.scheme(Palette.PRESETS[0], dark = true, black = true)
        assertEquals(0xFF000000.toInt(), s.background)
        assertEquals(0xFF000000.toInt(), s.surface)
        assertTrue(s.surfaceContainer != s.surface)
    }

    @Test
    fun wheelRoundTrips() {
        for (hue in listOf(0f, 45f, 120f, 210f, 300f)) {
            val (h, sat) = Palette.toWheel(Palette.fromWheel(hue, 0.6f))
            assertTrue("оттенок $hue → $h", abs(((h - hue + 540f) % 360f) - 180f) < 6f)
            assertTrue("насыщенность → $sat", sat > 0.3f)
        }
    }

    @Test
    fun sevenSegmentDigits() {
        assertEquals(0b0111111, SevenSegment.segments('0'))
        assertEquals(0b0000110, SevenSegment.segments('1'))
        assertEquals(0b1111111, SevenSegment.segments('8'))
        assertEquals(0, SevenSegment.segments(':'))
        // У «7» горит верх, но не середина.
        assertTrue(SevenSegment.lit('7', 0))
        assertTrue(!SevenSegment.lit('7', 6))
        // Каждая цифра отличается от других.
        assertEquals(10, ('0'..'9').map { SevenSegment.segments(it) }.toSet().size)
    }

    @Test
    fun noiseColorsDifferInTone() {
        fun highShare(kind: NoiseKind): Double {
            val pcm = Noise.render(kind, sampleRate = 8000, seconds = 2)
            var total = 0.0
            var high = 0.0
            for (i in 1 until pcm.size) {
                total += pcm[i].toDouble() * pcm[i]
                val d = pcm[i].toDouble() - pcm[i - 1]
                high += d * d
            }
            return high / total
        }
        // Доля высоких: у белого больше всех, у коричневого меньше всех.
        val white = highShare(NoiseKind.WHITE)
        val pink = highShare(NoiseKind.PINK)
        val brown = highShare(NoiseKind.BROWN)
        assertTrue("белый $white > розовый $pink", white > pink)
        assertTrue("розовый $pink > коричневый $brown", pink > brown)
    }

    @Test
    fun noiseLoopsWithoutAClickAndWithoutClipping() {
        for (kind in listOf(NoiseKind.WHITE, NoiseKind.PINK, NoiseKind.BROWN)) {
            val pcm = Noise.render(kind, sampleRate = 8000, seconds = 2)
            assertEquals(16_000, pcm.size)
            assertTrue("$kind: пик ${pcm.maxOf { abs(it.toInt()) }}", pcm.maxOf { abs(it.toInt()) } <= Short.MAX_VALUE / 2 + 1)
            if (kind == NoiseKind.BROWN) {
                // Коричневый меняется плавно — и на стыке круга тоже: без перехода стык был бы в разы больше любого шага.
                val largest = (1 until pcm.size).maxOf { abs(pcm[it] - pcm[it - 1]) }
                assertTrue("стык ${abs(pcm.first() - pcm.last())}, самый большой шаг $largest", abs(pcm.first() - pcm.last()) <= largest)
            }
        }
    }
}
