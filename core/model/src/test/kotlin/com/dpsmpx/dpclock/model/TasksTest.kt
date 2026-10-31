package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Задачи (D-032, D-033): свои задачи, повтор, напоминание, постоянные задачи и отметки. */
class TasksTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 31)

    @Test
    fun repeatsFindTheNextDate() {
        val saturday = today
        assertEquals(LocalDate.of(2026, 11, 1), TaskRules.next(TaskRepeat.Daily, saturday, saturday))
        // По будням: после субботы — понедельник.
        val weekdays = TaskRepeat.Weekly(DayOfWeek.entries.take(5).toSet())
        assertEquals(LocalDate.of(2026, 11, 2), TaskRules.next(weekdays, saturday, saturday))
        // Пустой набор дней — по дню недели срока.
        assertEquals(LocalDate.of(2026, 11, 7), TaskRules.next(TaskRepeat.Weekly(emptySet()), saturday, saturday))
        // 31-е: в ноябре — 30-е, потом снова 31-е.
        val monthly = TaskRepeat.Monthly(31)
        assertEquals(LocalDate.of(2026, 11, 30), TaskRules.next(monthly, saturday, saturday))
        assertEquals(LocalDate.of(2026, 12, 31), TaskRules.next(monthly, saturday, LocalDate.of(2026, 11, 30)))
        // 29 февраля — в невисокосный год 28-го.
        val leap = TaskRepeat.Yearly(2, 29)
        assertEquals(LocalDate.of(2027, 2, 28), TaskRules.next(leap, LocalDate.of(2024, 2, 29), LocalDate.of(2026, 3, 1)))
        assertEquals(LocalDate.of(2028, 2, 29), TaskRules.next(leap, LocalDate.of(2024, 2, 29), LocalDate.of(2027, 2, 28)))
        assertNull(TaskRules.next(TaskRepeat.None, saturday, saturday))
        // Код для базы и файла.
        for (repeat in listOf(TaskRepeat.None, TaskRepeat.Daily, weekdays, monthly, leap)) assertEquals(repeat, TaskRepeat.decode(repeat.code))
        assertEquals(TaskRepeat.None, TaskRepeat.decode("каждый вторник"))
        assertEquals(TaskRepeat.Monthly(15), TaskRepeat.of(TaskRepeat.Monthly(31), LocalDate.of(2026, 1, 15)))
    }

    @Test
    fun doneMovesARepeatingTaskAndClosesOthers() {
        val once = Task("1", "Хлеб", date = today, createdAt = 1L)
        val closed = TaskRules.complete(once, today, now = 500L)
        assertTrue(closed.done)
        assertEquals(500L, closed.doneAt)
        assertNull(TaskRules.day(closed))
        // Ежедневная, просроченная на три дня: следующий срок — завтра, не «позавчера».
        val daily = Task("2", "Зарядка", date = today.minusDays(3), repeat = TaskRepeat.Daily, eventId = 7L, inCalendar = true)
        val moved = TaskRules.complete(daily, today, now = 500L)
        assertFalse(moved.done)
        assertEquals(today.plusDays(1), moved.date)
        assertNull("событие следующего срока — новое", moved.eventId)
        assertTrue(moved.inCalendar)
        // Без срока повтор не двигает — задача закрывается.
        assertTrue(TaskRules.complete(Task("3", "Когда-нибудь", repeat = TaskRepeat.Daily), today, 1L).done)
    }

    @Test
    fun remindersAndDueMoments() {
        val now = today.atTime(8, 0).atZone(moscow).toInstant()
        val timed = Task("1", "Созвон", date = today, time = LocalTime.of(11, 0), reminder = 15)
        assertEquals(today.atTime(10, 45).atZone(moscow).toInstant(), TaskRules.reminderAt(timed, moscow, now))
        // Без времени — срок в 9 утра.
        val allDay = Task("2", "Отчёт", date = today, reminder = 0)
        assertEquals(today.atTime(9, 0).atZone(moscow).toInstant(), TaskRules.reminderAt(allDay, moscow, now))
        // Прошедшее, сделанное и без срока — не напоминают.
        assertNull(TaskRules.reminderAt(allDay.copy(reminder = 24 * 60), moscow, now))
        assertNull(TaskRules.reminderAt(timed.copy(done = true), moscow, now))
        assertNull(TaskRules.reminderAt(Task("3", "Без срока", reminder = 0), moscow, Instant.EPOCH))
        assertNull(TaskRules.reminderAt(timed.copy(reminder = null), moscow, now))
    }

    @Test
    fun dayListOverdueFirst() {
        val overdue = DayTask("1", "Старое", today.minusDays(2))
        val timed = DayTask("2", "В 9", today, LocalTime.of(9, 0))
        val plain = DayTask("4", "Просто", today)
        val tomorrow = DayTask("5", "Завтра", today.plusDays(1))
        val day = TaskRules.forDay(listOf(plain, tomorrow, timed, overdue, plain), today)
        assertEquals(listOf("1", "2", "4"), day.map { it.id })
        assertTrue(TaskRules.overdue(overdue, today))
        assertTrue(TaskRules.isMorning(LocalTime.of(7, 0)))
        assertFalse(TaskRules.isMorning(LocalTime.of(13, 0)))
        assertEquals(DayTask("9", "Сегодня", today, LocalTime.NOON), TaskRules.day(Task("9", "Сегодня", date = today, time = LocalTime.NOON)))
        assertNull(TaskRules.day(Task("10", "Без срока")))
    }

    @Test
    fun tasksTravelInTheFileWithoutTheCalendar() {
        val task = Task(
            "t1",
            "Подарок \"маме\"",
            note = "Цветы\nи открытка",
            date = today,
            time = LocalTime.of(18, 30),
            reminder = 60,
            repeat = TaskRepeat.Yearly(10, 31),
            createdAt = 5L,
            inCalendar = true,
            eventId = 42L,
        )
        val done = Task("t2", "Сделано", done = true, doneAt = 9L, createdAt = 6L)
        val back = BackupFormat.decode(BackupFormat.encode(Backup(tasks = listOf(task, done)), Instant.EPOCH, "0.19.0")).tasks
        assertEquals(listOf(task.copy(inCalendar = false, eventId = null), done), back)
        // Синхронизация — без флажка и события: они свои у телефона.
        val data = SyncItems.snapshot(Backup(tasks = listOf(task))).getValue(SyncItems.TASK + "t1")
        assertFalse("inCalendar" in data || "eventId" in data)
        assertEquals(task.copy(inCalendar = false, eventId = null), SyncItems.task(data))
        // Файл без задач — тот же формат 1, что у прежних версий.
        assertFalse("\"tasks\"" in BackupFormat.encode(Backup(), Instant.EPOCH, "0.19.0"))
        assertEquals(1, BackupRules.forImport(Backup(tasks = listOf(task))) { "new" }.tasks.count { it.id == "new" && !it.inCalendar })
    }

    @Test
    fun prefsRoundTripAndTheOldFile() {
        val prefs = TaskPrefs(showWidget = false, notifiedOn = today)
        assertEquals(prefs, TaskPrefs.decode(TaskPrefs.encode(prefs)))
        assertEquals(TaskPrefs(), TaskPrefs.decode("не JSON"))
        // 0.18.0–0.20.0: задачи Google убраны (D-034) — остаётся только выбор показа.
        val old = """{"version": 2, "google": true, "fetchedAt": 5, "showClock": false, "tasks": [{"id": "x", "listId": "L", "title": "Т", "date": "2026-10-31"}]}"""
        assertEquals(TaskPrefs(showClock = false), TaskPrefs.decode(old))
    }

    // Постоянные задачи и история (D-033)

    private val day = 86_400_000L
    private val hour = 3_600_000L

    @Test
    fun historyTellsWhatHappened() {
        val marks = listOf(10 * day, 0L, 5 * day, 16 * day + 21 * hour)
        val stats = TaskHistory.stats(marks)
        assertEquals(4, stats.count)
        assertEquals(16 * day + 21 * hour, stats.last)
        assertEquals((16 * day + 21 * hour) / 3, stats.average)
        assertEquals(5 * day, stats.shortest)
        assertEquals(6 * day + 21 * hour, stats.longest)
        val last = 16 * day + 21 * hour
        assertFalse(TaskHistory.late(stats, last + 2 * day))
        assertTrue(TaskHistory.late(stats, last + 6 * day))
        // Одна отметка — обычного ещё нет.
        assertNull(TaskHistory.stats(listOf(5L)).average)
        assertFalse(TaskHistory.late(TaskHistory.stats(listOf(5L)), 10 * day))
        assertEquals(TaskStats(0, null, null, null, null), TaskHistory.stats(emptyList()))
    }

    @Test
    fun elapsedInTwoUnits() {
        assertEquals(listOf(ElapsedUnit.MINUTE to 0), TaskHistory.elapsed(30_000L))
        assertEquals(listOf(ElapsedUnit.MINUTE to 42), TaskHistory.elapsed(42 * 60_000L))
        assertEquals(listOf(ElapsedUnit.HOUR to 5), TaskHistory.elapsed(5 * hour + 59 * 60_000L))
        assertEquals(listOf(ElapsedUnit.DAY to 2, ElapsedUnit.HOUR to 3), TaskHistory.elapsed(2 * day + 3 * hour))
        assertEquals(listOf(ElapsedUnit.DAY to 2), TaskHistory.elapsed(2 * day))
        assertEquals(listOf(ElapsedUnit.MONTH to 3, ElapsedUnit.DAY to 5), TaskHistory.elapsed(95 * day))
        assertEquals(listOf(ElapsedUnit.YEAR to 1, ElapsedUnit.MONTH to 2), TaskHistory.elapsed(425 * day))
        assertEquals(listOf(ElapsedUnit.MINUTE to 0), TaskHistory.elapsed(-5L))
    }

    @Test
    fun quietNudgesOncePerMark() {
        val noon = today.atTime(12, 0).atZone(moscow).toInstant()
        val base = noon.toEpochMilli()
        val task = Task("p", "Полить цветы", permanent = true, remindLate = true, remindAfterDays = 7, createdAt = 0L)
        // Обычно раз в 2 дня — дольше обычного наступит раньше «через 7 дней».
        val marks = listOf(base - 4 * day, base - 2 * day, base)
        assertEquals(Instant.ofEpochMilli(base + 2 * day), TaskHistory.nudgeAt(task, marks, noon, moscow))
        // Уже напомнили для этой отметки — больше нет.
        assertNull(TaskHistory.nudgeAt(task.copy(nudged = base), marks, noon, moscow))
        // Только «через N дней», считая от создания, если отметок нет.
        val fresh = task.copy(remindLate = false, createdAt = base)
        assertEquals(Instant.ofEpochMilli(base + 7 * day), TaskHistory.nudgeAt(fresh, emptyList(), noon, moscow))
        // Срок прошёл, а напоминания не было, — через минуту; ночью — в 9 утра.
        val evening = today.atTime(23, 30).atZone(moscow).toInstant()
        assertEquals(today.plusDays(1).atTime(9, 0).atZone(moscow).toInstant(), TaskHistory.nudgeAt(fresh.copy(createdAt = 0L), emptyList(), evening, moscow))
        assertEquals(noon.plusSeconds(60), TaskHistory.nudgeAt(fresh.copy(createdAt = 0L), emptyList(), noon, moscow))
        // Не постоянная и без напоминаний — нет.
        assertNull(TaskHistory.nudgeAt(task.copy(permanent = false), marks, noon, moscow))
        assertNull(TaskHistory.nudgeAt(task.copy(remindLate = false, remindAfterDays = null), marks, noon, moscow))
        // Постоянная не закрывается и не бывает «задачей дня».
        assertEquals(task, TaskRules.complete(task, today, 5L))
        assertNull(TaskRules.day(task.copy(date = today)))
    }

    @Test
    fun treeSortAndSearch() {
        val home = Task("h", "Квартира", permanent = true)
        val flowers = Task("f", "Полить цветы", parentId = "h", permanent = true, urgency = TaskLevel.HIGH)
        val filter = Task("w", "Фильтр воды", parentId = "h", permanent = true)
        val cartridge = Task("c", "Картридж", parentId = "w", permanent = true)
        val backup = Task("b", "Резервная копия", note = "Ноутбук, ёмкость", permanent = true, difficulty = TaskLevel.HIGH)
        val orphan = Task("o", "Сирота", parentId = "нет")
        val all = listOf(backup, cartridge, filter, flowers, home, orphan)
        val byName = TaskTree.order(TaskSort.NAME, emptyMap())
        assertEquals(listOf("h", "f", "w", "c", "b", "o"), TaskTree.flatten(all, emptySet(), byName).map { it.first.id })
        assertEquals(listOf(0, 1, 1, 2, 0, 0), TaskTree.flatten(all, emptySet(), byName).map { it.second })
        assertEquals(listOf("h", "b", "o"), TaskTree.flatten(all, setOf("h"), byName).map { it.first.id })
        assertEquals(setOf("f", "w", "c"), TaskTree.descendants(all, "h"))
        assertTrue(TaskTree.hasChildren(all, "w"))
        // «Давно не делал»: ни разу — первыми, потом самые давние.
        val last = mapOf("f" to 100L, "b" to 50L)
        assertEquals(listOf("c", "h", "o", "w", "b", "f"), all.sortedWith(TaskTree.order(TaskSort.ELAPSED, last)).map { it.id })
        assertEquals("f", all.sortedWith(TaskTree.order(TaskSort.URGENCY, last)).first().id)
        assertEquals("b", all.sortedWith(TaskTree.order(TaskSort.DIFFICULTY, last)).first().id)
        assertTrue(TaskTree.matches(backup, "ЕМКОСТЬ"))
        assertFalse(TaskTree.matches(flowers, "фильтр"))
        assertTrue(TaskTree.matches(flowers, " "))
    }

    @Test
    fun marksAndTreeTravelInTheFile() {
        val parent = Task("p", "Квартира", permanent = true, remindLate = true, remindAfterDays = 30, difficulty = TaskLevel.MEDIUM, urgency = TaskLevel.LOW, nudged = 7L, createdAt = 1L)
        val child = Task("c", "Цветы", parentId = "p", permanent = true, createdAt = 2L)
        val marks = listOf(TaskMark("m1", "c", 100L), TaskMark("m2", "c", 200L))
        val backup = Backup(tasks = listOf(parent, child), marks = marks)
        val back = BackupFormat.decode(BackupFormat.encode(backup, Instant.EPOCH, "0.20.0"))
        assertEquals(listOf(parent.copy(nudged = null), child), back.tasks)
        assertEquals(marks, back.marks)
        // Загрузка: новые id, ссылки на родителя и отметки — на новые.
        var n = 0
        val imported = BackupRules.forImport(backup) { "n" + n++ }
        val newParent = imported.tasks.first { it.title == "Квартира" }
        val newChild = imported.tasks.first { it.title == "Цветы" }
        assertEquals(newParent.id, newChild.parentId)
        assertTrue(imported.marks.all { it.taskId == newChild.id } && imported.marks.none { it.id.startsWith("m") })
        // Синхронизация: каждая отметка — своей записью.
        val snapshot = SyncItems.snapshot(backup)
        assertEquals(TaskMark("m1", "c", 100L), SyncItems.mark(snapshot.getValue(SyncItems.MARK + "m1")))
        assertFalse("nudged" in snapshot.getValue(SyncItems.TASK + "p"))
    }
}
