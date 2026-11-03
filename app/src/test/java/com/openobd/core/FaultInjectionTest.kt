package com.openobd.core

import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ModuleClient
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.elm.ElmError
import com.openobd.core.elm.ObdResult
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import com.openobd.core.transport.FaultPlan
import com.openobd.core.transport.FaultyTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Runs real sessions through [FaultyTransport]: fragmented BLE-style notifications, noise, adapter errors,
 * lost prompts, late replies and dropped links. Nothing may crash, and once the faults stop the session must
 * give exactly the right answers again (no stale replies left in the pipe, no stuck module headers).
 */
class FaultInjectionTest {
    private class Rig(val demo: DemoTransport, val faulty: FaultyTransport, val elm: Elm327, val client: ObdClient)

    private suspend fun rig(plan: FaultPlan, seed: Int = 1, headers: Boolean = true): Rig {
        val demo = DemoTransport(latencyMs = 0, random = Random(seed))
        val faulty = FaultyTransport(demo, plan, Random(seed))
        faulty.open()
        val elm = Elm327(faulty)
        elm.initialize(ElmConfig(headersOn = headers))
        val client = ObdClient(elm)
        client.discover()
        return Rig(demo, faulty, elm, client)
    }

    /** Everything the app does in a session, checked for exact results. */
    private suspend fun assertHealthy(r: Rig, what: String) {
        assertEquals(what, 8, r.client.readPids(listOf(0x0C, 0x0D, 0x05, 0x11, 0x04, 0x10, 0x0F, 0x42)).size)
        assertEquals(what, listOf("P0301", "P0420"), r.client.readDtcs(DtcType.STORED).codes.map { it.code })
        assertEquals(what, listOf("P0171"), r.client.readDtcs(DtcType.PENDING).codes.map { it.code })
        assertEquals(what, "1HGCM82633A004352", r.client.readVin())
        assertEquals(what, "P0301", r.client.readFreezeFrame()?.dtc)
        val abs = ModuleClient(r.elm).scan(EcuModule("ABS", "ABS", 0x760, 0x768))
        assertTrue("$what: ${abs.error}", abs.online)
        assertEquals(what, listOf("C0035"), abs.dtcs.map { it.code })
        // After module work the adapter must be back on OBD defaults.
        assertEquals(what, 2, r.client.readPids(listOf(0x0D, 0x0C)).size)
    }

    @Test
    fun fragmentedNotificationsAreLossless() = runTest {
        for (headers in listOf(true, false)) for (seed in 1..5) {
            val r = rig(FaultPlan.FRAGMENTED.copy(maxChunk = seed), seed, headers)
            // Fragmentation also during initialisation and AT commands.
            r.faulty.plan = r.faulty.plan.copy(obdOnly = false)
            assertHealthy(r, "chunk<=$seed headers=$headers")
        }
    }

    @Test
    fun nastyLinkNeverCrashesAndRecovers() = runTest {
        for (seed in 1..12) {
            val r = rig(FaultPlan.NONE, seed)
            r.faulty.plan = FaultPlan.NASTY
            val modules = ModuleClient(r.elm)
            repeat(60) { i ->
                when (i % 6) {
                    0 -> r.client.readPids(listOf(0x0C, 0x0D, 0x05, 0x11))
                    1 -> r.client.readPids(listOf(0x0C))
                    2 -> r.client.readDtcs(DtcType.entries[i % 3])
                    3 -> r.client.readVehicleInfo()
                    4 -> modules.scan(EcuModule("ABS", "ABS", 0x760, 0x768))
                    else -> r.client.readFreezeFrame()
                }
            }
            assertTrue("seed $seed injected nothing", r.faulty.injected.values.sum() > 10)
            r.faulty.plan = FaultPlan.NONE
            assertHealthy(r, "seed $seed after ${r.faulty.injected}")
        }
    }

    @Test
    fun lateReplyIsNotTakenForTheNextAnswer() = runTest {
        val r = rig(FaultPlan.NONE)
        r.faulty.plan = FaultPlan(lateReply = 1.0)
        val first = r.elm.query("010D", 2000)
        assertTrue(first is ObdResult.Failure && first.error == ElmError.TIMEOUT)
        r.faulty.plan = FaultPlan.NONE
        // The late "41 0D" must have been discarded; this has to be the coolant reply.
        val next = r.elm.query("0105") as ObdResult.Data
        assertEquals(listOf(0x05), next.positive(1).map { it.data[1] })
    }

    @Test
    fun missingPromptTimesOutAndResyncs() = runTest {
        val r = rig(FaultPlan.NONE)
        r.faulty.plan = FaultPlan(dropPrompt = 1.0)
        val first = r.elm.query("010C", 2000)
        assertTrue(first is ObdResult.Failure && first.error == ElmError.TIMEOUT)
        r.faulty.plan = FaultPlan.NONE
        assertHealthy(r, "after lost prompt")
    }

    @Test
    fun adapterErrorsAreReportedNotParsed() = runTest {
        val r = rig(FaultPlan.NONE)
        r.faulty.plan = FaultPlan(errorReply = 1.0)
        val seen = mutableSetOf<ElmError>()
        repeat(40) {
            val q = r.elm.query("010C")
            assertTrue("error reply parsed as data: ${q.raw}", q is ObdResult.Failure)
            seen += (q as ObdResult.Failure).error
        }
        assertTrue(seen.toString(), seen.containsAll(listOf(ElmError.BUFFER_FULL, ElmError.CAN_ERROR, ElmError.STOPPED, ElmError.NO_DATA)))
        // A failed DTC read must say so instead of claiming "no codes".
        val dtcs = r.client.readDtcs(DtcType.STORED)
        assertTrue(dtcs.codes.isEmpty())
        r.faulty.plan = FaultPlan.NONE
        assertHealthy(r, "after errors")
    }

    @Test
    fun linkLossMidReplyFailsFastAndOnce() = runTest {
        val r = rig(FaultPlan.NONE)
        var disconnects = 0
        r.faulty.onDisconnected = { disconnects++ }
        r.faulty.plan = FaultPlan(disconnectAfter = 0)
        val first = r.elm.query("0902", 2000)
        assertTrue(first is ObdResult.Failure)
        // Every later request fails immediately with LINK_LOST, nothing throws.
        val start = testScheduler.currentTime
        repeat(20) {
            val q = r.elm.query("010C")
            assertEquals(ElmError.LINK_LOST, (q as ObdResult.Failure).error)
        }
        assertTrue(r.client.readPids(listOf(0x0C, 0x0D)).isEmpty())
        assertEquals("no waiting for timeouts on a dead link", start, testScheduler.currentTime)
        assertEquals(1, disconnects)
    }
}
