package com.dpsmpx.dpclock.ui

import android.graphics.PointF
import android.text.format.DateFormat
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import com.dpsmpx.dpclock.model.ClockBackground

/** Где рисуется эффект-обои (D-024, п. 1): в «Часах», в ночных часах, фоном приложения за списками. */
enum class SceneLook { CLOCK, NIGHT, APP }

/** Тёмный фон эффектов: на нём читаются белые часы. */
const val SCENE_BACKGROUND = 0xFF0E1220.toInt()

/**
 * Цвета эффекта по месту: в «Часах» — светлый тон цвета темы на тёмном; ночью —
 * тёмно-красный и тусклый; фоном приложения — цвет темы на её фоне, едва заметно.
 */
@Composable
fun rememberScenePalette(look: SceneLook): ScenePalette {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    return when (look) {
        SceneLook.CLOCK -> ScenePalette(SCENE_BACKGROUND, (if (dark) scheme.primary else scheme.inversePrimary).toArgb(), android.graphics.Color.WHITE)
        SceneLook.NIGHT -> ScenePalette(android.graphics.Color.BLACK, NightFace.text.toArgb(), NightFace.text.toArgb(), strength = 0.45f)
        SceneLook.APP -> ScenePalette(scheme.background.toArgb(), scheme.primary.toArgb(), scheme.onBackground.toArgb(), strength = 0.22f)
    }
}

/**
 * Эффект-обои на экране приложения (D-024): кадр — через [SceneRenderer], как на
 * обоях Android. [interactive] — палец и наклон; за списками и ночью — нет.
 */
@Composable
fun SceneBackdrop(kind: ClockBackground, animate: Boolean, palette: ScenePalette, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val context = LocalContext.current
    val renderer = remember(kind) { SceneRenderer.create(kind) } ?: return
    val time = rememberSeconds(animate)
    val (tiltX, tiltY) = rememberTilt(animate && interactive && renderer.usesTilt)
    val input = remember { SceneInput() }
    input.is24Hour = DateFormat.is24HourFormat(context)
    val touch = if (!interactive) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                var change = awaitFirstDown(requireUnconsumed = false)
                var previous = change.position
                input.touch = PointF(previous.x, previous.y)
                while (change.pressed) {
                    change = awaitPointerEvent().changes.firstOrNull() ?: break
                    input.dragX += change.position.x - previous.x
                    input.dragY += change.position.y - previous.y
                    previous = change.position
                    input.touch = PointF(previous.x, previous.y)
                }
                input.touch = null
            }
        }
    }
    Spacer(
        modifier.fillMaxSize().then(touch).drawBehind {
            // Чтение времени — подписка: кадр перерисовывается с каждым шагом анимации.
            val t = time.floatValue
            input.tiltX = tiltX.floatValue
            input.tiltY = tiltY.floatValue
            drawIntoCanvas { canvas ->
                renderer.draw(canvas.nativeCanvas, size.width.toInt(), size.height.toInt(), t, System.currentTimeMillis(), input, palette)
            }
        },
    )
}
