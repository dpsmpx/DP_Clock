package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Таск-менеджеры (D-031): разбор ответов Todoist, TickTick и Google Tasks, задачи дня. */
class TasksTest {
    private val moscow = ZoneId.of("Europe/Moscow")
    private val today = LocalDate.of(2026, 10, 31)

    @Test
    fun todoistDatesTimesAndPriority() {
        // Ответ /api/v1/tasks/filter: день, плавающее время, точное время в UTC.
        val page = TodoistApi.parse(
            """
            {"results": [
              {"id": "a1", "project_id": "p", "content": "Купить хлеб", "priority": 4, "checked": false, "is_deleted": false,
               "due": {"date": "2026-10-31", "is_recurring": false, "string": "today", "lang": "ru"}},
              {"id": "a2", "project_id": "p", "content": "Позвонить", "priority": 1, "checked": false, "is_deleted": false,
               "due": {"date": "2026-10-31T09:30:00", "is_recurring": true, "string": "every day 9:30", "lang": "ru"}},
              {"id": "a3", "project_id": "p", "content": "Созвон", "priority": 2, "checked": false, "is_deleted": false,
               "due": {"date": "2026-10-30T17:00:00Z", "timezone": "Europe/London", "is_recurring": false}},
              {"id": "a4", "project_id": "p", "content": "Без срока", "priority": 1, "checked": false, "is_deleted": false, "due": null},
              {"id": "a5", "project_id": "p", "content": "Сделано", "priority": 1, "checked": true, "is_deleted": false,
               "due": {"date": "2026-10-31"}}
            ], "next_cursor": "abc.def"}
            """,
            moscow,
        )
        assertEquals("abc.def", page.next)
        assertEquals(listOf("a1", "a2", "a3"), page.tasks.map { it.id })
        assertEquals(3, page.tasks[0].priority)
        assertNull(page.tasks[0].time)
        assertEquals(LocalTime.of(9, 30), page.tasks[1].time)
        // 17:00 UTC — 20:00 в Москве.
        assertEquals(LocalDate.of(2026, 10, 30), page.tasks[2].date)
        assertEquals(LocalTime.of(20, 0), page.tasks[2].time)
        assertTrue(TodoistApi.filterUrl(null).endsWith("query=today+%7C+overdue&limit=200"))
        assertTrue(TodoistApi.filterUrl("abc.def").endsWith("&cursor=abc.def"))
        assertEquals("https://api.todoist.com/api/v1/tasks/a1/close", TodoistApi.closeUrl("a1"))
    }

    @Test
    fun tickTickAllDayInItsZoneAndTimedInOurs() {
        val tasks = TickTickApi.parse(
            """
            [
              {"id": "t1", "projectId": "inbox1", "title": "Отчёт", "startDate": "2026-10-30T21:00:00.000+0000",
               "dueDate": "2026-10-30T21:00:00.000+0000", "timeZone": "Europe/Moscow", "isAllDay": true, "priority": 5, "status": 0},
              {"id": "t2", "projectId": "p2", "title": "Встреча", "startDate": "2026-10-31T07:00:00.000+0000",
               "dueDate": "2026-10-31T08:00:00.000+0000", "timeZone": "Europe/Moscow", "isAllDay": false, "priority": 1, "status": 0},
              {"id": "t3", "projectId": "p2", "title": "Отпуск", "startDate": "2026-10-28T21:00:00+0000",
               "dueDate": "2026-11-02T21:00:00+0000", "timeZone": "Europe/Moscow", "isAllDay": true, "priority": 0, "status": 0},
              {"id": "t4", "projectId": "p2", "title": "Готово", "dueDate": "2026-10-31T08:00:00.000+0000", "status": 2}
            ]
            """,
            moscow,
        )
        assertEquals(listOf("t1", "t2", "t3"), tasks.map { it.id })
        assertEquals(today, tasks[0].date)
        assertNull(tasks[0].time)
        assertEquals(3, tasks[0].priority)
        assertEquals(LocalTime.of(11, 0), tasks[1].time)
        assertEquals(LocalDate.of(2026, 10, 29), tasks[2].start)
        assertEquals(LocalDate.of(2026, 11, 3), tasks[2].date)
        assertEquals(mapOf("endDate" to "2026-10-31T23:59:59.999+0300", "status" to listOf(0L)), Json.parse(TickTickApi.filterBody(today, moscow)))
        assertEquals("https://api.ticktick.com/open/v1/project/inbox1/task/t1/complete", TickTickApi.completeUrl("inbox1", "t1"))
    }

    @Test
    fun googleTasksDayOnly() {
        val (lists, next) = GoogleTasksApi.parseLists("""{"kind": "tasks#taskLists", "items": [{"id": "L1", "title": "Мои задачи"}, {"id": "L2"}], "nextPageToken": "n"}""")
        assertEquals(listOf("L1", "L2"), lists)
        assertEquals("n", next)
        val page = GoogleTasksApi.parseTasks(
            """
            {"items": [
              {"id": "g1", "title": "Полить цветы", "status": "needsAction", "due": "2026-10-31T00:00:00.000Z"},
              {"id": "g2", "title": "Без срока", "status": "needsAction"},
              {"id": "g3", "title": "Сделано", "status": "completed", "due": "2026-10-31T00:00:00.000Z"}
            ]}
            """,
            "L1",
        )
        assertEquals(listOf("g1"), page.tasks.map { it.id })
        assertEquals(today, page.tasks.single().date)
        assertEquals("L1", page.tasks.single().listId)
        assertNull(page.next)
        assertTrue(GoogleTasksApi.tasksUrl("L1", today, null).contains("dueMax=2026-10-31T23%3A59%3A59Z"))
        assertEquals(mapOf("status" to "completed"), Json.parse(GoogleTasksApi.COMPLETE_BODY))
    }

    @Test
    fun dayListOverdueFirst() {
        val overdue = DayTask(TaskService.GOOGLE, "1", "L", "Старое", today.minusDays(2))
        val timed = DayTask(TaskService.TODOIST, "2", "p", "В 9", today, LocalTime.of(9, 0))
        val urgent = DayTask(TaskService.TODOIST, "3", "p", "Срочно", today, priority = 3)
        val plain = DayTask(TaskService.TICKTICK, "4", "p", "Просто", today)
        val tomorrow = DayTask(TaskService.TICKTICK, "5", "p", "Завтра", today.plusDays(1))
        val running = DayTask(TaskService.TICKTICK, "6", "p", "Идёт", today.plusDays(2), start = today.minusDays(1))
        val day = TaskRules.forDay(listOf(plain, tomorrow, urgent, timed, overdue, running, plain), today)
        assertEquals(listOf("1", "2", "3", "4", "6"), day.map { it.id })
        assertTrue(TaskRules.overdue(overdue, today))
        assertTrue(TaskRules.isMorning(LocalTime.of(7, 0)))
        assertTrue(!TaskRules.isMorning(LocalTime.of(13, 0)))
    }

    @Test(expected = TaskFormatException::class)
    fun foreignAnswerIsAnError() {
        TodoistApi.parse("""{"error": "Unauthorized"}""", moscow)
    }

    @Test
    fun cacheRoundTrip() {
        val cache = TaskCache(
            connected = setOf(TaskService.TODOIST, TaskService.GOOGLE),
            tasks = listOf(DayTask(TaskService.TODOIST, "a", "p", "Хлеб \"бородинский\"", today, LocalTime.of(9, 30), today.minusDays(1), 2)),
            fetchedAt = 123L,
            failures = mapOf(TaskService.GOOGLE to TaskCache.SIGN_IN),
            googleAccount = "me@example.com",
            showWidget = false,
            notifiedOn = today,
        )
        assertEquals(cache, TaskCache.decode(TaskCache.encode(cache)))
        assertEquals(TaskCache(), TaskCache.decode("не JSON"))
    }
}
