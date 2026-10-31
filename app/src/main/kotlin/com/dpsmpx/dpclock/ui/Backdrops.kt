package com.dpsmpx.dpclock.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.MaterialTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.Sun
import com.dpsmpx.dpclock.model.WeatherSymbol
import com.dpsmpx.dpclock.model.Zones
import com.dpsmpx.dpclock.model.isWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Своя картинка фона — у приложения, уменьшенная до этой стороны (D-021, п. 6). */
private const val IMAGE_SIDE = 2048

private const val PARTICLES = 70
private const val STARS = 120

/** Сменилась картинка фона — тот же фон «своя картинка» перерисовывается с новой. */
private val backgroundVersion = MutableStateFlow(0L)

/** Файл своей картинки фона; в перенос и системную копию не входит. */
fun backgroundFile(context: Context): File = File(context.filesDir, "clock-background.jpg")

/** Сохранить выбранную картинку: уменьшить и записать JPEG. Не вышло — false. */
suspend fun saveBackground(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > IMAGE_SIDE) {
                val scale = IMAGE_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val file = backgroundFile(context)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        temp.renameTo(file).also { if (it) backgroundVersion.value = System.currentTimeMillis() }
    }.getOrDefault(false)
}

/** Есть ли фон и нужен ли поверх него белый текст. */
fun ClockBackground.isShown(context: Context): Boolean =
    this != ClockBackground.NONE && (this != ClockBackground.IMAGE || backgroundFile(context).exists())

/**
 * Фон приложения в полную силу — в «Часах» (D-021, п. 6; D-027): своя картинка,
 * анимированные, живые и эффекты-обои. [active] — фон на экране: только тогда идёт
 * анимация и слушается наклон. Анимация — и с выключенными анимациями системы: фон
 * выбран человеком (D-027). [interactive] — палец и наклон; за списками и ночью — нет.
 */
@Composable
fun ClockBackdrop(kind: ClockBackground, active: Boolean, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val context = LocalContext.current
    val animate = active
    when (kind) {
        ClockBackground.NONE -> Unit
        ClockBackground.IMAGE -> ImageBackdrop(modifier)
        ClockBackground.WEATHER -> {
            // Погода — из кэша мировых часов (D-019), без запросов в сеть.
            val symbol by produceState<WeatherSymbol?>(null) {
                value = withContext(Dispatchers.IO) { DpClockApp.graph(context).weather.cached()?.weather?.symbol }
            }
            val known = symbol
            if (known != null) WeatherSky(known, animate, modifier.fillMaxSize(), chosen = true) else SkyBackdrop(animate, live = false, modifier)
        }
        ClockBackground.SKY -> SkyBackdrop(animate, live = false, modifier)
        ClockBackground.LIVE_SKY -> SkyBackdrop(animate, live = interactive, modifier)
        ClockBackground.WAVES -> WavesBackdrop(animate, modifier)
        ClockBackground.STARS -> StarsBackdrop(animate, modifier)
        ClockBackground.PARTICLES -> ParticlesBackdrop(animate, interactive, modifier)
        // Эффекты-обои (D-024): тот же рисунок, что на обоях Android.
        ClockBackground.CONSTELLATION, ClockBackground.HYPERCUBE, ClockBackground.POLAR_CLOCK,
        ClockBackground.HOURGLASS, ClockBackground.MECHANISM, ClockBackground.DIGIT_RAIN,
        -> SceneBackdrop(kind, animate, rememberScenePalette(SceneLook.CLOCK), modifier, interactive)
    }
}

/** Пелена за списками: фон темы почти непрозрачный — текст темы читается на любом фоне. */
private const val APP_VEIL = 0.82f

/** Пелена в ночных часах: фон едва виден, комната остаётся тёмной. */
private const val NIGHT_VEIL = 0.8f

/**
 * Фон приложения за списками разделов (D-027): эффекты-обои — цветом темы, едва
 * заметно; остальные фоны — под пеленой цвета фона темы. Без касаний и наклона.
 */
@Composable
fun AppBackdrop(kind: ClockBackground, active: Boolean, modifier: Modifier = Modifier) {
    if (kind.isWallpaper) {
        SceneBackdrop(kind, active, rememberScenePalette(SceneLook.APP), modifier, interactive = false)
        return
    }
    val veil = MaterialTheme.colorScheme.background.copy(alpha = APP_VEIL)
    Box(modifier.fillMaxSize()) {
        ClockBackdrop(kind, active, interactive = false)
        Spacer(Modifier.fillMaxSize().drawWithCache { onDrawBehind { drawRect(veil) } })
    }
}

/** Фон приложения в ночных часах (D-027): эффекты-обои тёмно-красным и тускло, остальные — под чёрной пеленой. */
@Composable
fun NightBackdrop(kind: ClockBackground, modifier: Modifier = Modifier) {
    if (kind.isWallpaper) {
        SceneBackdrop(kind, animate = true, palette = rememberScenePalette(SceneLook.NIGHT), modifier = modifier, interactive = false)
        return
    }
    Box(modifier.fillMaxSize()) {
        ClockBackdrop(kind, active = true, interactive = false)
        Spacer(Modifier.fillMaxSize().drawWithCache { onDrawBehind { drawRect(Color.Black.copy(alpha = NIGHT_VEIL)) } })
    }
}

@Composable
private fun ImageBackdrop(modifier: Modifier) {
    val context = LocalContext.current
    val file = backgroundFile(context)
    val version by backgroundVersion.collectAsState()
    val bitmap by produceState<Bitmap?>(null, version) {
        value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull() }
    }
    Box(modifier.fillMaxSize()) {
        bitmap?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // Затемнение: белые часы читаются на любой картинке.
        Spacer(Modifier.fillMaxSize().drawWithCache { onDrawBehind { drawRect(Color.Black.copy(alpha = 0.35f)) } })
    }
}

/** Время в секундах для анимации: идёт кадрами, только пока [animate]. */
@Composable
internal fun rememberSeconds(animate: Boolean): androidx.compose.runtime.FloatState {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = withFrameNanos { it } - (time.floatValue * 1e9f).toLong()
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - last >= 33_000_000L) {
                        last = now
                        time.floatValue = (now - start) / 1e9f
                    }
                }
            }
        }
    }
    return time
}

/** Наклон телефона: x и y от −1 до 1, сглажено; датчик — только пока [active]. */
@Composable
internal fun rememberTilt(active: Boolean): Pair<androidx.compose.runtime.FloatState, androidx.compose.runtime.FloatState> {
    val context = LocalContext.current
    val x = remember { mutableFloatStateOf(0f) }
    val y = remember { mutableFloatStateOf(0f) }
    DisposableEffect(active) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Влево-вправо и вперёд-назад; 0,15 — плавно, без дрожи.
                x.floatValue += ((-event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) - x.floatValue) * 0.15f
                y.floatValue += ((event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f) - y.floatValue) * 0.15f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (active && sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        onDispose { manager?.unregisterListener(listener) }
    }
    return x to y
}

/** Высота солнца в вашем поясе сейчас: по ней — цвет неба. Пояса нет в таблице — по часам. */
private fun sunElevation(): Double {
    val zone = Zones.find(ZoneId.systemDefault().id)
    val now = Instant.now()
    return if (zone != null) {
        Sun.elevation(zone.latitude, zone.longitude, now)
    } else {
        val hour = now.atZone(ZoneId.systemDefault()).hour + now.atZone(ZoneId.systemDefault()).minute / 60.0
        60 * sin((hour - 6) / 12 * PI)
    }
}

/** Небо по высоте солнца: ночь, сумерки с розовым и оранжевым у горизонта, день. */
private fun skyColors(elevation: Double): Pair<Color, Color> {
    val night = Color(0xFF0A1428) to Color(0xFF16233F)
    val twilight = Color(0xFF2B3A6B) to Color(0xFFE08A5E)
    val day = Color(0xFF2F6CB0) to Color(0xFF7FB1E0)
    return when {
        elevation <= -12 -> night
        elevation < 0 -> ((elevation + 12) / 12).toFloat().let { lerp(night.first, twilight.first, it) to lerp(night.second, twilight.second, it) }
        elevation < 8 -> (elevation / 8).toFloat().let { lerp(twilight.first, day.first, it) to lerp(twilight.second, day.second, it) }
        else -> day
    }
}

/** «Небо» — по времени суток; [live] — «Живое небо»: смещается по наклону, касание оставляет круги. */
@Composable
private fun SkyBackdrop(animate: Boolean, live: Boolean, modifier: Modifier) {
    val time = rememberSeconds(animate)
    val (tiltX, tiltY) = rememberTilt(animate && live)
    // Цвет неба — раз в минуту: солнце движется медленно.
    val minute = rememberNowMillis(60_000L)
    val elevation = remember(minute) { sunElevation() }
    val stars = remember { Random(5).let { r -> List(60) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    val touches = remember { mutableListOf<Pair<Offset, Float>>() }
    val (top, bottom) = skyColors(elevation)
    val touchModifier = if (!live) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            synchronized(touches) { touches += down.position to time.floatValue }
        }
    }
    Spacer(
        modifier.fillMaxSize().then(touchModifier).drawWithCache {
            val gradient = Brush.verticalGradient(listOf(top, bottom))
            onDrawBehind {
                val t = time.floatValue
                drawRect(gradient)
                val shift = Offset(tiltX.floatValue * size.width * 0.04f, tiltY.floatValue * size.height * 0.03f)
                if (elevation < 2) {
                    val unit = size.minDimension / 300f
                    stars.forEach { (x, y, phase) ->
                        val alpha = (0.25f + 0.5f * (0.5f + 0.5f * sin(t * 0.8f + phase * 6.28f))) * ((2 - elevation.toFloat()) / 14f).coerceIn(0f, 1f)
                        drawCircle(Color.White.copy(alpha = alpha), unit * (0.8f + phase), Offset(x * size.width, y * size.height * 0.75f) + shift)
                    }
                } else {
                    // Днём — солнце мягким кругом, его высота — по-настоящему.
                    val sunY = size.height * (0.9f - (elevation.toFloat() / 60f).coerceIn(0f, 1f) * 0.7f)
                    val center = Offset(size.width * 0.75f, sunY) + shift * 1.5f
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9A8).copy(alpha = 0.5f), Color.Transparent), center, size.minDimension * 0.4f), size.minDimension * 0.4f, center)
                    drawCircle(Color(0xFFFFF1C4).copy(alpha = 0.9f), size.minDimension * 0.07f, center)
                }
                if (live) ripples(touches, t)
            }
        },
    )
}

/** Круги от касаний: расходятся и тают за 1,5 с. */
private fun DrawScope.ripples(touches: MutableList<Pair<Offset, Float>>, t: Float) {
    synchronized(touches) {
        touches.removeAll { t - it.second > 1.5f }
        for ((at, since) in touches) {
            val age = (t - since) / 1.5f
            drawCircle(Color.White.copy(alpha = 0.35f * (1 - age)), size.minDimension * 0.35f * age, at, style = Stroke(size.minDimension * 0.006f))
        }
    }
}

/** «Волны» — три слоя медленных волн внизу экрана. */
@Composable
private fun WavesBackdrop(animate: Boolean, modifier: Modifier) {
    val time = rememberSeconds(animate)
    val accent = MaterialTheme.colorScheme.primary
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val gradient = Brush.verticalGradient(listOf(Color(0xFF0E1A2B), Color(0xFF1D3A5F)))
            onDrawBehind {
                val t = time.floatValue
                drawRect(gradient)
                for (layer in 0 until 3) {
                    val path = Path()
                    val base = size.height * (0.62f + layer * 0.1f)
                    val amplitude = size.height * (0.035f - layer * 0.007f)
                    val length = size.width * (1.1f - layer * 0.2f)
                    val phase = t * (0.25f + layer * 0.12f)
                    path.moveTo(0f, size.height)
                    var x = 0f
                    while (x <= size.width + 8f) {
                        path.lineTo(x, base + amplitude * sin(x / length * 2 * PI.toFloat() + phase))
                        x += 8f
                    }
                    path.lineTo(size.width, size.height)
                    path.close()
                    drawPath(path, lerp(accent, Color.White, 0.2f * layer).copy(alpha = 0.22f + layer * 0.1f))
                }
            }
        },
    )
}

/** «Звёзды» — звёздное небо медленно поворачивается, звёзды мерцают. */
@Composable
private fun StarsBackdrop(animate: Boolean, modifier: Modifier) {
    val time = rememberSeconds(animate)
    val stars = remember { Random(9).let { r -> List(STARS) { floatArrayOf(r.nextFloat(), r.nextFloat(), r.nextFloat()) } } }
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val gradient = Brush.radialGradient(listOf(Color(0xFF1A2347), Color(0xFF05070F)), Offset(size.width * 0.3f, size.height * 0.2f), size.maxDimension)
            onDrawBehind {
                val t = time.floatValue
                drawRect(gradient)
                val pivot = Offset(size.width * 0.5f, size.height * 1.2f)
                val radius = hypot(size.width, size.height) * 1.2f
                val turn = t * 0.004f
                val unit = size.minDimension / 300f
                for (star in stars) {
                    val angle = star[0] * 2 * PI.toFloat() + turn
                    val distance = radius * (0.3f + 0.7f * star[1])
                    val at = Offset(pivot.x + distance * kotlin.math.cos(angle), pivot.y + distance * sin(angle))
                    if (at.x < -4 || at.y < -4 || at.x > size.width + 4 || at.y > size.height + 4) continue
                    val alpha = 0.3f + 0.6f * (0.5f + 0.5f * sin(t * (0.5f + star[2]) + star[2] * 6.28f))
                    drawCircle(Color.White.copy(alpha = alpha), unit * (0.6f + star[2] * 1.2f), at)
                }
            }
        },
    )
}

/** «Частицы» — живой фон: дрейфуют, стекают по наклону телефона и разлетаются от пальца ([interactive]). */
@Composable
private fun ParticlesBackdrop(animate: Boolean, interactive: Boolean, modifier: Modifier) {
    val time = rememberSeconds(animate)
    val (tiltX, tiltY) = rememberTilt(animate && interactive)
    val accent = MaterialTheme.colorScheme.primary
    val state = remember { ParticleField(PARTICLES) }
    Spacer(
        modifier
            .fillMaxSize()
            .pointerInput(interactive) {
                if (!interactive) return@pointerInput
                awaitEachGesture {
                    var change = awaitFirstDown(requireUnconsumed = false)
                    state.touch = change.position
                    while (change.pressed) {
                        val event = awaitPointerEvent()
                        change = event.changes.firstOrNull() ?: break
                        state.touch = change.position
                    }
                    state.touch = null
                }
            }
            .drawWithCache {
                val gradient = Brush.verticalGradient(listOf(Color(0xFF101626), Color(0xFF1C2238)))
                onDrawBehind {
                    state.step(time.floatValue, size.width, size.height, tiltX.floatValue, tiltY.floatValue)
                    drawRect(gradient)
                    val unit = size.minDimension / 300f
                    for (i in 0 until state.count) {
                        drawCircle(lerp(accent, Color.White, state.tone[i]).copy(alpha = 0.55f), unit * (1.5f + 2.5f * state.size[i]), Offset(state.x[i], state.y[i]))
                    }
                }
            },
    )
}

/** Простая физика частиц: скорость, затухание, сила наклона и отталкивание от пальца. */
private class ParticleField(val count: Int) {
    private val random = Random(3)
    val x = FloatArray(count)
    val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    val size = FloatArray(count) { random.nextFloat() }
    val tone = FloatArray(count) { random.nextFloat() * 0.6f }
    var touch: Offset? = null
    private var last = -1f
    private var placed = false

    fun step(t: Float, width: Float, height: Float, tiltX: Float, tiltY: Float) {
        if (!placed && width > 0) {
            for (i in 0 until count) {
                x[i] = random.nextFloat() * width
                y[i] = random.nextFloat() * height
            }
            placed = true
        }
        val dt = if (last < 0) 0f else (t - last).coerceIn(0f, 0.05f)
        last = t
        if (dt == 0f) return
        val unit = width / 300f
        val finger = touch
        for (i in 0 until count) {
            var ax = tiltX * 40f * unit + sin(t * 0.3f + i) * 4f * unit
            var ay = tiltY * 40f * unit + kotlin.math.cos(t * 0.27f + i * 1.7f) * 4f * unit
            if (finger != null) {
                val dx = x[i] - finger.x
                val dy = y[i] - finger.y
                val d = hypot(dx, dy).coerceAtLeast(unit * 4)
                val push = (unit * 9000f / (d * d)).coerceAtMost(unit * 600f)
                ax += dx / d * push
                ay += dy / d * push
            }
            vx[i] = (vx[i] + ax * dt) * 0.97f
            vy[i] = (vy[i] + ay * dt) * 0.97f
            x[i] = (x[i] + vx[i] * dt).mod(width)
            y[i] = (y[i] + vy[i] * dt).mod(height)
        }
    }
}
