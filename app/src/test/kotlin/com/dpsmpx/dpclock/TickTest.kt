package com.dpsmpx.dpclock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TickTest {
    @Test
    fun waitsUntilTheNextSecondBoundary() {
        assertEquals(1000L, millisToNextSecond(1_700_000_000_000L))
        assertEquals(999L, millisToNextSecond(1_700_000_000_001L))
        assertEquals(1L, millisToNextSecond(1_700_000_000_999L))
    }

    @Test
    fun neverWaitsZeroOrMoreThanASecond() {
        for (ms in -2_000L..2_000L) {
            val wait = millisToNextSecond(ms)
            assertTrue("ожидание $wait мс для $ms", wait in 1L..1000L)
            assertEquals(0L, Math.floorMod(ms + wait, 1000L))
        }
    }
}
