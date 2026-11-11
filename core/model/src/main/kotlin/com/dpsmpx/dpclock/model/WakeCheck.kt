package com.dpsmpx.dpclock.model

import kotlin.math.sqrt
import kotlin.random.Random

/** Способ подтверждения пробуждения (D-007, п. 4; D-011, п. 8; D-015). */
enum class WakeMethod {
    NONE, MATH, CODE, SHAKE, QR;

    companion object {
        /** «Снимок места» убран в 0.24.0 (D-036): в базе и файлах прежних версий он ещё встречается. */
        const val REMOVED_PHOTO = "PHOTO"
    }
}

/** Настойчивость: сложность примера, длина кода, число встряхиваний. */
enum class WakeLevel { EASY, MEDIUM, HARD }

/**
 * Подтверждение пробуждения у будильника (D-015). Пока его нет, «Выключить»
 * не выключает. [reference] — образец: текст QR-кода.
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

    /** QR без образца проверить нечем. */
    val isComplete: Boolean get() = method != WakeMethod.QR || reference.isNotEmpty()

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

        /**
         * Проверка из базы или файла по имени способа [method]. У будильника со «снимком
         * места» (убран в 0.24.0, D-036) — пример той же настойчивости, без образца.
         * Незнакомый способ — без проверки.
         */
        fun stored(method: String, level: WakeLevel, tasks: Int, reference: String, snoozeAllowed: Boolean, recheckMinutes: Int): WakeCheck {
            val photo = method == WakeMethod.REMOVED_PHOTO
            return WakeCheck(
                method = if (photo) WakeMethod.MATH else WakeMethod.entries.firstOrNull { it.name == method } ?: WakeMethod.NONE,
                level = level,
                tasks = tasks,
                reference = if (photo) "" else reference,
                snoozeAllowed = snoozeAllowed,
                recheckMinutes = recheckMinutes,
            )
        }
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
