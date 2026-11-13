package com.dpsmpx.dpclock.model

import java.time.LocalDateTime
import java.time.YearMonth
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Геометрия анимированных обоев (D-024): чистые функции, без Android. Рисует их
 * приложение — на обоях Android, в «Часах», в ночных часах и фоном приложения.
 */
object Scenes {
    /** Эффекты-обои: рисуются и на обоях Android (D-024, п. 2). */
    val WALLPAPERS: List<ClockBackground> = listOf(
        ClockBackground.CONSTELLATION,
        ClockBackground.HYPERCUBE,
        ClockBackground.POLAR_CLOCK,
        ClockBackground.HOURGLASS,
        ClockBackground.MECHANISM,
    )
}

/** Эффект-обои (D-024): его можно поставить на обои Android, в ночные часы и фоном приложения. */
val ClockBackground.isWallpaper: Boolean get() = this in Scenes.WALLPAPERS

/**
 * «Созвездие» (D-024, п. 3) — эффект главного меню AIM_Trainer_pas того же автора:
 * точки летят по прямой и отражаются от краёв, близкие соединяются линиями,
 * которые гаснут с расстоянием. Размеры — как у окна 800×600 игры, в масштабе экрана.
 */
object Constellation {
    const val COUNT = 100

    /** Окно игры, по которому подобраны размеры. */
    private const val REFERENCE_AREA = 800.0 * 600.0

    /** Связь точек — ближе этого (100 px окна игры). */
    private const val LINK = 100.0

    /** Линии к пальцу и к концу секундной стрелки — ближе этого (200 px). */
    private const val CURSOR_LINK = 200.0

    /** Скорость точки, 240 px/с окна игры. */
    private const val SPEED = 240.0

    /** Масштаб экрана [width]×[height] к окну игры: те же точки на ту же площадь. */
    fun scale(width: Float, height: Float): Float = sqrt(width.toDouble() * height / REFERENCE_AREA).toFloat()

    fun link(scale: Float): Float = (LINK * scale).toFloat()

    fun cursorLink(scale: Float): Float = (CURSOR_LINK * scale).toFloat()

    fun speed(scale: Float): Float = (SPEED * scale).toFloat()

    /**
     * Прозрачность линии длины [dist] при пределе [limit], 0–255: 120 у самой точки,
     * 0 на пределе; дальше предела линии нет — null. Как `link_alpha` в игре.
     */
    fun linkAlpha(dist: Float, limit: Float): Int? {
        if (dist >= limit) return null
        return (120 * (1 - dist / limit)).roundToInt()
    }

    /** Точка, которая обходит круг за минуту, — «конец секундной стрелки»: угол от 12 часов по часовой, радианы. */
    fun secondHandAngle(secondOfMinute: Double): Double = secondOfMinute / 60.0 * 2 * Math.PI
}

/**
 * Гиперкуб (D-024, п. 4): 16 вершин (±1 по четырём осям), 32 ребра — пары
 * вершин, различающихся одной координатой. Вращение в шести плоскостях,
 * проекция 4D → 3D → 2D с перспективой.
 */
object Tesseract {
    val vertices: List<DoubleArray> = (0 until 16).map { i ->
        DoubleArray(4) { axis -> if (i shr axis and 1 == 1) 1.0 else -1.0 }
    }

    val edges: List<Pair<Int, Int>> = buildList {
        for (i in 0 until 16) {
            for (axis in 0 until 4) {
                val j = i xor (1 shl axis)
                if (i < j) add(i to j)
            }
        }
    }

    /** Углы шести плоскостей вращения, радианы. */
    data class Rotation(
        val xy: Double = 0.0,
        val xz: Double = 0.0,
        val yz: Double = 0.0,
        val xw: Double = 0.0,
        val yw: Double = 0.0,
        val zw: Double = 0.0,
    )

    /** Повернуть точку [p] (x, y, z, w). */
    fun rotate(p: DoubleArray, r: Rotation): DoubleArray {
        val v = p.copyOf()
        fun plane(a: Int, b: Int, angle: Double) {
            if (angle == 0.0) return
            val c = cos(angle)
            val s = sin(angle)
            val x = v[a]
            val y = v[b]
            v[a] = x * c - y * s
            v[b] = x * s + y * c
        }
        plane(0, 3, r.xw)
        plane(1, 3, r.yw)
        plane(2, 3, r.zw)
        plane(0, 1, r.xy)
        plane(0, 2, r.xz)
        plane(1, 2, r.yz)
        return v
    }

    /**
     * Проекция на плоскость: сначала 4D → 3D из точки w = [wDistance], потом
     * 3D → 2D из z = [zDistance]. Возвращает x, y и глубину 0–1 (1 — ближе),
     * по ней дальние рёбра тоньше и тусклее.
     */
    fun project(p: DoubleArray, wDistance: Double = 3.0, zDistance: Double = 4.0): Triple<Double, Double, Double> {
        val k4 = 1.0 / (wDistance - p[3])
        val x3 = p[0] * k4
        val y3 = p[1] * k4
        val z3 = p[2] * k4
        val k3 = 1.0 / (zDistance - z3)
        val depth = ((p[3] + 1) / 2 * 0.5 + (z3 + 0.5) * 0.5).coerceIn(0.0, 1.0)
        return Triple(x3 * k3, y3 * k3, depth)
    }

    /** Вращение по времени (D-024, п. 4): плоскость XW — оборот за минуту, ZW — за час; XY — медленно, для объёма. */
    fun timeRotation(secondOfHour: Double): Rotation = Rotation(
        xw = secondOfHour % 60 / 60 * 2 * Math.PI,
        zw = secondOfHour / 3600 * 2 * Math.PI,
        xy = secondOfHour / 600 * 2 * Math.PI,
    )
}

/** Кольцо полярных часов (D-024, п. 5): доля пройденного [fraction] и значение [value]. */
data class PolarRing(val unit: PolarUnit, val fraction: Double, val value: Int)

enum class PolarUnit { SECOND, MINUTE, HOUR, WEEKDAY, DAY, MONTH }

/** «Полярные часы»: секунды, минуты, часы, день недели, число, месяц — каждое кольцо заполняется дугой. */
object PolarClock {
    /** Кольца снаружи внутрь: секунды — внешнее, месяц — внутреннее. Доли — плавные, с миллисекундами. */
    fun rings(time: LocalDateTime): List<PolarRing> {
        val second = time.second + time.nano / 1e9
        val minute = time.minute + second / 60
        val hour = time.hour + minute / 60
        val dayLength = YearMonth.from(time).lengthOfMonth()
        val day = time.dayOfMonth - 1 + hour / 24
        val weekday = time.dayOfWeek.value - 1 + hour / 24
        val month = time.monthValue - 1 + day / dayLength
        return listOf(
            PolarRing(PolarUnit.SECOND, second / 60, time.second),
            PolarRing(PolarUnit.MINUTE, minute / 60, time.minute),
            PolarRing(PolarUnit.HOUR, hour / 24, time.hour),
            PolarRing(PolarUnit.WEEKDAY, weekday / 7, time.dayOfWeek.value),
            PolarRing(PolarUnit.DAY, day / dayLength, time.dayOfMonth),
            PolarRing(PolarUnit.MONTH, month / 12, time.monthValue),
        )
    }
}

/** «Песочные часы» (D-024, п. 6): песок пересыпается за час. */
object Hourglass {
    /** Доля песка внизу — прошедшая часть часа. */
    fun fallen(time: LocalDateTime): Double = (time.minute * 60 + time.second + time.nano / 1e9) / 3600.0

    /** Горлышко — доля полуширины колбы. */
    const val NECK = 0.08

    /**
     * Полуширина колбы на расстоянии [u] от горлышка (0) до края (1), доля
     * наибольшей: у горлышка расширяется быстро, у краёв стенки почти отвесные.
     * По ней рисуются и стекло, и песок.
     */
    fun profile(u: Double): Double = NECK + (1 - NECK) * sin(u.coerceIn(0.0, 1.0) * Math.PI / 2)

    /** Объём колбы до [u] от горлышка: тело вращения, ∫ профиль² du. */
    private val volumes: DoubleArray = run {
        val steps = 400
        val table = DoubleArray(steps + 1)
        for (i in 1..steps) {
            val u = (i - 0.5) / steps
            table[i] = table[i - 1] + profile(u) * profile(u) / steps
        }
        table
    }

    private fun volumeTo(u: Double): Double {
        val x = u.coerceIn(0.0, 1.0) * (volumes.size - 1)
        val i = x.toInt().coerceAtMost(volumes.size - 2)
        return volumes[i] + (volumes[i + 1] - volumes[i]) * (x - i)
    }

    /** Уровень песка, налитого от горлышка, доля [volume] колбы: верхняя колба. */
    fun levelFromNeck(volume: Double): Double = solve(volume.coerceIn(0.0, 1.0)) { volumeTo(it) / volumeTo(1.0) }

    /** Уровень песка, насыпанного от края, доля [volume]: нижняя колба; ответ — расстояние от горлышка. */
    fun levelFromEnd(volume: Double): Double = solve(volume.coerceIn(0.0, 1.0)) { 1 - volumeTo(1 - it) / volumeTo(1.0) }.let { 1 - it }

    /** u, при котором растущая [share] равна [target]: делением пополам. */
    private fun solve(target: Double, share: (Double) -> Double): Double {
        var low = 0.0
        var high = 1.0
        repeat(40) {
            val mid = (low + high) / 2
            if (share(mid) < target) low = mid else high = mid
        }
        return (low + high) / 2
    }

    /** Колбы переворачиваются в начале часа — первые [FLIP_SECONDS] секунды; 0–1 — ход переворота, null — стоят. */
    fun flip(time: LocalDateTime): Double? {
        val second = time.minute * 60 + time.second + time.nano / 1e9
        return if (second < FLIP_SECONDS) second / FLIP_SECONDS else null
    }

    private const val FLIP_SECONDS = 1.5
}

/** «Механизм» (D-024, п. 7): углы колёс по настоящему времени, радианы по часовой. */
object Mechanism {
    data class Angles(val escape: Double, val second: Double, val minute: Double, val hour: Double, val balance: Double)

    /**
     * Анкерное колесо и секундное идут скачком раз в секунду (скачок — за 0,15 с),
     * минутное и часовое — плавно; баланс качается: полпериода — секунда.
     */
    fun angles(time: LocalDateTime): Angles {
        val fraction = time.nano / 1e9
        val jump = (fraction / 0.15).coerceAtMost(1.0)
        val ticks = time.second + jump
        val minute = time.minute + (time.second + fraction) / 60
        val hour = time.hour % 12 + minute / 60
        return Angles(
            escape = ticks / 15 * 2 * Math.PI,
            second = ticks / 60 * 2 * Math.PI,
            minute = minute / 60 * 2 * Math.PI,
            hour = hour / 12 * 2 * Math.PI,
            balance = sin((time.second + fraction) * Math.PI) * 0.9,
        )
    }

    /**
     * Угол колеса с [teeth] зубцами, сцепленного с колесом [driverTeeth] под углом
     * [driver]; [direction] — направление от оси ведущего к оси ведомого. Ведомое
     * идёт обратно и медленнее во столько, во сколько у него больше зубцов; когда
     * зубец ведущего смотрит на ведомое, напротив него — промежуток ведомого.
     */
    fun meshed(driver: Double, driverTeeth: Int, teeth: Int, direction: Double = 0.0): Double =
        direction + Math.PI + Math.PI / teeth - (driver - direction) * driverTeeth / teeth
}
