package com.dpsmpx.dpclock.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.dpsmpx.dpclock.R
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.ClockFont
import com.dpsmpx.dpclock.model.ClockStyle
import com.dpsmpx.dpclock.model.Palette
import com.dpsmpx.dpclock.model.ThemeMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Цвет приложения (D-021, п. 3; выбор автора — любой): из обоев, один из
 * готовых или свой на цветовом круге. [current] — null: из обоев.
 */
@Composable
fun ColorDialog(current: Int?, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    val start = current?.let { Palette.toWheel(it) } ?: (200f to 0.6f)
    var custom by rememberSaveable { mutableStateOf(current != null && current !in Palette.PRESETS) }
    var hue by rememberSaveable { mutableFloatStateOf(start.first) }
    var saturation by rememberSaveable { mutableFloatStateOf(start.second) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_color)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(
                    selected = current == null && !custom,
                    onClick = { onPick(null) },
                    label = { Text(stringResource(R.string.appearance_color_wallpaper)) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (color in Palette.PRESETS) {
                        Swatch(Color(color), selected = color == current && !custom, onClick = { onPick(color) })
                    }
                }
                FilterChip(selected = custom, onClick = { custom = true }, label = { Text(stringResource(R.string.appearance_color_custom)) })
                if (custom) {
                    HueWheel(hue, saturation, onHue = { hue = it }, modifier = Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally))
                    Text(stringResource(R.string.appearance_saturation), style = MaterialTheme.typography.labelLarge)
                    Slider(value = saturation, onValueChange = { saturation = it })
                }
            }
        },
        confirmButton = {
            if (custom) TextButton(onClick = { onPick(Palette.fromWheel(hue, saturation)) }) { Text(stringResource(R.string.done)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
            .clickable(onClick = onClick),
    )
}

/** Круг оттенков: точка — выбранный оттенок; в центре — итоговый цвет с насыщенностью. */
@Composable
private fun HueWheel(hue: Float, saturation: Float, onHue: (Float) -> Unit, modifier: Modifier) {
    val ring = (0..12).map { Color(Palette.fromWheel(it * 30f, 1f)) }
    val chosen = Color(Palette.fromWheel(hue, saturation))
    val description = stringResource(R.string.appearance_hue)
    fun hueAt(offset: Offset, center: Offset): Float {
        val degrees = Math.toDegrees(atan2((offset.y - center.y).toDouble(), (offset.x - center.x).toDouble())).toFloat() + 90f
        return (degrees + 360f) % 360f
    }
    Canvas(
        modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
            .pointerInput(Unit) { detectTapGestures { onHue(hueAt(it, Offset(size.width / 2f, size.height / 2f))) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> onHue(hueAt(change.position, Offset(size.width / 2f, size.height / 2f))) } },
    ) {
        val r = size.minDimension / 2
        val width = r * 0.24f
        drawCircle(Brush.sweepGradient(ring.map { it }, center), r - width / 2, center, style = Stroke(width))
        drawCircle(chosen, r * 0.42f, center)
        val a = (hue - 90f) * PI.toFloat() / 180f
        val knob = Offset(center.x + (r - width / 2) * cos(a), center.y + (r - width / 2) * sin(a))
        drawCircle(Color.White, width * 0.42f, knob)
        drawCircle(Color(Palette.fromWheel(hue, 1f)), width * 0.32f, knob)
    }
}

fun themeTitle(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.BLACK -> R.string.theme_black
}

fun clockStyleTitle(style: ClockStyle): Int = when (style) {
    ClockStyle.CIRCLE -> R.string.clock_style_circle
    ClockStyle.ANALOG_CLASSIC -> R.string.clock_style_classic
    ClockStyle.ANALOG_MINIMAL -> R.string.clock_style_minimal
    ClockStyle.DIGITS -> R.string.clock_style_digits
}

fun backgroundTitle(background: ClockBackground): Int = when (background) {
    ClockBackground.NONE -> R.string.background_none
    ClockBackground.IMAGE -> R.string.background_image
    ClockBackground.SKY -> R.string.background_sky
    ClockBackground.WAVES -> R.string.background_waves
    ClockBackground.STARS -> R.string.background_stars
    ClockBackground.WEATHER -> R.string.background_weather
    ClockBackground.PARTICLES -> R.string.background_particles
    ClockBackground.LIVE_SKY -> R.string.background_live_sky
}

fun clockFontTitle(font: ClockFont): Int = when (font) {
    ClockFont.REGULAR -> R.string.clock_font_regular
    ClockFont.THIN -> R.string.clock_font_thin
    ClockFont.BOLD -> R.string.clock_font_bold
    ClockFont.SEGMENT -> R.string.clock_font_segment
}

/** Цвет — точкой рядом с подписью в настройках. */
@Composable
fun ColorLabel(color: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (color != null) {
            Box(Modifier.size(16.dp).clip(CircleShape).background(Color(color)))
            Text(stringResource(R.string.appearance_color_own), modifier = Modifier.padding(start = 8.dp))
        } else {
            Text(stringResource(R.string.appearance_color_wallpaper))
        }
    }
}
