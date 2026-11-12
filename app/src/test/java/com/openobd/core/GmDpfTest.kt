package com.openobd.core

import com.openobd.core.brand.Equation
import com.openobd.core.brand.Families
import com.openobd.core.brand.GM_DPF_PIDS
import com.openobd.core.diesel.RegenMonitor
import com.openobd.core.diesel.RegenWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GM / Opel diesels (Astra J 1.6 / 2.0 CDTI ...) keep the filter's state in the engine computer's own identifiers. */
class GmDpfTest {
    private fun pid(id: String) = GM_DPF_PIDS.first { it.id == id }
    private fun decode(id: String, vararg bytes: Int) = Equation(pid(id).equation).evaluate(bytes)!!

    @Test fun `the identifiers are valid and built in for Opel and GM`() {
        GM_DPF_PIDS.forEach { assertNull(it.id, it.validate()) }
        val opel = Families.all.first { it.id == "gm-europe" }
        val gm = Families.all.first { it.id == "gm" }
        assertTrue(opel.builtinPids.containsAll(GM_DPF_PIDS))
        assertTrue(gm.builtinPids.containsAll(GM_DPF_PIDS))
        assertTrue(GM_DPF_PIDS.all { it.header == "7E0" && it.service == 0x22 })
        assertEquals(RegenWatch.makerKeys.toSet(), GM_DPF_PIDS.map { it.key }.toSet())
    }

    @Test fun `the values decode as OBDb documents them`() {
        // Soot load: raw x 763 / 50000 (0x0CCD = 3277 -> 50 %).
        assertEquals(50.0, decode("gm_dpf_soot", 0x0C, 0xCD), 0.1)
        assertEquals(50.0, decode("gm_dpf_soot_b", 0x7E, 0xF4), 0.1) // 32500 / 650
        // Inlet temperature: raw x 0.019608 - 40 (0x7A12 = 31250 -> about 573 °C).
        assertEquals(572.75, decode("gm_dpf_inlet_t", 0x7A, 0x12), 0.1)
        // Distance since the last regeneration, tenths of a km over three bytes (0x000F3C = 3900 -> 390 km).
        assertEquals(390.0, decode("gm_dpf_km", 0x00, 0x0F, 0x3C), 1e-9)
        assertEquals(412.0, decode("gm_dpf_regens", 0x01, 0x9C), 1e-9)
    }

    @Test fun `the drive watch shows the load and tells a regeneration from the engine's own temperature`() {
        val w = RegenWatch()
        var t = 0L
        fun cycle(temp: Double, rpm: Double = 2200.0) = w.onCycle(
            mapOf(RegenWatch.GM_SOOT to 63.0, RegenWatch.GM_INLET_TEMP to temp, RegenWatch.GM_KM to 390.0, RegenWatch.GM_REGENS to 412.0, RegenWatch.RPM to rpm), t,
        ).also { t += 1000 }
        assertNull(cycle(280.0))
        assertEquals(63.0, w.state.loadPct!!, 1e-9)
        assertEquals(390.0, w.state.kmSinceRegen!!, 1e-9)
        assertEquals(412, w.state.regenCount)
        // Hot for a minute: a regeneration.
        val events = (0..70).mapNotNull { cycle(600.0) }
        assertTrue("$events", events.single() is RegenWatch.Event.Started)
        assertTrue(w.state.active)
        // The engine stops in the middle: cut short.
        assertTrue(cycle(600.0, rpm = 0.0) is RegenWatch.Event.Interrupted)
    }

    @Test fun `only the load is enough for the card, and nonsense is ignored`() {
        val w = RegenWatch()
        w.onCycle(mapOf(RegenWatch.GM_SOOT_B to 41.0), 0)
        assertEquals(41.0, w.state.loadPct!!, 1e-9)
        assertTrue(!w.state.active)
        // An engine that answers the identifier with something else: not shown as a load.
        val other = RegenWatch()
        other.onCycle(mapOf(RegenWatch.GM_SOOT to 900.0, RegenWatch.GM_INLET_TEMP to 5000.0), 0)
        assertNull(other.state.loadPct)
        assertNull(other.state.tempC)
    }

    @Test fun `the diesel gauges take the standard PIDs first and GM's own values otherwise`() {
        val gm = (GM_DPF_PIDS + com.openobd.core.brand.GM_DIESEL_PIDS).map { it.toSensor() }
        val v = com.openobd.core.obd.VirtualSensors
        // An Astra J that reports neither the boost control PID nor the filter PIDs.
        val astra = setOf(0x0B, 0x0C, 0x0D, 0x23, 0x2C)
        assertEquals("X_gm_boost_target", v.resolve("BOOST_TARGET", astra, gm))
        assertEquals("X_gm_boost_actual", v.resolve("BOOST_ACTUAL", astra, gm))
        assertEquals("X_gm_dpf_soot", v.resolve("DPF_SOOT", astra, gm))
        assertEquals("X_gm_dpf_inlet_t", v.resolve("DPF_TEMP", astra, gm))
        assertEquals("X_gm_dpf_km", v.resolve("DPF_KM", astra, gm))
        // A car with the standard PIDs uses them.
        val modern = astra + setOf(0x70, 0x8B, 0x7C)
        assertEquals("0170_AC", v.resolve("BOOST_TARGET", modern, gm))
        assertEquals("0170_AS", v.resolve("BOOST_ACTUAL", modern, gm))
        assertEquals("018B_T", v.resolve("DPF_SOOT", modern, gm))
        assertEquals("017C_I", v.resolve("DPF_TEMP", modern, gm))
        // Another make without either: the boost from the manifold pressure, no filter values.
        assertEquals("010B", v.resolve("BOOST_ACTUAL", astra, emptyList()))
        assertNull(v.resolve("DPF_SOOT", astra, emptyList()))
        // Boost in hundredths of a kPa: 0x4E20 = 20000 -> 200 kPa.
        assertEquals(200.0, Equation(com.openobd.core.brand.GM_DIESEL_PIDS[0].equation).evaluate(intArrayOf(0x4E, 0x20))!!, 1e-9)
    }

    @Test fun `the helper sees the regeneration from the engine's temperature`() {
        val m = RegenMonitor()
        val s = m.onCycle(mapOf(RegenWatch.GM_INLET_TEMP to 610.0), 0)
        assertEquals(true, s.regenActive)
        assertEquals(610.0, s.dpfTempC!!, 1e-9)
    }
}
