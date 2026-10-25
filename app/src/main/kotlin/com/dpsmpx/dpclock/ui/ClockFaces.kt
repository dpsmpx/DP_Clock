package com.dpsmpx.dpclock.ui

import android.icu.text.DateFormat
import android.icu.text.DateFormatSymbols
import android.icu.util.Calendar
import android.icu.util.TimeZone
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dpsmpx.dpclock.millisToNextSecond
import com.dpsmpx.dpclock.model.Appearance
import com.dpsmpx.dpclock.model.ClockFont
import com.dpsmpx.dpclock.model.ClockStyle
import com.dpsmpx.dpclock.model.SevenSegment
import com.dpsmpx.dpclock.platform.TimeText
import kotlinx.coroutines.delay
import java.util.Date
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import android.text.format.DateFormat as SystemTimeFormat

/** Цвета циферблата: цифры и стрелки, метки, секундная, подложка (прозрачная — без неё). */
data class FaceColors(val text: Color, val dim: Color, val accent: Color, val dial: Color)

/** Тёмно-красный на чёрном (D-017, п. 4): виден ночью и почти не светит в комнату. */
val NightFace = FaceColors(Color(0xFF9A2B2B), Color(0xFF5E1E1E), Color(0xFF9A2B2B), Color.Transparent)

/**
 * Часы в выбранном стиле (D-021, п. 4): цифры в круге, стрелки — классические
 * или минимализм, крупные цифры или «табло». [night] — ночные часы: красным,
 * без круга и без секундной стрелки. [size] — сторона области под часы.
 * [active] — часы на экране: иначе стоят и не тратят батарею (раздел рядом в ленте).
 * [onBackdrop] — под часами фон (D-021, п. 6): часы белые на тёмной подложке.
 */
@Composable
fun ClockFace(
    appearance: Appearance,
    night: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    onBackdrop: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val colors = when {
        night -> NightFace
        onBackdrop -> FaceColors(Color.White, Color.White.copy(alpha = 0.7f), scheme.inversePrimary, Color.Black.copy(alpha = 0.28f))
        else -> FaceColors(scheme.onPrimaryContainer, scheme.outline, scheme.primary, scheme.primaryContainer)
    }
    CompositionLocalProvider(LocalClockActive provides active) {
        when (appearance.clockStyle) {
            ClockStyle.CIRCLE -> CircleFace(colors, night, size, modifier)
            ClockStyle.ANALOG_CLASSIC -> AnalogFace(colors, classic = true, seconds = !night, size = size, modifier = modifier)
            ClockStyle.ANALOG_MINIMAL -> AnalogFace(colors, classic = false, seconds = !night, size = size, modifier = modifier)
            ClockStyle.DIGITS -> DigitsFace(colors, appearance.clockFont, appearance.clockSize, night, modifier)
        }
    }
}

private val LocalClockActive = staticCompositionLocalOf { true }

/** Сейчас, мс: каждый кадр ([smooth] — плавная секундная) или раз в секунду; только пока часы на экране. */
@Composable
private fun rememberClockNow(smooth: Boolean): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val active = LocalClockActive.current
    LaunchedEffect(lifecycle, smooth, active) {
        now = System.currentTimeMillis()
        if (!active) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (smooth) {
                    withFrameMillis { now = System.currentTimeMillis() }
                } else {
                    now = System.currentTimeMillis()
                    delay(millisToNextSecond(now))
                }
            }
        }
    }
    return now
}

/** Настройку 12/24 меняют вне приложения — перечитывается при каждом возврате на экран. */
@Composable
private fun rememberIs24Hour(): Boolean {
    val context = LocalContext.current
    var is24 by remember { mutableStateOf(SystemTimeFormat.is24HourFormat(context)) }
    LifecycleStartEffect(context) {
        is24 = SystemTimeFormat.is24HourFormat(context)
        onStopOrDispose {}
    }
    return is24
}

/** Цифры в круге — как с 0.0.0; ночью — без круга. */
@Composable
private fun CircleFace(colors: FaceColors, night: Boolean, size: Dp, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val is24 = rememberIs24Hour()
    val now = rememberClockNow(smooth = false)
    val format = remember(locale, is24, night) {
        DateFormat.getInstanceForSkeleton(if (night) (if (is24) "Hm" else "hm") else (if (is24) "Hms" else "hms"), locale)
    }
    format.timeZone = TimeZone.getTimeZone(java.util.TimeZone.getDefault().id)
    val text = format.format(Date(now))
    val style = MaterialTheme.typography.displayLarge.copy(textAlign = TextAlign.Center, fontFeatureSettings = "tnum")
    if (night) {
        BasicText(text, modifier.fillMaxWidth(0.9f), style = style.copy(color = colors.text), maxLines = 1, autoSize = TextAutoSize.StepBased(maxFontSize = 160.sp))
        return
    }
    Surface(modifier.size(size), shape = CircleShape, color = colors.dial, contentColor = colors.text) {
        Box(contentAlignment = Alignment.Center) {
            BasicText(
                text,
                Modifier.fillMaxWidth(0.75f),
                style = style.copy(color = LocalContentColor.current),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 400.sp),
            )
        }
    }
}

/** Циферблат со стрелками: [classic] — числа и все метки, иначе — тонкие стрелки и метки часов. */
@Composable
private fun AnalogFace(colors: FaceColors, classic: Boolean, seconds: Boolean, size: Dp, modifier: Modifier) {
    val context = LocalContext.current
    val now = rememberClockNow(smooth = seconds)
    // Двенадцать чисел — в кэше все, иначе их перемеряли бы каждый кадр.
    val measurer = rememberTextMeasurer(cacheSize = 16)
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { (1..12).map { String.format(locale, "%d", it) } }
    val calendar = remember { Calendar.getInstance() }
    calendar.timeZone = TimeZone.getTimeZone(java.util.TimeZone.getDefault().id)
    calendar.timeInMillis = now
    val hour = calendar.get(Calendar.HOUR) + calendar.get(Calendar.MINUTE) / 60f
    val minute = calendar.get(Calendar.MINUTE) + calendar.get(Calendar.SECOND) / 60f
    val second = calendar.get(Calendar.SECOND) + calendar.get(Calendar.MILLISECOND) / 1000f
    val spoken = TimeText.time(context, now)
    Canvas(modifier.size(size).semantics { contentDescription = spoken }) {
        val r = this.size.minDimension / 2
        if (colors.dial != Color.Transparent) drawCircle(colors.dial, r)
        for (i in 0 until 60) {
            val major = i % 5 == 0
            val quarter = i % 15 == 0
            if (!classic && !major) continue
            val inner = when {
                classic && major -> 0.84f
                classic -> 0.9f
                quarter -> 0.8f
                else -> 0.88f
            }
            val width = when {
                classic && major -> 0.025f
                classic -> 0.008f
                else -> 0.012f
            }
            radial(i * 6f, r * inner, r * 0.94f, colors.dim, r * width)
        }
        if (classic) {
            val style = TextStyle(color = colors.text, fontSize = (r * 0.14f / density / fontScale).sp)
            numbers.forEachIndexed { index, label ->
                val layout = measurer.measure(label, style)
                val angle = (index + 1) * 30f
                val at = point(angle, r * 0.7f)
                drawText(layout, topLeft = Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f))
            }
        }
        radial(hour * 30f, -r * 0.08f, r * 0.5f, colors.text, r * if (classic) 0.055f else 0.022f)
        radial(minute * 6f, -r * 0.08f, r * 0.76f, colors.text, r * if (classic) 0.035f else 0.014f)
        if (seconds) {
            radial(second * 6f, -r * 0.16f, r * 0.86f, colors.accent, r * 0.012f)
            drawCircle(colors.accent, r * 0.03f)
        } else {
            drawCircle(colors.text, r * 0.03f)
        }
    }
}

/** Точка на окружности радиусом [radius]: угол — по часовой от 12. */
private fun DrawScope.point(degrees: Float, radius: Float): Offset {
    val a = (degrees - 90f) * PI.toFloat() / 180f
    return Offset(center.x + radius * cos(a), center.y + radius * sin(a))
}

/** Линия по радиусу от [from] до [to]; отрицательное [from] — хвост за центр. */
private fun DrawScope.radial(degrees: Float, from: Float, to: Float, color: Color, width: Float) =
    drawLine(color, point(degrees, from), point(degrees, to), strokeWidth = width, cap = StrokeCap.Round)

/** Крупные цифры: обычные, тонкие, жирные или семисегментное «табло»; [sizePercent] — доля ширины. */
@Composable
private fun DigitsFace(colors: FaceColors, font: ClockFont, sizePercent: Int, night: Boolean, modifier: Modifier) {
    val is24 = rememberIs24Hour()
    val now = rememberClockNow(smooth = false)
    val locale = LocalConfiguration.current.locales[0]
    val calendar = remember { Calendar.getInstance() }
    calendar.timeZone = TimeZone.getTimeZone(java.util.TimeZone.getDefault().id)
    calendar.timeInMillis = now
    val h24 = calendar.get(Calendar.HOUR_OF_DAY)
    val hour = if (is24) h24 else (h24 % 12).let { if (it == 0) 12 else it }
    val text = (if (is24) hour.toString().padStart(2, '0') else hour.toString()) + ":" + calendar.get(Calendar.MINUTE).toString().padStart(2, '0')
    val amPm = if (is24) null else DateFormatSymbols.getInstance(locale).amPmStrings[calendar.get(Calendar.AM_PM)]
    val fraction = sizePercent / 100f * if (night) 0.9f else 1f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (font == ClockFont.SEGMENT) {
            SegmentText(text, colors.text, Modifier.fillMaxWidth(fraction))
        } else {
            BasicText(
                text,
                Modifier.fillMaxWidth(fraction),
                style = MaterialTheme.typography.displayLarge.copy(
                    color = colors.text,
                    textAlign = TextAlign.Center,
                    fontFeatureSettings = "tnum",
                    fontWeight = when (font) {
                        ClockFont.THIN -> FontWeight.Thin
                        ClockFont.BOLD -> FontWeight.Bold
                        else -> FontWeight.Normal
                    },
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(maxFontSize = 600.sp),
            )
        }
        if (amPm != null) Text(amPm, color = colors.dim, style = MaterialTheme.typography.titleLarge)
    }
}

/**
 * Семисегментное «табло» (D-021, п. 4): цифры из шестиугольных сегментов,
 * погасшие — едва видны, как у настоящего табло.
 */
@Composable
fun SegmentText(text: String, color: Color, modifier: Modifier = Modifier) {
    val digits = text.count { it.isDigit() }
    val colons = text.count { it == ':' }
    val units = digits * DIGIT_UNITS + colons * COLON_UNITS
    Canvas(modifier.aspectRatio(units / HEIGHT_UNITS).semantics { contentDescription = text }) {
        val u = this.size.width / units
        var x = 0f
        for (char in text) {
            if (char == ':') {
                val cx = x + COLON_UNITS * u / 2
                drawCircle(color, u * 0.1f, Offset(cx, this.size.height * 0.33f))
                drawCircle(color, u * 0.1f, Offset(cx, this.size.height * 0.67f))
                x += COLON_UNITS * u
                continue
            }
            if (!char.isDigit()) continue
            for (segment in 0 until 7) {
                drawPath(segmentPath(segment, x + u * 0.09f, 0f, u * 0.82f, this.size.height, u * 0.14f), if (SevenSegment.lit(char, segment)) color else color.copy(alpha = 0.07f))
            }
            x += DIGIT_UNITS * u
        }
    }
}

private const val DIGIT_UNITS = 1f
private const val COLON_UNITS = 0.36f
private const val HEIGHT_UNITS = 1.7f

/** Сегмент цифры в рамке x, y, w, h: шестиугольник толщиной [t] с зазором на стыках. */
private fun segmentPath(segment: Int, x: Float, y: Float, w: Float, h: Float, t: Float): Path {
    val gap = t * 0.18f
    val mid = y + h / 2
    fun horizontal(cy: Float) = Path().apply {
        moveTo(x + gap + t / 2, cy)
        lineTo(x + t + gap, cy - t / 2)
        lineTo(x + w - t - gap, cy - t / 2)
        lineTo(x + w - gap - t / 2, cy)
        lineTo(x + w - t - gap, cy + t / 2)
        lineTo(x + t + gap, cy + t / 2)
        close()
    }
    fun vertical(cx: Float, top: Float, bottom: Float) = Path().apply {
        moveTo(cx, top + gap + t / 2)
        lineTo(cx + t / 2, top + t + gap)
        lineTo(cx + t / 2, bottom - t - gap)
        lineTo(cx, bottom - gap - t / 2)
        lineTo(cx - t / 2, bottom - t - gap)
        lineTo(cx - t / 2, top + t + gap)
        close()
    }
    val left = x + t / 2
    val right = x + w - t / 2
    val top = y + t / 2
    val bottom = y + h - t / 2
    return when (segment) {
        0 -> horizontal(top)
        1 -> vertical(right, top, mid)
        2 -> vertical(right, mid, bottom)
        3 -> horizontal(bottom)
        4 -> vertical(left, mid, bottom)
        5 -> vertical(left, top, mid)
        else -> horizontal(mid)
    }
}

/** Сторона области под часы — доля меньшей стороны окна. */
fun clockSide(width: Dp, height: Dp, fraction: Float): Dp = min(width, height) * fraction
