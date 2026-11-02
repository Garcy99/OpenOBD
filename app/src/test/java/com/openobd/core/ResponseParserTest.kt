package com.openobd.core

import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.elm.ResponseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseParserTest {
    private fun hex(vararg s: String) = s.flatMap { it.split(" ") }.map { it.toInt(16) }

    @Test
    fun canSingleFrameWithHeaders() {
        val msgs = ResponseParser.parse(listOf("7E8 04 41 0C 1A F8 00 00 00"), ObdProtocol.ISO_15765_4_CAN_11_500, true)
        assertEquals(1, msgs.size)
        assertEquals("7E8", msgs[0].ecu)
        assertEquals(hex("41 0C 1A F8"), msgs[0].data.toList())
    }

    @Test
    fun canMultiFrameVinWithHeaders() {
        val lines = listOf(
            "7E8 10 14 49 02 01 31 48 47",
            "7E8 21 43 4D 38 32 36 33 33",
            "7E8 22 41 30 30 34 33 35 32",
        )
        val msgs = ResponseParser.parse(lines, ObdProtocol.ISO_15765_4_CAN_11_500, true)
        assertEquals(1, msgs.size)
        val vin = msgs[0].data.drop(3).map { it.toChar() }.joinToString("")
        assertEquals("1HGCM82633A004352", vin)
    }

    @Test
    fun canMultipleEcus() {
        val lines = listOf("7E8 06 41 00 BE 3F A8 13 00", "7E9 06 41 00 98 18 80 01 00")
        val msgs = ResponseParser.parse(lines, ObdProtocol.ISO_15765_4_CAN_11_500, true)
        assertEquals(listOf("7E8", "7E9"), msgs.map { it.ecu })
    }

    @Test
    fun can29Bit() {
        val msgs = ResponseParser.parse(listOf("18 DA F1 10 03 41 0D 32 00 00 00 00"), ObdProtocol.ISO_15765_4_CAN_29_500, true)
        assertEquals("10", msgs[0].ecu)
        assertEquals(hex("41 0D 32"), msgs[0].data.toList())
    }

    @Test
    fun canNoSpaces() {
        val msgs = ResponseParser.parse(listOf("7E804410C1AF8000000"), ObdProtocol.ISO_15765_4_CAN_11_500, true)
        assertEquals(hex("41 0C 1A F8"), msgs[0].data.toList())
    }

    @Test
    fun canHeadersOffMultiFrame() {
        val lines = listOf("014", "0: 49 02 01 31 48 47", "1: 43 4D 38 32 36 33 33", "2: 41 30 30 34 33 35 32")
        val msgs = ResponseParser.parse(lines, ObdProtocol.ISO_15765_4_CAN_11_500, false)
        assertEquals(1, msgs.size)
        assertEquals(20, msgs[0].data.size)
    }

    @Test
    fun legacyJ1850WithHeaders() {
        val msgs = ResponseParser.parse(listOf("48 6B 10 41 0D 32 F2"), ObdProtocol.J1850_PWM, true)
        assertEquals("10", msgs[0].ecu)
        assertEquals(hex("41 0D 32"), msgs[0].data.toList())
    }

    @Test
    fun ignoresNonHexLines() {
        val lines = Elm327.clean("SEARCHING...\r7E8 03 41 0D 32 00 00 00 00\r\r", "010D")
        val msgs = ResponseParser.parse(lines, ObdProtocol.ISO_15765_4_CAN_11_500, true)
        assertEquals(1, msgs.size)
        assertTrue(ResponseParser.parse(listOf("NO DATA"), ObdProtocol.ISO_15765_4_CAN_11_500, true).isEmpty())
    }

    @Test
    fun cleanRemovesEchoAndBusInit() {
        val lines = Elm327.clean("0100\rBUS INIT: ...OK\r41 00 BE 1F B8 10\r", "0100")
        assertEquals(listOf("41 00 BE 1F B8 10"), lines)
    }
}
