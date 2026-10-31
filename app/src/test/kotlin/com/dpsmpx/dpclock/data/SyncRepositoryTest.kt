package com.dpsmpx.dpclock.data

import com.dpsmpx.dpclock.DpClockApp
import com.dpsmpx.dpclock.model.AlarmGroup
import com.dpsmpx.dpclock.model.Backup
import com.dpsmpx.dpclock.model.Json
import com.dpsmpx.dpclock.model.SyncFile
import com.dpsmpx.dpclock.model.SyncItem
import com.dpsmpx.dpclock.model.SyncItems
import com.dpsmpx.dpclock.model.SyncResult
import com.dpsmpx.dpclock.model.SyncRules
import com.dpsmpx.dpclock.model.SyncState
import com.dpsmpx.dpclock.model.Task
import com.dpsmpx.dpclock.model.TaskRepeat
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Синхронизация в базе (D-030): пришедшее с другого телефона применяется, включение
 * остаётся своим, и снимок после применения совпадает с пришедшим — телефоны не
 * перекидываются одним и тем же. Запуск — с `-Pscreenshots`, как у снимков: нужен
 * образ Android.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SyncRepositoryTest {
    private val repository get() = DpClockApp.graph(RuntimeEnvironment.getApplication()).repository
    private var state = SyncState()

    @Before
    fun setUp() {
        Assume.assumeTrue(System.getProperty("dpclock.robolectric") == "true")
        DpClockApp.resetGraph()
    }

    private suspend fun pass(remote: SyncFile?, now: Long): SyncResult {
        val result = repository.sync(replace = false) { local, hold -> SyncRules.run(local, state, remote, "Этот", now, hold = hold) }
        state = result.state
        return result
    }

    /** Версия с другого телефона, сделанная из [base]. */
    @Suppress("UNCHECKED_CAST")
    private fun edit(base: SyncItem?, key: String, data: Map<String, Any?>?, now: Long): SyncItem {
        val json = data?.let { Json.parse(Json.write(it)) as Map<String, Any?> }
        return SyncItem(key, json, json?.let { SyncRules.hash(it) } ?: SyncRules.DELETED, base?.hash, now, from = "Другой")
    }

    /** Свои задачи (D-032): «сделано» двигает повтор; правка с другого телефона не трогает флажок «В календарь». */
    @Test
    fun tasksRepeatAndKeepTheCalendarFlag() = runBlocking {
        val today = LocalDate.now()
        repository.saveTask(Task("t", "  Зарядка  ", date = today, repeat = TaskRepeat.Daily, reminder = 0, inCalendar = true))
        val saved = repository.task("t")!!
        assertEquals("Зарядка", saved.title)
        assertTrue(saved.createdAt > 0)
        repository.completeTask("t")
        val moved = repository.task("t")!!
        assertFalse(moved.done)
        assertEquals(today.plusDays(1), moved.date)

        val first = pass(remote = SyncFile(), now = 1_000L)
        val key = SyncItems.TASK + "t"
        val renamed = SyncItems.snapshot(Backup(tasks = listOf(moved.copy(title = "Зарядка 15 минут")))).getValue(key)
        pass(SyncFile("Другой", 2_000L, first.file.items + (key to edit(first.file.items[key], key, renamed, 2_000L))), now = 3_000L)
        val after = repository.task("t")!!
        assertEquals("Зарядка 15 минут", after.title)
        assertTrue("флажок — свой у телефона", after.inCalendar)
        assertFalse(SyncRules.hasLocalChanges(SyncItems.snapshot(repository.exportBackup()), state))
        repository.deleteTask("t")
        assertNull(repository.task("t"))
    }

    @Test
    fun incomingChangesApplyAndSettle() = runBlocking {
        repository.updateSettings { it.copy(onboardingDone = true, snoozeMinutes = 10) }
        repository.addAlarm(7, 0)
        val alarm = repository.surfaceData().alarms.single()
        assertTrue(alarm.enabled)
        val first = pass(remote = SyncFile(), now = 1_000L)
        assertTrue(first.upload)
        val file = first.file

        // Другой телефон: будильник на 6:30 и выключен у себя, новый будильник с группой, интервал «Отложить» 15.
        val alarmKey = SyncItems.ALARM + alarm.id
        val group = AlarmGroup("g", "Работа", 5L)
        val moved = SyncItems.snapshot(Backup(alarms = listOf(alarm.copy(hour = 6, minute = 30, enabled = false)))).getValue(alarmKey)
        val added = alarm.copy(id = "x", hour = 8, groupId = "g", createdAt = 6L, label = "Новый")
        val settings = repository.currentSettings().copy(snoozeMinutes = 15, onboardingDone = false)
        val items = file.items.toMutableMap()
        items[alarmKey] = edit(file.items[alarmKey], alarmKey, moved, 2_000L)
        items["alarm:x"] = edit(null, "alarm:x", SyncItems.snapshot(Backup(alarms = listOf(added))).getValue("alarm:x"), 2_000L)
        items["group:g"] = edit(null, "group:g", SyncItems.snapshot(Backup(groups = listOf(group))).getValue("group:g"), 2_000L)
        items[SyncItems.SETTINGS] = edit(file.items[SyncItems.SETTINGS], SyncItems.SETTINGS, SyncItems.snapshot(Backup(settings = settings)).getValue(SyncItems.SETTINGS), 2_000L)
        val second = pass(SyncFile("Другой", 2_000L, items), now = 3_000L)
        assertTrue(second.conflicts.isEmpty())
        assertFalse(second.upload)

        val data = repository.surfaceData()
        val mine = data.alarms.first { it.id == alarm.id }
        assertEquals(6, mine.hour)
        assertEquals(30, mine.minute)
        assertTrue("включение — своё у телефона", mine.enabled)
        val incoming = data.alarms.first { it.id == "x" }
        assertFalse("новый будильник приходит выключенным", incoming.enabled)
        assertEquals("g", incoming.groupId)
        assertEquals(15, data.settings.snoozeMinutes)
        assertTrue("отметка первого запуска — своя у телефона", data.settings.onboardingDone)

        // Снимок после применения — тот же, что пришёл: правок «на отправку» нет.
        assertFalse(SyncRules.hasLocalChanges(SyncItems.snapshot(repository.exportBackup()), state))

        // Удаление с другого телефона.
        val third = SyncFile("Другой", 4_000L, items + ("alarm:x" to edit(items.getValue("alarm:x"), "alarm:x", null, 4_000L)))
        pass(third, now = 5_000L)
        assertNull(repository.surfaceData().alarms.firstOrNull { it.id == "x" })
        assertFalse(SyncRules.hasLocalChanges(SyncItems.snapshot(repository.exportBackup()), state))
    }
}
