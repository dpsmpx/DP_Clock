package com.dpsmpx.dpclock.data

import com.dpsmpx.dpclock.model.ActivityEvent
import com.dpsmpx.dpclock.model.ActivityKind
import com.dpsmpx.dpclock.model.AfterAlarm
import com.dpsmpx.dpclock.model.Alarm
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.AlarmOrder
import com.dpsmpx.dpclock.model.AlarmSound
import com.dpsmpx.dpclock.model.AlarmTemplate
import com.dpsmpx.dpclock.model.Appearance
import com.dpsmpx.dpclock.model.Bedtime
import com.dpsmpx.dpclock.model.BedtimeMode
import com.dpsmpx.dpclock.model.Chimes
import com.dpsmpx.dpclock.model.ClockBackground
import com.dpsmpx.dpclock.model.ClockFont
import com.dpsmpx.dpclock.model.ClockStyle
import com.dpsmpx.dpclock.model.ExceptionPeriod
import com.dpsmpx.dpclock.model.Moment
import com.dpsmpx.dpclock.model.NoiseKind
import com.dpsmpx.dpclock.model.Pomodoro
import com.dpsmpx.dpclock.model.Repeat
import com.dpsmpx.dpclock.model.RulesCode
import com.dpsmpx.dpclock.model.Settings
import com.dpsmpx.dpclock.model.SignalEvent
import com.dpsmpx.dpclock.model.SignalOutcome
import com.dpsmpx.dpclock.model.SignalSource
import com.dpsmpx.dpclock.model.SignalStyle
import com.dpsmpx.dpclock.model.Stopwatch
import com.dpsmpx.dpclock.model.StopwatchState
import com.dpsmpx.dpclock.model.SunSignals
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskLevel
import com.dpsmpx.dpclock.model.TaskMark
import com.dpsmpx.dpclock.model.TaskRepeat
import com.dpsmpx.dpclock.model.ThemeMode
import com.dpsmpx.dpclock.model.TimerPreset
import com.dpsmpx.dpclock.model.TimerProgram
import com.dpsmpx.dpclock.model.TimerRun
import com.dpsmpx.dpclock.model.TimerState
import com.dpsmpx.dpclock.model.VolumeButtons
import com.dpsmpx.dpclock.model.WakeCheck
import com.dpsmpx.dpclock.model.WakeLevel
import com.dpsmpx.dpclock.model.WakeMethod
import com.dpsmpx.dpclock.model.Warnings
import com.dpsmpx.dpclock.model.WeatherMode
import com.dpsmpx.dpclock.model.WeatherPlace
import com.dpsmpx.dpclock.model.WeatherSettings
import com.dpsmpx.dpclock.model.bits
import com.dpsmpx.dpclock.model.days
import com.dpsmpx.dpclock.model.isWallpaper
import java.time.LocalDate
import java.time.LocalTime

// Строки базы ↔ модель :core:model.

fun AlarmEntity.toModel() = Alarm(
    id = id,
    hour = hour,
    minute = minute,
    // Испорченная строка правил — однократный: будильник хотя бы виден и правится.
    repeat = repeatRules?.let { text -> runCatching { RulesCode.decode(text) }.getOrDefault(Repeat.Once) } ?: when {
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
    skipTime = skipTime?.takeIf { it in 0 until 24 * 60 }?.let { LocalTime.of(it / 60, it % 60) },
    honorExceptions = honorExceptions,
    groupId = groupId,
    wakeCheck = WakeCheck(
        method = enumOr(wakeMethod, WakeMethod.NONE),
        level = enumOr(wakeLevel, WakeLevel.MEDIUM),
        tasks = wakeTasks.coerceIn(1, WakeCheck.MAX_TASKS),
        reference = wakeReference,
        snoozeAllowed = wakeSnooze,
        recheckMinutes = recheckMinutes.coerceAtLeast(0),
    ),
    gentleMinutes = gentleMinutes.coerceAtLeast(0),
    after = AfterAlarm(afterPresetId, afterReminder, afterReminderMinutes.coerceAtLeast(0), afterApp),
    calendarEventAt = calendarEventAt,
    warnings = Warnings.decode(warnings),
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
    wakeMethod = wakeCheck.method.name,
    wakeLevel = wakeCheck.level.name,
    wakeTasks = wakeCheck.tasks,
    wakeReference = wakeCheck.reference,
    wakeSnooze = wakeCheck.snoozeAllowed,
    recheckMinutes = wakeCheck.recheckMinutes,
    gentleMinutes = gentleMinutes,
    afterPresetId = after.presetId,
    afterReminder = after.reminder,
    afterReminderMinutes = after.reminderMinutes,
    afterApp = after.app,
    calendarEventAt = calendarEventAt,
    repeatRules = (repeat as? Repeat.Rules)?.let(RulesCode::encode),
    skipTime = skipTime?.let { it.hour * 60 + it.minute },
    warnings = Warnings.encode(warnings),
)

fun GroupEntity.toModel() = AlarmGroup(id, name, createdAt)

fun AlarmGroup.toEntity() = GroupEntity(id, name, createdAt)

fun TemplateEntity.toModel() = AlarmTemplate(id, name, AlarmTemplate.decodeAlarms(alarms), createdAt)

fun AlarmTemplate.toEntity() = TemplateEntity(id, name, encodeAlarms(), createdAt)

fun ExceptionEntity.toModel() = ExceptionPeriod(id, name, LocalDate.ofEpochDay(startDay), LocalDate.ofEpochDay(endDay))

fun ExceptionPeriod.toEntity() = ExceptionEntity(id, name, start.toEpochDay(), end.toEpochDay())

fun TaskEntity.toModel() = Task(
    id = id,
    title = title,
    note = note,
    date = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    time = minute?.let { LocalTime.of(it / 60 % 24, it % 60) },
    reminder = reminder,
    repeat = TaskRepeat.decode(repeat),
    done = done,
    doneAt = doneAt,
    createdAt = createdAt,
    permanent = permanent,
    parentId = parentId,
    difficulty = TaskLevel.entries.firstOrNull { it.name == difficulty } ?: TaskLevel.NONE,
    urgency = TaskLevel.entries.firstOrNull { it.name == urgency } ?: TaskLevel.NONE,
    remindLate = remindLate,
    remindAfterDays = remindAfterDays,
    inCalendar = inCalendar,
    eventId = eventId,
    nudged = nudged,
)

fun Task.toEntity() = TaskEntity(
    id = id,
    title = title,
    note = note,
    date = date?.toString(),
    minute = time?.let { it.hour * 60 + it.minute },
    reminder = reminder,
    repeat = repeat.code,
    done = done,
    doneAt = doneAt,
    createdAt = createdAt,
    inCalendar = inCalendar,
    eventId = eventId,
    permanent = permanent,
    parentId = parentId,
    difficulty = difficulty.name,
    urgency = urgency.name,
    remindLate = remindLate,
    remindAfterDays = remindAfterDays,
    nudged = nudged,
)

fun TaskMarkEntity.toModel() = TaskMark(id, taskId, at)

fun TaskMark.toEntity() = TaskMarkEntity(id, taskId, at)

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
    showAlarmCountdown = showAlarmCountdown,
    xiaomiHintsDone = xiaomiHintsDone,
    signalStyle = enumOr(signalStyle, SignalStyle.FULL_SCREEN),
    volumeButtons = enumOr(volumeButtons, VolumeButtons.SNOOZE),
    holidaysRu = holidaysRu,
    bedtime = runCatching {
        Bedtime(
            mode = enumOr(bedtimeMode, BedtimeMode.OFF),
            sleepMinutes = sleepMinutes,
            hour = bedtimeHour,
            minute = bedtimeMinute,
            days = Repeat.fromBits(bedtimeDays).days,
            leadMinutes = bedtimeLeadMinutes,
        )
    }.getOrDefault(Bedtime()),
    weather = WeatherSettings(
        mode = enumOr(weatherMode, WeatherMode.NOT_SET),
        place = weatherLat?.let { lat -> weatherLon?.let { lon -> runCatching { WeatherPlace(weatherPlace, lat, lon) }.getOrNull() } },
    ),
    appearance = Appearance(
        theme = enumOr(theme, ThemeMode.SYSTEM),
        seedColor = seedColor,
        clockStyle = enumOr(clockStyle, ClockStyle.CIRCLE),
        clockFont = enumOr(clockFont, ClockFont.REGULAR),
        clockSize = clockSize.coerceIn(Appearance.MIN_SIZE, Appearance.MAX_SIZE),
        nightNoise = enumOr(nightNoise, NoiseKind.NONE),
        background = enumOr(background, ClockBackground.NONE),
        highContrast = highContrast,
        wallpaper = enumOr(wallpaper, ClockBackground.CONSTELLATION).takeIf { it.isWallpaper } ?: ClockBackground.CONSTELLATION,
    ),
    showAlarmDate = showAlarmDate,
    onboardingDone = onboardingDone,
    haptics = haptics,
    defaultWarnings = Warnings.decode(defaultWarnings),
    warningSound = warningSound,
    chimes = Chimes.decode(chimes),
    pomodoro = Pomodoro.decode(pomodoro),
    sun = SunSignals.decode(sun),
)

fun Settings.toEntity() = SettingsEntity(
    snoozeMinutes = snoozeMinutes,
    autoStopMinutes = autoStopMinutes,
    alarmOrder = alarmOrder.name,
    showAlarmCountdown = showAlarmCountdown,
    xiaomiHintsDone = xiaomiHintsDone,
    signalStyle = signalStyle.name,
    volumeButtons = volumeButtons.name,
    holidaysRu = holidaysRu,
    bedtimeMode = bedtime.mode.name,
    sleepMinutes = bedtime.sleepMinutes,
    bedtimeHour = bedtime.hour,
    bedtimeMinute = bedtime.minute,
    bedtimeDays = Repeat.of(bedtime.days).bits,
    bedtimeLeadMinutes = bedtime.leadMinutes,
    weatherMode = weather.mode.name,
    weatherPlace = weather.place?.name.orEmpty(),
    weatherLat = weather.place?.latitude,
    weatherLon = weather.place?.longitude,
    theme = appearance.theme.name,
    seedColor = appearance.seedColor,
    clockStyle = appearance.clockStyle.name,
    clockFont = appearance.clockFont.name,
    clockSize = appearance.clockSize,
    nightNoise = appearance.nightNoise.name,
    background = appearance.background.name,
    highContrast = appearance.highContrast,
    wallpaper = appearance.wallpaper.name,
    showAlarmDate = showAlarmDate,
    onboardingDone = onboardingDone,
    haptics = haptics,
    defaultWarnings = Warnings.encode(defaultWarnings),
    warningSound = warningSound,
    chimes = chimes.encode(),
    pomodoro = pomodoro.encode(),
    sun = sun.encode(),
)

/** Неизвестное значение (например, из копии новой версии) — значение по умолчанию. */
private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name == name } ?: default

private fun moment(elapsed: Long?, bootCount: Int?, utc: Long?): Moment? =
    if (elapsed == null || bootCount == null || utc == null) null else Moment(elapsed, bootCount, utc)

fun SignalEventEntity.toModel() = SignalEvent(
    id = id,
    source = enumOr(source, SignalSource.ALARM),
    sourceId = sourceId,
    title = title,
    scheduledAt = scheduledAt,
    firedAt = firedAt,
    outcome = enumOr(outcome, SignalOutcome.DISMISSED),
    endedAt = endedAt,
)

fun ActivityEventEntity.toModel() = ActivityEvent(id, enumOr(kind, ActivityKind.TIMER), startedAt, endedAt, title, refId, amountMillis, count)

fun ActivityEvent.toEntity() = ActivityEventEntity(id, kind.name, startedAt, endedAt, title, refId, amountMillis, count)
