package com.dpsmpx.dpclock.model

import java.net.URLDecoder
import java.net.URLEncoder

/** Шаг программы таймера. */
data class TimerStep(
    val durationMillis: Long,
    val name: String = "",
    /** Полный сигнал — как будильник; короткий — звук и сразу следующий шаг. */
    val fullSignal: Boolean = true,
    /** Свой звук шага (D-016, п. 2); null — звук программы. */
    val sound: AlarmSound? = null,
    /** Работа помидора (D-025, п. 1): считается в статистике, на ней — «Не беспокоить». */
    val focus: Boolean = false,
) {
    init {
        require(durationMillis > 0) { "длительность $durationMillis" }
    }
}

/**
 * Одна модель для одиночного таймера, пресета, цепочки и цикла (DATA_MODEL.md, п. 5).
 * Одиночный таймер — один шаг с полным сигналом. [sound] — звук программы
 * (D-016, п. 2); [sleep] — таймер засыпания: в конце без сигнала
 * останавливает музыку и видео (D-016, п. 1). [hold] — после шага ждать
 * «Дальше» (помидор, D-025, п. 1); [forever] — круги, пока не остановят
 * (каждые N секунд, D-025, п. 4); [dnd] — «Не беспокоить» на шагах работы.
 */
data class TimerProgram(
    val steps: List<TimerStep>,
    val cycles: Int = 1,
    val sound: AlarmSound = AlarmSound.Default,
    val sleep: Boolean = false,
    val hold: Boolean = false,
    val forever: Boolean = false,
    val dnd: Boolean = false,
) {
    init {
        require(steps.isNotEmpty()) { "программа без шагов" }
        require(cycles >= 1) { "кругов $cycles" }
        require(!sleep || (steps.size == 1 && cycles == 1 && !hold && !forever)) { "таймер засыпания — один шаг" }
        require(!forever || cycles == 1) { "без конца — один круг в записи" }
    }

    /** Длина программы; у бесконечной — одного круга. */
    val totalMillis: Long get() = cycleMillis * cycles

    val cycleMillis: Long get() = steps.sumOf { it.durationMillis }

    val isChain: Boolean get() = steps.size > 1 || cycles > 1 || forever

    /** Помидор: есть шаги работы. */
    val isPomodoro: Boolean get() = steps.any { it.focus }

    /** Звук в конце шага [index]: свой у шага или звук программы. */
    fun soundOf(index: Int): AlarmSound = steps[index].sound ?: sound

    /**
     * Строка для базы: `v3;<круги>;<флаги>;<звук>;<название звука>;<мс>:<флаги шага>:<название>:<звук шага>:<название>;…`,
     * текст — в URL-кодировке. Флаги: `z` — засыпания, `h` — ждать «Дальше», `r` — без конца,
     * `d` — «Не беспокоить», `-` — никаких; шага: `f` или `s` — полный или короткий сигнал,
     * `w` — работа. Звук шага: пусто — как у программы, `default` — мелодия телефона.
     */
    fun encode(): String = buildString {
        append("v3;").append(cycles)
        val flags = buildString {
            if (sleep) append('z')
            if (hold) append('h')
            if (forever) append('r')
            if (dnd) append('d')
        }
        append(';').append(flags.ifEmpty { "-" })
        append(';').append(url(sound.encode())).append(';').append(url(sound.title))
        for (step in steps) {
            append(';').append(step.durationMillis)
            append(':').append(if (step.fullSignal) 'f' else 's')
            if (step.focus) append('w')
            append(':').append(url(step.name))
            val code = when (val own = step.sound) {
                null -> ""
                AlarmSound.Default -> STEP_DEFAULT
                else -> own.encode()
            }
            append(':').append(url(code)).append(':').append(url(step.sound?.title.orEmpty()))
        }
    }

    companion object {
        const val MAX_CYCLES = 99
        private const val STEP_DEFAULT = "default"

        fun single(durationMillis: Long, name: String = ""): TimerProgram =
            TimerProgram(listOf(TimerStep(durationMillis, name, fullSignal = true)))

        /** Таймер засыпания на [durationMillis]. */
        fun sleep(durationMillis: Long): TimerProgram = TimerProgram(listOf(TimerStep(durationMillis)), sleep = true)

        /**
         * Каждые [stepMillis] — короткий сигнал (D-025, п. 4): [count] раз, последний — полный;
         * null — пока не остановят.
         */
        fun interval(stepMillis: Long, count: Int?, name: String = ""): TimerProgram = TimerProgram(
            steps = listOf(TimerStep(stepMillis, name, fullSignal = false)),
            cycles = count ?: 1,
            forever = count == null,
        )

        /** Читает и `v1`, `v2` — программы версий до 0.5.0 и до 0.14.0. */
        fun decode(text: String): TimerProgram {
            val parts = text.split(';')
            return when (parts.firstOrNull()) {
                "v1" -> {
                    require(parts.size >= 3) { "формат программы: $text" }
                    val steps = parts.drop(2).map { part ->
                        val (millis, signal, name) = part.split(':', limit = 3)
                        TimerStep(millis.toLong(), unurl(name), signal == "f")
                    }
                    TimerProgram(steps, parts[1].toInt())
                }
                "v2", "v3" -> {
                    require(parts.size >= 6) { "формат программы: $text" }
                    val steps = parts.drop(5).map { part ->
                        val f = part.split(':')
                        val code = unurl(f[3])
                        val sound = when (code) {
                            "" -> null
                            STEP_DEFAULT -> AlarmSound.Default
                            else -> AlarmSound.decode(code, unurl(f[4]))
                        }
                        TimerStep(f[0].toLong(), unurl(f[2]), f[1].startsWith('f'), sound, focus = 'w' in f[1])
                    }
                    val flags = parts[2]
                    TimerProgram(
                        steps = steps,
                        cycles = parts[1].toInt(),
                        sound = AlarmSound.decode(unurl(parts[3]), unurl(parts[4])),
                        sleep = 'z' in flags,
                        hold = 'h' in flags,
                        forever = 'r' in flags,
                        dnd = 'd' in flags,
                    )
                }
                else -> throw IllegalArgumentException("формат программы: $text")
            }
        }

        // Перегрузки с Charset — только с Android 13; по имени кодировки — есть везде.
        private fun url(text: String) = URLEncoder.encode(text, "UTF-8")

        private fun unurl(text: String) = URLDecoder.decode(text, "UTF-8")
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
        get() = !program.forever && stepIndex == program.steps.lastIndex && cycleIndex == program.cycles - 1

    /** Ждёт «Дальше»: шаг закончился, следующий ещё не начат (D-025, п. 1). */
    val isWaiting: Boolean
        get() = program.hold && state == TimerState.PAUSED && remainingMillis == program.steps[stepIndex].durationMillis &&
            (stepIndex > 0 || cycleIndex > 0)

    /** Идёт шаг работы с «Не беспокоить» (D-025, п. 1). */
    val wantsDnd: Boolean get() = program.dnd && state == TimerState.RUNNING && step.focus

    /** Остаток текущего шага на момент [now]. */
    fun remainingAt(now: Moment): Long = when (state) {
        TimerState.RUNNING -> stepEndsAt!!.millisAfter(now).coerceAtLeast(0L)
        TimerState.PAUSED -> remainingMillis
        TimerState.RINGING -> 0L
    }
}

/** Чем отметить конец шага. */
/** [SLEEP] — конец таймера засыпания: без сигнала, остановить музыку и видео. */
enum class StepSignal { NONE, SHORT, FULL, SLEEP }

/** Итог проверки идущего таймера: новое состояние и сигнал за последний закончившийся шаг. */
data class TimerAdvance(
    val run: TimerRun,
    val signal: StepSignal,
    /** Закончившийся шаг опоздал больше чем на [AlarmRules.MISSED_AFTER_MILLIS]: не звонить. */
    val late: Boolean,
    /** Сколько шагов закончилось с прошлой проверки. */
    val endedSteps: Int,
    /** Звук сигнала: последнего закончившегося шага (D-016, п. 2). */
    val sound: AlarmSound = AlarmSound.Default,
    /** Расчётный конец последнего закончившегося шага; null — ни один не закончился. Для журнала. */
    val endedAt: Moment? = null,
    /** Сколько шагов работы помидора закончилось и сколько они длились (D-025, п. 1). */
    val focusEnded: Int = 0,
    val focusMillis: Long = 0L,
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
     * задержку. Конец последнего шага — состояние «звонит»; у программы с
     * ожиданием конец шага — пауза в начале следующего (D-025, п. 1).
     */
    fun advance(run: TimerRun, now: Moment): TimerAdvance {
        var current = run
        var signal = StepSignal.NONE
        var sound: AlarmSound = AlarmSound.Default
        var ended = 0
        var endedAt: Moment? = null
        var lateness = 0L
        var focusEnded = 0
        var focusMillis = 0L
        while (current.state == TimerState.RUNNING) {
            val endsAt = current.stepEndsAt!!
            val left = endsAt.millisAfter(now)
            if (left > 0) break
            ended++
            endedAt = endsAt
            lateness = -left
            signal = signalAtStepEnd(current)
            sound = current.program.soundOf(current.stepIndex)
            if (current.step.focus) {
                focusEnded++
                focusMillis += current.step.durationMillis
            }
            current = if (current.isLastStep) {
                current.copy(state = TimerState.RINGING, stepEndsAt = null, remainingMillis = 0L)
            } else {
                val moved = nextStep(current)
                if (current.program.hold) {
                    moved.copy(state = TimerState.PAUSED, stepEndsAt = null, remainingMillis = moved.step.durationMillis)
                } else {
                    moved.copy(stepEndsAt = endsAt + moved.step.durationMillis)
                }
            }
        }
        return TimerAdvance(current, signal, lateness > AlarmRules.MISSED_AFTER_MILLIS, ended, sound, endedAt, focusEnded, focusMillis)
    }

    /**
     * «Дальше» (D-025, п. 1): текущий шаг досрочно кончается без сигнала, следующий
     * идёт с [now]. У последнего шага — null: программа закончена.
     */
    fun skip(run: TimerRun, now: Moment): TimerRun? {
        if (run.isLastStep || run.state == TimerState.RINGING) return null
        val moved = nextStep(run)
        return moved.copy(state = TimerState.RUNNING, stepEndsAt = now + moved.step.durationMillis, remainingMillis = 0L)
    }

    /** Индексы следующего шага: после последнего шага круга — первый шаг следующего круга. */
    private fun nextStep(run: TimerRun): TimerRun {
        val lastInCycle = run.stepIndex == run.program.steps.lastIndex
        return run.copy(
            stepIndex = if (lastInCycle) 0 else run.stepIndex + 1,
            cycleIndex = if (lastInCycle) run.cycleIndex + 1 else run.cycleIndex,
        )
    }

    /** Какой сигнал будет в конце текущего шага идущего таймера. */
    fun signalAtStepEnd(run: TimerRun): StepSignal = when {
        run.program.sleep && run.isLastStep -> StepSignal.SLEEP
        run.step.fullSignal || run.isLastStep -> StepSignal.FULL
        else -> StepSignal.SHORT
    }
}
