package com.dpsmpx.dpclock.data

import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.Moment
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.bits

// Строки базы ↔ модель :core:model.

fun AlarmEntity.toModel() = Alarm(id, hour, minute, Repeat.fromBits(repeatDays), enabled, createdAt)

fun Alarm.toEntity() = AlarmEntity(id, hour, minute, repeat.bits, enabled, createdAt)

fun PresetEntity.toModel() = TimerPreset(id, name, TimerProgram.decode(program), createdAt)

fun TimerPreset.toEntity() = PresetEntity(id, name, program.encode(), createdAt)

fun TimerRunEntity.toModel() = TimerRun(
    id = id,
    presetId = presetId,
    name = name,
    program = TimerProgram.decode(program),
    state = TimerState.valueOf(state),
    stepIndex = stepIndex,
    cycleIndex = cycleIndex,
    stepEndsAt = moment(stepEndsAtElapsed, bootCount, stepEndsAtUtc),
    remainingMillis = remainingMillis,
    createdAt = createdAt,
)

fun TimerRun.toEntity() = TimerRunEntity(
    id = id,
    presetId = presetId,
    name = name,
    program = program.encode(),
    state = state.name,
    stepIndex = stepIndex,
    cycleIndex = cycleIndex,
    stepEndsAtElapsed = stepEndsAt?.elapsed,
    bootCount = stepEndsAt?.bootCount,
    stepEndsAtUtc = stepEndsAt?.utc,
    remainingMillis = remainingMillis,
    createdAt = createdAt,
)

fun StopwatchEntity?.toModel(): Stopwatch = if (this == null) Stopwatch() else Stopwatch(
    state = StopwatchState.valueOf(state),
    startedAt = moment(startedAtElapsed, bootCount, startedAtUtc),
    accumulatedMillis = accumulatedMillis,
    laps = Stopwatch.decodeLaps(laps),
)

fun Stopwatch.toEntity() = StopwatchEntity(
    state = state.name,
    startedAtElapsed = startedAt?.elapsed,
    bootCount = startedAt?.bootCount,
    startedAtUtc = startedAt?.utc,
    accumulatedMillis = accumulatedMillis,
    laps = encodeLaps(),
)

fun SettingsEntity?.toModel(): Settings = if (this == null) Settings() else Settings(
    snoozeMinutes = snoozeMinutes,
    autoStopMinutes = autoStopMinutes,
    alarmOrder = AlarmOrder.entries.firstOrNull { it.name == alarmOrder } ?: AlarmOrder.TIME_OF_DAY,
    xiaomiHintsDone = xiaomiHintsDone,
)

fun Settings.toEntity() = SettingsEntity(
    snoozeMinutes = snoozeMinutes,
    autoStopMinutes = autoStopMinutes,
    alarmOrder = alarmOrder.name,
    xiaomiHintsDone = xiaomiHintsDone,
)

private fun moment(elapsed: Long?, bootCount: Int?, utc: Long?): Moment? =
    if (elapsed == null || bootCount == null || utc == null) null else Moment(elapsed, bootCount, utc)
