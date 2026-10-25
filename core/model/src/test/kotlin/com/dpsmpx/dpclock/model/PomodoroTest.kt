package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Помидор и интервальный таймер (D-025, п. 1, 4). */
class PomodoroTest {
    private val t0 = Moment(elapsed = 1_000L, bootCount = 5, utc = 1_800_000_000_000L)
    private val minute = 60_000L

    @Test
    fun classicProgramIsFourPomodorosThenALongBreak() {
        val program = Pomodoro().program("Работа", "Перерыв", "Длинный")
        assertEquals(listOf(25, 5, 25, 5, 25, 5, 25, 15), program.steps.map { (it.durationMillis / minute).toInt() })
        assertEquals(listOf(true, false, true, false, true, false, true, false), program.steps.map { it.focus })
        assertTrue(program.hold)
        assertTrue(program.steps.all { it.fullSignal })
        assertTrue(program.isPomodoro)
        val auto = Pomodoro(autoAdvance = true, sets = 3).program("w", "s", "l")
        assertFalse(auto.hold)
        assertEquals(3, auto.cycles)
        assertTrue(auto.steps.none { it.fullSignal })
    }

    @Test
    fun holdWaitsForNextAndCountsFocus() {
        val program = Pomodoro(workMinutes = 25, shortMinutes = 5, longEvery = 2).program("w", "s", "l")
        val run = TimerRules.start("r", null, "", program, t0)
        assertTrue(run.wantsDnd.not())
        val dndProgram = program.copy(dnd = true)
        assertTrue(TimerRules.start("r", null, "", dndProgram, t0).wantsDnd)
        val ended = TimerRules.advance(run, t0 + 25 * minute + 300L)
        assertEquals(StepSignal.FULL, ended.signal)
        assertEquals(1, ended.focusEnded)
        assertEquals(25 * minute, ended.focusMillis)
        // Перерыв не начался: ждёт «Дальше» с полной длиной.
        assertEquals(TimerState.PAUSED, ended.run.state)
        assertEquals(1, ended.run.stepIndex)
        assertEquals(5 * minute, ended.run.remainingMillis)
        assertTrue(ended.run.isWaiting)
        // Даже через час — ничего не идёт и ничего не засчитано.
        assertEquals(0, TimerRules.advance(ended.run, t0 + 90 * minute).endedSteps)
        val resumed = TimerRules.resume(ended.run, t0 + 40 * minute)
        assertFalse(resumed.isWaiting)
        assertEquals(5 * minute, resumed.remainingAt(t0 + 40 * minute))
    }

    @Test
    fun autoAdvanceRunsThroughAndCountsSeveralPomodoros() {
        val program = Pomodoro(workMinutes = 10, shortMinutes = 2, longMinutes = 5, longEvery = 2, autoAdvance = true).program("w", "s", "l")
        val run = TimerRules.start("r", null, "", program, t0)
        // 10 + 2 + 10 — две работы и перерыв закончились, идёт длинный перерыв.
        val a = TimerRules.advance(run, t0 + 22 * minute + 1L)
        assertEquals(3, a.endedSteps)
        assertEquals(2, a.focusEnded)
        assertEquals(20 * minute, a.focusMillis)
        assertEquals(StepSignal.SHORT, a.signal)
        assertEquals(3, a.run.stepIndex)
        assertEquals(TimerState.RUNNING, a.run.state)
        val done = TimerRules.advance(a.run, t0 + 27 * minute)
        assertEquals(StepSignal.FULL, done.signal)
        assertEquals(TimerState.RINGING, done.run.state)
    }

    @Test
    fun skipMovesOnAndEndsAtTheLastStep() {
        val program = Pomodoro(longEvery = 1).program("w", "s", "l")
        val run = TimerRules.start("r", null, "", program, t0)
        val brk = TimerRules.skip(run, t0 + minute)!!
        assertEquals(1, brk.stepIndex)
        assertEquals(TimerState.RUNNING, brk.state)
        assertEquals(15 * minute, brk.remainingAt(t0 + minute))
        assertNull(TimerRules.skip(brk, t0 + 2 * minute))
    }

    @Test
    fun intervalTimerBeepsForeverOrNTimes() {
        val forever = TimerProgram.interval(10_000L, null)
        var run = TimerRules.start("r", null, "", forever, t0)
        repeat(500) { i ->
            val a = TimerRules.advance(run, t0 + (i + 1) * 10_000L)
            assertEquals(StepSignal.SHORT, a.signal)
            run = a.run
        }
        assertEquals(TimerState.RUNNING, run.state)
        assertEquals(500, run.cycleIndex)
        val three = TimerProgram.interval(5_000L, 3)
        val a = TimerRules.advance(TimerRules.start("r", null, "", three, t0), t0 + 15_000L)
        assertEquals(StepSignal.FULL, a.signal)
        assertEquals(TimerState.RINGING, a.run.state)
    }

    @Test
    fun programFlagsRoundTripAndOldFormatsStillDecode() {
        val pomodoro = Pomodoro(dnd = true).program("Работа: глубокая", "Перерыв", "Длинный")
        assertEquals(pomodoro, TimerProgram.decode(pomodoro.encode()))
        val forever = TimerProgram.interval(7_000L, null, "Пресс")
        assertEquals(forever, TimerProgram.decode(forever.encode()))
        // Строка версии 0.5.0–0.13.0.
        val v2 = "v2;2;-;default;;30000:s:%D0%A0:::;10000:f:::"
        val decoded = TimerProgram.decode(v2)
        assertEquals(2, decoded.cycles)
        assertFalse(decoded.hold || decoded.forever || decoded.dnd || decoded.isPomodoro)
        assertEquals(listOf(false, true), decoded.steps.map { it.fullSignal })
    }

    @Test
    fun settingsCodecFallsBackToClassic() {
        val custom = Pomodoro(50, 10, 30, 3, 2, autoAdvance = true, dnd = true)
        assertEquals(custom, Pomodoro.decode(custom.encode()))
        assertEquals(Pomodoro(), Pomodoro.decode(""))
        assertEquals(Pomodoro(), Pomodoro.decode("0,0,0,0,0,0,0"))
    }

    @Test
    fun pomodorosAreCountedInStatistics() {
        val day = 86_400_000L
        val now = java.time.Instant.ofEpochMilli(1_800_000_000_000L)
        val activities = listOf(
            ActivityEvent("1", ActivityKind.POMODORO, now.toEpochMilli() - 2 * day, now.toEpochMilli() - 2 * day + 25 * minute, count = 1, amountMillis = 25 * minute),
            ActivityEvent("2", ActivityKind.POMODORO, now.toEpochMilli() - 3_600_000L, now.toEpochMilli(), count = 2, amountMillis = 50 * minute),
        )
        val stats = StatsRules.report(emptyList(), activities, StatsPeriod.WEEK, now, java.time.ZoneOffset.UTC, 480).timers
        assertEquals(3, stats.pomodoros)
        assertEquals(75 * minute, stats.focusMillis)
        assertEquals(2, stats.pomodoroDays)
        assertEquals(0, stats.count)
    }
}
