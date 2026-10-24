package com.dpsmpx.dpclock.data

import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.AlarmTemplate
import com.dpsmpx.dpclock.model.ExceptionPeriod
import com.dpsmpx.dpclock.model.Moment
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalStyle
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.VolumeButtons
import com.dpsmpx.dpclock.model.bits
import java.time.LocalDate

// Строки базы ↔ модель :core:model.

fun AlarmEntity.toModel() = Alarm(
    id = id,
    hour = hour,
    minute = minute,
    repeat = when {
        workdays -> Repeat.Workdays
        cycleAnchor != null && cycleDays != null -> Repeat.Cycle(LocalDate.ofEpochDay(cycleAnchor), Repeat.Cycle.decodeDays(cycleDays))
        repeatDate != null -> Repeat.OnDate(LocalDate.ofEpochDay(repeatDate))
        else -> Repeat.fromBits(repeatDays)
    },
    enabled = enabled,
    createdAt = createdAt,
    label = label,
    sound = AlarmSound.decode(sound, soundTitle),
    vibrate = vibrate,
    rampSeconds = rampSeconds,
    snoozeMinutes = snoozeMinutes,
    skipDate = skipDate?.let(LocalDate::ofEpochDay),
    honorExceptions = honorExceptions,
    groupId = groupId,
)

fun Alarm.toEntity() = AlarmEntity(
    id = id,
    hour = hour,
    minute = minute,
    repeatDays = repeat.bits,
    enabled = enabled,
    createdAt = createdAt,
    label = label,
    sound = sound.encode(),
    soundTitle = sound.title,
    vibrate = vibrate,
    rampSeconds = rampSeconds,
    snoozeMinutes = snoozeMinutes,
    skipDate = skipDate?.toEpochDay(),
    repeatDate = (repeat as? Repeat.OnDate)?.date?.toEpochDay(),
    honorExceptions = honorExceptions,
    groupId = groupId,
    cycleAnchor = (repeat as? Repeat.Cycle)?.anchor?.toEpochDay(),
    cycleDays = (repeat as? Repeat.Cycle)?.encodeDays(),
    workdays = repeat == Repeat.Workdays,
)

fun GroupEntity.toModel() = AlarmGroup(id, name, createdAt)

fun AlarmGroup.toEntity() = GroupEntity(id, name, createdAt)

fun TemplateEntity.toModel() = AlarmTemplate(id, name, AlarmTemplate.decodeAlarms(alarms), createdAt)

fun AlarmTemplate.toEntity() = TemplateEntity(id, name, encodeAlarms(), createdAt)

fun ExceptionEntity.toModel() = ExceptionPeriod(id, name, LocalDate.ofEpochDay(startDay), LocalDate.ofEpochDay(endDay))

fun ExceptionPeriod.toEntity() = ExceptionEntity(id, name, start.toEpochDay(), end.toEpochDay())

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
    alarmOrder = enumOr(alarmOrder, AlarmOrder.TIME_OF_DAY),
    xiaomiHintsDone = xiaomiHintsDone,
    signalStyle = enumOr(signalStyle, SignalStyle.FULL_SCREEN),
    volumeButtons = enumOr(volumeButtons, VolumeButtons.SNOOZE),
    holidaysRu = holidaysRu,
)

fun Settings.toEntity() = SettingsEntity(
    snoozeMinutes = snoozeMinutes,
    autoStopMinutes = autoStopMinutes,
    alarmOrder = alarmOrder.name,
    xiaomiHintsDone = xiaomiHintsDone,
    signalStyle = signalStyle.name,
    volumeButtons = volumeButtons.name,
    holidaysRu = holidaysRu,
)

/** Неизвестное значение (например, из копии новой версии) — значение по умолчанию. */
private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

private fun moment(elapsed: Long?, bootCount: Int?, utc: Long?): Moment? =
    if (elapsed == null || bootCount == null || utc == null) null else Moment(elapsed, bootCount, utc)
