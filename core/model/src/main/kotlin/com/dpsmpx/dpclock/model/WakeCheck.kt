package com.dpsmpx.dpclock.model

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/** Способ подтверждения пробуждения (D-007, п. 4; D-011, п. 8; D-015). */
enum class WakeMethod { NONE, MATH, CODE, SHAKE, QR, PHOTO }

/** Настойчивость: сложность примера, длина кода, число встряхиваний, строгость сравнения снимка. */
enum class WakeLevel { EASY, MEDIUM, HARD }

/**
 * Подтверждение пробуждения у будильника (D-015). Пока его нет, «Выключить»
 * не выключает. [reference] — образец: текст QR-кода или подпись снимка места
 * ([PhotoSignature]); сам снимок не хранится.
 */
data class WakeCheck(
    val method: WakeMethod = WakeMethod.NONE,
    val level: WakeLevel = WakeLevel.MEDIUM,
    /** Сколько примеров или кодов подряд, 1–[MAX_TASKS]. */
    val tasks: Int = 1,
    val reference: String = "",
    /** «Отложить» без проверки; false — только «Выключить» через проверку. */
    val snoozeAllowed: Boolean = true,
    /** Повторная проверка «Не спите?» через столько минут после выключения; 0 — нет (D-011, п. 3). */
    val recheckMinutes: Int = 0,
) {
    init {
        require(tasks in 1..MAX_TASKS) { "задач $tasks" }
        require(recheckMinutes >= 0) { "повторная проверка $recheckMinutes" }
    }

    val required: Boolean get() = method != WakeMethod.NONE

    /** QR и снимок без образца проверить нечем. */
    val isComplete: Boolean get() = (method != WakeMethod.QR && method != WakeMethod.PHOTO) || reference.isNotEmpty()

    /** Встряхиваний до конца проверки. */
    val shakes: Int get() = when (level) {
        WakeLevel.EASY -> 20
        WakeLevel.MEDIUM -> 40
        WakeLevel.HARD -> 70
    }

    companion object {
        const val MAX_TASKS = 5
        val RECHECK_CHOICES = listOf(0, 3, 5, 10, 15)

        /** Запасной способ, когда камера или датчик не помогают: сложные примеры (D-015, п. 5). */
        val FALLBACK = WakeCheck(WakeMethod.MATH, WakeLevel.HARD, tasks = 3)
    }
}

/** Задача на клавиатуре: показать [question], ответ — число [answer]. */
data class KeypadTask(val question: String, val answer: String)

/** Задачи для примера и кода (D-015, п. 2). [random] — для воспроизводимых тестов. */
object WakeTasks {
    fun math(level: WakeLevel, random: Random = Random.Default): KeypadTask = when (level) {
        WakeLevel.EASY -> {
            val a = random.nextInt(12, 50)
            val b = random.nextInt(12, 50)
            KeypadTask("$a + $b", (a + b).toString())
        }
        WakeLevel.MEDIUM -> {
            val a = random.nextInt(3, 10)
            val b = random.nextInt(12, 30)
            val c = random.nextInt(11, 100)
            KeypadTask("$a × $b + $c", (a * b + c).toString())
        }
        WakeLevel.HARD -> {
            val a = random.nextInt(12, 30)
            val b = random.nextInt(12, 30)
            // Вычитаемое меньше произведения: ответ не отрицательный.
            val c = random.nextInt(101, a * b)
            KeypadTask("$a × $b − $c", (a * b - c).toString())
        }
    }

    /** Код из цифр: набирается на той же клавиатуре. */
    fun code(level: WakeLevel, random: Random = Random.Default): KeypadTask {
        val length = when (level) {
            WakeLevel.EASY -> 4
            WakeLevel.MEDIUM -> 6
            WakeLevel.HARD -> 8
        }
        val digits = (1..length).map { random.nextInt(0, 10) }.joinToString("")
        // Пробелы по три — легче читать спросонья; набирать их не нужно.
        return KeypadTask(digits.chunked(3).joinToString(" "), digits)
    }
}

/**
 * Счётчик встряхиваний по акселерометру (D-015, п. 2): толчок — перегрузка
 * выше [THRESHOLD_G], не чаще чем раз в [GAP_MILLIS].
 */
class ShakeCounter {
    var count = 0
        private set
    private var last = Long.MIN_VALUE / 2

    /** Ускорение по осям, м/с², в момент [atMillis]; true — засчитан новый толчок. */
    fun onSample(x: Float, y: Float, z: Float, atMillis: Long): Boolean {
        val g = sqrt(x * x + y * y + z * z) / EARTH
        if (g < THRESHOLD_G || atMillis - last < GAP_MILLIS) return false
        last = atMillis
        count++
        return true
    }

    companion object {
        const val THRESHOLD_G = 2.0f
        const val GAP_MILLIS = 250L
        private const val EARTH = 9.80665f
    }
}

/**
 * Подпись снимка места (D-015, п. 3): яркость в сетке [SIZE]×[SIZE], без цвета.
 * По ней место узнаётся, а сам снимок не хранится и не восстанавливается.
 * Сравнение — корреляция яркости с поправкой на сдвиг кадра на клетку.
 */
object PhotoSignature {
    const val SIZE = 16

    /**
     * Подпись из кадра яркости [luma] (строки по [rowStride] байт) размером
     * [width]×[height]: берётся центральный квадрат и усредняется по клеткам.
     */
    fun of(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): IntArray {
        val side = minOf(width, height)
        val left = (width - side) / 2
        val top = (height - side) / 2
        val cell = side / SIZE
        val out = IntArray(SIZE * SIZE)
        for (cy in 0 until SIZE) for (cx in 0 until SIZE) {
            var sum = 0L
            var n = 0
            // Каждую вторую точку: подписи хватает, а кадр обрабатывается быстрее.
            var y = top + cy * cell
            while (y < top + (cy + 1) * cell) {
                var x = left + cx * cell
                while (x < left + (cx + 1) * cell) {
                    sum += luma[y * rowStride + x].toInt() and 0xFF
                    n++
                    x += 2
                }
                y += 2
            }
            out[cy * SIZE + cx] = if (n == 0) 0 else (sum / n).toInt()
        }
        return out
    }

    fun encode(signature: IntArray): String = signature.joinToString("") { it.toString(16).padStart(2, '0') }

    fun decode(text: String): IntArray? =
        if (text.length != SIZE * SIZE * 2) null else IntArray(SIZE * SIZE) { text.substring(it * 2, it * 2 + 2).toInt(16) }

    /** Похожесть от −1 до 1: лучшая корреляция при сдвиге до клетки в любую сторону. */
    fun similarity(a: IntArray, b: IntArray): Double {
        var best = -1.0
        for (dy in -1..1) for (dx in -1..1) best = maxOf(best, correlation(a, b, dx, dy))
        return best
    }

    /** Порог похожести по настойчивости. */
    fun threshold(level: WakeLevel): Double = when (level) {
        WakeLevel.EASY -> 0.55
        WakeLevel.MEDIUM -> 0.68
        WakeLevel.HARD -> 0.8
    }

    private fun correlation(a: IntArray, b: IntArray, dx: Int, dy: Int): Double {
        val xs = DoubleArray(SIZE * SIZE)
        val ys = DoubleArray(SIZE * SIZE)
        var n = 0
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val bx = x + dx
            val by = y + dy
            if (bx !in 0 until SIZE || by !in 0 until SIZE) continue
            xs[n] = a[y * SIZE + x].toDouble()
            ys[n] = b[by * SIZE + bx].toDouble()
            n++
        }
        val mx = xs.take(n).average()
        val my = ys.take(n).average()
        var cov = 0.0
        var vx = 0.0
        var vy = 0.0
        for (i in 0 until n) {
            cov += (xs[i] - mx) * (ys[i] - my)
            vx += (xs[i] - mx) * (xs[i] - mx)
            vy += (ys[i] - my) * (ys[i] - my)
        }
        // Ровная картинка (тёмная комната, закрытая камера) ни на что не похожа.
        if (vx < FLAT || vy < FLAT) return -1.0
        return cov / sqrt(vx * vy)
    }

    /** Разброс яркости ниже этого — кадр почти однотонный. */
    private const val FLAT = 64.0 * SIZE * SIZE

    /** Средний перепад яркости соседних клеток: в почти однотонном кадре образец не снять. */
    fun detail(signature: IntArray): Double {
        var sum = 0.0
        for (y in 0 until SIZE) for (x in 1 until SIZE) sum += abs(signature[y * SIZE + x] - signature[y * SIZE + x - 1])
        return sum / (SIZE * (SIZE - 1))
    }

    /** Перепада меньше — образец не годится. */
    const val MIN_DETAIL = 4.0
}
