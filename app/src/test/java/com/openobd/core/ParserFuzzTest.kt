package com.openobd.core

import com.openobd.core.brand.DidCatalog
import com.openobd.core.brand.Equation
import com.openobd.core.brand.ModuleClient
import com.openobd.core.brand.TorqueCsv
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ObdProtocol
import com.openobd.core.elm.ResponseParser
import com.openobd.core.obd.DtcDecoder
import com.openobd.core.obd.Mode06
import com.openobd.core.obd.Pids
import com.openobd.core.obd.Readiness
import com.openobd.core.obd.VinDecoder
import com.openobd.core.transport.DemoTransport
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Throws random and half-valid input at every parser that sees data from the car or the user.
 * They may return nothing or nonsense values, but must never throw: one bad byte from a cheap
 * adapter must not crash the app.
 */
class ParserFuzzTest {
    private val rnd = Random(20261103)
    private val iterations = 4000

    private fun hex(v: Int) = "%02X".format(v and 0xFF)

    /** Lines that look like adapter output: valid frames, truncated frames, noise, error texts. */
    private fun randomLine(): String {
        val bytes = List(rnd.nextInt(0, 12)) { rnd.nextInt(256) }
        return when (rnd.nextInt(14)) {
            0 -> "7E8 " + bytes.joinToString(" ", transform = ::hex)
            1 -> "7E8 1" + rnd.nextInt(16).toString(16).uppercase() + " " + bytes.joinToString(" ", transform = ::hex)
            2 -> "7E8 2" + rnd.nextInt(16).toString(16).uppercase() + " " + bytes.joinToString(" ", transform = ::hex)
            3 -> "18 DA F1 " + bytes.joinToString(" ", transform = ::hex)
            4 -> "%03X".format(rnd.nextInt(0x1000))
            5 -> "${rnd.nextInt(16).toString(16).uppercase()}: " + bytes.joinToString(" ", transform = ::hex)
            6 -> bytes.joinToString("", transform = ::hex)
            7 -> listOf("NO DATA", "?", "CAN ERROR", "BUFFER FULL", "STOPPED", "SEARCHING...", "BUS INIT: ...ERROR", ">", "").random(rnd)
            8 -> String(CharArray(rnd.nextInt(1, 20)) { (rnd.nextInt(0, 256)).toChar() })
            9 -> "48 6B 10 " + bytes.joinToString(" ", transform = ::hex)
            10 -> "0: " + bytes.joinToString(" ", transform = ::hex) + " Z"
            11 -> bytes.joinToString(" ", transform = ::hex).replaceFirstChar { 'G' }
            12 -> "7E8" + bytes.joinToString("", transform = ::hex)
            else -> bytes.joinToString(" ", transform = ::hex)
        }
    }

    private fun randomBytes(max: Int = 40) = IntArray(rnd.nextInt(0, max)) { rnd.nextInt(256) }

    private inline fun check(what: String, input: Any?, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            fail("$what threw ${e::class.simpleName}: ${e.message}\ninput: ${if (input is IntArray) input.joinToString(" ", transform = ::hex) else input}")
        }
    }

    @Test
    fun responseParserAndInterpret() {
        val elm = Elm327(DemoTransport(latencyMs = 0))
        repeat(iterations) {
            val lines = List(rnd.nextInt(1, 8)) { randomLine() }
            for (p in ObdProtocol.entries) for (headers in listOf(true, false)) {
                check("parse($p, $headers)", lines) { ResponseParser.parse(lines, p, headers) }
            }
            check("interpret", lines) { elm.interpret(lines) }
            check("clean", lines) { Elm327.clean(lines.joinToString("\r"), "010C") }
        }
    }

    @Test
    fun decodersOfCarData() {
        val modules = ModuleClient(Elm327(DemoTransport(latencyMs = 0)))
        repeat(iterations) {
            val d = randomBytes()
            check("DtcDecoder.parseResponse", d) { DtcDecoder.parseResponse(d, true); DtcDecoder.parseResponse(d, false) }
            check("Mode06.parse", d) { Mode06.parse(d, "7E8") }
            check("parseUds", d) { modules.parseUds(d) }
            check("parseKwp", d) { modules.parseKwp(d) }
            check("parseSnapshots", d) { modules.parseSnapshots(d) }
            check("DidCatalog", d) { DidCatalog.decode(rnd.nextInt(0x10000), d); DidCatalog.decode(0xF400 + rnd.nextInt(256), d) }
            if (d.size >= 4) check("Readiness", d) { Readiness.decode(d) }
            if (d.size >= 2) check("DtcDecoder.decode", d) { DtcDecoder.decode(d[0], d[1]) }
            val short = d.copyOf(rnd.nextInt(0, 6).coerceAtMost(d.size))
            for (s in Pids.sensors.values) check("sensor ${s.key}", short) {
                s.decodeSafe(short)?.let { v -> assertTrue("${s.key} gave $v", !v.isNaN()) }
            }
        }
    }

    @Test
    fun userAndTextInput() {
        val alphabet = "AB CDZ0123456789()+-*/%^<>=!&|{}:,.SignedInt16MinMaxAbs\"\t\n;"
        repeat(iterations) {
            val text = String(CharArray(rnd.nextInt(0, 30)) { alphabet[rnd.nextInt(alphabet.length)] })
            check("Equation", text) { runCatching { Equation(text) }.getOrNull()?.evaluate(randomBytes(8)) }
            check("VinDecoder", text) { VinDecoder.decode(text) }
            check("VinDecoder random", text) { VinDecoder.decode(String(CharArray(17) { "ABCDEFGHJKLMNPRSTUVWXYZ0123456789IOQ"[rnd.nextInt(36)] })) }
            val csv = List(rnd.nextInt(0, 5)) { List(rnd.nextInt(0, 9)) { text.take(rnd.nextInt(0, 6)) }.joinToString(",") }.joinToString("\n")
            check("TorqueCsv", csv) { TorqueCsv.parse(csv, "f") }
        }
    }

    @Test
    fun simulatorNeverThrowsOnRandomCommands() = kotlinx.coroutines.runBlocking {
        for (p in listOf(ObdProtocol.ISO_15765_4_CAN_11_500, ObdProtocol.ISO_9141_2, ObdProtocol.ISO_15765_4_CAN_29_500)) {
            val t = DemoTransport(latencyMs = 0, protocol = p)
            t.open()
            repeat(1500) {
                val cmd = when (rnd.nextInt(4)) {
                    0 -> "AT" + listOf("SH7E0", "SH760", "CRA7E8", "SP6", "SP3", "SPB", "H0", "H1", "S0", "Z", "DPN").random(rnd)
                    1 -> randomBytes(8).joinToString("", transform = ::hex)
                    2 -> randomBytes(4).joinToString("", transform = ::hex) + rnd.nextInt(10)
                    else -> "0" + rnd.nextInt(10) + randomBytes(3).joinToString("", transform = ::hex)
                }
                check("simulator", cmd) { t.send("$cmd\r".toByteArray()); while (t.incoming.tryReceive().isSuccess) Unit }
            }
        }
    }
}
