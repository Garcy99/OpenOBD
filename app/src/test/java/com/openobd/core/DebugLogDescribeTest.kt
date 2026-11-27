package com.openobd.core

import com.openobd.core.debug.DebugLog
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugLogDescribeTest {
    @Test fun `an error is told by its message and its cause`() {
        val e = java.io.IOException("Could not connect to OBDII", java.io.IOException("read failed, socket might closed or timeout, read ret: -1"))
        assertEquals("Could not connect to OBDII ← read failed, socket might closed or timeout, read ret: -1", DebugLog.describe(e))
        val log = DebugLog()
        log.error("Connection failed", e)
        assertEquals("Connection failed: Could not connect to OBDII ← read failed, socket might closed or timeout, read ret: -1", log.entries().single().text)
        // Without a message, the full class name (not a shortened one).
        assertEquals("java.lang.IllegalStateException", DebugLog.describe(IllegalStateException()))
    }
}
