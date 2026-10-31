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

/** Задачи (D-032): свои задачи, повтор, напоминание, Google Tasks и вход в браузере. */
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
        val overdue = DayTask(TaskSource.GOOGLE, "1", "L", "Старое", today.minusDays(2))
        val timed = DayTask(TaskSource.OWN, "2", "", "В 9", today, LocalTime.of(9, 0))
        val plain = DayTask(TaskSource.OWN, "4", "", "Просто", today)
        val tomorrow = DayTask(TaskSource.OWN, "5", "", "Завтра", today.plusDays(1))
        val day = TaskRules.forDay(listOf(plain, tomorrow, timed, overdue, plain), today)
        assertEquals(listOf("1", "2", "4"), day.map { it.id })
        assertTrue(TaskRules.overdue(overdue, today))
        assertTrue(TaskRules.isMorning(LocalTime.of(7, 0)))
        assertFalse(TaskRules.isMorning(LocalTime.of(13, 0)))
        assertEquals(DayTask(TaskSource.OWN, "9", "", "Сегодня", today, LocalTime.NOON), TaskRules.day(Task("9", "Сегодня", date = today, time = LocalTime.NOON)))
        assertNull(TaskRules.day(Task("10", "Без срока")))
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

    @Test(expected = TaskFormatException::class)
    fun foreignAnswerIsAnError() {
        GoogleTasksApi.parseLists("[1, 2]")
    }

    @Test
    fun browserSignIn() {
        // Образец из RFC 7636, приложение B.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        assertEquals(43, GoogleOAuth.verifier(ByteArray(32) { it.toByte() }).length)
        val url = GoogleOAuth.authUrl("123.apps.googleusercontent.com", "http://127.0.0.1:41234", "ch", "st")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?client_id=123.apps.googleusercontent.com&redirect_uri=http%3A%2F%2F127.0.0.1%3A41234&response_type=code"))
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Ftasks"))
        assertTrue(url.contains("code_challenge=ch&code_challenge_method=S256&state=st&access_type=offline&prompt=consent"))
        assertEquals(
            GoogleOAuth.Redirect("4/0Ab_c", "st", null),
            GoogleOAuth.parseRedirect("/?state=st&code=4%2F0Ab_c&scope=https://www.googleapis.com/auth/tasks"),
        )
        assertEquals(GoogleOAuth.Redirect(null, "st", "access_denied"), GoogleOAuth.parseRedirect("/?error=access_denied&state=st"))
        assertEquals(GoogleOAuth.Redirect(null, null, null), GoogleOAuth.parseRedirect("/favicon.ico"))
        assertEquals(
            GoogleOAuth.Tokens("ya29.a", 3599L, "1//r"),
            GoogleOAuth.parseTokens("""{"access_token": "ya29.a", "expires_in": 3599, "refresh_token": "1//r", "scope": "https://www.googleapis.com/auth/tasks", "token_type": "Bearer"}"""),
        )
        assertEquals("invalid_client: The OAuth client was not found.", GoogleOAuth.parseError("""{"error": "invalid_client", "error_description": "The OAuth client was not found."}"""))
        assertEquals("", GoogleOAuth.parseError("<html>"))
        assertTrue(GoogleOAuth.codeBody("c", "s", "4/x", "v", "http://127.0.0.1:1").contains("grant_type=authorization_code&redirect_uri=http%3A%2F%2F127.0.0.1%3A1"))
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
    fun cacheRoundTripAndTheOldFile() {
        val cache = TaskCache(
            google = true,
            tasks = listOf(DayTask(TaskSource.GOOGLE, "a", "L", "Хлеб \"бородинский\"", today, LocalTime.of(9, 30))),
            fetchedAt = 123L,
            failure = TaskCache.SIGN_IN,
            showWidget = false,
            notifiedOn = today,
        )
        assertEquals(cache, TaskCache.decode(TaskCache.encode(cache)))
        assertEquals(TaskCache(), TaskCache.decode("не JSON"))
        // 0.18.0: Todoist и TickTick убраны, вход Google другой — остаётся только выбор показа.
        val old = """{"connected": ["TODOIST", "GOOGLE"], "fetchedAt": 5, "showClock": false, "tasks": [{"service": "TODOIST", "id": "x"}]}"""
        assertEquals(TaskCache(showClock = false), TaskCache.decode(old))
    }
}
