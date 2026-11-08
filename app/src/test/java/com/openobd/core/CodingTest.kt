package com.openobd.core

import com.openobd.core.brand.CarState
import com.openobd.core.brand.Coding
import com.openobd.core.brand.CodingEngine
import com.openobd.core.brand.CodingOption
import com.openobd.core.brand.ModuleClient
import com.openobd.core.brand.OutcomeKind
import com.openobd.core.brand.Routine
import com.openobd.core.brand.WriteRecord
import com.openobd.core.brand.WriteRisk
import com.openobd.core.brand.WriteSafety
import com.openobd.core.brand.WriteStatus
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CodingTest {
    private class Rig(headers: Boolean) {
        val t = DemoTransport(latencyMs = 0)
        val obd: ObdClient
        val mc: ModuleClient
        val history = linkedMapOf<String, WriteRecord>()
        /** Snapshot of the stored records each time one is saved, to check the backup exists before the write. */
        val writesSeenAtSave = mutableListOf<Int>()
        var n = 0
        val engine: CodingEngine

        init {
            runBlocking { t.open() }
            val elm = Elm327(t)
            runBlocking { elm.initialize(ElmConfig(headersOn = headers)) }
            obd = ObdClient(elm).also { runBlocking { it.discover() } }
            mc = ModuleClient(elm)
            engine = CodingEngine(mc, save = { history[it.id] = it; writesSeenAtSave += t.writes }, newId = { "w${n++}" }, now = { 1000L })
        }
    }

    private val lights = Coding(
        id = "c1", name = "Daytime running lights", header = "726", did = "0101", byte = 0, mask = 0x04,
        options = listOf(CodingOption("Off", "00"), CodingOption("On", "04")), risk = WriteRisk.SAFE,
    )
    private val sweep = Coding(
        id = "c2", name = "Needle sweep", header = "720", did = "0600", byte = 9, mask = 0x40,
        options = listOf(CodingOption("Off", "00"), CodingOption("On", "40")), risk = WriteRisk.SAFE,
    )

    @Test fun `short coding changes only its bits, backs up first and can be undone`() = runBlocking {
        val r = Rig(headers = true)
        val out = r.engine.apply("car", lights, lights.options[0])
        assertEquals(out.message + out.reason, OutcomeKind.DONE, out.kind)
        assertArrayEquals(intArrayOf(0x01, 0x00, 0x10), r.t.codingData.getValue(0x726).getValue(0x0101))
        val rec = r.history.values.single()
        assertEquals("050010", rec.before)
        assertEquals("010010", rec.after)
        assertEquals(WriteStatus.APPLIED, rec.status)
        // The first save (the backup) happened before any write reached the car.
        assertEquals(0, r.writesSeenAtSave.first())
        assertTrue(rec.undoable)

        // Another setting in the same identifier changes later; undo must keep it.
        r.t.codingData.getValue(0x726)[0x0101] = intArrayOf(0x01, 0x00, 0x11)
        val undo = r.engine.undo(rec)
        assertTrue(undo.ok)
        assertArrayEquals(intArrayOf(0x05, 0x00, 0x11), r.t.codingData.getValue(0x726).getValue(0x0101))
        assertEquals(WriteStatus.UNDONE, r.history.getValue(rec.id).status)
        assertEquals(rec.id, r.history.values.last().undoOf)
        // Standard OBD still works after all the header switching.
        assertEquals(2, r.obd.readPids(listOf(0x0C, 0x0D)).size)
    }

    @Test fun `already set writes nothing`() = runBlocking {
        val r = Rig(headers = true)
        val out = r.engine.apply("car", lights, lights.options[1])
        assertEquals(OutcomeKind.NOT_CHANGED, out.kind)
        assertEquals(CodingEngine.ALREADY, out.message)
        assertEquals(0, r.t.writes)
        assertTrue(r.history.isEmpty())
    }

    @Test fun `long coding is sent in several frames`() = runBlocking {
        for (headers in listOf(true, false)) {
            val r = Rig(headers)
            assertEquals("On", sweep.current(r.mc.read(sweep.module(), 0x0600))?.label)
            val out = r.engine.apply("car", sweep, sweep.options[0])
            assertEquals("headers=$headers: ${out.message} ${out.reason}", OutcomeKind.DONE, out.kind)
            assertEquals(0x00, r.t.codingData.getValue(0x720).getValue(0x0600)[9])
            assertEquals(0x22, r.t.codingData.getValue(0x720).getValue(0x0600)[1])
            assertEquals("Off", sweep.current(r.mc.read(sweep.module(), 0x0600))?.label)
            assertTrue(r.engine.undo(r.history.values.first()).ok)
            assertEquals(0x40, r.t.codingData.getValue(0x720).getValue(0x0600)[9])
            assertEquals(2, r.obd.readPids(listOf(0x0C, 0x0D)).size)
        }
    }

    @Test fun `module that ignores the write is reported, nothing changed`() = runBlocking {
        val r = Rig(headers = true)
        r.t.writeFault = DemoTransport.WriteFault.IGNORE
        val out = r.engine.apply("car", lights, lights.options[0])
        assertEquals(OutcomeKind.NOT_CHANGED, out.kind)
        assertEquals(WriteStatus.UNCHANGED, r.history.values.single().status)
        assertArrayEquals(intArrayOf(0x05, 0x00, 0x10), r.t.codingData.getValue(0x726).getValue(0x0101))
    }

    @Test fun `wrong value stored is put back automatically`() = runBlocking {
        val r = Rig(headers = true)
        r.t.writeFault = DemoTransport.WriteFault.CORRUPT
        val out = r.engine.apply("car", lights, lights.options[0])
        assertEquals(OutcomeKind.RESTORED, out.kind)
        assertArrayEquals(intArrayOf(0x05, 0x00, 0x10), r.t.codingData.getValue(0x726).getValue(0x0101))
        assertEquals(WriteStatus.RESTORED, r.history.values.single().status)
    }

    @Test fun `failed restore keeps the backup and can be retried`() = runBlocking {
        val r = Rig(headers = true)
        r.t.writeFault = DemoTransport.WriteFault.CORRUPT_STICKY
        val out = r.engine.apply("car", lights, lights.options[0])
        assertEquals(OutcomeKind.NEEDS_ATTENTION, out.kind)
        val rec = r.history.values.single()
        assertEquals(WriteStatus.RESTORE_FAILED, rec.status)
        assertTrue(rec.needsRestore)
        r.t.writeFault = null
        val retry = r.engine.retryRestore(rec)
        assertEquals(OutcomeKind.RESTORED, retry.kind)
        assertArrayEquals(intArrayOf(0x05, 0x00, 0x10), r.t.codingData.getValue(0x726).getValue(0x0101))
    }

    @Test fun `no answer on the check read is retried and confirmed`() = runBlocking {
        val r = Rig(headers = true)
        r.t.writeFault = DemoTransport.WriteFault.SILENT_READ
        val out = r.engine.apply("car", lights, lights.options[0])
        // The read-back fails once, so the engine writes the backup back and confirms it.
        assertEquals(OutcomeKind.RESTORED, out.kind)
        assertArrayEquals(intArrayOf(0x05, 0x00, 0x10), r.t.codingData.getValue(0x726).getValue(0x0101))
    }

    @Test fun `refusals explain why and change nothing`() = runBlocking {
        val r = Rig(headers = true)
        r.t.writeFault = DemoTransport.WriteFault.REFUSE
        val refused = r.engine.apply("car", lights, lights.options[0])
        assertEquals(OutcomeKind.NOT_CHANGED, refused.kind)
        assertEquals("Conditions not correct: engine off, ignition on, car parked.", refused.reason)
        r.t.writeFault = null

        val locked = lights.copy(did = "0909", mask = 0x01, options = listOf(CodingOption("Off", "00"), CodingOption("On", "01")))
        val out = r.engine.apply("car", locked, locked.options[1])
        assertEquals("The module is locked (security access). OpenOBD does not unlock modules.", out.reason)
        assertArrayEquals(intArrayOf(0x00), r.t.codingData.getValue(0x726).getValue(0x0909))

        val missing = lights.copy(did = "0102")
        val m = r.engine.apply("car", missing, missing.options[0])
        assertEquals(CodingEngine.READ_FAILED, m.message)
        assertEquals("The module does not know this identifier or value: it does not match your car.", m.reason)

        val tooShort = lights.copy(byte = 7)
        assertEquals(CodingEngine.TOO_SHORT, r.engine.apply("car", tooShort, tooShort.options[0]).message)
    }

    @Test fun `routine starts in the extended session and stops`() = runBlocking {
        val r = Rig(headers = true)
        val regen = Routine(id = "r1", name = "Service regeneration", header = "7E0", start = "3101F001", stop = "3102F001", engineRunning = true)
        assertNull(regen.validate())
        val out = r.engine.run("car", regen)
        assertTrue(out.message, out.ok)
        assertTrue(0xF001 in r.t.runningRoutines)
        assertTrue(r.engine.stop(regen))
        assertFalse(0xF001 in r.t.runningRoutines)
        val bad = regen.copy(start = "3101F002")
        assertEquals(OutcomeKind.NOT_CHANGED, r.engine.run("car", bad).kind)
        assertEquals(WriteStatus.ROUTINE_FAILED, r.history.values.last().status)
    }

    @Test fun `codings are validated`() {
        assertNull(lights.validate())
        assertNull(sweep.validate())
        assertNotNull(lights.copy(did = "F190").validate()) // the VIN is never written
        assertNotNull(lights.copy(options = listOf(CodingOption("On", "08"), CodingOption("Off", "00"))).validate()) // outside mask
        assertNotNull(lights.copy(options = listOf(CodingOption("On", "04"))).validate())
        assertNotNull(lights.copy(header = "7E").validate())
        assertNotNull(Routine("r", "x", header = "7E0", start = "2E0101").validate())
        assertNull(lights.copy(header = "DA40F1").validate())
        assertEquals(0x40, lights.copy(header = "DA40F1").module().tx)
    }

    @Test fun `dangerous writes need the car parked, engine off and a good battery`() {
        val ok = CarState(rpm = 0.0, speedKmh = 0.0, volts = 12.5)
        assertTrue(WriteSafety.problems(WriteRisk.DANGEROUS, ok).isEmpty())
        assertTrue(WriteSafety.problems(WriteRisk.SAFE, CarState(null, null, null)).isEmpty())
        assertTrue(WriteSafety.problems(WriteRisk.DANGEROUS, CarState(null, null, null)).isEmpty())
        assertEquals(3, WriteSafety.unknowns(CarState(null, null, null)).size)
        assertEquals(listOf("The car must be stopped."), WriteSafety.problems(WriteRisk.SAFE, CarState(800.0, 30.0, 14.0)))
        assertTrue("Switch the engine off and leave the ignition on." in WriteSafety.problems(WriteRisk.DANGEROUS, CarState(800.0, 0.0, 14.0)))
        assertTrue("Battery too low. Charge it or connect a charger first." in WriteSafety.problems(WriteRisk.DANGEROUS, ok.copy(volts = 11.6)))
        // A regeneration needs the engine running instead.
        assertEquals(listOf("Start the engine first."), WriteSafety.problems(WriteRisk.DANGEROUS, ok, engineRunning = true))
        assertTrue(WriteSafety.problems(WriteRisk.DANGEROUS, CarState(850.0, 0.0, 13.9), engineRunning = true).isEmpty())
    }
}

class CodingFinderTest {
    @Test fun `changed bits become a coding`() {
        val before = mapOf(0x0101 to intArrayOf(0x05, 0x00, 0x10), 0x0102 to intArrayOf(1, 2), 0x0103 to intArrayOf(9))
        val after = mapOf(0x0101 to intArrayOf(0x01, 0x00, 0x10), 0x0102 to intArrayOf(1, 2, 3), 0x0103 to intArrayOf(9))
        val changes = com.openobd.core.brand.CodingFinder.diff(before, after)
        assertEquals(1, changes.size)
        val c = changes.single()
        assertEquals(0x0101, c.did); assertEquals(0, c.byte); assertEquals(0x04, c.mask)
        val m = com.openobd.core.brand.EcuModule("BCM", "BCM", 0x726, 0x72E)
        val coding = com.openobd.core.brand.CodingFinder.toCoding("x", "Auto lock", m, c, "Before", "After")
        assertNull(coding.validate())
        assertEquals("726", coding.header)
        assertEquals("", coding.receive)
        assertEquals(listOf("04", "00"), coding.options.map { it.value })
        assertEquals(WriteRisk.DANGEROUS, coding.risk)
        assertEquals("Before", coding.current(intArrayOf(0x05, 0, 0x10))?.label)
        val gm = com.openobd.core.brand.EcuModule("BCM", "BCM", 0x241, 0x641)
        assertEquals("241" to "641", com.openobd.core.brand.CodingFinder.addressOf(gm))
    }
}
