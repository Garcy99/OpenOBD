package com.openobd.core

import com.openobd.core.transport.BaudProbe
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaudProbeTest {
    @Test
    fun recognisesElmReplies() {
        assertTrue(BaudProbe.looksLikeElm("ATI\rELM327 v1.5\r\r>"))
        assertTrue(BaudProbe.looksLikeElm("?\r\r>"))
        assertTrue(BaudProbe.looksLikeElm("STN1110 v4.2"))
        assertTrue(BaudProbe.looksLikeElm("OK"))
        // Garbage at the wrong speed
        assertFalse(BaudProbe.looksLikeElm("\u0000ÿàx\u0080"))
        assertFalse(BaudProbe.looksLikeElm(""))
        assertFalse(BaudProbe.looksLikeElm("BOOKING")) // "OK" inside a word is not an answer
    }

    @Test
    fun findsTheCableSpeedAndTriesTheRememberedOneFirst() = runTest {
        val tried = mutableListOf<Int>()
        val cable = 115200
        val found = BaudProbe.find(null) { rate -> tried += rate; if (rate == cable) "ELM327 v2.1\r>" else "ø\u0000" }
        assertEquals(cable, found)
        assertEquals(listOf(38400, 115200), tried)

        tried.clear()
        assertEquals(cable, BaudProbe.find(cable) { rate -> tried += rate; if (rate == cable) ">" else "" })
        assertEquals(listOf(cable), tried)
    }

    @Test
    fun nothingAnswers() = runTest {
        var calls = 0
        assertNull(BaudProbe.find(9600) { calls++; throw java.io.IOException("unplugged") })
        assertEquals(BaudProbe.RATES.size, calls)
    }
}
