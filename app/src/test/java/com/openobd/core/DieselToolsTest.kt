package com.openobd.core

import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ModuleClient
import com.openobd.core.diesel.RegenMonitor
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.service.Procedures
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DieselToolsTest {
    private val cruise = mapOf("0105" to 88.0, "010D" to 100.0, "010C" to 2300.0)

    @Test
    fun regenConditionsAccumulateWithShortDipsTolerated() {
        val m = RegenMonitor(targetSeconds = 600.0, toleranceSeconds = 30.0)
        var t = 0L
        var s = m.onCycle(cruise, t)
        repeat(300) { t += 1000; s = m.onCycle(cruise, t) }
        assertEquals(300.0, s.secondsInConditions, 0.5)
        assertTrue(s.allConditionsMet)
        // A 20 s slowdown (roundabout) keeps the progress, a long stop resets it.
        repeat(20) { t += 1000; s = m.onCycle(cruise + ("010D" to 30.0), t) }
        assertEquals(300.0, s.secondsInConditions, 0.5)
        repeat(60) { t += 1000; s = m.onCycle(cruise + ("010D" to 0.0), t) }
        assertEquals(0.0, s.secondsInConditions, 0.5)
        assertNull("no temperature reported", s.regenActive)
    }

    @Test
    fun regenDetectedFromFilterTemperature() {
        val m = RegenMonitor()
        var t = 0L
        val temps = listOf(300.0, 420.0, 520.0, 600.0, 610.0, 500.0, 390.0, 350.0, 460.0)
        val active = temps.map { temp -> t += 1000; m.onCycle(cruise + ("017CI" to temp), t).regenActive }
        assertEquals(listOf(false, false, true, true, true, true, true, false, true), active)
        assertEquals(2, m.onCycle(cruise + ("017CI" to 470.0), t + 1000).regensSeen)
    }

    @Test
    fun proceduresForTheCar() {
        val astra = Procedures.forCar("gm-europe", diesel = true).map { it.id }
        assertTrue(astra.containsAll(listOf("dpf-drive", "opel-oil-life", "after-battery", "drive-cycle")))
        val petrolFord = Procedures.forCar("ford", diesel = false).map { it.id }
        assertFalse("dpf-drive" in petrolFord || "opel-oil-life" in petrolFord)
    }

    @Test
    fun identifierExplorerFindsAndWatchesValues() = runTest {
        val t = DemoTransport(latencyMs = 0)
        t.open()
        val elm = Elm327(t)
        elm.initialize(ElmConfig())
        val client = ObdClient(elm).also { it.discover() }
        val mc = ModuleClient(elm).apply { gmlan = true }
        val ecm = EcuModule("ECM", "ECM", 0x7E0, 0x7E8)
        val ident = mc.scanIds(ecm, 0xF180..0xF1FF)
        assertEquals(listOf(0xF187, 0xF18C, 0xF190, 0xF195, 0xF197), ident.keys.toList())
        assertEquals("1HGCM82633A004352", ident.getValue(0xF190).map { it.toChar() }.joinToString(""))
        assertEquals(setOf(0x0D10), mc.scanIds(ecm, 0x0D00..0x0D1F).keys)
        assertEquals(2, mc.readIds(ecm, listOf(0x0D10)).getValue(0x0D10).size)
        val ebcm = EcuModule("EBCM", "EBCM", 0x243, 0x643)
        assertEquals(setOf(0x90, 0xC1), mc.scanIds(ebcm, 0x80..0xCF, gmlanIds = true).keys)
        // OBD works normally afterwards.
        assertEquals(2, client.readPids(listOf(0x0C, 0x0D)).size)
    }
}
