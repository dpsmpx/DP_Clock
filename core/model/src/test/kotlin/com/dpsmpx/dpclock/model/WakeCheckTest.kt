package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class WakeCheckTest {
    @Test
    fun mathAnswersAreRightAndNotNegative() {
        val random = Random(42)
        for (level in WakeLevel.entries) repeat(500) {
            val task = WakeTasks.math(level, random)
            val parts = task.question.split(' ')
            val value = when (level) {
                WakeLevel.EASY -> parts[0].toInt() + parts[2].toInt()
                WakeLevel.MEDIUM -> parts[0].toInt() * parts[2].toInt() + parts[4].toInt()
                WakeLevel.HARD -> parts[0].toInt() * parts[2].toInt() - parts[4].toInt()
            }
            assertEquals(task.question, value.toString(), task.answer)
            assertTrue(task.question, value >= 0)
        }
    }

    @Test
    fun codeIsDigitsOfLevelLengthShownInGroups() {
        val random = Random(7)
        val lengths = mapOf(WakeLevel.EASY to 4, WakeLevel.MEDIUM to 6, WakeLevel.HARD to 8)
        for ((level, length) in lengths) {
            val task = WakeTasks.code(level, random)
            assertEquals(length, task.answer.length)
            assertTrue(task.answer.all { it.isDigit() })
            assertEquals(task.answer, task.question.replace(" ", ""))
        }
    }

    @Test
    fun shakesCountStrongJoltsWithAGap() {
        val counter = ShakeCounter()
        assertFalse(counter.onSample(0f, 0f, 9.8f, 0))
        assertTrue(counter.onSample(25f, 0f, 9.8f, 1_000))
        // Тот же толчок через 100 мс — не новый.
        assertFalse(counter.onSample(25f, 5f, 9.8f, 1_100))
        assertTrue(counter.onSample(0f, -22f, 9.8f, 1_300))
        assertEquals(2, counter.count)
    }

    @Test
    fun cameraMethodsNeedAReference() {
        assertFalse(WakeCheck(WakeMethod.QR).isComplete)
        assertTrue(WakeCheck(WakeMethod.QR, reference = "кухня").isComplete)
        assertTrue(WakeCheck(WakeMethod.MATH).isComplete)
        assertFalse(WakeCheck().required)
        // «Снимок места» прежних версий (убран в 0.24.0) читается примером без образца; незнакомый способ — без проверки.
        val photo = WakeCheck.stored("PHOTO", WakeLevel.HARD, 2, "00ff", snoozeAllowed = false, recheckMinutes = 5)
        assertEquals(WakeCheck(WakeMethod.MATH, WakeLevel.HARD, 2, "", snoozeAllowed = false, recheckMinutes = 5), photo)
        assertEquals(WakeCheck(WakeMethod.QR, reference = "кухня"), WakeCheck.stored("QR", WakeLevel.MEDIUM, 1, "кухня", true, 0))
        assertEquals(WakeMethod.NONE, WakeCheck.stored("FACE", WakeLevel.MEDIUM, 1, "", true, 0).method)
    }

    @Test
    fun templateKeepsWakeCheck() {
        val check = WakeCheck(WakeMethod.QR, WakeLevel.HARD, tasks = 3, reference = "a&b=c\nd", snoozeAllowed = false, recheckMinutes = 5)
        val alarm = Alarm("", 6, 0, Repeat.Once, enabled = true, createdAt = 0, wakeCheck = check)
        val template = AlarmTemplate("t", "т", listOf(alarm), 0)
        assertEquals(check, AlarmTemplate.decodeAlarms(template.encodeAlarms()).single().wakeCheck)
    }
}
