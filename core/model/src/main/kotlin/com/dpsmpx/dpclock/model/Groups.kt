package com.dpsmpx.dpclock.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.time.LocalDate

/** Группа будильников: общее включение и выключение (D-014, п. 5). */
data class AlarmGroup(val id: String, val name: String, val createdAt: Long) {
    companion object {
        const val MAX_NAME = 40
    }
}

/**
 * Шаблон — набор будильников (D-014, п. 6, выбор автора): сохраняется из
 * группы и разворачивается в новую группу. Будильник шаблона — без id,
 * включения, пропуска и группы.
 */
data class AlarmTemplate(val id: String, val name: String, val alarms: List<Alarm>, val createdAt: Long) {
    /** Будильники для базы: строка на будильник, поля `ключ=значение` через `&` в URL-кодировке. */
    fun encodeAlarms(): String = "v1\n" + alarms.joinToString("\n") { alarm ->
        listOf(
            "h" to alarm.hour.toString(),
            "m" to alarm.minute.toString(),
            "r" to RepeatCode.encode(alarm.repeat),
            "l" to alarm.label,
            "s" to alarm.sound.encode(),
            "st" to alarm.sound.title,
            "v" to if (alarm.vibrate) "1" else "0",
            "ra" to alarm.rampSeconds.toString(),
            "sn" to (alarm.snoozeMinutes?.toString() ?: ""),
            "ex" to if (alarm.honorExceptions) "1" else "0",
            "w" to alarm.wakeCheck.method.name,
            "wl" to alarm.wakeCheck.level.name,
            "wt" to alarm.wakeCheck.tasks.toString(),
            "wr" to alarm.wakeCheck.reference,
            "ws" to if (alarm.wakeCheck.snoozeAllowed) "1" else "0",
            "rc" to alarm.wakeCheck.recheckMinutes.toString(),
            "gm" to alarm.gentleMinutes.toString(),
            "ap" to alarm.after.presetId.orEmpty(),
            "ar" to alarm.after.reminder,
            "am" to alarm.after.reminderMinutes.toString(),
            "aa" to alarm.after.app,
            "wn" to Warnings.encode(alarm.warnings),
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }
    }

    companion object {
        fun from(group: AlarmGroup, alarms: List<Alarm>, id: String, now: Long, name: String = group.name) =
            AlarmTemplate(id, name, alarms.map { it.copy(id = "", enabled = true, createdAt = 0L, skipDate = null, skipTime = null, groupId = null) }, now)

        fun decodeAlarms(text: String): List<Alarm> {
            val lines = text.split('\n')
            require(lines.first() == "v1") { "формат шаблона: ${lines.first()}" }
            return lines.drop(1).filter { it.isNotEmpty() }.map { line ->
                val f = line.split('&').associate {
                    val (k, v) = it.split('=', limit = 2)
                    k to URLDecoder.decode(v, "UTF-8")
                }
                Alarm(
                    id = "",
                    hour = f.getValue("h").toInt(),
                    minute = f.getValue("m").toInt(),
                    repeat = RepeatCode.decode(f.getValue("r")),
                    enabled = true,
                    createdAt = 0L,
                    label = f["l"].orEmpty(),
                    sound = AlarmSound.decode(f["s"].orEmpty(), f["st"].orEmpty()),
                    vibrate = f["v"] != "0",
                    rampSeconds = f["ra"]?.toIntOrNull() ?: 0,
                    snoozeMinutes = f["sn"]?.toIntOrNull(),
                    honorExceptions = f["ex"] != "0",
                    wakeCheck = WakeCheck(
                        method = WakeMethod.entries.firstOrNull { it.name == f["w"] } ?: WakeMethod.NONE,
                        level = WakeLevel.entries.firstOrNull { it.name == f["wl"] } ?: WakeLevel.MEDIUM,
                        tasks = f["wt"]?.toIntOrNull()?.coerceIn(1, WakeCheck.MAX_TASKS) ?: 1,
                        reference = f["wr"].orEmpty(),
                        snoozeAllowed = f["ws"] != "0",
                        recheckMinutes = f["rc"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    ),
                    gentleMinutes = f["gm"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                    after = AfterAlarm(
                        presetId = f["ap"]?.takeIf { it.isNotEmpty() },
                        reminder = f["ar"].orEmpty(),
                        reminderMinutes = f["am"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                        app = f["aa"].orEmpty(),
                    ),
                    warnings = Warnings.decode(f["wn"].orEmpty()),
                )
            }
        }
    }
}

/** Повтор строкой: `once`, `w:<биты>`, `d:<день>`, `c:<день>:<дни цикла>`, `wd`, `x:<правила JSON>` (D-025). */
object RepeatCode {
    fun encode(repeat: Repeat): String = when (repeat) {
        Repeat.Once -> "once"
        is Repeat.Weekly -> "w:" + repeat.bits
        is Repeat.OnDate -> "d:" + repeat.date.toEpochDay()
        is Repeat.Cycle -> "c:" + repeat.anchor.toEpochDay() + ":" + repeat.encodeDays()
        Repeat.Workdays -> "wd"
        is Repeat.Rules -> "x:" + RulesCode.encode(repeat)
    }

    fun decode(code: String): Repeat {
        if (code.startsWith("x:")) return RulesCode.decode(code.removePrefix("x:"))
        val parts = code.split(':', limit = 3)
        return when (parts[0]) {
            "w" -> Repeat.fromBits(parts[1].toInt())
            "d" -> Repeat.OnDate(LocalDate.ofEpochDay(parts[1].toLong()))
            "c" -> Repeat.Cycle(LocalDate.ofEpochDay(parts[1].toLong()), Repeat.Cycle.decodeDays(parts[2]))
            "wd" -> Repeat.Workdays
            else -> Repeat.Once
        }
    }
}
