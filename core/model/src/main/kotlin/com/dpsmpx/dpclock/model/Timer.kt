package com.dpsmpx.dpclock.model

import java.net.URLDecoder
import java.net.URLEncoder

/** Шаг программы таймера. */
data class TimerStep(
    val durationMillis: Long,
    val name: String = "",
    /** Полный сигнал — как будильник; короткий — звук и сразу следующий шаг. */
    val fullSignal: Boolean = true,
) {
    init {
        require(durationMillis > 0) { "длительность $durationMillis" }
    }
}

/**
 * Одна модель для одиночного таймера, пресета, цепочки и цикла (DATA_MODEL.md, п. 5).
 * Одиночный таймер — один шаг с полным сигналом.
 */
data class TimerProgram(val steps: List<TimerStep>, val cycles: Int = 1) {
    init {
        require(steps.isNotEmpty()) { "программа без шагов" }
        require(cycles >= 1) { "кругов $cycles" }
    }

    val totalMillis: Long get() = steps.sumOf { it.durationMillis } * cycles

    val isChain: Boolean get() = steps.size > 1 || cycles > 1

    /** Строка для базы: `v1;<круги>;<мс>:<f|s>:<название>;…`, название — в URL-кодировке. */
    fun encode(): String = buildString {
        append("v1;").append(cycles)
        for (step in steps) {
            append(';').append(step.durationMillis)
            append(':').append(if (step.fullSignal) 'f' else 's')
            append(':').append(URLEncoder.encode(step.name, Charsets.UTF_8))
        }
    }

    companion object {
        const val MAX_CYCLES = 99

        fun single(durationMillis: Long, name: String = ""): TimerProgram =
            TimerProgram(listOf(TimerStep(durationMillis, name, fullSignal = true)))

        fun decode(text: String): TimerProgram {
            val parts = text.split(';')
            require(parts.size >= 3 && parts[0] == "v1") { "формат программы: $text" }
            val steps = parts.drop(2).map { part ->
                val (millis, signal, name) = part.split(':', limit = 3)
                TimerStep(millis.toLong(), URLDecoder.decode(name, Charsets.UTF_8), signal == "f")
            }
            return TimerProgram(steps, parts[1].toInt())
        }
    }
}

/** Сохранённый пресет. */
data class TimerPreset(
    val id: String,
    val name: String,
    val program: TimerProgram,
    val createdAt: Long,
)

enum class TimerState { RUNNING, PAUSED, RINGING }

/**
 * Запуск таймера (DATA_MODEL.md, п. 5); их может быть несколько. Программа —
 * копия: правка пресета не меняет идущий таймер.
 */
data class TimerRun(
    val id: String,
    val presetId: String?,
    val name: String,
    val program: TimerProgram,
    val state: TimerState,
    val stepIndex: Int,
    val cycleIndex: Int,
    /** Конец текущего шага, пока идёт. */
    val stepEndsAt: Moment?,
    /** Остаток шага на паузе; у звонящего — 0. */
    val remainingMillis: Long,
    val createdAt: Long,
) {
    val step: TimerStep get() = program.steps[stepIndex]

    val isLastStep: Boolean
        get() = stepIndex == program.steps.lastIndex && cycleIndex == program.cycles - 1

    /** Остаток текущего шага на момент [now]. */
    fun remainingAt(now: Moment): Long = when (state) {
        TimerState.RUNNING -> stepEndsAt!!.millisAfter(now).coerceAtLeast(0L)
        TimerState.PAUSED -> remainingMillis
        TimerState.RINGING -> 0L
    }
}

/** Чем отметить конец шага. */
enum class StepSignal { NONE, SHORT, FULL }

/** Итог проверки идущего таймера: новое состояние и сигнал за последний закончившийся шаг. */
data class TimerAdvance(
    val run: TimerRun,
    val signal: StepSignal,
    /** Закончившийся шаг опоздал больше чем на [AlarmRules.MISSED_AFTER_MILLIS]: не звонить. */
    val late: Boolean,
    /** Сколько шагов закончилось с прошлой проверки. */
    val endedSteps: Int,
)

/** Переходы таймера — чистые функции от «сейчас» (D-009, п. 5). */
object TimerRules {
    const val ADD_MILLIS: Long = 60_000L

    fun start(id: String, presetId: String?, name: String, program: TimerProgram, now: Moment): TimerRun =
        TimerRun(
            id = id,
            presetId = presetId,
            name = name,
            program = program,
            state = TimerState.RUNNING,
            stepIndex = 0,
            cycleIndex = 0,
            stepEndsAt = now + program.steps[0].durationMillis,
            remainingMillis = 0L,
            createdAt = now.utc,
        )

    fun pause(run: TimerRun, now: Moment): TimerRun =
        if (run.state != TimerState.RUNNING) run
        else run.copy(state = TimerState.PAUSED, stepEndsAt = null, remainingMillis = run.remainingAt(now))

    fun resume(run: TimerRun, now: Moment): TimerRun =
        if (run.state != TimerState.PAUSED) run
        else run.copy(state = TimerState.RUNNING, stepEndsAt = now + run.remainingMillis, remainingMillis = 0L)

    /** К началу программы, на паузе. */
    fun reset(run: TimerRun): TimerRun = run.copy(
        state = TimerState.PAUSED,
        stepIndex = 0,
        cycleIndex = 0,
        stepEndsAt = null,
        remainingMillis = run.program.steps[0].durationMillis,
    )

    /** «+1 мин»: у звонящего — ещё минута текущего шага. */
    fun addMinute(run: TimerRun, now: Moment): TimerRun = when (run.state) {
        TimerState.RUNNING -> run.copy(stepEndsAt = run.stepEndsAt!! + ADD_MILLIS)
        TimerState.PAUSED -> run.copy(remainingMillis = run.remainingMillis + ADD_MILLIS)
        TimerState.RINGING -> run.copy(state = TimerState.RUNNING, stepEndsAt = now + ADD_MILLIS, remainingMillis = 0L)
    }

    /**
     * Проверить идущий таймер в момент [now]: закончившиеся шаги засчитываются,
     * следующий шаг отсчитывается от конца предыдущего — цепочка не копит
     * задержку. Конец последнего шага — состояние «звонит».
     */
    fun advance(run: TimerRun, now: Moment): TimerAdvance {
        var current = run
        var signal = StepSignal.NONE
        var ended = 0
        var lateness = 0L
        while (current.state == TimerState.RUNNING) {
            val endsAt = current.stepEndsAt!!
            val left = endsAt.millisAfter(now)
            if (left > 0) break
            ended++
            lateness = -left
            signal = if (current.step.fullSignal || current.isLastStep) StepSignal.FULL else StepSignal.SHORT
            current = if (current.isLastStep) {
                current.copy(state = TimerState.RINGING, stepEndsAt = null, remainingMillis = 0L)
            } else {
                val lastInCycle = current.stepIndex == current.program.steps.lastIndex
                val nextStep = if (lastInCycle) 0 else current.stepIndex + 1
                val nextCycle = if (lastInCycle) current.cycleIndex + 1 else current.cycleIndex
                current.copy(
                    stepIndex = nextStep,
                    cycleIndex = nextCycle,
                    stepEndsAt = endsAt + current.program.steps[nextStep].durationMillis,
                )
            }
        }
        return TimerAdvance(current, signal, lateness > AlarmRules.MISSED_AFTER_MILLIS, ended)
    }

    /** Какой сигнал будет в конце текущего шага идущего таймера. */
    fun signalAtStepEnd(run: TimerRun): StepSignal =
        if (run.step.fullSignal || run.isLastStep) StepSignal.FULL else StepSignal.SHORT
}
