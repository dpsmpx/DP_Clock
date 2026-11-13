package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AutoBackupTest {
    private val hour = 3_600_000L
    private val day = 24 * hour

    @Test
    fun dueByPeriod() {
        val last = 100 * day
        assertFalse(AutoBackupRules.isDue(AutoBackupPeriod.OFF, 0L, last))
        // Копии ещё не было — пора сразу.
        assertTrue(AutoBackupRules.isDue(AutoBackupPeriod.WEEKLY, 0L, last))
        assertFalse(AutoBackupRules.isDue(AutoBackupPeriod.DAILY, last, last + 20 * hour))
        // Задание пришло на полчаса раньше суток — уже пора.
        assertTrue(AutoBackupRules.isDue(AutoBackupPeriod.DAILY, last, last + 23 * hour + hour / 2))
        assertFalse(AutoBackupRules.isDue(AutoBackupPeriod.WEEKLY, last, last + 6 * day - 2 * hour))
        assertTrue(AutoBackupRules.isDue(AutoBackupPeriod.WEEKLY, last, last + 7 * day))
        // Часы перевели назад — не ждать год.
        assertTrue(AutoBackupRules.isDue(AutoBackupPeriod.DAILY, last, last - 5 * day))
    }

    @Test
    fun oldCopiesAreRemovedAndForeignFilesStay() {
        val ours = (1..10).map { AutoBackupRules.fileName(LocalDate.of(2026, 11, it)) }
        assertEquals("dp-clock-auto-2026-11-01.json", ours.first())
        val names = ours.shuffled() + listOf("dp-clock-2026-11-01.json", "photo.jpg", "dp-clock-auto-notes.txt")
        assertEquals(ours.take(3).reversed(), AutoBackupRules.stale(names))
        assertEquals(emptyList<String>(), AutoBackupRules.stale(ours.take(7)))
        assertTrue(AutoBackupRules.isOurs(ours.last()))
        assertFalse(AutoBackupRules.isOurs("dp-clock-2026-11-01.json"))
    }
}
