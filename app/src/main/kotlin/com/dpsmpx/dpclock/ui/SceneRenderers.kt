package com.dpsmpx.dpclock.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withSave
import androidx.core.graphics.withTranslation
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.Constellation
import com.dpsmpx.dpclock.model.Hourglass
import com.dpsmpx.dpclock.model.Mechanism
import com.dpsmpx.dpclock.model.PolarClock
import com.dpsmpx.dpclock.model.PolarUnit
import com.dpsmpx.dpclock.model.Tesseract
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Цвета эффекта (D-024): [background] — фон, [accent] — основной цвет,
 * [text] — подписи, ARGB. [strength] 0–1 — насколько ярко: ночью и под
 * списками приложения тусклее.
 */
data class ScenePalette(val background: Int, val accent: Int, val text: Int, val strength: Float = 1f)

/** Что эффект знает о человеке: палец, сдвиг пальцем с начала, наклон телефона −1…1. */
class SceneInput {
    var is24Hour = true
    var touch: PointF? = null
    var dragX = 0f
    var dragY = 0f
    var tiltX = 0f
    var tiltY = 0f
}

/**
 * Эффект-обои (D-024): рисует кадр на `android.graphics.Canvas` — одним кодом
 * на обоях Android, в «Часах», ночных часах и фоном приложения. [t] — секунды
 * анимации, [now] — часы, мс UTC.
 */
abstract class SceneRenderer {
    abstract fun draw(canvas: Canvas, width: Int, height: Int, t: Float, now: Long, input: SceneInput, palette: ScenePalette)

    /** Нужен ли датчик наклона. */
    open val usesTilt: Boolean = true

    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /** Цвет с прозрачностью [alpha] 0–1, приглушённый силой палитры. */
    protected fun ScenePalette.tint(color: Int, alpha: Float): Int {
        val a = (alpha * strength).coerceIn(0f, 1f)
        return (color and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
    }

    protected fun local(now: Long): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())

    companion object {
        fun create(kind: ClockBackground): SceneRenderer? = when (kind) {
            ClockBackground.CONSTELLATION -> ConstellationScene()
            ClockBackground.HYPERCUBE -> HypercubeScene()
            ClockBackground.POLAR_CLOCK -> PolarClockScene()
            ClockBackground.HOURGLASS -> HourglassScene()
            ClockBackground.MECHANISM -> MechanismScene()
            else -> null
        }
    }
}

/** Смешать два ARGB-цвета: [f] = 0 — первый, 1 — второй. */
private fun mix(a: Int, b: Int, f: Float): Int {
    fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - f) + ((b shr shift) and 0xFF) * f).toInt() and 0xFF
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private const val WHITE = -0x1

/**
 * «Созвездие» (D-024, п. 3) — эффект главного меню AIM_Trainer_pas: 100 точек,
 * связи ближе 100 px окна игры, линии к пальцу и к концу невидимой секундной
 * стрелки ближе 200 px; наклон сносит точки.
 */
private class ConstellationScene : SceneRenderer() {
    private val count = Constellation.COUNT
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val dx = FloatArray(count)
    private val dy = FloatArray(count)
    private var placedFor = 0L
    private var last = -1f

    override fun draw(canvas: Canvas, width: Int, height: Int, t: Float, now: Long, input: SceneInput, palette: ScenePalette) {
        canvas.drawColor(palette.background)
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val scale = Constellation.scale(w, h)
        val size = width.toLong() shl 32 or height.toLong()
        if (placedFor != size) {
            // Как при входе в меню игры: случайно по экрану, случайное направление.
            val random = Random(size)
            for (i in 0 until count) {
                x[i] = random.nextFloat() * w
                y[i] = random.nextFloat() * h
                val angle = random.nextFloat() * 2 * PI.toFloat()
                dx[i] = cos(angle)
                dy[i] = sin(angle)
            }
            placedFor = size
        }
        // Пауза между кадрами больше 0,2 с (экран гас) — один обычный кадр, как в игре.
        var dt = if (last < 0) 0f else t - last
        if (dt < 0 || dt > 0.2f) dt = 1 / 60f
        last = t
        val speed = Constellation.speed(scale)
        for (i in 0 until count) {
            x[i] += (dx[i] + input.tiltX * 0.6f) * speed * dt
            y[i] += (dy[i] + input.tiltY * 0.6f) * speed * dt
            if (x[i] < 0 || x[i] > w) dx[i] = -dx[i]
            if (y[i] < 0 || y[i] > h) dy[i] = -dy[i]
            x[i] = x[i].coerceIn(0f, w)
            y[i] = y[i].coerceIn(0f, h)
        }
        stroke.strokeWidth = scale.coerceAtLeast(1f)
        // Невидимая секундная стрелка: её конец обходит круг за минуту и собирает линии.
        val angle = Constellation.secondHandAngle((now / 1000.0 % 60.0)).toFloat()
        val radius = min(w, h) * 0.32f
        val hand = PointF(w / 2 + radius * sin(angle), h / 2 - radius * cos(angle))
        val cursorLink = Constellation.cursorLink(scale)
        for (cursor in listOfNotNull(input.touch, hand)) {
            for (i in 0 until count) {
                val alpha = Constellation.linkAlpha(hypot(x[i] - cursor.x, y[i] - cursor.y), cursorLink) ?: continue
                stroke.color = palette.tint(palette.accent, alpha / 255f)
                canvas.drawLine(x[i], y[i], cursor.x, cursor.y, stroke)
            }
        }
        val link = Constellation.link(scale)
        val limit = link * link
        for (i in 0 until count - 1) {
            for (j in i + 1 until count) {
                val ddx = x[i] - x[j]
                val ddy = y[i] - y[j]
                if (ddx * ddx + ddy * ddy > limit) continue
                val alpha = Constellation.linkAlpha(hypot(ddx, ddy), link) ?: continue
                stroke.color = palette.tint(palette.accent, alpha / 255f)
                canvas.drawLine(x[i], y[i], x[j], y[j], stroke)
            }
        }
        fill.color = palette.tint(palette.accent, 60 / 255f)
        for (i in 0 until count) canvas.drawCircle(x[i], y[i], 3 * scale, fill)
    }
}

/**
 * Гиперкуб (D-024, п. 4): каркас вращается в 4D по времени — оборот за минуту и за
 * час; палец раскручивает его, и он плавно тормозит; наклон поворачивает проекцию.
 */
private class HypercubeScene : SceneRenderer() {
    private val points = Array(16) { FloatArray(3) }
    private var spinX = 0.0
    private var spinY = 0.0
    private var velocityX = 0.0
    private var velocityY = 0.0
    private var lastDragX = 0f
    private var lastDragY = 0f
    private var last = -1f

    override fun draw(canvas: Canvas, width: Int, height: Int, t: Float, now: Long, input: SceneInput, palette: ScenePalette) {
        canvas.drawColor(palette.background)
        val side = min(width, height).toFloat()
        val dt = if (last < 0) 0f else (t - last).coerceIn(0f, 0.2f)
        last = t
        // Сдвиг пальцем — толчок; дальше вращение затухает за пару секунд.
        val kick = 3.0 / side
        velocityX += (input.dragX - lastDragX) * kick / dt.coerceAtLeast(1 / 60f) * 0.1
        velocityY += (input.dragY - lastDragY) * kick / dt.coerceAtLeast(1 / 60f) * 0.1
        lastDragX = input.dragX
        lastDragY = input.dragY
        spinX += velocityX * dt
        spinY += velocityY * dt
        val decay = Math.pow(0.25, dt.toDouble())
        velocityX *= decay
        velocityY *= decay
        val second = now / 1000.0 % 3600.0
        val base = Tesseract.timeRotation(second)
        val rotation = base.copy(
            yw = spinX,
            xz = base.xz + spinY + input.tiltX * 0.6,
            yz = base.yz + input.tiltY * 0.6,
        )
        val scale = side * 1.25f
        for (i in 0 until 16) {
            val (px, py, depth) = Tesseract.project(Tesseract.rotate(Tesseract.vertices[i], rotation))
            points[i][0] = width / 2f + (px * scale).toFloat()
            points[i][1] = height / 2f + (py * scale).toFloat()
            points[i][2] = depth.toFloat()
        }
        val unit = side / 400f
        for ((a, b) in Tesseract.edges) {
            val depth = (points[a][2] + points[b][2]) / 2
            stroke.strokeWidth = unit * (0.8f + 2.2f * depth)
            stroke.color = palette.tint(mix(palette.accent, WHITE, depth * 0.35f), 0.3f + 0.7f * depth)
            canvas.drawLine(points[a][0], points[a][1], points[b][0], points[b][1], stroke)
        }
        for (p in points) {
            fill.color = palette.tint(WHITE, 0.4f + 0.6f * p[2])
            canvas.drawCircle(p[0], p[1], unit * (1.5f + 2f * p[2]), fill)
        }
    }
}

/** «Полярные часы» (D-024, п. 5): шесть колец — секунды, минуты, часы, день недели, число, месяц. */
private class PolarClockScene : SceneRenderer() {
    override val usesTilt = false
    private val oval = RectF()

    override fun draw(canvas: Canvas, width: Int, height: Int, t: Float, now: Long, input: SceneInput, palette: ScenePalette) {
        canvas.drawColor(palette.background)
        val time = local(now)
        val rings = PolarClock.rings(time)
        val outer = min(width, height) * 0.46f
        val band = outer / 9.5f
        val cx = width / 2f
        val cy = height / 2f
        val locale = Locale.getDefault()
        stroke.strokeCap = Paint.Cap.ROUND
        text.textSize = band * 0.62f
        text.typeface = Typeface.DEFAULT_BOLD
        rings.forEachIndexed { index, ring ->
            val radius = outer - index * band * 1.3f - band / 2
            oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
            stroke.strokeWidth = band
            stroke.color = palette.tint(palette.accent, 0.12f)
            canvas.drawArc(oval, 0f, 360f, false, stroke)
            // Снаружи внутрь светлее: одна краска, без радуги.
            stroke.color = palette.tint(mix(palette.accent, WHITE, index * 0.1f), 0.9f)
            val sweep = (ring.fraction * 360).toFloat().coerceAtLeast(0.5f)
            canvas.drawArc(oval, -90f, sweep, false, stroke)
            val label = when (ring.unit) {
                PolarUnit.WEEKDAY -> time.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
                PolarUnit.MONTH -> time.month.getDisplayName(TextStyle.SHORT, locale)
                else -> ring.value.toString()
            }
            val end = Math.toRadians(-90.0 + sweep).toFloat()
            text.color = palette.tint(palette.text, 0.9f)
            canvas.drawText(label, cx + radius * cos(end), cy + radius * sin(end) + text.textSize * 0.35f, text)
        }
    }
}

/**
 * «Песочные часы» (D-024, п. 6): песок пересыпается за час, в начале часа колбы
 * переворачиваются; крупинки падают по наклону телефона.
 */
private const val SAND_STEPS = 24
private const val GLASS_STEPS = 40

private class HourglassScene : SceneRenderer() {
    private val grains = 70
    private val gx = FloatArray(grains)
    private val gy = FloatArray(grains)
    private val gv = FloatArray(grains)
    private val random = Random(11)
    private var last = -1f
    private val path = Path()
    private val point = PointF()

    // Размеры кадра — для стенок колбы.
    private var cx = 0f
    private var cy = 0f
    private var top = 0f
    private var bottom = 0f
    private var halfW = 0f

    /** Точка стенки: [u] от горлышка (0) до края (1); [side] −1 — слева, 1 — справа, 0 — середина; [up] — верхняя колба. */
    private fun wall(u: Double, side: Int, up: Boolean): PointF {
        point.y = if (up) cy - (cy - top) * u.toFloat() else cy + (bottom - cy) * u.toFloat()
        point.x = cx + side * halfW * Hourglass.profile(u).toFloat()
        return point
    }

    /** Песок от уровня [from] до [to] одной колбы — по её стенкам; поверхность — с наклоном [tilt] и горкой [mound]. */
    private fun sand(canvas: Canvas, from: Double, to: Double, up: Boolean, tilt: Float, mound: Float) {
        path.reset()
        for (k in 0..SAND_STEPS) {
            val p = wall(from + (to - from) * k / SAND_STEPS, -1, up)
            if (k == 0) path.moveTo(p.x, p.y) else if (k == SAND_STEPS) path.lineTo(p.x, p.y - tilt) else path.lineTo(p.x, p.y)
        }
        val right = wall(to, 1, up)
        path.quadTo(cx, right.y - mound, right.x, right.y + tilt)
        for (k in SAND_STEPS - 1 downTo 0) {
            val p = wall(from + (to - from) * k / SAND_STEPS, 1, up)
            path.lineTo(p.x, p.y)
        }
        path.close()
        canvas.drawPath(path, fill)
    }

    override fun draw(canvas: Canvas, width: Int, height: Int, t: Float, now: Long, input: SceneInput, palette: ScenePalette) {
        canvas.drawColor(palette.background)
        val time = local(now)
        val h = height * 0.62f
        halfW = min(width * 0.32f, h * 0.36f)
        cx = width / 2f
        cy = height / 2f
        top = cy - h / 2
        bottom = cy + h / 2
        val flip = Hourglass.flip(time)
        // Переворот: весь песок внизу поворачивается наверх.
        val fallen = if (flip != null) 1.0 else Hourglass.fallen(time)
        val dt = if (last < 0) 0f else (t - last).coerceIn(0f, 0.1f)
        last = t
        canvas.withSave {
            if (flip != null) {
                val f = flip.toFloat()
                rotate(f * f * (3 - 2 * f) * 180f, cx, cy)
            }
            val sandColor = palette.tint(mix(palette.accent, 0xFFE8C27A.toInt(), 0.55f), 0.85f)
            fill.color = sandColor
            val tilt = input.tiltX * halfW * 0.2f
            // Верх — от горлышка до уровня; низ — от края до горки.
            val topLevel = Hourglass.levelFromNeck(1 - fallen)
            if (topLevel > 0.01) sand(this, 0.0, topLevel, up = true, tilt = tilt, mound = 0f)
            val bottomLevel = Hourglass.levelFromEnd(fallen)
            if (bottomLevel < 0.995) sand(this, 1.0, bottomLevel, up = false, tilt = tilt, mound = halfW * 0.25f * (1 - bottomLevel).toFloat().coerceAtMost(0.4f))
            // Струйка: крупинки падают от горлышка к горке, наклон сносит их в сторону.
            val pileTop = wall(bottomLevel, 0, up = false).y
            if (flip == null && topLevel > 0.01) {
                for (i in 0 until grains) {
                    if (gy[i] <= 0f || gy[i] > pileTop) {
                        gx[i] = cx + (random.nextFloat() - 0.5f) * halfW * Hourglass.NECK.toFloat()
                        gy[i] = cy + random.nextFloat() * (pileTop - cy)
                        gv[i] = h * 0.4f
                    }
                    gv[i] += h * 2.5f * dt
                    gy[i] += gv[i] * dt
                    gx[i] += input.tiltX * h * 0.15f * dt
                    drawCircle(gx[i], gy[i], halfW * 0.012f + 1f, fill)
                }
            }
            // Стекло — по той же форме, что песок; рамка сверху и снизу.
            stroke.strokeWidth = halfW * 0.02f + 1f
            stroke.color = palette.tint(mix(palette.accent, WHITE, 0.4f), 0.7f)
            for (side in intArrayOf(-1, 1)) {
                path.reset()
                for (k in GLASS_STEPS downTo 0) {
                    val p = wall(k.toDouble() / GLASS_STEPS, side, up = true)
                    if (k == GLASS_STEPS) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                for (k in 1..GLASS_STEPS) {
                    val p = wall(k.toDouble() / GLASS_STEPS, side, up = false)
                    path.lineTo(p.x, p.y)
                }
                drawPath(path, stroke)
            }
            stroke.strokeWidth = halfW * 0.05f
            stroke.color = palette.tint(palette.accent, 0.9f)
            drawLine(cx - halfW * 1.15f, top, cx + halfW * 1.15f, top, stroke)
            drawLine(cx - halfW * 1.15f, bottom, cx + halfW * 1.15f, bottom, stroke)
        }
    }
}

/**
 * «Механизм» (D-024, п. 7): часовое и минутное колёса на одной оси, секундное с
 * паразитным и анкерным — скачком раз в секунду, баланс качается.
 */
private class MechanismScene : SceneRenderer() {
    override val usesTilt = false
    private class Gear(val teeth: Int, val radius: Float) {
        val path = Path()
    }

    private var builtFor = -1f
    private val spiral = Path()
    private lateinit var hourGear: Gear
    private lateinit var minuteGear: Gear
    private lateinit var secondGear: Gear
    private lateinit var idler: Gear
    private lateinit var escape: Gear

    /** Колесо: зубцы трапециями, спицы, ступица; радиус — делительный. */
    private fun build(teeth: Int, radius: Float, spokes: Int): Gear = Gear(teeth, radius).apply {
        val depth = radius * 2 * PI.toFloat() / teeth * 0.35f
        val pitch = 2 * PI / teeth
        for (i in 0 until teeth) {
            val a = i * pitch
            val points = listOf(
                a - pitch * 0.5 to radius - depth,
                a - pitch * 0.25 to radius + depth,
                a + pitch * 0.25 to radius + depth,
                a + pitch * 0.5 to radius - depth,
            )
            points.forEachIndexed { k, (angle, r) ->
                val px = (r * sin(angle)).toFloat()
                val py = (-r * cos(angle)).toFloat()
                if (i == 0 && k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
        }
        path.close()
        path.addCircle(0f, 0f, radius * 0.7f, Path.Direction.CW)
        path.addCircle(0f, 0f, radius * 0.12f, Path.Direction.CW)
        for (i in 0 until spokes) {
            val a = i * 2 * PI / spokes
            path.moveTo((radius * 0.12f * sin(a)).toFloat(), (-radius * 0.12f * cos(a)).toFloat())
            path.lineTo((radius * 0.7f * sin(a)).toFloat(), (-radius * 0.7f * cos(a)).toFloat())
        }
    }

    override fun draw(canvas: Canvas, width: Int, height: Int, t: Float, now: Long, input: SceneInput, palette: ScenePalette) {
        canvas.drawColor(palette.background)
        val m = min(width, height).toFloat()
        if (builtFor != m) {
            hourGear = build(72, m * 0.3f, 6)
            minuteGear = build(48, m * 0.2f, 5)
            secondGear = build(40, m * 0.11f, 4)
            idler = build(24, m * 0.066f, 3)
            escape = build(16, m * 0.044f, 3)
            builtFor = m
        }
        val time = local(now)
        val angles = Mechanism.angles(time)
        // Механизм — от верха часового колеса до низа секундного — по центру экрана.
        val cx = width / 2f
        val cy = height / 2f - m * 0.15f
        val unit = m / 400f
        stroke.strokeWidth = unit * 1.4f
        fun gearAt(gear: Gear, x: Float, y: Float, angle: Double, alpha: Float) {
            canvas.withTranslation(x, y) {
                rotate(Math.toDegrees(angle).toFloat())
                stroke.color = palette.tint(palette.accent, alpha)
                drawPath(gear.path, stroke)
            }
        }
        gearAt(hourGear, cx, cy, angles.hour, 0.55f)
        gearAt(minuteGear, cx, cy, angles.minute, 0.8f)
        // Стрелки поверх колёс: время видно и на обоях.
        stroke.color = palette.tint(WHITE, 0.85f)
        stroke.strokeWidth = unit * 4
        canvas.drawLine(cx, cy, cx + hourGear.radius * 0.6f * sin(angles.hour).toFloat(), cy - hourGear.radius * 0.6f * cos(angles.hour).toFloat(), stroke)
        stroke.strokeWidth = unit * 2.5f
        canvas.drawLine(cx, cy, cx + hourGear.radius * 0.9f * sin(angles.minute).toFloat(), cy - hourGear.radius * 0.9f * cos(angles.minute).toFloat(), stroke)
        stroke.strokeWidth = unit * 1.4f
        // Секундное колесо снизу, к нему — паразитное и анкерное; углы — по сцеплению зубцов.
        val sx = cx - m * 0.18f
        val sy = cy + m * 0.5f
        val toIdler = 0.35
        val ix = sx + ((secondGear.radius + idler.radius) * sin(toIdler)).toFloat()
        val iy = sy - ((secondGear.radius + idler.radius) * cos(toIdler)).toFloat()
        val idlerAngle = Mechanism.meshed(angles.second, secondGear.teeth, idler.teeth, toIdler)
        val toEscape = PI / 2 + 0.5
        val ex = ix + ((idler.radius + escape.radius) * sin(toEscape)).toFloat()
        val ey = iy - ((idler.radius + escape.radius) * cos(toEscape)).toFloat()
        val escapeAngle = Mechanism.meshed(idlerAngle, idler.teeth, escape.teeth, toEscape)
        gearAt(secondGear, sx, sy, angles.second, 0.8f)
        gearAt(idler, ix, iy, idlerAngle, 0.7f)
        gearAt(escape, ex, ey, escapeAngle, 0.9f)
        // Анкерная вилка и баланс со спиралью.
        val bx = ex + m * 0.2f
        val by = ey + m * 0.06f
        stroke.color = palette.tint(mix(palette.accent, WHITE, 0.3f), 0.85f)
        val fork = angles.balance * 0.25
        canvas.drawLine(ex + escape.radius * 1.3f, ey, bx - m * 0.02f, by + (m * 0.08f * sin(fork)).toFloat(), stroke)
        canvas.withTranslation(bx, by) {
            rotate(Math.toDegrees(angles.balance).toFloat())
            drawCircle(0f, 0f, m * 0.075f, stroke)
            drawLine(-m * 0.075f, 0f, m * 0.075f, 0f, stroke)
            drawLine(0f, -m * 0.075f, 0f, m * 0.075f, stroke)
        }
        stroke.color = palette.tint(palette.accent, 0.5f)
        spiral.reset()
        for (k in 0..80) {
            val a = k / 80.0 * 6 * PI + angles.balance * 0.3
            val r = m * 0.008f + m * 0.05f * k / 80f
            val px = bx + (r * cos(a)).toFloat()
            val py = by + (r * sin(a)).toFloat()
            if (k == 0) spiral.moveTo(px, py) else spiral.lineTo(px, py)
        }
        canvas.drawPath(spiral, stroke)
    }
}
