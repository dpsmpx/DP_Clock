package com.dpsmpx.dpclock.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Задача на сегодня для «Часов», уведомления и виджета (D-032): [date] — срок, [time] — время срока. */
data class DayTask(
    val id: String,
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
 * телефона с одним аккаунтом Google сделали бы два события. [nudged] — тоже своё:
 * для какой последней отметки уже было тихое напоминание (D-033).
 *
 * Постоянная задача ([permanent], D-033; идея — DP_Repeated_Tasks автора) не
 * закрывается: «сделано» ставит отметку ([TaskMark]), и видно, сколько прошло с
 * прошлого раза. [parentId] — чья это подзадача. [remindLate] и [remindAfterDays] —
 * тихое напоминание постоянной: когда прошло дольше обычного и через столько дней
 * после последней отметки.
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
    val permanent: Boolean = false,
    val parentId: String? = null,
    val difficulty: TaskLevel = TaskLevel.NONE,
    val urgency: TaskLevel = TaskLevel.NONE,
    val remindLate: Boolean = false,
    val remindAfterDays: Int? = null,
    val inCalendar: Boolean = false,
    val eventId: Long? = null,
    val nudged: Long? = null,
) {
    companion object {
        const val MAX_TITLE = 300
        const val MAX_NOTE = 4000
    }
}

/** Сложность и срочность задачи (D-033): не указана, низкая, средняя, высокая. */
enum class TaskLevel { NONE, LOW, MEDIUM, HIGH }

/** Отметка «сделано» (D-033): когда, мс UTC. Отметки не меняются — добавляются и удаляются. */
data class TaskMark(val id: String, val taskId: String, val at: Long)

/** Что было на самом деле: сколько раз, когда последний, обычный, самый короткий и самый длинный промежуток. */
data class TaskStats(val count: Int, val last: Long?, val average: Long?, val shortest: Long?, val longest: Long?)

/** Единицы «прошло»: годы и месяцы — по 365 и 30 дней, как в DP_Repeated_Tasks. */
enum class ElapsedUnit { YEAR, MONTH, DAY, HOUR, MINUTE }

/** Сортировка списков задач (D-033). */
enum class TaskSort { DUE, ELAPSED, NAME, URGENCY, DIFFICULTY }

/**
 * История отметок (D-033): «прошло с прошлого раза», «обычно раз в …», «дольше
 * обычного» и тихое напоминание постоянной задачи. Обычный промежуток — среднее
 * между соседними отметками: это описание того, что было, а не расписание.
 */
object TaskHistory {
    /** Тихие напоминания — не ночью: с 21 до 9 часов ждут утра. */
    const val QUIET_FROM = 21
    const val QUIET_TO = 9

    /** «Напомнить через»: 1, 2, 3 дня, неделю, 2 недели, месяц, 2, 3, 6 месяцев, год. */
    val AFTER_DAYS = listOf(1, 2, 3, 7, 14, 30, 60, 90, 180, 365)

    fun stats(marks: List<Long>): TaskStats {
        val sorted = marks.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }
        return TaskStats(
            count = sorted.size,
            last = sorted.lastOrNull(),
            average = if (gaps.isEmpty()) null else gaps.sum() / gaps.size,
            shortest = gaps.minOrNull(),
            longest = gaps.maxOrNull(),
        )
    }

    /** Прошло дольше обычного: нужна хотя бы пара отметок. */
    fun late(stats: TaskStats, now: Long): Boolean {
        val last = stats.last ?: return false
        val average = stats.average ?: return false
        return now - last > average
    }

    /** «2 д 3 ч»: две старшие единицы; меньше минуты — ноль минут. */
    fun elapsed(millis: Long): List<Pair<ElapsedUnit, Int>> {
        val minutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
        val hours = minutes / 60
        val days = hours / 24
        val years = days / 365
        val months = days % 365 / 30
        return when {
            hours == 0 -> listOf(ElapsedUnit.MINUTE to minutes)
            days == 0 -> listOf(ElapsedUnit.HOUR to hours)
            days < 30 -> listOf(ElapsedUnit.DAY to days, ElapsedUnit.HOUR to hours % 24).filter { it.second > 0 || it.first == ElapsedUnit.DAY }
            years == 0 -> listOf(ElapsedUnit.MONTH to months, ElapsedUnit.DAY to days % 365 % 30).filter { it.second > 0 || it.first == ElapsedUnit.MONTH }
            else -> listOf(ElapsedUnit.YEAR to years, ElapsedUnit.MONTH to months).filter { it.second > 0 || it.first == ElapsedUnit.YEAR }
        }
    }

    /**
     * Когда тихо напомнить о постоянной задаче (выбор автора — когда дольше обычного и
     * через заданное время): что наступит раньше, один раз на последнюю отметку
     * ([Task.nudged]). Прошло, а напоминания не было, — через минуту. Ночью — утром.
     */
    fun nudgeAt(task: Task, marks: List<Long>, now: Instant, zone: ZoneId): Instant? {
        if (!task.permanent || task.done) return null
        val stats = stats(marks)
        val base = stats.last ?: task.createdAt
        if (task.nudged == base) return null
        val late = if (task.remindLate && stats.last != null) stats.average?.let { stats.last + it } else null
        val after = task.remindAfterDays?.let { base + it * 86_400_000L }
        val at = listOfNotNull(late, after).minOrNull() ?: return null
        return awake(maxOf(Instant.ofEpochMilli(at), now.plusSeconds(60)), zone)
    }

    /** Момент вне тихих часов: ночью — в [QUIET_TO] утра. */
    fun awake(at: Instant, zone: ZoneId): Instant {
        val local = at.atZone(zone)
        return when {
            local.hour >= QUIET_FROM -> local.toLocalDate().plusDays(1).atTime(QUIET_TO, 0).atZone(zone).toInstant()
            local.hour < QUIET_TO -> local.toLocalDate().atTime(QUIET_TO, 0).atZone(zone).toInstant()
            else -> at
        }
    }

    /** Основа напоминания: последняя отметка или создание. */
    fun base(task: Task, marks: List<Long>): Long = marks.maxOrNull() ?: task.createdAt
}

/** Подзадачи, сортировка и поиск (D-033). */
object TaskTree {
    /** Глубже не бывает; защита от петли в повреждённых данных. */
    const val MAX_DEPTH = 16

    /**
     * Деревом: верхние — по [order], под каждой — её подзадачи тем же порядком.
     * Свёрнутые ([collapsed]) — без подзадач. Подзадача, чьей родительской нет в
     * списке, — наверх. Возвращает задачу и глубину.
     */
    fun flatten(tasks: List<Task>, collapsed: Set<String>, order: Comparator<Task>): List<Pair<Task, Int>> {
        val ids = tasks.map { it.id }.toSet()
        val children = tasks.filter { it.parentId != null && it.parentId in ids }.groupBy { it.parentId }
        val out = mutableListOf<Pair<Task, Int>>()
        val seen = mutableSetOf<String>()
        fun add(task: Task, depth: Int) {
            if (!seen.add(task.id) || depth > MAX_DEPTH) return
            out += task to depth
            if (task.id !in collapsed) children[task.id].orEmpty().sortedWith(order).forEach { add(it, depth + 1) }
        }
        tasks.filter { it.parentId == null || it.parentId !in ids }.sortedWith(order).forEach { add(it, 0) }
        return out
    }

    /** Все подзадачи [id] на любой глубине. */
    fun descendants(tasks: List<Task>, id: String): Set<String> {
        val children = tasks.groupBy { it.parentId }
        val out = mutableSetOf<String>()
        fun walk(parent: String, depth: Int) {
            if (depth > MAX_DEPTH) return
            for (child in children[parent].orEmpty()) if (out.add(child.id)) walk(child.id, depth + 1)
        }
        walk(id, 0)
        return out
    }

    /** Есть ли подзадачи. */
    fun hasChildren(tasks: List<Task>, id: String): Boolean = tasks.any { it.parentId == id }

    /**
     * Порядок: по сроку (без срока — в конце), «давно не делал» (ни разу — первыми,
     * потом самые давние), по названию, по срочности и сложности (высокая — первой).
     * [last] — последняя отметка задачи.
     */
    fun order(sort: TaskSort, last: Map<String, Long>): Comparator<Task> {
        val byName = compareBy<Task> { it.title.lowercase() }
        return when (sort) {
            TaskSort.DUE -> compareBy<Task, LocalDate?>(nullsLast()) { it.date }.thenBy(nullsLast()) { it.time }.then(byName)
            TaskSort.ELAPSED -> compareBy<Task> { last[it.id] ?: Long.MIN_VALUE }.then(byName)
            TaskSort.NAME -> byName
            TaskSort.URGENCY -> compareByDescending<Task> { it.urgency.ordinal }.then(byName)
            TaskSort.DIFFICULTY -> compareByDescending<Task> { it.difficulty.ordinal }.then(byName)
        }
    }

    /** Поиск по названию и заметке без учёта регистра и «ё». */
    fun matches(task: Task, query: String): Boolean {
        val q = normalize(query.trim())
        return q.isEmpty() || normalize(task.title).contains(q) || normalize(task.note).contains(q)
    }

    private fun normalize(text: String) = text.lowercase().replace('ё', 'е')
}

/** Задачи дня и правила своих задач (выбор автора — сегодня и просроченные). */
object TaskRules {
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
            .distinctBy { it.id }
            .sortedWith(
                compareBy<DayTask> { if (overdue(it, today)) 0 else 1 }
                    .thenBy { it.date }
                    .thenBy(nullsLast()) { it.time }
                    .thenBy { it.title.lowercase() },
            )

    fun isMorning(time: LocalTime): Boolean = time.hour in MORNING_FROM until MORNING_TO

    /** Своя задача для списков дня: несделанная, не постоянная и со сроком. */
    fun day(task: Task): DayTask? =
        if (task.done || task.permanent || task.date == null) null else DayTask(task.id, task.title, task.date, task.time)

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
        // Постоянная не закрывается: отметку ставит хранилище (D-033).
        if (task.permanent) return task
        val date = task.date
        val next = date?.let { next(task.repeat, it, maxOf(it, today)) }
        return if (next != null) task.copy(date = next, eventId = null) else task.copy(done = true, doneAt = now)
    }
}

/**
 * Где показывать задачи дня (D-031, п. 3) и день последнего уведомления после
 * будильника — своё у телефона, вне переноса и синхронизации.
 */
data class TaskPrefs(
    val showClock: Boolean = true,
    val showAfterAlarm: Boolean = true,
    val showWidget: Boolean = true,
    val notifiedOn: LocalDate? = null,
    /**
     * Доска, сложность, срочность и добавление подзадач (D-036, п. 3): по умолчанию
     * спрятаны. Данные не трогаются: уже созданные подзадачи видны всегда.
     */
    val advanced: Boolean = false,
) {
    companion object {
        fun encode(prefs: TaskPrefs): String = Json.write(
            linkedMapOf(
                "showClock" to prefs.showClock,
                "showAfterAlarm" to prefs.showAfterAlarm,
                "showWidget" to prefs.showWidget,
                "notifiedOn" to prefs.notifiedOn?.toString(),
                "advanced" to prefs.advanced,
            ),
        )

        /** Повреждённый файл — по умолчанию. Файл 0.18.0–0.20.0 с задачами Google — только выбор показа (D-034). */
        fun decode(text: String): TaskPrefs = runCatching {
            val root = Json.parse(text) as Map<*, *>
            TaskPrefs(
                showClock = root["showClock"] as? Boolean ?: true,
                showAfterAlarm = root["showAfterAlarm"] as? Boolean ?: true,
                showWidget = root["showWidget"] as? Boolean ?: true,
                notifiedOn = (root["notifiedOn"] as? String)?.let { LocalDate.parse(it) },
                advanced = root["advanced"] as? Boolean ?: false,
            )
        }.getOrDefault(TaskPrefs())
    }
}
