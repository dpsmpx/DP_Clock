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

    private fun scene(seed: Int, size: Int = 160): ByteArray {
        val random = Random(seed)
        // Крупные пятна: так выглядит реальная сцена в низком разрешении.
        val blobs = (0 until 12).map { Triple(random.nextInt(size), random.nextInt(size), random.nextInt(30, 220)) }
        return ByteArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val (bx, by, v) = blobs.minBy { (bx, by, _) -> (bx - x) * (bx - x) + (by - y) * (by - y) }
            (v + (bx - by) % 7).coerceIn(0, 255).toByte()
        }
    }

    @Test
    fun photoSignatureRecognisesTheSamePlaceAndRejectsAnother() {
        val place = PhotoSignature.of(scene(1), 160, 160)
        assertTrue(PhotoSignature.detail(place) >= PhotoSignature.MIN_DETAIL)
        // То же место, темнее и со сдвигом кадра на 6 точек.
        val darker = scene(1).map { ((it.toInt() and 0xFF) * 0.7).toInt().toByte() }.toByteArray()
        val shifted = ByteArray(160 * 160) { i ->
            val x = (i % 160 + 6).coerceAtMost(159)
            darker[(i / 160) * 160 + x]
        }
        val again = PhotoSignature.of(shifted, 160, 160)
        val other = PhotoSignature.of(scene(2), 160, 160)
        assertTrue(PhotoSignature.similarity(place, again) >= PhotoSignature.threshold(WakeLevel.HARD))
        assertTrue(PhotoSignature.similarity(place, other) < PhotoSignature.threshold(WakeLevel.EASY))
        assertEquals(place.toList(), PhotoSignature.decode(PhotoSignature.encode(place))!!.toList())
    }

    @Test
    fun flatFrameMatchesNothing() {
        val flat = PhotoSignature.of(ByteArray(160 * 160) { 40 }, 160, 160)
        assertTrue(PhotoSignature.detail(flat) < PhotoSignature.MIN_DETAIL)
        assertEquals(-1.0, PhotoSignature.similarity(flat, flat), 0.0)
    }

    @Test
    fun cameraMethodsNeedAReference() {
        assertFalse(WakeCheck(WakeMethod.QR).isComplete)
        assertTrue(WakeCheck(WakeMethod.QR, reference = "кухня").isComplete)
        assertTrue(WakeCheck(WakeMethod.MATH).isComplete)
        assertFalse(WakeCheck().required)
    }

    @Test
    fun templateKeepsWakeCheck() {
        val check = WakeCheck(WakeMethod.QR, WakeLevel.HARD, tasks = 3, reference = "a&b=c\nd", snoozeAllowed = false, recheckMinutes = 5)
        val alarm = Alarm("", 6, 0, Repeat.Once, enabled = true, createdAt = 0, wakeCheck = check)
        val template = AlarmTemplate("t", "т", listOf(alarm), 0)
        assertEquals(check, AlarmTemplate.decodeAlarms(template.encodeAlarms()).single().wakeCheck)
    }
}
