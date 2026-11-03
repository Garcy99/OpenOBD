package com.openobd.core

import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.UnitSystem
import com.openobd.core.voice.VoiceWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAlertsTest {
    private fun run(w: VoiceWatch, from: Long, to: Long, step: Long = 500, v: (Long) -> Map<String, Double>) =
        (from..to step step).flatMap { t -> w.onCycle(v(t), t) }

    @Test
    fun overheatingIsSpokenOnceAfterAMoment() {
        val w = VoiceWatch()
        // A single hot reading is not enough
        assertTrue(w.onCycle(mapOf("0105" to 115.0), 0).isEmpty())
        assertTrue(w.onCycle(mapOf("0105" to 90.0), 500).isEmpty())
        val said = run(w, 1_000, 60_000) { mapOf("0105" to 112.0) }
        assertEquals(1, said.size)
        assertEquals(listOf("112", "degrees"), said[0].args)
        // Repeated after five minutes if still hot
        assertEquals(1, run(w, 60_500, 400_000) { mapOf("0105" to 112.0) }.size)
    }

    @Test
    fun notChargingNeedsTheEngineRunning() {
        val w = VoiceWatch()
        assertTrue(run(w, 0, 120_000) { mapOf("0142" to 12.1, "010C" to 0.0) }.isEmpty()) // engine off: normal
        assertTrue(run(w, 0, 120_000) { mapOf("0142" to 13.0, "010C" to 800.0) }.isEmpty()) // smart alternator low setpoint
        val said = run(w, 200_000, 260_000) { mapOf("0142" to 12.0, "010C" to 800.0) }
        assertEquals(1, said.size)
        assertTrue(said[0].text.startsWith("The battery is not charging"))
    }

    @Test
    fun regenerationStartAndEnd() {
        val w = VoiceWatch()
        val base = mapOf("0105" to 90.0, "010D" to 90.0, "010C" to 2200.0)
        val said = run(w, 0, 10_000) { base + ("017C_I" to 300.0) } +
            run(w, 10_500, 20_000) { base + ("017C_I" to 520.0) } +
            run(w, 20_500, 30_000) { base + ("017C_I" to 350.0) }
        assertEquals(listOf("Particulate filter cleaning started. Keep driving if you can and do not switch off the engine.", "Particulate filter cleaning finished."), said.map { it.text })
        // No exhaust temperature reported: nothing is said
        assertTrue(run(VoiceWatch(), 0, 30_000) { base }.isEmpty())
    }

    @Test
    fun spokenUnits() {
        assertEquals("88" to "kilometres per hour", VoiceWatch.spoken(88.4, ObdUnit.KMH, UnitSystem.METRIC))
        assertEquals("55" to "miles per hour", VoiceWatch.spoken(88.4, ObdUnit.KMH, UnitSystem.IMPERIAL))
        val a = VoiceWatch().alert("Coolant temperature", 108.6, ObdUnit.CELSIUS, 0)
        assertEquals(listOf("Coolant temperature", "109", "degrees"), a.args)
    }
}
