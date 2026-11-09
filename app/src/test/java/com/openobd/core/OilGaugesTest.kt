package com.openobd.core

import com.openobd.core.brand.Brands
import com.openobd.core.brand.CustomSensors
import com.openobd.core.brand.Equation
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.VirtualSensors
import com.openobd.ui.screens.GaugeGrid
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OilGaugesTest {
    @After fun tearDown() = CustomSensors.update(emptyList())

    @Test fun `gauges fill the screen in balanced rows`() {
        // A landscape car screen: 6 gauges in two rows of three, not four and two.
        assertEquals(listOf(3, 3), GaugeGrid.plan(6, 960f, 460f, 10f)!!.rows)
        assertEquals(listOf(5, 5), GaugeGrid.plan(10, 1200f, 520f, 10f)!!.rows)
        assertEquals(listOf(4, 4), GaugeGrid.plan(8, 1000f, 500f, 10f)!!.rows)
        // A phone held upright: two per row.
        assertEquals(listOf(2, 2, 2), GaugeGrid.plan(6, 390f, 640f, 10f)!!.rows)
        // Odd counts: the fuller rows first, as even as possible.
        assertEquals(listOf(3, 2), GaugeGrid.plan(5, 960f, 460f, 10f)!!.rows)
        assertEquals(listOf(4, 3), GaugeGrid.balanced(7, 2))
        // Too many for a small screen: null (the app then scrolls).
        assertNull(GaugeGrid.plan(30, 390f, 640f, 10f))
        // The gauges are as big as the screen allows: one gauge on a phone is the phone's width.
        assertEquals(390f, GaugeGrid.plan(1, 390f, 640f, 10f)!!.cell, 0.01f)
    }

    @Test fun `oil gauges read the standard PID or the maker's`() {
        assertEquals("015C", VirtualSensors.resolve("OIL_TEMP", setOf(0x0C, 0x5C)))
        assertNull(VirtualSensors.resolve("OIL_TEMP", setOf(0x0C)))
        assertNull(VirtualSensors.resolve("OIL_PRESS", setOf(0x0C, 0x5C)))
        assertEquals("010C", VirtualSensors.resolve("010C", emptySet()))
        // An Opel (GM) without PID 5C: the ECM's own identifiers.
        CustomSensors.update(Brands.byId["opel"]!!.family.builtinPids)
        assertEquals("X_gm_eot", VirtualSensors.resolve("OIL_TEMP", setOf(0x0C)))
        assertEquals("X_gm_eop", VirtualSensors.resolve("OIL_PRESS", setOf(0x0C)))
        assertEquals("Engine oil pressure (GM)", SensorCatalog.get("X_gm_eop")!!.name)
        assertEquals("Engine oil temperature", SensorCatalog.get("OIL_TEMP")!!.name)
    }

    @Test fun `every brand asked for has oil temperature and pressure with sensible formulas`() {
        for (id in listOf("opel", "chevrolet", "volkswagen", "audi", "seat", "skoda", "bmw", "mini", "renault", "dacia", "peugeot", "citroen")) {
            val b = Brands.byId[id] ?: continue
            val pids = b.family.builtinPids.filter { "_eo" in it.id }
            assertEquals("$id has both", 2, pids.size)
            CustomSensors.update(pids)
            assertTrue(id, VirtualSensors.resolve("OIL_TEMP", emptySet()) != null && VirtualSensors.resolve("OIL_PRESS", emptySet()) != null)
            assertTrue(pids.all { it.request.startsWith("22") && it.request.length == 6 })
        }
        fun eval(eq: String, vararg b: Int) = Equation(eq).evaluate(b)!!
        // 90 °C and 3.2 bar, encoded the way each maker sends them.
        assertEquals(90.0, eval("A-40", 130), 1e-9)
        assertEquals(3.2, eval("A*0.04", 80), 1e-9)
        assertEquals(90.0, eval("(A*256+B)/10-273.1", 0x0E, 0x2F), 0.05) // 3631 -> 363.1 K
        assertEquals(3.2, eval("Int16(A,B)/1000", 0x0C, 0x80), 1e-9)
        assertEquals(3.2, eval("(A*256+B)/1000", 0x0C, 0x80), 1e-9)
        assertEquals(90.0, eval("Int16(A,B)/10-273.14", 0x0E, 0x2F), 0.05)
    }

    @Test fun `tyre gauges find each wheel's pressure, built in or imported`() {
        // Names as OBDb and PID packs write them.
        assertEquals("FL", VirtualSensors.wheelOf("Front left tire pressure"))
        assertEquals("FL", VirtualSensors.wheelOf("Left front tire pressure"))
        assertEquals("RR", VirtualSensors.wheelOf("Tire pressure, rear right outer"))
        assertEquals("RL", VirtualSensors.wheelOf("Presión neumático trasero izquierdo"))
        assertEquals("FR", VirtualSensors.wheelOf("TPMS RF"))
        assertNull(VirtualSensors.wheelOf("Spare tire pressure"))
        for (id in listOf("opel", "chevrolet", "volkswagen", "audi", "bmw", "mini", "ford", "toyota")) {
            val b = Brands.byId[id] ?: continue
            CustomSensors.update(b.family.builtinPids)
            val wheels = VirtualSensors.tyres.map { VirtualSensors.resolve(it.key, emptySet()) }
            assertTrue("$id: $wheels", wheels.all { it != null } && wheels.toSet().size == 4)
            assertEquals("bar", SensorCatalog.get(wheels[0]!!)!!.unit.symbol)
        }
        // Set points, warnings and the inner twin tyres are not the wheel's pressure.
        CustomSensors.update(listOf(
            com.openobd.core.brand.CustomPid("a", "Front left tire pressure, set point", "x", "221234", "A"),
            com.openobd.core.brand.CustomPid("b", "Rear right inner tire pressure", "x", "221235", "A"),
            com.openobd.core.brand.CustomPid("c", "Rear right outer tire pressure", "x", "221236", "A"),
        ))
        assertNull(VirtualSensors.resolve("TYRE_FL", emptySet()))
        assertEquals("X_c", VirtualSensors.resolve("TYRE_RR", emptySet()))

        fun eval(eq: String, vararg b: Int) = Equation(eq).evaluate(b)!!
        fun pid(list: List<com.openobd.core.brand.CustomPid>, w: String) = list.first { it.id.endsWith("_tp_" + w) }.equation
        // 2.3 bar as each maker sends it.
        assertEquals(2.3, eval(pid(com.openobd.core.brand.VAG_TYRE_PIDS, "fl"), 0, 0, 0, 0, 0, 92), 1e-9)
        assertEquals(2.3, eval(pid(com.openobd.core.brand.GM_TYRE_PIDS, "fl"), 33), 0.03) // 33 psi
        assertEquals(2.3, eval(pid(com.openobd.core.brand.FORD_TYRE_PIDS, "fl"), 0x02, 0x9A), 0.01) // 666/20 = 33.3 psi
        assertEquals(2.3, eval(pid(com.openobd.core.brand.TOYOTA_TYRE_PIDS, "rl"), 0, 0, 162, 0), 0.01)
        // BMW: high byte first (X3) or low byte first (F30, MINI), told apart by the set pressure in the reply.
        val bmw = pid(com.openobd.core.brand.BMW_TYRE_PIDS, "fl")
        assertEquals(2.3, eval(bmw, 0, 0, 0, 0, 0, 0x08, 0xFC, 0, 0x09, 0x60), 1e-9)   // 2300, set 2400, big-endian
        assertEquals(2.3, eval(bmw, 0, 0, 0, 0, 0, 0xFC, 0x08, 0, 0x60, 0x09), 1e-9)   // the same, little-endian
        assertEquals(2.304, eval(bmw, 0, 0, 0, 0, 0, 0x00, 0x09, 0, 0x60, 0x09), 1e-9) // 2304 LE: low byte 0 would fool a range check alone
        assertEquals(2.3, eval(bmw, 0, 0, 0, 0, 0, 0x08, 0xFC, 0, 0, 0), 1e-9)          // no set pressure: the plausible reading
    }
}
