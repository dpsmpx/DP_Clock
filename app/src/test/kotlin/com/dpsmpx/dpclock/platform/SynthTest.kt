package com.dpsmpx.dpclock.platform

import com.dpsmpx.dpclock.ui.builtinName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class SynthTest {
    @Test
    fun idsAreUniqueAndEachHasItsOwnName() {
        val ids = Synth.catalog.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(ids.size, ids.map(::builtinName).toSet().size)
        assertEquals(Synth.catalog.first(), Synth.find("нет такого"))
    }

    @Test
    fun everySoundIsOneAudibleLoopWithoutClipping() {
        for (sound in Synth.catalog) {
            val pcm = Synth.render(sound)
            assertEquals(sound.id, (sound.loop * Synth.SAMPLE_RATE).roundToInt(), pcm.size)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("${sound.id}: пик $peak", peak in 20_000..Short.MAX_VALUE - 1)
            // Круг повторяется без щелчка: скачок на стыке не больше обычного шага волны.
            val seam = abs(pcm.first() - pcm.last())
            val typicalStep = (1 until pcm.size).maxOf { abs(pcm[it] - pcm[it - 1]) }
            assertTrue("${sound.id}: стык $seam при шаге $typicalStep", seam <= typicalStep)
        }
    }
}
