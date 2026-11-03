package com.openobd.core

import com.openobd.core.debug.DebugLog
import com.openobd.core.debug.DebugReport
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import com.openobd.core.transport.FaultPlan
import com.openobd.core.transport.FaultyTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportTest {
    @Test
    fun logIsBounded() {
        var t = 0L
        val log = DebugLog(capacity = 3) { t++ }
        repeat(5) { log.add('>', "cmd $it") }
        assertEquals(listOf("cmd 2", "cmd 3", "cmd 4"), log.entries().map { it.text })
        assertEquals(2, log.dropped)
        assertTrue(log.format().startsWith("... 2 older entries dropped"))
        log.clear()
        assertEquals(0, log.size)
    }

    @Test
    fun reportRedactsPersonalData() {
        val log = DebugLog()
        log.event("Connecting to OBDII (00:1D:A5:68:98:8B)")
        log.add('<', "VIN 1HGCM82633A004352")
        val report = DebugReport.build(
            "Test",
            listOf("Connection" to listOf("VIN" to DebugReport.maskVin("1HGCM82633A004352"), "Adapter" to null)),
            log, crash = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)",
        )
        assertFalse(report, report.contains("00:1D:A5"))
        assertFalse(report, report.contains("004352"))
        assertTrue(report.contains("1HGCM82633A******"))
        assertTrue(report.contains("XX:XX:XX:XX:XX:XX"))
        assertTrue(report.contains("- Adapter: -"))
        assertTrue(report.contains("## Crash\njava.lang.IllegalStateException: boom"))
        assertTrue(report.contains("## Adapter log (2 entries"))
    }

    @Test
    fun adapterTrafficAndTimeoutsAreRecorded() = runTest {
        val log = DebugLog()
        val faulty = FaultyTransport(DemoTransport(latencyMs = 0), FaultPlan.NONE)
        faulty.open()
        val elm = Elm327(faulty) { d, s -> log.add(d, s) }
        elm.initialize(ElmConfig())
        ObdClient(elm).discover()
        faulty.plan = FaultPlan(dropPrompt = 1.0)
        elm.query("010C", 1000)
        val text = log.format()
        assertTrue(text, text.contains("> ATZ") && text.contains("> 0100") && text.contains("< 7E8"))
        assertTrue(text, text.contains("! timeout after 1000ms"))
    }
}
