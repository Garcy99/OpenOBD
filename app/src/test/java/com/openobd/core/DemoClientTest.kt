package com.openobd.core

import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.ObdClient
import com.openobd.core.obd.Pids
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoClientTest {
    private fun connect(headers: Boolean = true): Triple<DemoTransport, Elm327, ObdClient> = runBlocking {
        val t = DemoTransport(latencyMs = 0)
        t.open()
        val elm = Elm327(t)
        val info = elm.initialize(ElmConfig(headersOn = headers))
        assertEquals(ObdProtocol.ISO_15765_4_CAN_11_500, info.protocol)
        Triple(t, elm, ObdClient(elm))
    }

    @Test
    fun fullSession() = runBlocking {
        val (demo, _, client) = connect()
        val supported = client.discover()
        assertTrue(0x0C in supported && 0x0D in supported && 0xA6 in supported)
        assertEquals("7E8", client.ecus.first().address)
        assertEquals(2, client.ecus.size)

        val data = client.readPids(listOf(0x0C, 0x0D, 0x05, 0x11, 0x04, 0x10, 0x0F, 0x42))
        assertEquals(8, data.size)
        val rpm = Pids.sensors.getValue("010C").decode(data.getValue(0x0C))!!
        assertTrue(rpm in 500.0..7000.0)

        val stored = client.readDtcs(DtcType.STORED)
        assertEquals(listOf("P0301", "P0420"), stored.codes.map { it.code })
        assertEquals(listOf("P0171"), client.readDtcs(DtcType.PENDING).codes.map { it.code })
        assertEquals(listOf("P0420"), client.readDtcs(DtcType.PERMANENT).codes.map { it.code })

        val ff = client.readFreezeFrame()
        assertNotNull(ff)
        assertEquals("P0301", ff!!.dtc)
        assertEquals(850.0, ff.values.getValue("010C"), 0.1)

        val readiness = client.readReadiness()!!
        assertTrue(readiness.milOn)
        assertEquals(2, readiness.dtcCount)

        val info = client.readVehicleInfo()
        assertEquals(demo.vin, info.vin)
        assertEquals("OPENOBD-DEMO-1.0", info.calibrationIds.first().second)
        assertEquals("1B3C9A7F", info.cvns.first().second)
        assertTrue(info.ecuNames.first().second.contains("EngineControl"))
        assertTrue(info.inUsePerformance.isNotEmpty())

        val tests = client.readMonitorTests()
        assertTrue(tests.size >= 5)

        val clear = client.clearDtcs()
        assertTrue(clear.message, clear.success)
        assertTrue(client.readDtcs(DtcType.STORED).codes.isEmpty())
        assertTrue(!client.readReadiness()!!.milOn)
    }

    @Test
    fun headersOff() = runBlocking {
        val (demo, _, client) = connect(headers = false)
        client.discover()
        val data = client.readPids(listOf(0x0C, 0x0D))
        assertEquals(2, data.size)
        assertEquals(demo.vin, client.readVin())
    }

    @Test
    fun withoutBatching() = runBlocking {
        val (_, _, client) = connect()
        client.discover()
        client.batchingEnabled = false
        val data = client.readPids(listOf(0x0C, 0x0D, 0x05))
        assertEquals(3, data.size)
    }
}

class StnFastModeTest {
    @Test
    fun verifiesAndUsesStpxOnStnAdapters() = runBlocking {
        val t = DemoTransport(latencyMs = 0, stn = true, withTransmission = false).also { it.open() }
        val elm = Elm327(t)
        val info = elm.initialize(ElmConfig())
        assertTrue(info.isStn)
        val client = ObdClient(elm).apply { stn = true }
        client.discover()
        assertEquals(1, client.ecus.size)
        val data = client.readPids(listOf(0x0C, 0x0D, 0x05, 0x04))
        assertEquals(4, data.size)
        assertEquals(true, client.stnFastVerified)
        assertTrue(client.stnFastActive)
        assertEquals(4, client.readPids(listOf(0x0C, 0x0D, 0x05, 0x04)).size) // now via STPX
        val bench = client.benchmark(setOf(0x0C, 0x0D, 0x04, 0x11, 0x05, 0x0B), rounds = 3)
        assertTrue(bench.pidsPerSecond > 0)
        assertEquals(6, bench.batchSize)
    }

    @Test
    fun plainElmIsNotStn() = runBlocking {
        val t = DemoTransport(latencyMs = 0).also { it.open() }
        val elm = Elm327(t)
        assertTrue(!elm.initialize(ElmConfig()).isStn)
    }
}
