package com.dpsmpx.dpclock.ui

import android.provider.Settings
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import com.dpsmpx.dpclock.model.Intensity
import com.dpsmpx.dpclock.model.Precipitation
import com.dpsmpx.dpclock.model.Sky
import com.dpsmpx.dpclock.model.WeatherSymbol
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Кадр — не чаще ~30 в секунду: движение медленное, больше не нужно. */
private const val FRAME_NANOS = 33_000_000L

private const val MAX_PARTICLES = 80

/** Случайные, но постоянные для экрана места частиц: каждый кадр рисуется заново по времени, без накопления. */
private class Particles(seed: Int) {
    private val random = Random(seed)
    val x = FloatArray(MAX_PARTICLES) { random.nextFloat() }
    val y = FloatArray(MAX_PARTICLES) { random.nextFloat() }
    val speed = FloatArray(MAX_PARTICLES) { random.nextFloat() }
    val size = FloatArray(MAX_PARTICLES) { random.nextFloat() }
    val phase = FloatArray(MAX_PARTICLES) { random.nextFloat() }
}

private class Palette(val top: Color, val bottom: Color)

/**
 * Тихий фон погоды (D-019, п. 5; выбор автора): небо по погоде и времени суток,
 * медленные облака, косые штрихи дождя, редкие снежинки, мягкое солнце, ночью —
 * звёзды и луна. [animate] — экран виден; с выключенными анимациями системы
 * картинка стоит, если это не выбранный человеком фон приложения ([chosen], D-027).
 */
@Composable
fun WeatherSky(symbol: WeatherSymbol, animate: Boolean, modifier: Modifier = Modifier, chosen: Boolean = false) {
    val context = LocalContext.current
    val still = remember {
        !chosen && Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val time = remember { mutableFloatStateOf(0f) }
    if (animate && !still) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it } - (time.floatValue * 1e9f).toLong()
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= FRAME_NANOS) {
                        last = now
                        time.floatValue = (now - start) / 1e9f
                    }
                }
            }
        }
    }
    val particles = remember { Particles(seed = 11) }
    Spacer(
        modifier.drawWithCache {
            val palette = palette(symbol)
            val background = Brush.verticalGradient(listOf(palette.top, palette.bottom))
            // Снизу темнее: белый текст читается на любом небе.
            val scrim = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.35f))
            val moonCenter = Offset(size.width * 0.8f, size.height * 0.28f)
            val moonRadius = size.height * 0.09f
            val moon = Path().apply {
                op(
                    Path().apply { addOval(androidx.compose.ui.geometry.Rect(moonCenter, moonRadius)) },
                    Path().apply { addOval(androidx.compose.ui.geometry.Rect(moonCenter + Offset(moonRadius * 0.55f, -moonRadius * 0.3f), moonRadius)) },
                    PathOperation.Difference,
                )
            }
            onDrawBehind {
                val t = time.floatValue
                drawRect(background)
                val clearish = symbol.sky == Sky.CLEAR || symbol.sky == Sky.FAIR || symbol.sky == Sky.PARTLY_CLOUDY
                if (symbol.night) {
                    if (clearish && symbol.precipitation == Precipitation.NONE) {
                        stars(particles, t)
                        drawPath(moon, Color(0xFFF1EEDF).copy(alpha = 0.9f))
                    }
                } else if (clearish && symbol.precipitation == Precipitation.NONE) {
                    sun(t)
                }
                clouds(particles, t, symbol)
                if (symbol.sky == Sky.FOG) fog(t)
                when (symbol.precipitation) {
                    Precipitation.RAIN -> rain(particles, t, count(symbol.intensity, 22, 42, 70), symbol.night)
                    Precipitation.SNOW -> snow(particles, t, count(symbol.intensity, 18, 32, 52))
                    Precipitation.SLEET -> {
                        rain(particles, t, count(symbol.intensity, 12, 22, 36), symbol.night)
                        snow(particles, t, count(symbol.intensity, 9, 16, 26), offset = 40)
                    }
                    Precipitation.NONE -> Unit
                }
                if (symbol.thunder) flash(t)
                drawRect(scrim)
            }
        },
    )
}

private fun count(intensity: Intensity, light: Int, moderate: Int, heavy: Int): Int = when (intensity) {
    Intensity.LIGHT -> light
    Intensity.MODERATE -> moderate
    Intensity.HEAVY -> heavy
}

private fun palette(symbol: WeatherSymbol): Palette {
    val dull = symbol.precipitation != Precipitation.NONE || symbol.sky == Sky.CLOUDY
    return when {
        symbol.night && symbol.thunder -> Palette(Color(0xFF10151D), Color(0xFF242C38))
        symbol.night && !dull && symbol.sky != Sky.FOG -> Palette(Color(0xFF0B1630), Color(0xFF213158))
        symbol.night -> Palette(Color(0xFF161D2A), Color(0xFF2C3646))
        symbol.thunder -> Palette(Color(0xFF39424F), Color(0xFF5B6573))
        dull -> Palette(Color(0xFF55657A), Color(0xFF8593A3))
        symbol.sky == Sky.FOG -> Palette(Color(0xFF75828F), Color(0xFFA2ACB6))
        symbol.sky == Sky.PARTLY_CLOUDY -> Palette(Color(0xFF3F75AD), Color(0xFF7FA3C8))
        else -> Palette(Color(0xFF2F6CB0), Color(0xFF64A0D8))
    }
}

private fun wave(t: Float, period: Float, phase: Float = 0f): Float = sin((t / period + phase) * 2 * PI.toFloat())

/** Солнце — мягкое свечение, чуть дышит раз в 10 с. */
private fun DrawScope.sun(t: Float) {
    val center = Offset(size.width * 0.8f, size.height * 0.3f)
    val glow = size.height * 0.5f * (1f + 0.03f * wave(t, 10f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE9A8).copy(alpha = 0.55f), Color.Transparent), center, glow),
        glow,
        center,
    )
    drawCircle(Color(0xFFFFF1C4).copy(alpha = 0.95f), size.height * 0.11f, center)
}

/** Звёзды мерцают каждая в своём ритме. */
private fun DrawScope.stars(p: Particles, t: Float) {
    val unit = size.height / 200f
    for (i in 0 until 22) {
        val alpha = 0.25f + 0.45f * (0.5f + 0.5f * wave(t, 3f + 4f * p.speed[i], p.phase[i]))
        drawCircle(Color.White.copy(alpha = alpha), unit * (1f + p.size[i]), Offset(p.x[i] * size.width, p.y[i] * size.height * 0.7f))
    }
}

/** Облака плывут слева направо за полторы-три минуты и возвращаются из-за края. */
private fun DrawScope.clouds(p: Particles, t: Float, symbol: WeatherSymbol) {
    val count = when {
        symbol.thunder -> 4
        symbol.precipitation != Precipitation.NONE || symbol.sky == Sky.CLOUDY || symbol.sky == Sky.FOG -> 3
        symbol.sky == Sky.PARTLY_CLOUDY -> 2
        symbol.sky == Sky.FAIR -> 1
        else -> 0
    }
    val color = when {
        symbol.night -> Color(0xFFB8C2D6).copy(alpha = 0.14f)
        symbol.precipitation != Precipitation.NONE || symbol.thunder -> Color(0xFFDDE3EA).copy(alpha = 0.42f)
        else -> Color.White.copy(alpha = 0.5f)
    }
    for (i in 0 until count) {
        val k = i + 50
        val width = size.width * (0.32f + 0.18f * p.size[k])
        val span = size.width + width
        val x = ((p.x[k] * span + t * size.width / (100f + 80f * p.speed[k])) % span) - width
        val y = size.height * (0.14f + 0.36f * p.y[k])
        drawCircle(color, width * 0.22f, Offset(x + width * 0.3f, y))
        drawCircle(color, width * 0.28f, Offset(x + width * 0.55f, y - width * 0.08f))
        drawCircle(color, width * 0.2f, Offset(x + width * 0.78f, y))
        drawRoundRect(color, Offset(x + width * 0.1f, y), Size(width * 0.85f, width * 0.18f), CornerRadius(width * 0.09f))
    }
}

/** Дождь — тонкие косые штрихи, падают за полторы-две секунды. */
private fun DrawScope.rain(p: Particles, t: Float, count: Int, night: Boolean) {
    val unit = size.height / 200f
    val color = Color.White.copy(alpha = if (night) 0.22f else 0.32f)
    for (i in 0 until count.coerceAtMost(MAX_PARTICLES)) {
        val length = unit * (9f + 6f * p.size[i])
        val fall = (p.y[i] + t * (0.5f + 0.3f * p.speed[i])) % 1.2f - 0.1f
        val y = fall * size.height
        val x = p.x[i] * size.width * 1.15f - 0.15f * y
        drawLine(color, Offset(x, y), Offset(x - length * 0.15f, y + length), strokeWidth = unit * 1.1f, cap = StrokeCap.Round)
    }
}

/** Снег — редкие хлопья, медленно и чуть покачиваясь. */
private fun DrawScope.snow(p: Particles, t: Float, count: Int, offset: Int = 0) {
    val unit = size.height / 200f
    for (j in 0 until count.coerceAtMost(MAX_PARTICLES - offset)) {
        val i = j + offset
        val fall = (p.y[i] + t * (0.05f + 0.05f * p.speed[i])) % 1.1f - 0.05f
        val x = p.x[i] * size.width + unit * 5f * wave(t, 5f + 3f * p.speed[i], p.phase[i])
        drawCircle(Color.White.copy(alpha = 0.85f), unit * (1.3f + 1.5f * p.size[i]), Offset(x, fall * size.height))
    }
}

/** Туман — три светлые полосы, едва заметно дрейфуют. */
private fun DrawScope.fog(t: Float) {
    for (i in 0 until 3) {
        val shift = size.width * 0.08f * wave(t, 40f + 10f * i, i / 3f)
        drawRoundRect(
            Color.White.copy(alpha = 0.16f),
            Offset(-size.width * 0.2f + shift, size.height * (0.35f + 0.18f * i)),
            Size(size.width * 1.4f, size.height * 0.12f),
            CornerRadius(size.height * 0.06f),
        )
    }
}

/** Гроза — слабая двойная вспышка раз в 11 с. */
private fun DrawScope.flash(t: Float) {
    val phase = t % 11f
    val alpha = when {
        phase < 0.12f -> 0.18f * (1f - phase / 0.12f)
        phase in 0.25f..0.33f -> 0.1f * (1f - (phase - 0.25f) / 0.08f)
        else -> 0f
    }
    if (alpha > 0f) drawRect(Color.White.copy(alpha = alpha))
}
