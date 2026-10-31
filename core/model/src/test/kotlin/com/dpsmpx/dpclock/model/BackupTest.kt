package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

class BackupTest {
    private val exportedAt = Instant.parse("2026-10-24T18:30:00Z")

    /** Сложный повтор со всеми видами дат и времён (D-025). */
    private val rules = Repeat.Rules(
        rules = listOf(
            RepeatRule(
                DatePattern.Weeks(setOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), everyWeeks = 2, anchor = LocalDate.of(2026, 10, 19)),
                RuleTimes.At(listOf(LocalTime.of(9, 0), LocalTime.of(9, 30))),
                from = LocalDate.of(2026, 10, 1),
                until = LocalDate.of(2027, 6, 30),
            ),
            RepeatRule(DatePattern.MonthDays(setOf(1, 15, DatePattern.LAST)), RuleTimes.Every(LocalTime.of(10, 0), LocalTime.of(18, 0), 90)),
            RepeatRule(DatePattern.MonthWeekday(DatePattern.LAST, DayOfWeek.FRIDAY), RuleTimes.At(listOf(LocalTime.of(17, 0)))),
            RepeatRule(DatePattern.Yearly(2, 29), RuleTimes.At(listOf(LocalTime.of(12, 0)))),
            RepeatRule(
                DatePattern.Shifts(LocalDate.of(2026, 10, 1), listOf(true, true, false, false)),
                RuleTimes.At(listOf(LocalTime.of(7, 0))),
                weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.SATURDAY),
                weekdayTimes = mapOf(DayOfWeek.SATURDAY to LocalTime.of(9, 0)),
            ),
            RepeatRule(DatePattern.Workdays, RuleTimes.At(listOf(LocalTime.of(8, 0)))),
        ),
        except = listOf(DatePattern.Dates(setOf(LocalDate.of(2026, 12, 31))), DatePattern.Weeks(setOf(DayOfWeek.SUNDAY))),
    )

    /** Всё, что бывает в базе: каждый вид повтора и звука, проверка, мягкое пробуждение, действия после. */
    private val full = Backup(
        alarms = listOf(
            Alarm("a1", 7, 0, Repeat.Once, enabled = true, createdAt = 1L),
            Alarm(
                "a2", 6, 30, Repeat.Weekly(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)), enabled = false, createdAt = 2L,
                label = "Работа \"рано\"\nстрока", sound = AlarmSound.Builtin("dawn"), vibrate = false, rampSeconds = 60,
                snoozeMinutes = 5, skipDate = LocalDate.of(2026, 10, 30), honorExceptions = false, groupId = "g1",
                wakeCheck = WakeCheck(WakeMethod.QR, WakeLevel.HARD, tasks = 3, reference = "КОД-42", snoozeAllowed = false, recheckMinutes = 5),
                gentleMinutes = 20, after = AfterAlarm("p1", "Таблетки", 15, "org.example.app"),
            ),
            Alarm("a3", 9, 15, Repeat.OnDate(LocalDate.of(2026, 12, 31)), enabled = true, createdAt = 3L, sound = AlarmSound.Silent),
            Alarm(
                "a4", 8, 0, Repeat.Cycle(LocalDate.of(2026, 10, 1), listOf(LocalTime.of(8, 0), LocalTime.of(20, 5), null, null)),
                enabled = true, createdAt = 4L, sound = AlarmSound.Ringtone("content://media/internal/audio/media/12", "Утро"),
            ),
            Alarm("a5", 7, 45, Repeat.Workdays, enabled = true, createdAt = 5L, sound = AlarmSound.File("content://files/x.mp3", "Свой файл")),
            Alarm("a6", 9, 0, rules, enabled = true, createdAt = 6L, skipDate = LocalDate.of(2026, 11, 2), skipTime = LocalTime.of(9, 30), warnings = listOf(1440, 45, 5)),
        ),
        groups = listOf(AlarmGroup("g1", "Будни", 10L)),
        templates = listOf(
            AlarmTemplate("t1", "Смена", listOf(Alarm("", 6, 0, Repeat.Workdays, enabled = true, createdAt = 0L, after = AfterAlarm(presetId = "p1"))), 11L),
            AlarmTemplate("t2", "Правила", listOf(Alarm("", 9, 0, rules, enabled = true, createdAt = 0L, warnings = listOf(30))), 14L),
        ),
        exceptions = listOf(ExceptionPeriod("e1", "Отпуск", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 14))),
        presets = listOf(
            TimerPreset(
                "p1", "Планка",
                TimerProgram(
                    listOf(TimerStep(30_000L, "Работа", fullSignal = false, sound = AlarmSound.Default), TimerStep(10_000L, "Отдых")),
                    cycles = 3, sound = AlarmSound.Builtin("marimba"),
                ),
                12L,
            ),
            TimerPreset("p2", "Сон", TimerProgram.sleep(30 * 60_000L), 13L),
            TimerPreset("p3", "Помидор", Pomodoro(longEvery = 2, dnd = true).program("Работа", "Перерыв", "Длинный"), 15L),
            TimerPreset("p4", "Каждые 10 с", TimerProgram.interval(10_000L, null), 16L),
        ),
        settings = Settings(
            snoozeMinutes = 5, autoStopMinutes = null, alarmOrder = AlarmOrder.NEXT, showAlarmCountdown = true, signalStyle = SignalStyle.NOTIFICATION,
            volumeButtons = VolumeButtons.NOTHING, holidaysRu = true,
            bedtime = Bedtime(BedtimeMode.SCHEDULE, 450, 22, 30, setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY), 30),
            weather = WeatherSettings(WeatherMode.CITY, WeatherPlace("Тула", 54.1931, 37.6177)),
            appearance = Appearance(ThemeMode.BLACK, 0xFF3F5AA9.toInt(), ClockStyle.DIGITS, ClockFont.SEGMENT, 80, NoiseKind.PINK, ClockBackground.STARS, highContrast = true, nightBackground = ClockBackground.POLAR_CLOCK, appBackground = ClockBackground.CONSTELLATION, wallpaper = ClockBackground.HYPERCUBE),
            showAlarmDate = true, defaultWarnings = listOf(60, 5), warningSound = true,
            chimes = Chimes(ChimeEvery.HALF_HOUR, vibrate = true, countHours = false, quiet = true, LocalTime.of(23, 30), LocalTime.of(7, 15), setOf(DayOfWeek.SATURDAY)),
            pomodoro = Pomodoro(50, 10, 30, 3, 2, autoAdvance = true, dnd = true),
        ),
        worldCities = listOf("Asia/Tokyo", "Europe/London"),
    )

    @Test
    fun everythingRoundTrips() {
        val text = BackupFormat.encode(full, exportedAt, "0.7.0")
        assertEquals(full.copy(exportedAt = exportedAt, appVersion = "0.7.0"), BackupFormat.decode(text))
        // Читается глазами: кириллица как есть, дни по именам.
        assertTrue(text.contains("\"name\": \"Будни\""))
        assertTrue(text.contains("\"days\": [\"MONDAY\", \"FRIDAY\"]"))
        assertTrue(text.contains("\"days\": [\"08:00\", \"20:05\", null, null]"))
    }

    @Test
    fun formatTwoOnlyWithComplexRepeats() {
        assertTrue(BackupFormat.encode(full, exportedAt, "0.14.0").contains("\"format\": 2"))
        val plain = full.copy(alarms = full.alarms.filter { it.repeat !is Repeat.Rules }, templates = full.templates.take(1))
        assertTrue(BackupFormat.encode(plain, exportedAt, "0.14.0").contains("\"format\": 1"))
    }

    @Test
    fun xiaomiMarkStaysOnThePhone() {
        val backup = Backup(settings = Settings(xiaomiHintsDone = true))
        val decoded = BackupFormat.decode(BackupFormat.encode(backup, exportedAt, "0.7.0"))
        assertEquals(false, decoded.settings!!.xiaomiHintsDone)
    }

    @Test
    fun missingFieldsTakeDefaults() {
        val text = """{"app": "DP_Clock", "format": 1, "alarms": [{"id": "x", "hour": 7, "minute": 5, "repeat": {"type": "once"}}], "settings": {}}"""
        val backup = BackupFormat.decode(text)
        assertEquals(listOf(Alarm("x", 7, 5, Repeat.Once, enabled = true, createdAt = 0L)), backup.alarms)
        assertEquals(Settings(), backup.settings)
        assertEquals(WeatherMode.NOT_SET, backup.settings!!.weather.mode)
        assertTrue(backup.worldCities.isEmpty())
        assertNull(backup.exportedAt)
        assertTrue(backup.groups.isEmpty() && backup.presets.isEmpty())
        // Без настроек в файле — настройки не трогаются.
        assertNull(BackupFormat.decode("""{"app": "DP_Clock", "format": 1}""").settings)
    }

    @Test
    fun unknownValuesDoNotBreakTheFile() {
        val text = """
            {"app": "DP_Clock", "format": 1, "future": [1, 2.5e3, {"x": true}],
             "alarms": [{"id": "x", "hour": 7, "minute": 0, "repeat": {"type": "once"},
                         "sound": {"type": "hologram"}, "wakeCheck": {"method": "NFC", "tasks": 99}}]}
        """
        val alarm = BackupFormat.decode(text).alarms.single()
        assertEquals(AlarmSound.Default, alarm.sound)
        assertEquals(WakeMethod.NONE, alarm.wakeCheck.method)
        assertEquals(WakeCheck.MAX_TASKS, alarm.wakeCheck.tasks)
    }

    @Test
    fun badFilesAreRejectedWithAReason() {
        fun reason(text: String): BackupException.Reason {
            try {
                BackupFormat.decode(text)
            } catch (e: BackupException) {
                return e.reason
            }
            throw AssertionError("файл загрузился: $text")
        }
        assertEquals(BackupException.Reason.NOT_DP_CLOCK, reason("будильник на 7:00"))
        assertEquals(BackupException.Reason.NOT_DP_CLOCK, reason(""))
        assertEquals(BackupException.Reason.NOT_DP_CLOCK, reason("""{"app": "Другие часы", "format": 1}"""))
        assertEquals(BackupException.Reason.NOT_DP_CLOCK, reason("""{"app": "DP_Clock", "format": 1, "alarms": ["""))
        assertEquals(BackupException.Reason.NEWER_FORMAT, reason("""{"app": "DP_Clock", "format": 3}"""))
        assertEquals(BackupException.Reason.BROKEN, reason("""{"app": "DP_Clock"}"""))
        val alarm = """{"id": "x", "hour": 7, "minute": 0, "repeat": {"type": "once"}}"""
        for (broken in listOf(
            alarm.replace("\"hour\": 7", "\"hour\": 25"),
            alarm.replace("\"hour\": 7", "\"hour\": \"7\""),
            alarm.replace("\"once\"", "\"monthly\""),
            alarm.replace("{\"type\": \"once\"}", "{\"type\": \"date\", \"date\": \"2026-13-01\"}"),
            alarm.replace("{\"type\": \"once\"}", "{\"type\": \"cycle\", \"anchor\": \"2026-10-01\", \"days\": [null, null]}"),
            alarm.replace(", \"repeat\": {\"type\": \"once\"}", ""),
        )) {
            assertEquals(broken, BackupException.Reason.BROKEN, reason("""{"app": "DP_Clock", "format": 1, "alarms": [$broken]}"""))
        }
        assertEquals(
            BackupException.Reason.BROKEN,
            reason("""{"app": "DP_Clock", "format": 1, "exceptions": [{"id": "e", "name": "", "start": "2026-07-14", "end": "2026-07-01"}]}"""),
        )
        assertEquals(BackupException.Reason.BROKEN, reason("""{"app": "DP_Clock", "format": 1, "presets": [{"id": "p", "name": "", "program": {"steps": []}}]}"""))
        assertEquals(BackupException.Reason.BROKEN, reason("""{"app": "DP_Clock", "format": 1, "settings": {"snoozeMinutes": 0}}"""))
    }

    @Test
    fun importGetsNewIdsAndKeepsLinks() {
        var next = 0
        val imported = BackupRules.forImport(full) { "n" + next++ }
        val ids = imported.alarms.map { it.id } + imported.groups.map { it.id } + imported.templates.map { it.id } +
            imported.exceptions.map { it.id } + imported.presets.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.none { it in setOf("a1", "a2", "g1", "t1", "e1", "p1", "p2") })
        val alarm = imported.alarms[1]
        assertEquals(imported.groups.single().id, alarm.groupId)
        assertEquals(imported.presets.first().id, alarm.after.presetId)
        assertEquals(imported.presets.first().id, imported.templates.first().alarms.single().after.presetId)
        // Всё прочее — как в файле.
        assertEquals(full.alarms[1].copy(id = alarm.id, groupId = alarm.groupId, after = alarm.after), alarm)
        assertEquals(full.settings, imported.settings)
    }

    @Test
    fun linksToMissingGroupsAndPresetsAreDropped() {
        val backup = Backup(alarms = listOf(Alarm("a", 7, 0, Repeat.Once, true, 0L, groupId = "gone", after = AfterAlarm(presetId = "gone", reminder = "Вода"))))
        val alarm = BackupRules.forImport(backup) { "new" }.alarms.single()
        assertNull(alarm.groupId)
        assertNull(alarm.after.presetId)
        assertEquals("Вода", alarm.after.reminder)
        assertNotEquals("a", alarm.id)
    }
}
