package com.dpsmpx.dpclock.model

/**
 * Таймер помидора (D-025, п. 1): [longEvery] помидоров работы с короткими
 * перерывами, затем длинный перерыв; так [sets] раз. [autoAdvance] — следующий
 * этап начинается сам, иначе сигнал и ожидание «Дальше»; [dnd] — «Не беспокоить»
 * на время работы.
 */
data class Pomodoro(
    val workMinutes: Int = 25,
    val shortMinutes: Int = 5,
    val longMinutes: Int = 15,
    val longEvery: Int = 4,
    val sets: Int = 1,
    val autoAdvance: Boolean = false,
    val dnd: Boolean = false,
) {
    init {
        require(workMinutes in 1..MAX_MINUTES) { "работа $workMinutes" }
        require(shortMinutes in 1..MAX_MINUTES) { "перерыв $shortMinutes" }
        require(longMinutes in 1..MAX_MINUTES) { "длинный перерыв $longMinutes" }
        require(longEvery in 1..MAX_LONG_EVERY) { "помидоров до длинного $longEvery" }
        require(sets in 1..TimerProgram.MAX_CYCLES) { "подходов $sets" }
    }

    /** Программа таймера; названия этапов — на языке телефона. */
    fun program(work: String, shortBreak: String, longBreak: String): TimerProgram {
        val signal = !autoAdvance
        val steps = buildList {
            for (i in 1..longEvery) {
                add(TimerStep(workMinutes * 60_000L, work, fullSignal = signal, focus = true))
                if (i < longEvery) add(TimerStep(shortMinutes * 60_000L, shortBreak, fullSignal = signal))
            }
            add(TimerStep(longMinutes * 60_000L, longBreak, fullSignal = signal))
        }
        return TimerProgram(steps, cycles = sets, hold = !autoAdvance, dnd = dnd)
    }

    /** Для базы: числа и флаги через запятую. */
    fun encode(): String =
        listOf(workMinutes, shortMinutes, longMinutes, longEvery, sets, if (autoAdvance) 1 else 0, if (dnd) 1 else 0).joinToString(",")

    companion object {
        const val MAX_MINUTES = 180
        const val MAX_LONG_EVERY = 12

        /** Нечитаемое — классика 25/5/15. */
        fun decode(text: String): Pomodoro {
            val f = text.split(',').map { it.trim().toIntOrNull() }
            if (f.size < 7 || f.any { it == null }) return Pomodoro()
            return runCatching {
                Pomodoro(f[0]!!, f[1]!!, f[2]!!, f[3]!!, f[4]!!, f[5] == 1, f[6] == 1)
            }.getOrDefault(Pomodoro())
        }
    }
}
