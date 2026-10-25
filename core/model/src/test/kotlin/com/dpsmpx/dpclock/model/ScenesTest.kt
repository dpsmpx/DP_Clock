package com.dpsmpx.dpclock.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.sqrt

class ScenesTest {
    @Test
    fun constellationKeepsTheGameProportionsAndFadesLinks() {
        // Окно игры — масштаб 1: связи 100 px, курсор 200 px, скорость 240 px/с.
        assertEquals(1f, Constellation.scale(800f, 600f), 1e-6f)
        assertEquals(100f, Constellation.link(1f))
        assertEquals(200f, Constellation.cursorLink(1f))
        assertEquals(240f, Constellation.speed(1f))
        // Экран вчетверо больше по площади — вдвое длиннее связи.
        assertEquals(2f, Constellation.scale(1600f, 1200f), 1e-6f)
        // Как link_alpha игры: 120 у точки, 60 на полпути, на пределе и дальше — нет.
        assertEquals(120, Constellation.linkAlpha(0f, 100f))
        assertEquals(60, Constellation.linkAlpha(50f, 100f))
        assertNull(Constellation.linkAlpha(100f, 100f))
        assertNull(Constellation.linkAlpha(150f, 100f))
        assertEquals(Math.PI, Constellation.secondHandAngle(30.0), 1e-9)
    }

    @Test
    fun tesseractHasSixteenVerticesAndThirtyTwoEdgesOfDegreeFour() {
        assertEquals(16, Tesseract.vertices.size)
        assertEquals(32, Tesseract.edges.size)
        val degree = IntArray(16)
        for ((a, b) in Tesseract.edges) {
            degree[a]++
            degree[b]++
            // Ребро — вершины, различающиеся одной координатой.
            assertEquals(1, (0 until 4).count { Tesseract.vertices[a][it] != Tesseract.vertices[b][it] })
        }
        assertTrue(degree.all { it == 4 })
    }

    @Test
    fun rotationKeepsDistancesAndProjectionStaysInFrontOfTheEye() {
        val rotation = Tesseract.Rotation(xy = 0.3, xz = 1.1, yz = -0.4, xw = 2.0, yw = 0.7, zw = -1.3)
        for (v in Tesseract.vertices) {
            val r = Tesseract.rotate(v, rotation)
            assertEquals(2.0, sqrt(r.sumOf { it * it }), 1e-9)
            val (x, y, depth) = Tesseract.project(r)
            assertTrue(abs(x) < 2 && abs(y) < 2)
            assertTrue(depth in 0.0..1.0)
        }
        // Оборот за минуту в XW: через 15 с — четверть оборота.
        assertEquals(Math.PI / 2, Tesseract.timeRotation(15.0).xw, 1e-9)
        assertEquals(Math.PI / 2, Tesseract.timeRotation(900.0).zw, 1e-9)
    }

    @Test
    fun polarRingsFillWithTheirUnits() {
        val rings = PolarClock.rings(LocalDateTime.of(2026, 7, 16, 12, 30, 30))
        assertEquals(PolarUnit.entries, rings.map { it.unit })
        assertEquals(0.5, rings[0].fraction, 1e-9)
        assertEquals(30.5 / 60, rings[1].fraction, 1e-9)
        assertEquals((12 + 30.5 / 60) / 24, rings[2].fraction, 1e-9)
        assertEquals(16, rings[4].value)
        assertEquals(7, rings[5].value)
        assertTrue(rings.all { it.fraction in 0.0..1.0 })
        // Последняя секунда года — все кольца почти полные.
        val end = PolarClock.rings(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000))
        assertTrue(end.filter { it.unit != PolarUnit.WEEKDAY }.all { it.fraction > 0.99 })
    }

    @Test
    fun hourglassPoursOverAnHourAndFlipsAtItsStart() {
        assertEquals(0.5, Hourglass.fallen(LocalDateTime.of(2026, 1, 1, 9, 30)), 1e-9)
        assertEquals(Hourglass.NECK, Hourglass.profile(0.0), 1e-9)
        assertEquals(1.0, Hourglass.profile(1.0), 1e-9)
        // Пустая и полная колба; половина объёма — дальше середины: у горлышка колба узкая.
        assertEquals(0.0, Hourglass.levelFromNeck(0.0), 1e-6)
        assertEquals(1.0, Hourglass.levelFromNeck(1.0), 1e-6)
        assertTrue(Hourglass.levelFromNeck(0.5) > 0.5)
        assertEquals(1.0, Hourglass.levelFromEnd(0.0), 1e-6)
        assertEquals(0.0, Hourglass.levelFromEnd(1.0), 1e-6)
        // 30 % от горлышка и 70 % от края — один и тот же уровень.
        assertEquals(Hourglass.levelFromNeck(0.3), Hourglass.levelFromEnd(0.7), 1e-6)
        assertEquals(0.0, Hourglass.flip(LocalDateTime.of(2026, 1, 1, 9, 0))!!, 1e-9)
        assertNull(Hourglass.flip(LocalDateTime.of(2026, 1, 1, 9, 0, 2)))
    }

    @Test
    fun mechanismTicksOncePerSecondAndHandsFollowTheTime() {
        val at = Mechanism.angles(LocalDateTime.of(2026, 1, 1, 3, 15, 30))
        assertEquals(Math.PI, at.second, 1e-9)
        assertEquals((15.5 / 60) * 2 * Math.PI, at.minute, 1e-9)
        assertEquals(((3 + 15.5 / 60) / 12) * 2 * Math.PI, at.hour, 1e-9)
        // В середине секунды скачок уже прошёл.
        val later = Mechanism.angles(LocalDateTime.of(2026, 1, 1, 3, 15, 30, 500_000_000))
        assertEquals(31.0 / 60 * 2 * Math.PI, later.second, 1e-9)
        // Сцепленное колесо идёт в обратную сторону, медленнее во столько, во сколько больше зубцов.
        val step = Mechanism.meshed(0.1, 20, 40) - Mechanism.meshed(0.0, 20, 40)
        assertEquals(-0.05, step, 1e-9)
        // Зубец ведущего смотрит на ведомое — напротив промежуток ведомого: полшага зубца от направления.
        val direction = 0.7
        val driven = Mechanism.meshed(direction, 20, 40, direction)
        val pitch = 2 * Math.PI / 40
        val offset = ((driven - (direction + Math.PI)) % pitch + pitch) % pitch
        assertEquals(pitch / 2, offset, 1e-9)
    }
}
