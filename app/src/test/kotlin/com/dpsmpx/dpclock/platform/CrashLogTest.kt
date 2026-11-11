package com.dpsmpx.dpclock.platform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun entriesKeepOrderAndOnlyTheLastFew() {
        val log = CrashLog(File(folder.root, "sub/crashes.log"), maxEntries = 3)
        assertEquals(emptyList<String>(), log.entries())
        for (i in 1..5) log.append("сбой $i", IllegalStateException("причина $i", RuntimeException("глубже")))

        val entries = log.entries()
        assertEquals(listOf("сбой 3", "сбой 4", "сбой 5"), entries.map { it.lineSequence().first() })
        assertTrue(entries.last().contains("IllegalStateException: причина 5"))
        assertTrue(entries.last().contains("Caused by: java.lang.RuntimeException: глубже"))
        assertTrue(entries.last().contains("CrashLogTest"))

        log.clear()
        assertEquals(emptyList<String>(), log.entries())
    }

    @Test
    fun brokenFileAndUnwritablePlaceDoNotThrow() {
        val file = folder.newFile("crashes.log")
        file.writeText("что-то без разделителей")
        assertEquals(listOf("что-то без разделителей"), CrashLog(file).entries())
        // Вместо файла — папка: записать нельзя, но сбой при записи сбоя недопустим.
        val log = CrashLog(folder.newFolder("dir"))
        log.append("сбой", Exception())
        assertEquals(emptyList<String>(), log.entries())
    }
}
