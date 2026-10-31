package com.dpsmpx.dpclock.model

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Откуда задача (D-032): своя — в приложении, или из Google Tasks. */
enum class TaskSource { OWN, GOOGLE }

/**
 * Задача на сегодня для экранов: своя или из Google Tasks. [date] — срок (день),
 * [time] — время срока, если задано. [listId] — список Google: по нему задача
 * закрывается; у своей — пусто.
 */
data class DayTask(
    val source: TaskSource,
    val id: String,
    val listId: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
)

/** Повтор своей задачи (D-032; выбор автора — повтор есть). */
sealed interface TaskRepeat {
    data object None : TaskRepeat

    data object Daily : TaskRepeat

    /** По дням недели; пусто — по дню недели срока. */
    data class Weekly(val days: Set<DayOfWeek>) : TaskRepeat

    /** Каждый месяц [day]-го; в коротком месяце — в последний день. */
    data class Monthly(val day: Int) : TaskRepeat

    /** Каждый год [day] [month]-го; 29 февраля в невисокосный год — 28-го. */
    data class Yearly(val month: Int, val day: Int) : TaskRepeat

    /** Строкой для базы и файла: «», «daily», «weekly:MONDAY,FRIDAY», «monthly:31», «yearly:2-29». */
    val code: String
        get() = when (this) {
            None -> ""
            Daily -> "daily"
            is Weekly -> "weekly:" + DayOfWeek.entries.filter { it in days }.joinToString(",") { it.name }
            is Monthly -> "monthly:$day"
            is Yearly -> "yearly:$month-$day"
        }

    companion object {
        /** Повтор с тем же видом, но привязанный к [date]: месяц и год — от числа срока. */
        fun of(kind: TaskRepeat, date: LocalDate): TaskRepeat = when (kind) {
            is Monthly -> Monthly(date.dayOfMonth)
            is Yearly -> Yearly(date.monthValue, date.dayOfMonth)
            else -> kind
        }

        /** Чужое и повреждённое — без повтора. */
        fun decode(code: String): TaskRepeat = runCatching {
            when {
                code.isEmpty() -> None
                code == "daily" -> Daily
                code.startsWith("weekly:") -> Weekly(code.removePrefix("weekly:").split(',').filter { it.isNotEmpty() }.map { DayOfWeek.valueOf(it) }.toSet())
                code.startsWith("monthly:") -> Monthly(code.removePrefix("monthly:").toInt().coerceIn(1, 31))
                code.startsWith("yearly:") -> code.removePrefix("yearly:").split('-').let { (m, d) -> Yearly(m.toInt().coerceIn(1, 12), d.toInt().coerceIn(1, 31)) }
                else -> None
            }
        }.getOrDefault(None)
    }
}

/**
 * Своя задача (D-032). [date] — срок, null — без срока; [time] — время срока.
 * [reminder] — за сколько минут до срока напомнить, null — не напоминать; у задачи
 * без времени срок — в [TaskRules.DAY_HOUR] часов. Сделанная повторяющаяся задача
 * не закрывается, а переходит на следующий срок.
 *
 * [inCalendar] и [eventId] — свои у телефона (как включение будильника, D-030):
 * событие пишет в календарь только тот телефон, где стоит флажок, — иначе два
 * телефона с одним аккаунтом Google сделали бы два события.
 */
data class Task(
    val id: String,
    val title: String,
    val note: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val reminder: Int? = null,
    val repeat: TaskRepeat = TaskRepeat.None,
    val done: Boolean = false,
    val doneAt: Long? = null,
    val createdAt: Long = 0L,
    val inCalendar: Boolean = false,
    val eventId: Long? = null,
) {
    companion object {
        const val MAX_TITLE = 300
        const val MAX_NOTE = 4000
    }
}

/** Задачи дня и правила своих задач (выбор автора — сегодня и просроченные). */
object TaskRules {
    /** Google Tasks — не чаще: задачи меняются не каждую минуту, а запросы — к чужому серверу. */
    const val REFRESH_MILLIS = 15 * 60_000L

    /** Уведомление после будильника — утром: с 4 до 12 часов, один раз в день. */
    const val MORNING_FROM = 4
    const val MORNING_TO = 12

    /** Срок задачи без времени для напоминания и календаря — в 9 утра. */
    const val DAY_HOUR = 9

    /** Напоминание: в срок, за 5, 15, 30 минут, за час, за день. */
    val REMINDERS = listOf(0, 5, 15, 30, 60, 24 * 60)

    fun overdue(task: DayTask, today: LocalDate): Boolean = task.date < today

    /** Сегодня и просроченные: сначала просроченные по сроку, потом по времени и названию. */
    fun forDay(tasks: List<DayTask>, today: LocalDate): List<DayTask> =
        tasks.filter { it.date <= today }
            .distinctBy { it.source to it.id }
            .sortedWith(
                compareBy<DayTask> { if (overdue(it, today)) 0 else 1 }
                    .thenBy { it.date }
                    .thenBy(nullsLast()) { it.time }
                    .thenBy { it.title.lowercase() },
            )

    fun isMorning(time: LocalTime): Boolean = time.hour in MORNING_FROM until MORNING_TO

    /** Своя задача для списков дня: несделанная и со сроком. */
    fun day(task: Task): DayTask? =
        if (task.done || task.date == null) null else DayTask(TaskSource.OWN, task.id, "", task.title, task.date, task.time)

    /** Момент срока: дата и время, у задачи без времени — [DAY_HOUR] часов. */
    fun dueAt(task: Task, zone: ZoneId): Instant? = task.date?.atTime(task.time ?: LocalTime.of(DAY_HOUR, 0))?.atZone(zone)?.toInstant()

    /** Когда напомнить: только в будущем и у несделанной задачи со сроком. */
    fun reminderAt(task: Task, zone: ZoneId, now: Instant): Instant? {
        val minutes = task.reminder ?: return null
        if (task.done) return null
        val at = dueAt(task, zone)?.minusSeconds(minutes * 60L) ?: return null
        return at.takeIf { it.isAfter(now) }
    }

    /** Ближайший срок повтора строго после [after]; без повтора — null. */
    fun next(repeat: TaskRepeat, date: LocalDate, after: LocalDate): LocalDate? = when (repeat) {
        TaskRepeat.None -> null
        TaskRepeat.Daily -> after.plusDays(1)
        is TaskRepeat.Weekly -> {
            val days = repeat.days.ifEmpty { setOf(date.dayOfWeek) }
            (1L..7L).map { after.plusDays(it) }.first { it.dayOfWeek in days }
        }
        is TaskRepeat.Monthly -> {
            var month = after.withDayOfMonth(1)
            var found: LocalDate
            do {
                found = month.withDayOfMonth(minOf(repeat.day, month.lengthOfMonth()))
                month = month.plusMonths(1)
            } while (found <= after)
            found
        }
        is TaskRepeat.Yearly -> {
            var year = after.year
            var found: LocalDate
            do {
                val first = LocalDate.of(year, repeat.month, 1)
                found = first.withDayOfMonth(minOf(repeat.day, first.lengthOfMonth()))
                year++
            } while (found <= after)
            found
        }
    }

    /**
     * «Сделано» (выбор автора — отметка). Повторяющаяся задача со сроком переходит
     * на ближайший срок после сегодня или после прежнего срока, что позже;
     * остальные отмечаются сделанными.
     */
    fun complete(task: Task, today: LocalDate, now: Long): Task {
        val date = task.date
        val next = date?.let { next(task.repeat, it, maxOf(it, today)) }
        return if (next != null) task.copy(date = next, eventId = null) else task.copy(done = true, doneAt = now)
    }
}

/** Ответ сервиса не разобрался. */
class TaskFormatException(message: String) : RuntimeException(message)

/** Страница ответа: задачи и метка следующей страницы. */
data class TaskPage(val tasks: List<DayTask>, val next: String?)

/**
 * Google Tasks API v1 (tasks.googleapis.com), доступ `https://www.googleapis.com/auth/tasks`.
 * Срок у Google — только день: время в `due` всегда полночь UTC.
 */
object GoogleTasksApi {
    const val BASE = "https://tasks.googleapis.com/tasks/v1"
    const val SCOPE = "https://www.googleapis.com/auth/tasks"

    /** Больше списков не читаем: на каждый — свой запрос. */
    const val MAX_LISTS = 20

    /** Закрыть задачу — `PATCH` с этим телом. */
    val COMPLETE_BODY: String = Json.write(mapOf("status" to "completed"))

    fun listsUrl(pageToken: String?): String = "$BASE/users/@me/lists?maxResults=100" + (pageToken?.let { "&pageToken=" + encode(it) } ?: "")

    /** Невыполненные задачи списка со сроком не позже [today]. */
    fun tasksUrl(listId: String, today: LocalDate, pageToken: String?): String =
        "$BASE/lists/" + encode(listId) + "/tasks?showCompleted=false&showHidden=false&maxResults=100&dueMax=" +
            encode(today.atTime(23, 59, 59).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)) +
            (pageToken?.let { "&pageToken=" + encode(it) } ?: "")

    fun taskUrl(listId: String, id: String): String = "$BASE/lists/" + encode(listId) + "/tasks/" + encode(id)

    /** Списки: id и следующая страница. */
    fun parseLists(text: String): Pair<List<String>, String?> = parsing {
        val root = Json.parse(text) as Map<*, *>
        val ids = (root["items"] as? List<*>).orEmpty().map { (it as Map<*, *>)["id"] as String }
        ids to root["nextPageToken"] as? String
    }

    fun parseTasks(text: String, listId: String): TaskPage = parsing {
        val root = Json.parse(text) as Map<*, *>
        val tasks = (root["items"] as? List<*>).orEmpty().mapNotNull { raw ->
            val item = raw as Map<*, *>
            if (item["status"] != "needsAction" || item["deleted"] == true || item["hidden"] == true) return@mapNotNull null
            val due = item["due"] as? String ?: return@mapNotNull null
            DayTask(
                source = TaskSource.GOOGLE,
                id = item["id"] as String,
                listId = listId,
                title = (item["title"] as? String).orEmpty(),
                date = OffsetDateTime.parse(due).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
            )
        }
        TaskPage(tasks, root["nextPageToken"] as? String)
    }
}

/**
 * Вход Google в браузере (D-032; выбор автора — «веб-форма»): OAuth 2.0 для
 * установленных приложений — клиент «Компьютерное приложение», возврат на
 * `http://127.0.0.1:<порт>`, PKCE (S256). Клиент и секрет — из проекта Google
 * Cloud человека: у установленных приложений секрет не тайна (так пишет Google),
 * но в коде его нет.
 */
object GoogleOAuth {
    const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_URL = "https://oauth2.googleapis.com/token"
    const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"

    private val base64 = Base64.getUrlEncoder().withoutPadding()

    /** Секрет проверки (code_verifier) из 32 случайных байт — 43 знака. */
    fun verifier(random: ByteArray): String = base64.encodeToString(random)

    fun challenge(verifier: String): String = base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** Страница входа: доступ только к Google Tasks; `offline` и `consent` — чтобы пришёл ключ обновления. */
    fun authUrl(clientId: String, redirect: String, challenge: String, state: String): String = AUTH_URL + "?" + form(
        "client_id" to clientId,
        "redirect_uri" to redirect,
        "response_type" to "code",
        "scope" to GoogleTasksApi.SCOPE,
        "code_challenge" to challenge,
        "code_challenge_method" to "S256",
        "state" to state,
        "access_type" to "offline",
        "prompt" to "consent",
    )

    fun codeBody(clientId: String, secret: String, code: String, verifier: String, redirect: String): String = form(
        "client_id" to clientId,
        "client_secret" to secret,
        "code" to code,
        "code_verifier" to verifier,
        "grant_type" to "authorization_code",
        "redirect_uri" to redirect,
    )

    fun refreshBody(clientId: String, secret: String, refreshToken: String): String = form(
        "client_id" to clientId,
        "client_secret" to secret,
        "refresh_token" to refreshToken,
        "grant_type" to "refresh_token",
    )

    fun revokeBody(token: String): String = form("token" to token)

    /** Ключи из ответа: [refresh] приходит только при первом входе. */
    data class Tokens(val access: String, val expiresIn: Long, val refresh: String?)

    fun parseTokens(text: String): Tokens = parsing {
        val root = Json.parse(text) as Map<*, *>
        Tokens(root["access_token"] as String, root["expires_in"] as? Long ?: 3600L, root["refresh_token"] as? String)
    }

    /** Отказ Google в теле ответа: `{"error": "invalid_client", "error_description": "…"}`; не разобрался — пусто. */
    fun parseError(text: String): String = runCatching {
        val root = Json.parse(text) as Map<*, *>
        listOfNotNull(root["error"] as? String, root["error_description"] as? String).joinToString(": ")
    }.getOrDefault("")

    /** Что браузер принёс на 127.0.0.1: код или отказ и state. */
    data class Redirect(val code: String?, val state: String?, val error: String?)

    /** [target] — путь из первой строки запроса: «/?state=…&code=…&scope=…». */
    fun parseRedirect(target: String): Redirect {
        val query = target.substringAfter('?', "")
        val values = query.split('&').filter { '=' in it }.associate {
            URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8.name()) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8.name())
        }
        return Redirect(values["code"], values["state"], values["error"])
    }

    private fun form(vararg pairs: Pair<String, String>): String = pairs.joinToString("&") { (k, v) -> encode(k) + "=" + encode(v) }
}

/**
 * Что телефон помнит о задачах Google и показе (D-032): последние задачи Google,
 * где показывать задачи дня и день последнего уведомления после будильника.
 */
data class TaskCache(
    val google: Boolean = false,
    val tasks: List<DayTask> = emptyList(),
    val fetchedAt: Long = 0L,
    /** Почему Google не обновился в прошлый раз: код HTTP, 0 — нет связи, [SIGN_IN] — нужен вход; null — обновился. */
    val failure: Int? = null,
    val showClock: Boolean = true,
    val showAfterAlarm: Boolean = true,
    val showWidget: Boolean = true,
    val notifiedOn: LocalDate? = null,
) {
    companion object {
        const val SIGN_IN = -1
        private const val VERSION = 2

        fun encode(cache: TaskCache): String = Json.write(
            linkedMapOf(
                "version" to VERSION,
                "google" to cache.google,
                "fetchedAt" to cache.fetchedAt,
                "failure" to cache.failure,
                "showClock" to cache.showClock,
                "showAfterAlarm" to cache.showAfterAlarm,
                "showWidget" to cache.showWidget,
                "notifiedOn" to cache.notifiedOn?.toString(),
                "tasks" to cache.tasks.map {
                    linkedMapOf(
                        "id" to it.id,
                        "listId" to it.listId,
                        "title" to it.title,
                        "date" to it.date.toString(),
                        "time" to it.time?.toString(),
                    )
                },
            ),
        )

        /**
         * Повреждённый файл — пустой: задачи придут при следующем обновлении. Файл
         * 0.18.0 (без версии) — только выбор показа: вход Google с тех пор другой.
         */
        fun decode(text: String): TaskCache = runCatching {
            val root = Json.parse(text) as Map<*, *>
            val shown = TaskCache(
                showClock = root["showClock"] as? Boolean ?: true,
                showAfterAlarm = root["showAfterAlarm"] as? Boolean ?: true,
                showWidget = root["showWidget"] as? Boolean ?: true,
                notifiedOn = (root["notifiedOn"] as? String)?.let { LocalDate.parse(it) },
            )
            if (root["version"] != VERSION.toLong()) return@runCatching shown
            shown.copy(
                google = root["google"] as Boolean,
                fetchedAt = root["fetchedAt"] as Long,
                failure = (root["failure"] as? Long)?.toInt(),
                tasks = (root["tasks"] as List<*>).map { raw ->
                    val item = raw as Map<*, *>
                    DayTask(
                        source = TaskSource.GOOGLE,
                        id = item["id"] as String,
                        listId = item["listId"] as String,
                        title = item["title"] as String,
                        date = LocalDate.parse(item["date"] as String),
                        time = (item["time"] as? String)?.let { LocalTime.parse(it) },
                    )
                },
            )
        }.getOrDefault(TaskCache())
    }
}

private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

/** Ошибка разбора — [TaskFormatException]: ответ не тот, что ждали. */
private fun <T> parsing(block: () -> T): T = try {
    block()
} catch (e: TaskFormatException) {
    throw e
} catch (e: RuntimeException) {
    throw TaskFormatException(e.message ?: e::class.simpleName.orEmpty())
}
