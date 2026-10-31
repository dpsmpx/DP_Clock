package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** Синхронизация общим файлом (D-030): слияние телефонов, расхождения, удаления. */
class SyncTest {
    /** Общий файл на Диске — строкой, как его видит телефон. */
    private class Cloud {
        var text: String? = null
        var writes = 0
    }

    /** Телефон в тесте: свои данные, состояние и последние расхождения. */
    private class Phone(val name: String) {
        val local = mutableMapOf<String, Map<String, Any?>>()
        var state = SyncState()
        var conflicts = emptyList<SyncConflict>()

        /** Прочитать файл, слить и записать; [read] — заранее прочитанный файл (для гонки). */
        fun sync(cloud: Cloud, now: Long, choices: Map<String, SyncChoice> = emptyMap(), read: String? = cloud.text) {
            val remote = read?.let { SyncFormat.decode(it) }
            val result = SyncRules.run(local, state, remote, name, now, choices)
            for (change in result.changes) {
                when (change) {
                    is SyncChange.Put -> local[change.key] = change.data
                    is SyncChange.Remove -> local.remove(change.key)
                }
            }
            // Состояние — через JSON, как телефон его хранит.
            state = SyncFormat.decodeState(SyncFormat.encodeState(result.state))
            conflicts = result.conflicts
            if (result.upload) {
                cloud.text = SyncFormat.encode(result.file, "0.17.0")
                cloud.writes++
            }
        }
    }

    /** Будильник как запись синхронизации — числа Long, как после чтения файла. */
    @Suppress("UNCHECKED_CAST")
    private fun alarm(id: String, hour: Int, label: String = ""): Map<String, Any?> {
        val data = SyncItems.snapshot(Backup(alarms = listOf(Alarm(id, hour, 0, Repeat.Weekly(setOf(DayOfWeek.MONDAY)), enabled = true, createdAt = 1L, label = label))))
            .getValue(SyncItems.ALARM + id)
        return Json.parse(Json.write(data)) as Map<String, Any?>
    }

    @Test
    fun firstSyncIsAUnionAndThenEditsTravel() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        a.local["alarm:1"] = alarm("1", 7)
        b.local["alarm:2"] = alarm("2", 8)
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        a.sync(cloud, now = 300)
        assertEquals(setOf("alarm:1", "alarm:2"), a.local.keys)
        assertEquals(setOf("alarm:1", "alarm:2"), b.local.keys)
        // Правка на одном — без вопроса на другом.
        b.local["alarm:1"] = alarm("1", 6, "Рано")
        b.sync(cloud, now = 400)
        a.sync(cloud, now = 500)
        assertEquals(alarm("1", 6, "Рано"), a.local["alarm:1"])
        assertTrue(a.conflicts.isEmpty())
        // И дальше тишина: файл не переписывается без дела.
        val writes = cloud.writes
        b.sync(cloud, now = 600)
        a.sync(cloud, now = 700)
        assertEquals(a.local, b.local)
        assertEquals(writes, cloud.writes)
    }

    @Test
    fun bothEditedMeansAskAndTheAnswerSettlesBothPhones() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        a.local["alarm:1"] = alarm("1", 7)
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        a.local["alarm:1"] = alarm("1", 6)
        b.local["alarm:1"] = alarm("1", 9)
        a.sync(cloud, now = 400)
        b.sync(cloud, now = 500)
        val conflict = b.conflicts.single()
        assertEquals(alarm("1", 9), conflict.local)
        assertEquals(500L, conflict.localUpdatedAt)
        assertEquals(alarm("1", 6), conflict.remote)
        assertEquals("a", conflict.remoteDevice)
        // Пока не ответили — ничего не тронуто: у «b» своё, в файле — от «a».
        assertEquals(alarm("1", 9), b.local["alarm:1"])
        a.sync(cloud, now = 550)
        assertEquals(alarm("1", 6), a.local["alarm:1"])
        assertTrue(a.conflicts.isEmpty())
        // Ответ на одном телефоне — другой берёт его без вопроса.
        b.sync(cloud, now = 600, choices = mapOf("alarm:1" to SyncChoice.LOCAL))
        assertTrue(b.conflicts.isEmpty())
        a.sync(cloud, now = 700)
        assertTrue(a.conflicts.isEmpty())
        assertEquals(alarm("1", 9), a.local["alarm:1"])
        b.sync(cloud, now = 800)
        assertTrue(b.conflicts.isEmpty())
    }

    @Test
    fun choosingTheOtherPhoneTakesItsVersion() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        a.local["settings"] = mapOf("snoozeMinutes" to 10L)
        b.local["settings"] = mapOf("snoozeMinutes" to 5L)
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        assertEquals(listOf("settings"), b.conflicts.map { it.key })
        b.sync(cloud, now = 300, choices = mapOf("settings" to SyncChoice.REMOTE))
        assertEquals(mapOf("snoozeMinutes" to 10L), b.local["settings"])
        a.sync(cloud, now = 400)
        assertTrue(a.conflicts.isEmpty())
        assertEquals(mapOf("snoozeMinutes" to 10L), a.local["settings"])
    }

    @Test
    fun deletionTravelsAndEditVersusDeleteIsAsked() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        a.local["alarm:1"] = alarm("1", 7)
        a.local["alarm:2"] = alarm("2", 8)
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        a.local.remove("alarm:1")
        a.sync(cloud, now = 400)
        b.sync(cloud, now = 500)
        assertNull(b.local["alarm:1"])
        // Удалили на одном и поправили на другом — спросить.
        a.local.remove("alarm:2")
        b.local["alarm:2"] = alarm("2", 9)
        a.sync(cloud, now = 600)
        b.sync(cloud, now = 700)
        val conflict = b.conflicts.single()
        assertEquals("alarm:2", conflict.key)
        assertNull(conflict.remote)
        // «Удалить» — удаляется и здесь.
        b.sync(cloud, now = 800, choices = mapOf("alarm:2" to SyncChoice.REMOTE))
        assertNull(b.local["alarm:2"])
        assertTrue(b.conflicts.isEmpty())
    }

    @Test
    fun aLostWriteIsWrittenAgain() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        a.local["preset:p"] = mapOf("name" to "Чай")
        a.local["preset:q"] = mapOf("name" to "Кофе")
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        // «b» прочитал файл, тут «a» записал свою правку, а «b» — поверх, со старым «p».
        val before = cloud.text
        a.local["preset:p"] = mapOf("name" to "Чай 4")
        a.sync(cloud, now = 300)
        b.local["preset:q"] = mapOf("name" to "Кофе 2")
        b.sync(cloud, now = 310, read = before)
        // Следующий проход «a» вернёт свою правку без вопроса, и «b» её получит.
        a.sync(cloud, now = 400)
        assertTrue(a.conflicts.isEmpty())
        assertEquals(mapOf("name" to "Кофе 2"), a.local["preset:q"])
        b.sync(cloud, now = 500)
        assertTrue(b.conflicts.isEmpty())
        assertEquals(mapOf("name" to "Чай 4"), b.local["preset:p"])
    }

    @Test
    fun aRingingAlarmWaitsAndLocalChangesAreSeen() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        a.local["alarm:1"] = alarm("1", 7)
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        assertFalse(SyncRules.hasLocalChanges(b.local, b.state))
        a.local["alarm:1"] = alarm("1", 6)
        assertTrue(SyncRules.hasLocalChanges(a.local, a.state))
        a.sync(cloud, now = 300)
        // У «b» будильник звонит — правка подождёт.
        val result = SyncRules.run(b.local, b.state, SyncFormat.decode(cloud.text!!), "b", 400, hold = setOf("alarm:1"))
        assertTrue(result.changes.isEmpty())
        assertFalse(result.upload)
        b.sync(cloud, now = 500)
        assertEquals(alarm("1", 6), b.local["alarm:1"])
        b.local.remove("alarm:1")
        assertTrue(SyncRules.hasLocalChanges(b.local, b.state))
    }

    @Test
    fun threePhonesAndTombstonesExpire() {
        val cloud = Cloud()
        val a = Phone("a")
        val b = Phone("b")
        val c = Phone("c")
        a.local["preset:p"] = mapOf("name" to "Чай")
        a.sync(cloud, now = 100)
        b.sync(cloud, now = 200)
        c.sync(cloud, now = 300)
        b.local["preset:p"] = mapOf("name" to "Чай 4")
        b.sync(cloud, now = 400)
        c.sync(cloud, now = 450)
        c.local["preset:p"] = mapOf("name" to "Чай 5")
        c.sync(cloud, now = 500)
        a.sync(cloud, now = 600)
        b.sync(cloud, now = 650)
        assertEquals(mapOf("name" to "Чай 5"), a.local["preset:p"])
        assertEquals(mapOf("name" to "Чай 5"), b.local["preset:p"])
        assertTrue(a.conflicts.isEmpty() && b.conflicts.isEmpty())
        // Удалённое помнится 90 дней, потом забывается.
        a.local.remove("preset:p")
        a.sync(cloud, now = 700)
        assertTrue(SyncFormat.decode(cloud.text!!)!!.items.getValue("preset:p").deleted)
        a.sync(cloud, now = 700 + 91 * 86_400_000L)
        assertFalse("preset:p" in SyncFormat.decode(cloud.text!!)!!.items)
        assertFalse("preset:p" in a.state.published)
    }

    @Test
    fun enabledIsPerPhoneAndCalendarAlarmsStayHome() {
        val on = Alarm("1", 7, 0, Repeat.Once, enabled = true, createdAt = 1L, skipDate = LocalDate.of(2026, 11, 2))
        val off = on.copy(enabled = false, skipDate = null)
        val calendar = Alarm("2", 6, 0, Repeat.OnDate(LocalDate.of(2026, 11, 3)), enabled = true, createdAt = 2L, calendarEventAt = 5L)
        val one = SyncItems.snapshot(Backup(alarms = listOf(on, calendar)))
        val two = SyncItems.snapshot(Backup(alarms = listOf(off)))
        assertEquals(one.getValue("alarm:1"), two.getValue("alarm:1"))
        assertTrue("alarm:2" !in one)
        // Данные будильника читаются обратно — с id и временем.
        val decoded = SyncItems.alarm(one.getValue("alarm:1"))
        assertEquals("1", decoded.id)
        assertEquals(7, decoded.hour)
    }

    @Test
    fun settingsReadBackTheSameAndKeepTheOwnPicture() {
        val image = Settings(snoozeMinutes = 7, appearance = Appearance(background = ClockBackground.IMAGE))
        val data = SyncItems.snapshot(Backup(settings = image)).getValue(SyncItems.SETTINGS)
        val back = SyncItems.settings(data)
        assertEquals(ClockBackground.IMAGE, back.appearance.background)
        assertEquals(7, back.snoozeMinutes)
        // Прочитанное и снятое снова — тот же хеш: телефоны не перекидываются одним и тем же.
        assertEquals(SyncRules.hash(data), SyncRules.hash(SyncItems.snapshot(Backup(settings = back)).getValue(SyncItems.SETTINGS)))
    }

    @Test
    fun fileFormat() {
        val cloud = Cloud()
        val a = Phone("Redmi Note 13")
        a.local["alarm:1"] = alarm("1", 7, "Подъём \"рано\"")
        a.local["worldCities"] = mapOf("cities" to listOf("Asia/Tokyo"))
        a.sync(cloud, now = 100)
        a.local.remove("alarm:1")
        a.sync(cloud, now = 200)
        val file = SyncFormat.decode(cloud.text!!)!!
        assertEquals("Redmi Note 13", file.writtenBy)
        assertEquals(file, SyncFormat.decode(SyncFormat.encode(file, "0.17.0")))
        assertEquals("Redmi Note 13", file.items.getValue("worldCities").from)
        // Только что созданный файл пуст — это не ошибка; чужой и повреждённый — ошибка.
        assertEquals(SyncFile(), SyncFormat.decode(""))
        assertNull(SyncFormat.decode("""{"app": "Другие", "sync": 1}"""))
        assertNull(SyncFormat.decode("не JSON"))
        assertEquals("dp-clock-sync.json", SyncFormat.FILE_NAME)
    }
}
