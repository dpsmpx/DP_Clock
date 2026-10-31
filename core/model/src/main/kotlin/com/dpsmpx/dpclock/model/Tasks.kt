package com.dpsmpx.dpclock.model

import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Таск-менеджеры (D-031; выбор автора — все три). */
enum class TaskService { TODOIST, TICKTICK, GOOGLE }

/**
 * Задача на сегодня из таск-менеджера. [date] — срок (день), [time] — время срока,
 * если задано; [start] — с какого дня задача идёт (у TickTick — начало; у остальных — срок).
 * [listId] — список или проект: TickTick и Google Tasks закрывают задачу по нему.
 * [priority] — 0 (нет) … 3 (высокий), одинаково для всех сервисов.
 */
data class DayTask(
    val service: TaskService,
    val id: String,
    val listId: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val start: LocalDate = date,
    val priority: Int = 0,
)

/** Что показывать и в каком порядке (выбор автора — сегодня и просроченные). */
object TaskRules {
    /** Не чаще: задачи меняются не каждую минуту, а запросы — к чужим серверам. */
    const val REFRESH_MILLIS = 15 * 60_000L

    /** Уведомление после будильника — утром: с 4 до 12 часов, один раз в день. */
    const val MORNING_FROM = 4
    const val MORNING_TO = 12

    fun overdue(task: DayTask, today: LocalDate): Boolean = task.date < today

    /** Сегодня и просроченные: сначала просроченные по сроку, потом по времени, важности и названию. */
    fun forDay(tasks: List<DayTask>, today: LocalDate): List<DayTask> =
        tasks.filter { it.start <= today }
            .distinctBy { it.service to it.id }
            .sortedWith(
                compareBy<DayTask> { if (overdue(it, today)) 0 else 1 }
                    .thenBy { it.date }
                    .thenBy(nullsLast()) { it.time }
                    .thenByDescending { it.priority }
                    .thenBy { it.title.lowercase() },
            )

    fun isMorning(time: LocalTime): Boolean = time.hour in MORNING_FROM until MORNING_TO
}

/** Ответ сервиса не разобрался. */
class TaskFormatException(message: String) : RuntimeException(message)

/** Страница ответа: задачи и метка следующей страницы. */
data class TaskPage(val tasks: List<DayTask>, val next: String?)

/**
 * Todoist API v1 (developer.todoist.com/api/v1): личный ключ из «Настройки →
 * Интеграции → Разработчик» в заголовке `Authorization: Bearer`.
 */
object TodoistApi {
    const val BASE = "https://api.todoist.com/api/v1"

    /** Фильтр Todoist: сегодня и просроченные. */
    const val QUERY = "today | overdue"
    private const val LIMIT = 200

    fun filterUrl(cursor: String?): String =
        "$BASE/tasks/filter?query=" + encode(QUERY) + "&limit=$LIMIT" + (cursor?.let { "&cursor=" + encode(it) } ?: "")

    fun closeUrl(id: String): String = "$BASE/tasks/" + encode(id) + "/close"

    /**
     * Срок — `due.date`: день «2026-10-31», плавающее время «2026-10-31T09:00:00»
     * или точное в UTC «2026-10-31T09:00:00Z»; точное переводится в пояс телефона.
     */
    fun parse(text: String, zone: ZoneId): TaskPage = parsing {
        val root = Json.parse(text) as Map<*, *>
        val tasks = (root["results"] as List<*>).mapNotNull { raw ->
            val item = raw as Map<*, *>
            if (item["checked"] == true || item["is_deleted"] == true) return@mapNotNull null
            val due = (item["due"] as? Map<*, *>)?.get("date") as? String ?: return@mapNotNull null
            val (date, time) = dateTime(due, zone)
            DayTask(
                service = TaskService.TODOIST,
                id = item["id"] as String,
                listId = item["project_id"] as? String ?: "",
                title = (item["content"] as? String).orEmpty(),
                date = date,
                time = time,
                // Todoist: 1 — обычная, 4 — срочная.
                priority = ((item["priority"] as? Long)?.toInt() ?: 1).coerceIn(1, 4) - 1,
            )
        }
        TaskPage(tasks, root["next_cursor"] as? String)
    }

    private fun dateTime(due: String, zone: ZoneId): Pair<LocalDate, LocalTime?> = when {
        due.length == 10 -> LocalDate.parse(due) to null
        due.endsWith("Z") -> Instant.parse(due).atZone(zone).let { it.toLocalDate() to it.toLocalTime() }
        else -> LocalDateTime.parse(due).let { it.toLocalDate() to it.toLocalTime() }
    }
}

/**
 * TickTick Open API (developer.ticktick.com/docs#/openapi): личный ключ из веб-версии,
 * «Настройки → Аккаунт → API Token», в заголовке `Authorization: Bearer`.
 */
object TickTickApi {
    const val BASE = "https://api.ticktick.com/open/v1"
    const val FILTER_URL = "$BASE/task/filter"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSZ")

    fun completeUrl(projectId: String, id: String): String = "$BASE/project/" + encode(projectId) + "/task/" + encode(id) + "/complete"

    /** Открытые задачи, начатые не позже конца [today]: сегодняшние и просроченные (до 200). */
    fun filterBody(today: LocalDate, zone: ZoneId): String = Json.write(
        linkedMapOf(
            "endDate" to today.atTime(LocalTime.MAX).atZone(zone).format(STAMP),
            "status" to listOf(0),
        ),
    )

    /**
     * Срок — `dueDate`, без него — `startDate`: момент «2026-03-05T00:00:00.000+0000».
     * У задачи на весь день (`isAllDay`) день считается в её поясе (`timeZone`).
     */
    fun parse(text: String, zone: ZoneId): List<DayTask> = parsing {
        (Json.parse(text) as List<*>).mapNotNull { raw ->
            val item = raw as Map<*, *>
            if ((item["status"] as? Long ?: 0L) != 0L) return@mapNotNull null
            val dueText = item["dueDate"] as? String ?: item["startDate"] as? String ?: return@mapNotNull null
            val startText = item["startDate"] as? String ?: dueText
            val allDay = item["isAllDay"] as? Boolean ?: false
            val taskZone = (item["timeZone"] as? String)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: zone
            val due = stamp(dueText)
            val start = stamp(startText)
            DayTask(
                service = TaskService.TICKTICK,
                id = item["id"] as String,
                listId = item["projectId"] as? String ?: "",
                title = (item["title"] as? String).orEmpty(),
                date = due.atZone(if (allDay) taskZone else zone).toLocalDate(),
                time = if (allDay) null else due.atZone(zone).toLocalTime(),
                start = start.atZone(if (allDay) taskZone else zone).toLocalDate(),
                // TickTick: 0 — нет, 1 — низкая, 3 — средняя, 5 — высокая.
                priority = when ((item["priority"] as? Long)?.toInt() ?: 0) {
                    5 -> 3
                    3 -> 2
                    1 -> 1
                    else -> 0
                },
            )
        }
    }

    private fun stamp(text: String): Instant = try {
        OffsetDateTime.parse(text, STAMP).toInstant()
    } catch (e: DateTimeParseException) {
        // Без долей секунды: «2019-11-13T03:00:00+0000».
        OffsetDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")).toInstant()
    }
}

/**
 * Google Tasks API v1 (tasks.googleapis.com): вход Google, доступ
 * `https://www.googleapis.com/auth/tasks`. Срок у Google — только день: время в
 * `due` всегда полночь UTC.
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
                service = TaskService.GOOGLE,
                id = item["id"] as String,
                listId = listId,
                title = (item["title"] as? String).orEmpty(),
                date = OffsetDateTime.parse(due).withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
            )
        }
        TaskPage(tasks, root["nextPageToken"] as? String)
    }
}

/** Последние задачи на телефоне (D-031, п. 6): что показывать без сети. */
data class TaskCache(
    val connected: Set<TaskService> = emptySet(),
    val tasks: List<DayTask> = emptyList(),
    val fetchedAt: Long = 0L,
    /** Почему сервис не обновился в прошлый раз: код HTTP, 0 — нет связи, -1 — нужен вход. */
    val failures: Map<TaskService, Int> = emptyMap(),
    /** Аккаунт Google — показать в настройках. */
    val googleAccount: String = "",
    val showClock: Boolean = true,
    val showAfterAlarm: Boolean = true,
    val showWidget: Boolean = true,
    /** День, когда уже было уведомление после будильника. */
    val notifiedOn: LocalDate? = null,
) {
    companion object {
        const val SIGN_IN = -1

        fun encode(cache: TaskCache): String = Json.write(
            linkedMapOf(
                "connected" to cache.connected.map { it.name }.sorted(),
                "fetchedAt" to cache.fetchedAt,
                "failures" to cache.failures.entries.associate { (service, code) -> service.name to code }.toSortedMap(),
                "googleAccount" to cache.googleAccount,
                "showClock" to cache.showClock,
                "showAfterAlarm" to cache.showAfterAlarm,
                "showWidget" to cache.showWidget,
                "notifiedOn" to cache.notifiedOn?.toString(),
                "tasks" to cache.tasks.map {
                    linkedMapOf(
                        "service" to it.service.name,
                        "id" to it.id,
                        "listId" to it.listId,
                        "title" to it.title,
                        "date" to it.date.toString(),
                        "time" to it.time?.toString(),
                        "start" to it.start.toString(),
                        "priority" to it.priority,
                    )
                },
            ),
        )

        /** Повреждённый файл — пустой: задачи придут при следующем обновлении. */
        fun decode(text: String): TaskCache = runCatching {
            val root = Json.parse(text) as Map<*, *>
            fun service(name: Any?) = TaskService.entries.firstOrNull { it.name == name }
            TaskCache(
                connected = (root["connected"] as List<*>).mapNotNull { service(it) }.toSet(),
                fetchedAt = root["fetchedAt"] as Long,
                failures = (root["failures"] as? Map<*, *>).orEmpty().entries.mapNotNull { (k, v) -> service(k)?.let { it to (v as Long).toInt() } }.toMap(),
                googleAccount = root["googleAccount"] as? String ?: "",
                showClock = root["showClock"] as? Boolean ?: true,
                showAfterAlarm = root["showAfterAlarm"] as? Boolean ?: true,
                showWidget = root["showWidget"] as? Boolean ?: true,
                notifiedOn = (root["notifiedOn"] as? String)?.let { LocalDate.parse(it) },
                tasks = (root["tasks"] as List<*>).mapNotNull { raw ->
                    val item = raw as Map<*, *>
                    DayTask(
                        service = service(item["service"]) ?: return@mapNotNull null,
                        id = item["id"] as String,
                        listId = item["listId"] as String,
                        title = item["title"] as String,
                        date = LocalDate.parse(item["date"] as String),
                        time = (item["time"] as? String)?.let { LocalTime.parse(it) },
                        start = LocalDate.parse(item["start"] as String),
                        priority = (item["priority"] as Long).toInt(),
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
