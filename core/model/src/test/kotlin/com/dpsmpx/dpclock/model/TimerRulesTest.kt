package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TimerRulesTest {
    private val t0 = Moment(elapsed = 1_000L, bootCount = 5, utc = 1_800_000_000_000L)

    private fun chain() = TimerProgram(
        listOf(TimerStep(30_000L, "работа", fullSignal = false), TimerStep(10_000L, "отдых", fullSignal = false)),
        cycles = 2,
    )

    @Test
    fun singleTimerRingsAtEnd() {
        val run = TimerRules.start("r", null, "", TimerProgram.single(60_000L), t0)
        assertEquals(60_000L, run.remainingAt(t0))
        val early = TimerRules.advance(run, t0 + 59_999L)
        assertEquals(StepSignal.NONE, early.signal)
        assertEquals(TimerState.RUNNING, early.run.state)
        assertEquals(null, early.endedAt)
        val done = TimerRules.advance(run, t0 + 60_250L)
        assertEquals(StepSignal.FULL, done.signal)
        assertEquals(TimerState.RINGING, done.run.state)
        assertFalse(done.late)
        // Журнал: шаг должен был кончиться в 60 с, сигнал — на 250 мс позже.
        assertEquals(t0 + 60_000L, done.endedAt)
    }

    @Test
    fun chainStepsGiveShortSignalAndKeepExactSchedule() {
        var run = TimerRules.start("r", null, "", chain(), t0)
        // Шаг закончился с опозданием 2 с — следующий всё равно кончится в 40 с от старта.
        val a = TimerRules.advance(run, t0 + 32_000L)
        assertEquals(StepSignal.SHORT, a.signal)
        run = a.run
        assertEquals(1, run.stepIndex)
        assertEquals(0, run.cycleIndex)
        assertEquals(8_000L, run.remainingAt(t0 + 32_000L))
        run = TimerRules.advance(run, t0 + 40_000L).run
        assertEquals(0, run.stepIndex)
        assertEquals(1, run.cycleIndex)
        run = TimerRules.advance(run, t0 + 70_000L).run
        assertEquals(1, run.stepIndex)
        assertTrue(run.isLastStep)
        val end = TimerRules.advance(run, t0 + 80_000L)
        assertEquals(StepSignal.FULL, end.signal)
        assertEquals(TimerState.RINGING, end.run.state)
    }

    @Test
    fun catchUpAfterLongSleepCountsStepsAndMarksLate() {
        val run = TimerRules.start("r", null, "", chain(), t0)
        val after = TimerRules.advance(run, t0 + 3_600_000L)
        assertEquals(4, after.endedSteps)
        assertEquals(t0 + 80_000L, after.endedAt)
        assertEquals(TimerState.RINGING, after.run.state)
        assertTrue(after.late)
    }

    @Test
    fun pauseResumeKeepRemaining() {
        var run = TimerRules.start("r", null, "", TimerProgram.single(60_000L), t0)
        run = TimerRules.pause(run, t0 + 15_000L)
        assertEquals(TimerState.PAUSED, run.state)
        assertEquals(45_000L, run.remainingAt(t0 + 999_999L))
        run = TimerRules.resume(run, t0 + 100_000L)
        assertEquals(45_000L, run.remainingAt(t0 + 100_000L))
        run = TimerRules.addMinute(run, t0 + 100_000L)
        assertEquals(105_000L, run.remainingAt(t0 + 100_000L))
    }

    @Test
    fun addMinuteToRingingRestartsCountdown() {
        val ringing = TimerRules.advance(TimerRules.start("r", null, "", TimerProgram.single(1_000L), t0), t0 + 1_000L).run
        val more = TimerRules.addMinute(ringing, t0 + 5_000L)
        assertEquals(TimerState.RUNNING, more.state)
        assertEquals(60_000L, more.remainingAt(t0 + 5_000L))
    }

    @Test
    fun resetGoesBackToFirstStepPaused() {
        val run = TimerRules.advance(TimerRules.start("r", null, "", chain(), t0), t0 + 45_000L).run
        val reset = TimerRules.reset(run)
        assertEquals(TimerState.PAUSED, reset.state)
        assertEquals(0, reset.stepIndex)
        assertEquals(0, reset.cycleIndex)
        assertEquals(30_000L, reset.remainingMillis)
    }

    @Test
    fun rebootUsesUtc() {
        val run = TimerRules.start("r", null, "", TimerProgram.single(600_000L), t0)
        // Перезагрузка: монотонные часы начались заново, номер загрузки другой.
        val afterBoot = Moment(elapsed = 20_000L, bootCount = 6, utc = t0.utc + 120_000L)
        assertEquals(480_000L, run.remainingAt(afterBoot))
        val rebased = run.stepEndsAt!!.rebasedTo(afterBoot)
        assertEquals(6, rebased.bootCount)
        assertEquals(500_000L, rebased.elapsed)
    }

    @Test
    fun programCodecRoundTrip() {
        val program = TimerProgram(
            listOf(TimerStep(1_000L, "чай; с:лимоном %", fullSignal = false), TimerStep(2_000L)),
            cycles = 3,
        )
        assertEquals(program, TimerProgram.decode(program.encode()))
        assertEquals(TimerProgram.single(5_000L), TimerProgram.decode(TimerProgram.single(5_000L).encode()))
        assertEquals(9_000L, program.totalMillis)
    }

    @Test
    fun stopwatchCountsAndMarksLaps() {
        var sw = Stopwatch().start(t0)
        sw = sw.lap(t0 + 10_000L)
        sw = sw.lap(t0 + 25_000L)
        sw = sw.pause(t0 + 30_000L)
        assertEquals(30_000L, sw.elapsedAt(t0 + 99_000L))
        sw = sw.start(t0 + 100_000L).lap(t0 + 103_000L)
        assertEquals(33_000L, sw.elapsedAt(t0 + 103_000L))
        val rows = sw.lapRows()
        assertEquals(listOf(3, 2, 1), rows.map { it.number })
        assertEquals(listOf(8_000L, 15_000L, 10_000L), rows.map { it.lapMillis })
        assertTrue(rows[0].isBest)
        assertTrue(rows[1].isWorst)
        assertEquals(sw.laps, Stopwatch.decodeLaps(sw.encodeLaps()))
        assertEquals(Stopwatch(), sw.reset())
    }

    @Test
    fun durationInputFillsFromRight() {
        var input = DurationInput()
        input = input.press(0)
        assertTrue(input.isEmpty)
        for (d in listOf(1, 3, 0)) input = input.press(d)
        assertEquals(1, input.minutes)
        assertEquals(30, input.seconds)
        assertEquals(90_000L, input.millis)
        assertEquals(DurationInput("13"), input.backspace())
        assertEquals(DurationInput("630"), DurationInput("90").plusMinutes(5))
        var full = DurationInput()
        repeat(8) { full = full.press(9) }
        assertEquals("999999", full.digits)
        assertEquals(DurationInput("1000"), DurationInput.ofMillis(600_000L))
    }

    @Test
    fun countdownDisplayRoundsUp() {
        assertEquals(Hms(0, 0, 1, 0), Hms.ofCountdown(1L))
        assertEquals(Hms(0, 0, 0, 0), Hms.ofCountdown(0L))
        assertEquals(Hms(1, 1, 1, 50), Hms.of(3_661_500L))
    }

    @Test
    fun programWithSoundsAndSleepRoundTrips() {
        val program = TimerProgram(
            listOf(
                TimerStep(1_000L, "работа", fullSignal = false, sound = AlarmSound.Builtin("marimba")),
                TimerStep(2_000L, "отдых", fullSignal = false),
                TimerStep(3_000L, "", sound = AlarmSound.Default),
                TimerStep(4_000L, "a:b;c", sound = AlarmSound.Ringtone("content://m/1?x=1;y", "Утро: ранее")),
            ),
            cycles = 2,
            sound = AlarmSound.Builtin("dawn"),
        )
        assertEquals(program, TimerProgram.decode(program.encode()))
        // Шаг без своего звука звучит звуком программы; «мелодия телефона» у шага — своя.
        assertEquals(AlarmSound.Builtin("dawn"), program.soundOf(1))
        assertEquals(AlarmSound.Default, program.soundOf(2))
        assertEquals(TimerProgram.sleep(900_000L), TimerProgram.decode(TimerProgram.sleep(900_000L).encode()))
    }

    @Test
    fun presetsOfVersion1StillDecode() {
        val old = TimerProgram.decode("v1;3;30000:s:%D1%87%D0%B0%D0%B9;10000:f:")
        assertEquals(TimerProgram(listOf(TimerStep(30_000L, "чай", fullSignal = false), TimerStep(10_000L)), cycles = 3), old)
    }

    @Test
    fun sleepTimerEndsWithoutRinging() {
        val run = TimerRules.start("s", null, "", TimerProgram.sleep(60_000L), t0)
        assertEquals(StepSignal.SLEEP, TimerRules.signalAtStepEnd(run))
        val done = TimerRules.advance(run, t0 + 60_000L)
        assertEquals(StepSignal.SLEEP, done.signal)
        assertEquals(TimerState.RINGING, done.run.state)
    }

    @Test
    fun advanceReportsTheSoundOfTheEndedStep() {
        val program = TimerProgram(
            listOf(TimerStep(1_000L, fullSignal = false, sound = AlarmSound.Builtin("pulse")), TimerStep(1_000L)),
            sound = AlarmSound.Builtin("chime"),
        )
        val run = TimerRules.start("r", null, "", program, t0)
        assertEquals(AlarmSound.Builtin("pulse"), TimerRules.advance(run, t0 + 1_000L).sound)
        assertEquals(AlarmSound.Builtin("chime"), TimerRules.advance(run, t0 + 2_000L).sound)
    }

    @Test
    fun ceilDivWithoutAndroid15() {
        // Своё деление вверх вместо Math.ceilDiv: тот есть в Android только с API 35.
        assertEquals(0L, ceilDiv(0L, 1000L))
        assertEquals(1L, ceilDiv(1L, 1000L))
        assertEquals(1L, ceilDiv(1000L, 1000L))
        assertEquals(2L, ceilDiv(1001L, 1000L))
        assertEquals(Hms.of(2000L), Hms.ofCountdown(1001L))
    }
}
