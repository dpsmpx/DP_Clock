package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Что переносится файлом (D-018, п. 2): всё, что человек настроил сам. Идущие
 * таймеры, секундомер, срабатывания и журнал — состояние телефона, их в файле нет.
 */
data class Backup(
    val alarms: List<Alarm> = emptyList(),
    val groups: List<AlarmGroup> = emptyList(),
    val templates: List<AlarmTemplate> = emptyList(),
    val exceptions: List<ExceptionPeriod> = emptyList(),
    val presets: List<TimerPreset> = emptyList(),
    /** Города мировых часов — пояса tz по порядку (D-019). */
    val worldCities: List<String> = emptyList(),
    /** null — в файле настроек нет. Отметки про Xiaomi и первый запуск относятся к телефону и не переносятся. */
    val settings: Settings? = null,
    /** Когда и какой версией приложения сохранён файл; не указано — null и пусто. */
    val exportedAt: Instant? = null,
    val appVersion: String = "",
) {
    val isEmpty: Boolean
        get() = alarms.isEmpty() && groups.isEmpty() && templates.isEmpty() && exceptions.isEmpty() && presets.isEmpty() &&
            worldCities.isEmpty() && settings == null
}

/** Файл не загружается (D-018, п. 5); в базе ничего не меняется. */
class BackupException(val reason: Reason, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Reason {
        /** Не JSON или JSON не от DP_Clock. */
        NOT_DP_CLOCK,

        /** Формат новее, чем знает эта версия приложения. */
        NEWER_FORMAT,

        /** Файл DP_Clock, но данные в нём повреждены. */
        BROKEN,
    }
}

/**
 * Файл экспорта (D-018, п. 3): JSON в UTF-8, поля по-английски, как в модели.
 * Отсутствующее поле — значение по умолчанию; неизвестные поля пропускаются,
 * неизвестное значение перечисления — по умолчанию. Формат меняется, только
 * если старая версия приложения прочтёт файл неверно.
 */
object BackupFormat {
    const val APP = "DP_Clock"

    /** 2 — сложные повторы (D-025): версия до 0.14.0 скажет «файл новее», а не «повреждён». */
    const val VERSION = 2
    const val MIME = "application/json"

    /** Больше файлов DP_Clock не бывает: тысяча будильников — около мегабайта. */
    const val MAX_BYTES = 4 * 1024 * 1024

    fun fileName(date: LocalDate): String = "dp-clock-$date.json"

    fun encode(backup: Backup, exportedAt: Instant, appVersion: String): String {
        // Без сложных повторов файл читают и версии до 0.14.0.
        val rules = (backup.alarms + backup.templates.flatMap { it.alarms }).any { it.repeat is Repeat.Rules }
        val root = linkedMapOf<String, Any?>(
            "app" to APP,
            "format" to if (rules) VERSION else 1,
            "appVersion" to appVersion,
            "exportedAt" to exportedAt.toString(),
            "alarms" to backup.alarms.map(::alarmMap),
            "groups" to backup.groups.map(::groupMap),
            "templates" to backup.templates.map(::templateMap),
            "exceptions" to backup.exceptions.map(::exceptionMap),
            "presets" to backup.presets.map(::presetMap),
            "worldCities" to backup.worldCities,
        )
        backup.settings?.let { root["settings"] = settings(it) }
        return Json.write(root)
    }

    /** Разбор файла; не получилось — [BackupException]. */
    fun decode(text: String): Backup {
        val root = try {
            Json.parse(text.removePrefix("﻿"))
        } catch (e: IllegalArgumentException) {
            throw BackupException(BackupException.Reason.NOT_DP_CLOCK, "не JSON: ${e.message}", e)
        }
        if (root !is Map<*, *> || root["app"] != APP) throw BackupException(BackupException.Reason.NOT_DP_CLOCK, "нет \"app\": \"$APP\"")
        val format = root["format"] as? Long ?: throw BackupException(BackupException.Reason.BROKEN, "нет \"format\"")
        if (format > VERSION) throw BackupException(BackupException.Reason.NEWER_FORMAT, "формат $format")
        if (format < 1) throw BackupException(BackupException.Reason.BROKEN, "формат $format")
        return try {
            val o = Obj(root, "")
            Backup(
                alarms = o.objs("alarms").map { alarm(it, blueprint = false) },
                groups = o.objs("groups").map(::group),
                templates = o.objs("templates").map(::template),
                exceptions = o.objs("exceptions").map(::exception),
                presets = o.objs("presets").map(::preset),
                worldCities = o.list("worldCities").map { it as String }.distinct(),
                settings = o.obj("settings")?.let(::settings),
                exportedAt = o.stringOrNull("exportedAt")?.let { runCatching { Instant.parse(it) }.getOrNull() },
                appVersion = o.string("appVersion", ""),
            )
        } catch (e: BackupException) {
            throw e
        } catch (e: RuntimeException) {
            // Значения вне допустимого (час 25, пустой график), неверные даты.
            throw BackupException(BackupException.Reason.BROKEN, e.message ?: e::class.simpleName.orEmpty(), e)
        }
    }

    // Части файла — и для синхронизации (D-030): тот же вид, что в файле переноса.

    internal fun alarmMap(alarm: Alarm): Map<String, Any?> = alarm(alarm, blueprint = false)

    internal fun groupMap(group: AlarmGroup): Map<String, Any?> = mapOf("id" to group.id, "name" to group.name, "createdAt" to group.createdAt)

    internal fun templateMap(template: AlarmTemplate): Map<String, Any?> = mapOf(
        "id" to template.id,
        "name" to template.name,
        "createdAt" to template.createdAt,
        "alarms" to template.alarms.map { alarm(it, blueprint = true) },
    )

    internal fun exceptionMap(period: ExceptionPeriod): Map<String, Any?> =
        mapOf("id" to period.id, "name" to period.name, "start" to period.start.toString(), "end" to period.end.toString())

    internal fun presetMap(preset: TimerPreset): Map<String, Any?> =
        mapOf("id" to preset.id, "name" to preset.name, "createdAt" to preset.createdAt, "program" to program(preset.program))

    internal fun settingsMap(settings: Settings): Map<String, Any?> = settings(settings)

    internal fun alarmFrom(map: Map<*, *>): Alarm = parse { alarm(Obj(map, "alarm"), blueprint = false) }

    internal fun groupFrom(map: Map<*, *>): AlarmGroup = parse { group(Obj(map, "group")) }

    internal fun templateFrom(map: Map<*, *>): AlarmTemplate = parse { template(Obj(map, "template")) }

    internal fun exceptionFrom(map: Map<*, *>): ExceptionPeriod = parse { exception(Obj(map, "exception")) }

    internal fun presetFrom(map: Map<*, *>): TimerPreset = parse { preset(Obj(map, "preset")) }

    internal fun settingsFrom(map: Map<*, *>): Settings = parse { settings(Obj(map, "settings")) }

    /** Ошибка в данных — [BackupException] «повреждён», как у файла переноса. */
    private fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: BackupException) {
        throw e
    } catch (e: RuntimeException) {
        throw BackupException(BackupException.Reason.BROKEN, e.message ?: e::class.simpleName.orEmpty(), e)
    }

    private fun group(o: Obj) = AlarmGroup(o.string("id"), o.string("name", "").take(AlarmGroup.MAX_NAME), o.long("createdAt", 0L))

    private fun template(o: Obj) = AlarmTemplate(
        id = o.string("id"),
        name = o.string("name", "").take(AlarmGroup.MAX_NAME),
        alarms = o.objs("alarms").map { blueprint -> alarm(blueprint, blueprint = true) },
        createdAt = o.long("createdAt", 0L),
    )

    private fun exception(o: Obj) = ExceptionPeriod(
        id = o.string("id"),
        name = o.string("name", "").take(ExceptionPeriod.MAX_NAME),
        start = LocalDate.parse(o.string("start")),
        end = LocalDate.parse(o.string("end")),
    )

    private fun preset(o: Obj) = TimerPreset(o.string("id"), o.string("name", ""), program(o.obj("program") ?: o.missing("program")), o.long("createdAt", 0L))

    // Запись

    /** Будильник; у будильника шаблона ([blueprint]) нет id, включения, создания, пропуска и группы. */
    private fun alarm(alarm: Alarm, blueprint: Boolean): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        if (!blueprint) out["id"] = alarm.id
        out["hour"] = alarm.hour
        out["minute"] = alarm.minute
        out["repeat"] = repeat(alarm.repeat)
        if (!blueprint) {
            out["enabled"] = alarm.enabled
            out["createdAt"] = alarm.createdAt
        }
        out["label"] = alarm.label
        out["sound"] = sound(alarm.sound)
        out["vibrate"] = alarm.vibrate
        out["rampSeconds"] = alarm.rampSeconds
        out["snoozeMinutes"] = alarm.snoozeMinutes
        if (!blueprint) {
            out["skipDate"] = alarm.skipDate?.toString()
            out["skipTime"] = alarm.skipTime?.let { two(it.hour) + ":" + two(it.minute) }
        }
        out["honorExceptions"] = alarm.honorExceptions
        if (!blueprint) out["groupId"] = alarm.groupId
        out["wakeCheck"] = alarm.wakeCheck.let {
            mapOf(
                "method" to it.method.name,
                "level" to it.level.name,
                "tasks" to it.tasks,
                "reference" to it.reference,
                "snoozeAllowed" to it.snoozeAllowed,
                "recheckMinutes" to it.recheckMinutes,
            )
        }
        out["gentleMinutes"] = alarm.gentleMinutes
        out["after"] = alarm.after.let {
            mapOf("presetId" to it.presetId, "reminder" to it.reminder, "reminderMinutes" to it.reminderMinutes, "app" to it.app)
        }
        out["warnings"] = alarm.warnings
        return out
    }

    private fun repeat(repeat: Repeat): Map<String, Any?> = when (repeat) {
        Repeat.Once -> mapOf("type" to "once")
        is Repeat.Weekly -> mapOf("type" to "weekly", "days" to days(repeat.days))
        is Repeat.OnDate -> mapOf("type" to "date", "date" to repeat.date.toString())
        is Repeat.Cycle -> mapOf(
            "type" to "cycle",
            "anchor" to repeat.anchor.toString(),
            "days" to repeat.days.map { time -> time?.let { two(it.hour) + ":" + two(it.minute) } },
        )
        Repeat.Workdays -> mapOf("type" to "workdays")
        is Repeat.Rules -> mapOf("type" to "rules") + RulesCode.toMap(repeat)
    }

    private fun two(value: Int) = value.toString().padStart(2, '0')

    private fun days(days: Set<DayOfWeek>): List<String> = days.sorted().map { it.name }

    private fun sound(sound: AlarmSound): Map<String, Any?> = when (sound) {
        AlarmSound.Default -> mapOf("type" to "default")
        AlarmSound.Silent -> mapOf("type" to "silent")
        is AlarmSound.Builtin -> mapOf("type" to "builtin", "id" to sound.id)
        is AlarmSound.Ringtone -> mapOf("type" to "ringtone", "uri" to sound.uri, "title" to sound.title)
        is AlarmSound.File -> mapOf("type" to "file", "uri" to sound.uri, "title" to sound.title)
    }

    private fun program(program: TimerProgram): Map<String, Any?> = mapOf(
        "cycles" to program.cycles,
        "sleep" to program.sleep,
        "hold" to program.hold,
        "forever" to program.forever,
        "dnd" to program.dnd,
        "sound" to sound(program.sound),
        "steps" to program.steps.map {
            // Звук шага null — как у программы.
            mapOf(
                "millis" to it.durationMillis,
                "name" to it.name,
                "fullSignal" to it.fullSignal,
                "sound" to it.sound?.let(::sound),
                "focus" to it.focus,
            )
        },
    )

    private fun settings(settings: Settings): Map<String, Any?> = mapOf(
        "snoozeMinutes" to settings.snoozeMinutes,
        "autoStopMinutes" to settings.autoStopMinutes,
        "alarmOrder" to settings.alarmOrder.name,
        "showAlarmCountdown" to settings.showAlarmCountdown,
        "showAlarmDate" to settings.showAlarmDate,
        "haptics" to settings.haptics,
        "defaultWarnings" to settings.defaultWarnings,
        "warningSound" to settings.warningSound,
        "chimes" to settings.chimes.let {
            mapOf(
                "every" to it.every.name,
                "vibrate" to it.vibrate,
                "countHours" to it.countHours,
                "quiet" to it.quiet,
                "quietFrom" to two(it.quietFrom.hour) + ":" + two(it.quietFrom.minute),
                "quietTo" to two(it.quietTo.hour) + ":" + two(it.quietTo.minute),
                "days" to days(it.days),
            )
        },
        "pomodoro" to settings.pomodoro.let {
            mapOf(
                "workMinutes" to it.workMinutes,
                "shortMinutes" to it.shortMinutes,
                "longMinutes" to it.longMinutes,
                "longEvery" to it.longEvery,
                "sets" to it.sets,
                "autoAdvance" to it.autoAdvance,
                "dnd" to it.dnd,
            )
        },
        "signalStyle" to settings.signalStyle.name,
        "volumeButtons" to settings.volumeButtons.name,
        "holidaysRu" to settings.holidaysRu,
        "appearance" to settings.appearance.let {
            mapOf(
                "theme" to it.theme.name,
                "seedColor" to it.seedColor?.let { color -> "#" + Integer.toHexString(color).uppercase().padStart(8, '0') },
                "clockStyle" to it.clockStyle.name,
                "clockFont" to it.clockFont.name,
                "clockSize" to it.clockSize,
                "nightNoise" to it.nightNoise.name,
                "background" to it.background.name,
                "highContrast" to it.highContrast,
                "wallpaper" to it.wallpaper.name,
            )
        },
        "weather" to settings.weather.let { weather ->
            mapOf(
                "mode" to weather.mode.name,
                "place" to weather.place?.let { mapOf("name" to it.name, "latitude" to it.latitude, "longitude" to it.longitude) },
            )
        },
        "bedtime" to settings.bedtime.let {
            mapOf(
                "mode" to it.mode.name,
                "sleepMinutes" to it.sleepMinutes,
                "hour" to it.hour,
                "minute" to it.minute,
                "days" to days(it.days),
                "leadMinutes" to it.leadMinutes,
            )
        },
    )

    // Чтение

    private fun alarm(o: Obj, blueprint: Boolean): Alarm {
        val wake = o.obj("wakeCheck")
        val after = o.obj("after")
        return Alarm(
            id = if (blueprint) "" else o.string("id"),
            hour = o.int("hour"),
            minute = o.int("minute"),
            repeat = repeat(o.obj("repeat") ?: o.missing("repeat")),
            enabled = blueprint || o.bool("enabled", true),
            createdAt = if (blueprint) 0L else o.long("createdAt", 0L),
            label = o.string("label", "").take(Alarm.MAX_LABEL),
            sound = o.obj("sound")?.let(::sound) ?: AlarmSound.Default,
            vibrate = o.bool("vibrate", true),
            rampSeconds = o.int("rampSeconds", 0),
            snoozeMinutes = o.intOrNull("snoozeMinutes"),
            skipDate = if (blueprint) null else o.stringOrNull("skipDate")?.let(LocalDate::parse),
            skipTime = if (blueprint) null else o.stringOrNull("skipTime")?.let(LocalTime::parse),
            honorExceptions = o.bool("honorExceptions", true),
            groupId = if (blueprint) null else o.stringOrNull("groupId"),
            wakeCheck = wake?.let {
                WakeCheck(
                    method = it.enum("method", WakeMethod.NONE),
                    level = it.enum("level", WakeLevel.MEDIUM),
                    tasks = it.int("tasks", 1).coerceIn(1, WakeCheck.MAX_TASKS),
                    reference = it.string("reference", ""),
                    snoozeAllowed = it.bool("snoozeAllowed", true),
                    recheckMinutes = it.int("recheckMinutes", 0),
                )
            } ?: WakeCheck(),
            gentleMinutes = o.int("gentleMinutes", 0),
            after = after?.let {
                AfterAlarm(
                    presetId = it.stringOrNull("presetId"),
                    reminder = it.string("reminder", "").take(AfterAlarm.MAX_REMINDER),
                    reminderMinutes = it.int("reminderMinutes", 0).coerceAtLeast(0),
                    app = it.string("app", ""),
                )
            } ?: AfterAlarm(),
            warnings = Warnings.normalize(o.list("warnings").map { (it as? Long)?.toInt() ?: 0 }),
        )
    }

    private fun repeat(o: Obj): Repeat = when (val type = o.string("type")) {
        "once" -> Repeat.Once
        "weekly" -> Repeat.of(days(o, "days"))
        "date" -> Repeat.OnDate(LocalDate.parse(o.string("date")))
        "cycle" -> Repeat.Cycle(
            LocalDate.parse(o.string("anchor")),
            o.list("days").map { (it as String?)?.let(LocalTime::parse) },
        )
        "workdays" -> Repeat.Workdays
        "rules" -> RulesCode.fromMap(o.raw)
        else -> throw BackupException(BackupException.Reason.BROKEN, "повтор «$type»")
    }

    private fun days(o: Obj, key: String): Set<DayOfWeek> = o.list(key).mapTo(mutableSetOf()) { DayOfWeek.valueOf(it as String) }

    /** Неизвестный звук — мелодия по умолчанию: будильник всё равно прозвонит. */
    private fun sound(o: Obj): AlarmSound = when (o.string("type", "default")) {
        "silent" -> AlarmSound.Silent
        "builtin" -> AlarmSound.Builtin(o.string("id"))
        "ringtone" -> AlarmSound.Ringtone(o.string("uri"), o.string("title", ""))
        "file" -> AlarmSound.File(o.string("uri"), o.string("title", ""))
        else -> AlarmSound.Default
    }

    private fun program(o: Obj): TimerProgram {
        val forever = o.bool("forever", false)
        return TimerProgram(
            steps = o.objs("steps").map {
                TimerStep(it.long("millis"), it.string("name", ""), it.bool("fullSignal", true), it.obj("sound")?.let(::sound), it.bool("focus", false))
            },
            cycles = if (forever) 1 else o.int("cycles", 1).coerceIn(1, TimerProgram.MAX_CYCLES),
            sound = o.obj("sound")?.let(::sound) ?: AlarmSound.Default,
            sleep = o.bool("sleep", false),
            hold = o.bool("hold", false),
            forever = forever,
            dnd = o.bool("dnd", false),
        )
    }

    private fun settings(o: Obj): Settings {
        val defaults = Settings()
        val bedtime = o.obj("bedtime")?.let {
            val base = Bedtime()
            Bedtime(
                mode = it.enum("mode", base.mode),
                sleepMinutes = it.int("sleepMinutes", base.sleepMinutes),
                hour = it.int("hour", base.hour),
                minute = it.int("minute", base.minute),
                days = if (it.has("days")) days(it, "days") else base.days,
                leadMinutes = it.int("leadMinutes", base.leadMinutes),
            )
        } ?: defaults.bedtime
        val snooze = o.int("snoozeMinutes", defaults.snoozeMinutes)
        // null в файле — «никогда»; поля нет — по умолчанию.
        val autoStop = if (o.contains("autoStopMinutes")) o.intOrNull("autoStopMinutes") else defaults.autoStopMinutes
        require(snooze in 1..MAX_MINUTES) { "«Отложить» $snooze" }
        require(autoStop == null || autoStop in 1..MAX_MINUTES) { "автовыключение $autoStop" }
        return Settings(
            snoozeMinutes = snooze,
            autoStopMinutes = autoStop,
            alarmOrder = o.enum("alarmOrder", defaults.alarmOrder),
            showAlarmCountdown = o.bool("showAlarmCountdown", defaults.showAlarmCountdown),
            showAlarmDate = o.bool("showAlarmDate", defaults.showAlarmDate),
            haptics = o.bool("haptics", defaults.haptics),
            defaultWarnings = Warnings.normalize(o.list("defaultWarnings").map { (it as? Long)?.toInt() ?: 0 }),
            warningSound = o.bool("warningSound", defaults.warningSound),
            chimes = o.obj("chimes")?.let {
                val base = Chimes()
                Chimes(
                    every = it.enum("every", base.every),
                    vibrate = it.bool("vibrate", base.vibrate),
                    countHours = it.bool("countHours", base.countHours),
                    quiet = it.bool("quiet", base.quiet),
                    quietFrom = it.stringOrNull("quietFrom")?.let(LocalTime::parse) ?: base.quietFrom,
                    quietTo = it.stringOrNull("quietTo")?.let(LocalTime::parse) ?: base.quietTo,
                    days = if (it.has("days")) days(it, "days") else base.days,
                )
            } ?: defaults.chimes,
            pomodoro = o.obj("pomodoro")?.let {
                val base = Pomodoro()
                Pomodoro(
                    workMinutes = it.int("workMinutes", base.workMinutes),
                    shortMinutes = it.int("shortMinutes", base.shortMinutes),
                    longMinutes = it.int("longMinutes", base.longMinutes),
                    longEvery = it.int("longEvery", base.longEvery),
                    sets = it.int("sets", base.sets),
                    autoAdvance = it.bool("autoAdvance", base.autoAdvance),
                    dnd = it.bool("dnd", base.dnd),
                )
            } ?: defaults.pomodoro,
            signalStyle = o.enum("signalStyle", defaults.signalStyle),
            volumeButtons = o.enum("volumeButtons", defaults.volumeButtons),
            holidaysRu = o.bool("holidaysRu", defaults.holidaysRu),
            bedtime = bedtime,
            weather = o.obj("weather")?.let { weather ->
                WeatherSettings(
                    mode = weather.enum("mode", WeatherMode.NOT_SET),
                    place = weather.obj("place")?.let { WeatherPlace(it.string("name", ""), it.double("latitude"), it.double("longitude")) },
                )
            } ?: defaults.weather,
            appearance = o.obj("appearance")?.let {
                val base = Appearance()
                Appearance(
                    theme = it.enum("theme", base.theme),
                    seedColor = it.stringOrNull("seedColor")?.let { text ->
                        text.removePrefix("#").toLongOrNull(16)?.toInt() ?: throw BackupException(BackupException.Reason.BROKEN, "цвет $text")
                    },
                    clockStyle = it.enum("clockStyle", base.clockStyle),
                    clockFont = it.enum("clockFont", base.clockFont),
                    clockSize = it.int("clockSize", base.clockSize).coerceIn(Appearance.MIN_SIZE, Appearance.MAX_SIZE),
                    nightNoise = it.enum("nightNoise", base.nightNoise),
                    // Своя картинка — файл на телефоне, в перенос не входит: на новом телефоне фон «нет».
                    // Файлы 0.13–0.14: фона «Часов» нет — фон приложения или ночных часов (D-027).
                    background = it.enum("background", base.background).takeIf { bg -> bg != ClockBackground.IMAGE }?.takeIf { bg -> bg != ClockBackground.NONE }
                        ?: listOf("appBackground", "nightBackground").map { key -> it.enum(key, ClockBackground.NONE) }.firstOrNull { bg -> bg.isWallpaper }
                        ?: ClockBackground.NONE,
                    highContrast = it.bool("highContrast", base.highContrast),
                    // Ночные часы, фон приложения и обои — только эффекты-обои: чужое значение — по умолчанию.
                    wallpaper = it.enum("wallpaper", base.wallpaper).takeIf { bg -> bg.isWallpaper } ?: base.wallpaper,
                )
            } ?: defaults.appearance,
        )
    }

    private const val MAX_MINUTES = 120
}

/**
 * Загрузка из файла (D-018, п. 4) — чистые функции.
 */
object BackupRules {
    /**
     * Копия для загрузки: у всего новые id — загруженное не задевает имеющееся,
     * а тот же файл можно загрузить дважды. Ссылки будильников на группы и пресеты
     * файла переводятся на новые id; на то, чего в файле нет, — снимаются.
     */
    fun forImport(backup: Backup, newId: () -> String): Backup {
        val groups = backup.groups.associate { it.id to newId() }
        val presets = backup.presets.associate { it.id to newId() }
        fun after(after: AfterAlarm) = after.copy(presetId = after.presetId?.let(presets::get))
        return backup.copy(
            alarms = backup.alarms.map { it.copy(id = newId(), groupId = it.groupId?.let(groups::get), after = after(it.after)) },
            groups = backup.groups.map { it.copy(id = groups.getValue(it.id)) },
            templates = backup.templates.map { template ->
                template.copy(id = newId(), alarms = template.alarms.map { it.copy(after = after(it.after)) })
            },
            exceptions = backup.exceptions.map { it.copy(id = newId()) },
            presets = backup.presets.map { it.copy(id = presets.getValue(it.id)) },
        )
    }
}

/** Объект JSON с путём для сообщения об ошибке. */
private class Obj(private val map: Map<*, *>, private val path: String) {
    /** Сам объект — для разбора, общего с базой (сложный повтор). */
    val raw: Map<*, *> get() = map

    fun has(key: String): Boolean = map[key] != null

    /** Поле есть, хотя бы и null. */
    fun contains(key: String): Boolean = map.containsKey(key)

    fun missing(key: String): Nothing = throw BackupException(BackupException.Reason.BROKEN, "нет $path/$key")

    private fun wrong(key: String): Nothing = throw BackupException(BackupException.Reason.BROKEN, "неверно $path/$key")

    fun string(key: String, default: String? = null): String = when (val v = map[key]) {
        null -> default ?: missing(key)
        is String -> v
        else -> wrong(key)
    }

    fun stringOrNull(key: String): String? = map[key]?.let { it as? String ?: wrong(key) }

    fun long(key: String, default: Long? = null): Long = when (val v = map[key]) {
        null -> default ?: missing(key)
        is Long -> v
        else -> wrong(key)
    }

    fun int(key: String, default: Int? = null): Int = intOrNull(key) ?: default ?: missing(key)

    fun intOrNull(key: String): Int? = when (val v = map[key]) {
        null -> null
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else wrong(key)
        else -> wrong(key)
    }

    fun double(key: String): Double = when (val v = map[key]) {
        null -> missing(key)
        is Number -> v.toDouble()
        else -> wrong(key)
    }

    fun bool(key: String, default: Boolean): Boolean = when (val v = map[key]) {
        null -> default
        is Boolean -> v
        else -> wrong(key)
    }

    inline fun <reified E : Enum<E>> enum(key: String, default: E): E {
        val name = stringOrNull(key) ?: return default
        return enumValues<E>().firstOrNull { it.name == name } ?: default
    }

    fun obj(key: String): Obj? = when (val v = map[key]) {
        null -> null
        is Map<*, *> -> Obj(v, "$path/$key")
        else -> wrong(key)
    }

    fun list(key: String): List<Any?> = when (val v = map[key]) {
        null -> emptyList()
        is List<*> -> v
        else -> wrong(key)
    }

    fun objs(key: String): List<Obj> = list(key).mapIndexed { index, item ->
        Obj(item as? Map<*, *> ?: wrong("$key[$index]"), "$path/$key[$index]")
    }
}
