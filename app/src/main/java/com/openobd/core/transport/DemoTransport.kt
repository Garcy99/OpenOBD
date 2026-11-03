package com.openobd.core.transport

import com.openobd.core.elm.ObdProtocol
import com.openobd.core.obd.DtcDecoder
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * A simulated ELM327 adapter connected to a simulated car. By default the car speaks ISO 15765-4 CAN 11-bit
 * 500 kbaud (engine ECU 7E8 + transmission ECU 7E9); [protocol] can select 29-bit CAN or one of the pre-CAN
 * protocols (J1850 PWM/VPW, ISO 9141-2, KWP2000) with their framing, headers, checksums and limits.
 * Used for the in-app demo mode and for unit tests.
 */
class DemoTransport(
    private val latencyMs: Long = 35,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default,
    /** Behave like a low-quality clone that lacks ATCRA and the flow-control commands. */
    private val limitedClone: Boolean = false,
    /** Behave like an OBDLink (STN) adapter: answers STI and supports STPX. */
    private val stn: Boolean = false,
    /** Simulate a transmission ECU that also answers OBD requests (two responders). */
    private val withTransmission: Boolean = true,
    /** The protocol the simulated car speaks. */
    val protocol: ObdProtocol = ObdProtocol.ISO_15765_4_CAN_11_500,
) : ObdTransport() {
    override val description: String =
        if (protocol == ObdProtocol.ISO_15765_4_CAN_11_500) "Demo car (simulated)" else "Demo car (simulated, ${protocol.label})"

    private val can11 = protocol.isCan && !protocol.is29Bit
    private val legacy = !protocol.isCan
    private val engine = if (can11) "7E8" else "10"
    private val trans = if (can11) "7E9" else "18"
    private val defaultHeader = when {
        can11 -> "7DF"
        protocol.is29Bit -> "DB33F1"
        protocol == ObdProtocol.J1850_PWM -> "616AF1"
        protocol == ObdProtocol.ISO_14230_4_KWP_5BAUD || protocol == ObdProtocol.ISO_14230_4_KWP_FAST -> "C133F1"
        else -> "686AF1"
    }
    private val needsBusInit = protocol in setOf(ObdProtocol.ISO_9141_2, ObdProtocol.ISO_14230_4_KWP_5BAUD, ObdProtocol.ISO_14230_4_KWP_FAST)

    private val buffer = StringBuilder()
    private val startedAt = clock()

    // Adapter state
    private var echo = true
    private var spaces = true
    private var headers = false
    private var connectedToCar = false
    private var header = defaultHeader
    /** ATSP value; "B" = User1 CAN 125k, i.e. the MS-CAN side of an HS/MS switch adapter. */
    private var protocolCode = '0'
    /** Modules on the simulated medium-speed bus (reached with ATSP B). */
    val msModules: Map<Int, SimModule> = mapOf(
        0x733 to SimModule(0x73B, "HVAC", "OB-18C612-DEMO", mutableListOf(Triple("B1A4F", 0x12, 0x09))),
        0x740 to SimModule(0x748, "DDM", "OB-14B533-DEMO", mutableListOf()),
        0x7D0 to SimModule(0x7D8, "APIM", "OB-14G371-DEMO", mutableListOf(Triple("U0184", 0x00, 0x28))),
    )
    private val onMsCan get() = protocolCode == 'B' && can11
    /** ATCAF: CAN auto formatting (ISO-TP PCI bytes added / interpreted by the adapter). */
    private var caf = true
    /** ATPB options / baud divisor for protocol B; defaults are the ELM327's (125 kbps). */
    private var pbOptions = 0xE0
    private var pbDivisor = 0x04
    private val pbKbps: Double get() = 500.0 * (if (pbOptions and 0x10 != 0) 8.0 / 7 else 1.0) / pbDivisor
    /** Opel-style MS-CAN (95.24 kbps) with GMLAN modules. */
    val opelMsModules: Map<Int, SimModule> = mapOf(
        0x244 to SimModule(0x644, "RADIO", "13346053", mutableListOf(Triple("B1025", 0x00, 0x6F)), gmlan = true),
        0x251 to SimModule(0x651, "HVAC", "13406121", mutableListOf(), gmlan = true),
    )
    private var receiveFilter: String? = null
    private var canFilter: Int? = null
    private var canMask: Int? = null

    /** Simulated non-OBD control modules: request ID -> module. Mutable so tests can inspect / clear. */
    class SimModule(val rx: Int, val name: String, val partNumber: String, val dtcs: MutableList<Triple<String, Int, Int>>, val gmlan: Boolean = false)
    val modules: Map<Int, SimModule> = mapOf(
        0x7E0 to SimModule(0x7E8, "ECM", "OB-12A650-DEMO", mutableListOf()),
        0x7E1 to SimModule(0x7E9, "TCM", "OB-7C150-DEMO", mutableListOf()),
        0x760 to SimModule(0x768, "ABS", "OB-2C219-DEMO", mutableListOf(Triple("C0035", 0x31, 0x09))),
        0x737 to SimModule(0x73F, "RCM", "OB-14B321-DEMO", mutableListOf(Triple("B0001", 0x13, 0x88))),
        0x726 to SimModule(0x72E, "BCM", "OB-15604-DEMO", mutableListOf(Triple("U0155", 0x00, 0x28))),
        0x720 to SimModule(0x728, "IPC", "OB-10849-DEMO", mutableListOf()),
        // GM / Opel style modules that only speak GMLAN (codes via \$A9, cleared with \$04)
        0x243 to SimModule(0x643, "EBCM", "13354012", mutableListOf(Triple("C0561", 0x71, 0x6F), Triple("C0035", 0x00, 0x2F)), gmlan = true),
        0x247 to SimModule(0x647, "SDM", "13583468", mutableListOf(), gmlan = true),
    )

    // Car state
    val stored = mutableListOf("P0301", "P0420")
    val pending = mutableListOf("P0171")
    val permanent = mutableListOf("P0420")
    private var monitorsComplete = true
    var vin = "1HGCM82633A004352"

    private val engineFreezeFrame = mapOf(
        0x04 to intArrayOf(0x8C), 0x05 to intArrayOf(0x7B), 0x06 to intArrayOf(0x8A), 0x07 to intArrayOf(0x84),
        0x0B to intArrayOf(0x45), 0x0C to intArrayOf(0x0D, 0x48), 0x0D to intArrayOf(0x38), 0x0E to intArrayOf(0x94),
        0x0F to intArrayOf(0x41), 0x11 to intArrayOf(0x30),
    )

    private val enginePids: Set<Int> = setOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15, 0x1C, 0x1F,
        0x21, 0x2F, 0x30, 0x31, 0x33, 0x3C, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x49, 0x4A, 0x4C, 0x4D, 0x4E,
        0x51, 0x5C, 0x5E, 0xA6,
    ).let { it + supportMarkers(it) }
    private val transmissionPids: Set<Int> = setOf(0x01, 0x0D).let { it + supportMarkers(it) }

    private fun supportMarkers(pids: Set<Int>): Set<Int> {
        val maxPid = pids.max()
        return (0x20..0xE0 step 0x20).filter { it < maxPid }.toSet()
    }

    override suspend fun open() { isOpen = true }

    override suspend fun close() { isOpen = false }

    override suspend fun send(data: ByteArray) {
        if (!isOpen) throw TransportException("Demo link closed")
        buffer.append(String(data, Charsets.US_ASCII))
        while (true) {
            val idx = buffer.indexOf("\r")
            if (idx < 0) break
            val cmd = buffer.substring(0, idx)
            buffer.delete(0, idx + 1)
            val reply = handle(cmd.trim())
            if (latencyMs > 0) delay(latencyMs)
            val echoPart = if (echo) "$cmd\r" else ""
            incoming.trySend((echoPart + reply.joinToString("\r") + "\r\r>").toByteArray(Charsets.US_ASCII))
        }
    }

    // ------------------------------------------------------------------ AT commands

    private fun handle(raw: String): List<String> {
        val cmd = raw.replace(" ", "").uppercase()
        if (cmd.isEmpty()) return listOf("")
        if (cmd.startsWith("AT")) return handleAt(cmd.substring(2))
        if (cmd.startsWith("STPX") && stn) {
            val data = Regex("D:([0-9A-F]+)").find(cmd)?.groupValues?.get(1) ?: return listOf("?")
            return handleObd(data)
        }
        if (cmd == "STI" && stn) return listOf("STN2120 v5.6.19")
        if (cmd.startsWith("ST")) return listOf("?")
        if (!cmd.all { it.isDigit() || it in 'A'..'F' }) return listOf("?")
        return handleObd(cmd)
    }

    private fun handleAt(c: String): List<String> = when {
        c == "Z" || c == "WS" -> {
            echo = true; spaces = true; headers = false; connectedToCar = false
            protocolCode = '0'; caf = true; pbOptions = 0xE0; pbDivisor = 0x04
            header = defaultHeader; receiveFilter = null; canFilter = null; canMask = null
            listOf("", "ELM327 v1.5")
        }
        limitedClone && ((c.startsWith("CRA") && c != "CRA") || c.startsWith("FCS")) -> listOf("?")
        c.startsWith("CAF") -> { caf = c.endsWith("1"); listOf("OK") }
        c.startsWith("PB") && c.length == 6 -> {
            if (limitedClone) listOf("?") else {
                pbOptions = c.substring(2, 4).toInt(16); pbDivisor = c.substring(4, 6).toInt(16).coerceAtLeast(1); listOf("OK")
            }
        }
        c.startsWith("SH") -> {
            val h = c.substring(2)
            header = if (protocol.is29Bit && h.length == 8) h.substring(2) else h
            listOf("OK")
        }
        c == "CRA" -> { receiveFilter = null; canFilter = null; canMask = null; listOf("OK") }
        c.startsWith("CRA") -> { receiveFilter = c.substring(3); listOf("OK") }
        c.startsWith("CF") -> { canFilter = c.substring(2).toIntOrNull(16); listOf("OK") }
        c.startsWith("CM") -> { canMask = c.substring(2).toIntOrNull(16); listOf("OK") }
        c == "I" -> listOf("ELM327 v1.5")
        c == "@1" -> listOf("OpenOBD Demo Adapter")
        c == "RV" -> listOf("%.1fV".format(if (connectedToCar) 14.1 + random.nextDouble(-0.1, 0.1) else 12.6))
        c == "DPN" -> listOf(if (onMsCan) "B" else if (!connectedToCar) protocolCode.toString() else if (protocolCode == '0') "A${protocol.code}" else "${protocol.code}")
        c.startsWith("SP") || c.startsWith("TP") -> { protocolCode = c.last(); listOf("OK") }
        c == "DP" -> listOf(if (protocolCode == '0') "AUTO, ${protocol.label}" else protocol.label)
        c.startsWith("E") -> { echo = c.endsWith("1"); listOf("OK") }
        c.startsWith("S") && c.length == 2 && (c[1] == '0' || c[1] == '1') -> { spaces = c[1] == '1'; listOf("OK") }
        c.startsWith("H") && c.length == 2 -> { headers = c[1] == '1'; listOf("OK") }
        else -> listOf("OK")
    }

    // ------------------------------------------------------------------ OBD requests

    private fun handleObd(cmd: String): List<String> {
        // Odd length -> trailing "expected responses" digit
        val hex = if (cmd.length % 2 == 1) cmd.dropLast(1) else cmd
        // With CAN auto formatting off the tester supplies the ISO-TP PCI byte itself.
        val bytes = hex.chunked(2).map { it.toInt(16) }.let { if (!caf && it.isNotEmpty() && it[0] in 1..7) it.drop(1).take(it[0]) else it }
        if (bytes.isEmpty()) return listOf("?")
        if (!onMsCan && protocolCode != '0' && protocolCode != protocol.code) {
            // Locked to a protocol this car does not speak.
            return listOf(when (protocolCode) { '3', '4', '5' -> "BUS INIT: ...ERROR"; '1', '2' -> "NO DATA"; else -> "CAN ERROR" })
        }
        val firstContact = !connectedToCar
        connectedToCar = true
        if (onMsCan) {
            val tx = header.toIntOrNull(16) ?: return listOf("NO DATA")
            // The modules only answer at their bus speed; anything else is garbage on the wire.
            val bus = when {
                kotlin.math.abs(pbKbps - 125.0) < 1 -> msModules
                kotlin.math.abs(pbKbps - 95.24) < 1 -> opelMsModules
                else -> return listOf("CAN ERROR")
            }
            val module = bus[tx] ?: return listOf("NO DATA")
            if (module.gmlan) gmlanReply(module, bytes)?.let { return it }
            if (!accepts(module.rx)) return listOf("NO DATA")
            val payload = moduleReply(tx, module, bytes) ?: return listOf("NO DATA")
            return format("%03X".format(module.rx), payload)
        }
        if (header != defaultHeader) {
            // Physical addressing of individual modules is simulated on 11-bit CAN only.
            if (!can11) return listOf("NO DATA")
            val tx = header.toIntOrNull(16) ?: return listOf("NO DATA")
            val module = modules[tx] ?: return listOf("NO DATA")
            val isObdService = bytes[0] in setOf(0x01, 0x02, 0x03, 0x04, 0x06, 0x07, 0x09, 0x0A)
            val rxHex = "%03X".format(module.rx)
            if (module.gmlan) gmlanReply(module, bytes)?.let { return it }
            if (!accepts(module.rx)) return listOf("NO DATA")
            if (isObdService && tx in 0x7E0..0x7E1) {
                val saved = header
                header = "7DF"
                val all = handleObd(cmd)
                header = saved
                val mine = all.filter { it.startsWith(rxHex) || !headers }
                return if (headers) mine.ifEmpty { listOf("NO DATA") } else all
            }
            val payload = moduleReply(tx, module, bytes) ?: return listOf("NO DATA")
            return format(rxHex, payload)
        }
        val replies = mutableListOf<Pair<String, List<Int>>>()
        val mode = bytes[0]
        when (mode) {
            0x01 -> {
                val pids = bytes.drop(1)
                if (pids.isEmpty() || pids.size > 6) return listOf("?")
                for ((ecu, supported) in listOf(engine to enginePids, trans to transmissionPids).filter { withTransmission || it.first == engine }) {
                    val payload = mutableListOf(0x41)
                    // Pre-CAN ECUs answer one PID per request (multi-PID requests are a CAN feature).
                    for (p in if (legacy) pids.take(1) else pids) {
                        val d = if (p % 0x20 == 0) supportBitmap(p, supported) else if (p in supported) pidValue(p, ecu) else null
                        if (d != null) { payload += p; payload += d.toList() }
                    }
                    if (payload.size > 1) replies += ecu to payload
                }
            }
            0x02 -> {
                if (bytes.size < 2) return listOf("?")
                val pid = bytes[1]
                val frame = bytes.getOrElse(2) { 0 }
                if (frame == 0 && stored.isNotEmpty()) {
                    val ff = engineFreezeFrame
                    val d: IntArray? = when {
                        pid % 0x20 == 0 -> supportBitmap(pid, ff.keys + 0x02 + 0x00)
                        pid == 0x02 -> DtcDecoder.encode(stored.first()).let { intArrayOf(it.first, it.second) }
                        else -> ff[pid]
                    }
                    if (d != null) replies += engine to listOf(0x42, pid, frame) + d.toList()
                }
            }
            0x03, 0x07, 0x0A -> {
                if (legacy && mode == 0x0A) return listOf("NO DATA")
                val list = when (mode) { 0x03 -> stored; 0x07 -> pending; else -> permanent }
                if (legacy) {
                    // No count byte; three codes per frame, padded with 00 00.
                    for (group in list.chunked(3).ifEmpty { listOf(emptyList()) }) {
                        val payload = mutableListOf(mode + 0x40)
                        group.forEach { val (a, b) = DtcDecoder.encode(it); payload += a; payload += b }
                        while (payload.size < 7) payload += 0
                        replies += engine to payload
                    }
                    if (mode == 0x03 && withTransmission) replies += trans to listOf(0x43, 0, 0, 0, 0, 0, 0)
                } else {
                    val payload = mutableListOf(mode + 0x40, list.size)
                    list.forEach { val (a, b) = DtcDecoder.encode(it); payload += a; payload += b }
                    replies += engine to payload
                    if (mode == 0x03 && withTransmission) replies += trans to listOf(0x43, 0)
                }
            }
            0x04 -> {
                stored.clear(); pending.clear(); monitorsComplete = false
                replies += engine to listOf(0x44)
                if (withTransmission) replies += trans to listOf(0x44)
            }
            0x06 -> if (legacy) return listOf("NO DATA") else replies += mode06(bytes.getOrElse(1) { 0 }) ?: return listOf("NO DATA")
            0x09 -> replies += (if (legacy) legacyMode09(bytes.getOrElse(1) { 0 }) else mode09(bytes.getOrElse(1) { 0 })?.let { listOf(it) })
                ?: return listOf("NO DATA")
            else -> return listOf("NO DATA")
        }
        if (replies.isEmpty()) return listOf("NO DATA")
        val prefix = if (!firstContact) emptyList()
            else (if (protocolCode == '0') listOf("SEARCHING...") else emptyList()) + (if (needsBusInit) listOf("BUS INIT: ...OK") else emptyList())
        return prefix + replies.flatMap { (ecu, payload) -> format(ecu, payload) }
    }

    /**
     * GMLAN behaviour: UDS / KWP code services are rejected, \$A9 \$81 sends one UUDT frame per code on 0x5xx
     * (raw, no PCI), \$04 clears. Returns null for requests handled like any other module.
     */
    private fun gmlanReply(m: SimModule, b: List<Int>): List<String>? {
        val rxHex = "%03X".format(m.rx)
        fun usdt(vararg payload: Int) = if (accepts(m.rx)) format(rxHex, payload.toList()) else listOf("NO DATA")
        return when (b.getOrNull(0)) {
            0x19, 0x18, 0x14 -> usdt(0x7F, b[0], 0x11)
            0x3E -> usdt(0x7E)
            0x04 -> { m.dtcs.clear(); usdt(0x44) }
            0xA9 -> {
                val uudt = m.rx - 0x100
                if (!accepts(uudt)) return listOf("NO DATA")
                val frames = m.dtcs.map { (code, ftb, st) -> DtcDecoder.encode(code).let { listOf(0x81, it.first, it.second, ftb, st, 0, 0, 0) } } +
                    listOf(listOf(0x81, 0, 0, 0, 0xFF, 0, 0, 0))
                frames.map { f -> join(listOf("%03X".format(uudt)) + f.map(::hex)) }
            }
            else -> null
        }
    }

    private fun accepts(rx: Int): Boolean {
        receiveFilter?.let { f ->
            val id = "%03X".format(rx)
            if (f.length != id.length || f.indices.any { f[it] != 'X' && f[it] != id[it] }) return false
        }
        val cf = canFilter; val cm = canMask
        if (cf != null && cm != null && (rx and cm) != (cf and cm)) return false
        return true
    }

    /** UDS behaviour of the simulated modules. */
    private fun moduleReply(tx: Int, m: SimModule, b: List<Int>): List<Int>? = try {
        moduleReplyUnchecked(tx, m, b)
    } catch (e: IndexOutOfBoundsException) {
        listOf(0x7F, b[0], 0x13) // incorrect message length
    }

    private fun moduleReplyUnchecked(tx: Int, m: SimModule, b: List<Int>): List<Int>? {
        fun neg(nrc: Int) = listOf(0x7F, b[0], nrc)
        return when (b[0]) {
            0x3E -> listOf(0x7E, 0x00)
            0x10 -> listOf(0x50, b.getOrElse(1) { 1 }, 0x00, 0x32, 0x01, 0xF4)
            0x19 -> if (b.getOrNull(1) == 0x04 || b.getOrNull(1) == 0x06) {
                // Snapshot / extended data for the ABS wheel-speed code
                val code = DtcDecoder.decode(b[2], b[3])
                val entry = m.dtcs.firstOrNull { it.first == code && it.second == b[4] } ?: return neg(0x31)
                val head = listOf(0x59, b[1], b[2], b[3], b[4], entry.third)
                if (b[1] == 0x04) head + listOf(0x01, 0x04, 0xF4, 0x0D, 0x2A, 0xF4, 0x0C, 0x0B, 0xB8, 0xF4, 0x42, 0x36, 0xB0, 0xDD, 0x01, 0x01, 0x86, 0xA0)
                else when (b[5]) { 0x01 -> head + listOf(0x01, 0x03); 0x02 -> head + listOf(0x02, 0x28); else -> neg(0x31) }
            } else if (b.getOrNull(1) != 0x02) neg(0x12) else {
                val codes = if (tx == 0x7E0) stored.map { Triple(it, 0x00, 0x2F) } + pending.map { Triple(it, 0x00, 0x24) } else m.dtcs
                listOf(0x59, 0x02, 0xFF) + codes.flatMap { (c, ftb, st) -> DtcDecoder.encode(c).let { listOf(it.first, it.second, ftb, st) } }
            }
            0x14 -> {
                m.dtcs.clear()
                if (tx == 0x7E0) { stored.clear(); pending.clear(); monitorsComplete = false }
                listOf(0x54)
            }
            0x22 -> {
                val did = (b.getOrElse(1) { 0 } shl 8) or b.getOrElse(2) { 0 }
                fun ascii(s: String) = listOf(0x62, b[1], b[2]) + s.map { it.code }
                when (did) {
                    0xF187 -> ascii(m.partNumber)
                    0xF190 -> ascii(vin)
                    0xF195 -> ascii("SW 2.4.1")
                    0xF197 -> ascii(m.name)
                    0xF18C -> ascii("SN%06d".format(tx * 37))
                    0x1E1C -> if (tx in 0x7E0..0x7E1) {
                        val raw = ((62 + noise(1.5) + t() / 30.0) * 16).roundToInt().coerceIn(0, 65535)
                        listOf(0x62, 0x1E, 0x1C, raw shr 8, raw and 0xFF)
                    } else neg(0x31)
                    else -> neg(0x31)
                }
            }
            else -> neg(0x11)
        }
    }

    private fun supportBitmap(base: Int, supported: Set<Int>): IntArray? {
        if (base != 0 && base !in supported) return null
        val bits = IntArray(4)
        for (i in 0 until 32) if (base + i + 1 in supported) bits[i / 8] = bits[i / 8] or (1 shl (7 - i % 8))
        return bits
    }

    private fun mode06(mid: Int): Pair<String, List<Int>>? {
        val mids = setOf(0x01, 0x21, 0xA1, 0xA2, 0xA3)
        if (mid % 0x20 == 0) return supportBitmap(mid, mids + 0x20 + 0x40 + 0x60 + 0x80 + 0xA0)?.let { engine to listOf(0x46, mid) + it.toList() }
        fun test(tid: Int, uas: Int, v: Int, mn: Int, mx: Int) =
            listOf(mid, tid, uas, v shr 8, v and 0xFF, mn shr 8, mn and 0xFF, mx shr 8, mx and 0xFF)
        val tests: List<Int> = when (mid) {
            0x01 -> test(0x01, 0x0A, 0x0E10, 0x0C00, 0x1000) + test(0x05, 0x10, 0x0048, 0x0000, 0x0096)
            0x21 -> test(0x80, 0x24, 0x0052, 0x0000, if (stored.contains("P0420")) 0x0040 else 0x00C8)
            0xA1 -> test(0x0B, 0x24, 0x0003, 0x0000, 0xFFFF) + test(0x0C, 0x24, 0x0001, 0x0000, 0xFFFF)
            0xA2 -> test(0x0B, 0x24, if (stored.contains("P0301")) 0x0021 else 0, 0x0000, 0xFFFF) + test(0x0C, 0x24, if (stored.contains("P0301")) 0x0018 else 0, 0x0000, 0xFFFF)
            0xA3 -> test(0x0B, 0x24, 0x0000, 0x0000, 0xFFFF) + test(0x0C, 0x24, 0x0000, 0x0000, 0xFFFF)
            else -> return null
        }
        return engine to listOf(0x46) + tests
    }

    private fun mode09(type: Int): Pair<String, List<Int>>? = when (type) {
        0x00 -> engine to listOf(0x49, 0x00) + supportBitmap(0, setOf(0x02, 0x04, 0x06, 0x08, 0x0A))!!.toList()
        0x02 -> engine to listOf(0x49, 0x02, 0x01) + vin.map { it.code }
        0x04 -> engine to listOf(0x49, 0x04, 0x01) + "OPENOBD-DEMO-1.0".padEnd(16, '\u0000').map { it.code }
        0x06 -> engine to listOf(0x49, 0x06, 0x01, 0x1B, 0x3C, 0x9A, 0x7F)
        0x08 -> engine to listOf(0x49, 0x08, 0x10) + listOf(312, 1048, 287, 312, 0, 0, 301, 312, 0, 0, 155, 312, 0, 0, 98, 312)
            .flatMap { listOf(it shr 8, it and 0xFF) }
        0x0A -> engine to listOf(0x49, 0x0A, 0x01) + "ECM\u0000-EngineControl".padEnd(20, '\u0000').map { it.code }
        else -> null
    }

    /** Pre-CAN service 09: every message carries a sequence number and 4 data bytes. */
    private fun legacyMode09(type: Int): List<Pair<String, List<Int>>>? {
        fun messages(data: List<Int>) = data.chunked(4).mapIndexed { i, c -> engine to listOf(0x49, type, i + 1) + c + List(4 - c.size) { 0 } }
        return when (type) {
            0x00 -> listOf(engine to listOf(0x49, 0x00, 0x01) + supportBitmap(0, setOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06))!!.toList())
            0x01 -> listOf(engine to listOf(0x49, 0x01, 0x05))
            0x02 -> messages(listOf(0, 0, 0) + vin.map { it.code })
            0x03 -> listOf(engine to listOf(0x49, 0x03, 0x04))
            0x04 -> messages("OPENOBD-DEMO-1.0".map { it.code })
            0x05 -> listOf(engine to listOf(0x49, 0x05, 0x01))
            0x06 -> messages(listOf(0x1B, 0x3C, 0x9A, 0x7F))
            else -> null
        }
    }

    // ------------------------------------------------------------------ live values

    private fun t(): Double = (clock() - startedAt) / 1000.0

    private fun noise(amount: Double) = random.nextDouble(-amount, amount)

    private fun speedAt(time: Double) = max(0.0, 52.0 + 62.0 * sin(time * 2 * PI / 50.0) + 8 * sin(time * 2 * PI / 9.0))

    private fun pidValue(pid: Int, ecu: String): IntArray? {
        val time = t()
        val speed = speedAt(time)
        val accel = speedAt(time + 0.5) - speedAt(time - 0.5)
        val gear = when { speed < 1 -> 0; speed < 18 -> 1; speed < 35 -> 2; speed < 55 -> 3; speed < 75 -> 4; speed < 95 -> 5; else -> 6 }
        val ratio = doubleArrayOf(0.0, 120.0, 72.0, 50.0, 38.0, 31.0, 26.0)[gear]
        val rpm = if (gear == 0) 780 + noise(25.0) else min(6500.0, 900 + speed * ratio + noise(30.0))
        val throttle = (if (accel > 0) 18 + accel * 9 else 11.0).coerceIn(9.0, 95.0) + noise(1.0)
        val load = (throttle * 0.9 + 14 + noise(2.0)).coerceIn(10.0, 100.0)
        val coolant = min(91.0, 22 + time * 0.6) + noise(0.4)
        val maf = rpm * load / 9000.0 + noise(0.2)
        fun b(v: Double) = v.roundToInt().coerceIn(0, 255)
        fun w(v: Double) = v.roundToInt().coerceIn(0, 65535).let { intArrayOf(it shr 8, it and 0xFF) }
        if (ecu == trans) return when (pid) {
            0x01 -> intArrayOf(0x00, 0x00, 0x00, 0x00)
            0x0D -> intArrayOf(b(speed))
            else -> null
        }
        return when (pid) {
            0x01 -> {
                val mil = if (stored.isNotEmpty()) 0x80 or stored.size else 0
                intArrayOf(mil, 0x07, 0x65, if (monitorsComplete) 0x00 else 0x65)
            }
            0x03 -> intArrayOf(if (coolant < 40) 0x01 else 0x02, 0x00)
            0x04 -> intArrayOf(b(load * 2.55))
            0x05 -> intArrayOf(b(coolant + 40))
            0x06 -> intArrayOf(b(128 + (2.5 + noise(2.0)) * 1.28))
            0x07 -> intArrayOf(b(128 + 4.7 * 1.28))
            0x0B -> intArrayOf(b(28 + load * 0.7))
            0x0C -> w(rpm * 4)
            0x0D -> intArrayOf(b(speed))
            0x0E -> intArrayOf(b((12 + speed / 8 + noise(1.0) + 64) * 2))
            0x0F -> intArrayOf(b(31 + noise(0.5) + 40))
            0x10 -> w(maf * 100)
            0x11 -> intArrayOf(b(throttle * 2.55))
            0x13 -> intArrayOf(0x03)
            0x14 -> intArrayOf(b((0.45 + 0.4 * sin(time * 7)) * 200), b(128 + noise(4.0)))
            0x15 -> intArrayOf(b((0.68 + noise(0.03)) * 200), 0xFF)
            0x1C -> intArrayOf(0x01)
            0x1F -> w(time)
            0x21 -> w(if (stored.isNotEmpty()) 412.0 else 0.0)
            0x2F -> intArrayOf(b((63 - time / 120.0) * 2.55))
            0x30 -> intArrayOf(if (monitorsComplete) 87 else 1)
            0x31 -> w(if (monitorsComplete) 2387.0 else time / 60.0)
            0x33 -> intArrayOf(101)
            0x3C -> w((min(650.0, 150 + time * 4) + noise(3.0) + 40) * 10)
            0x41 -> intArrayOf(0x00, 0x07, 0x65, 0x24)
            0x42 -> w((14.1 + noise(0.08)) * 1000)
            0x43 -> w(load * 2.55 * 0.85)
            0x44 -> w((if (coolant < 40) 0.92 else 1.0) * 32768)
            0x45 -> intArrayOf(b(abs(throttle - 8) * 2.55))
            0x46 -> intArrayOf(b(19.0 + 40))
            0x47 -> intArrayOf(b((throttle + 16) * 2.55))
            0x49 -> intArrayOf(b((throttle * 0.8 + 15) * 2.55))
            0x4A -> intArrayOf(b((throttle * 0.4 + 7.5) * 2.55))
            0x4C -> intArrayOf(b(throttle * 2.55))
            0x4D -> w(if (stored.isNotEmpty()) 318.0 else 0.0)
            0x4E -> w(if (monitorsComplete) 4120.0 else time / 60.0)
            0x51 -> intArrayOf(0x01)
            0x5C -> intArrayOf(b(min(98.0, 20 + time * 0.45) + 40))
            0x5E -> w(maf * 3600 / (14.7 * 745) * 20)
            0xA6 -> (1_284_512 + (time * speed / 360.0).toInt()).let { intArrayOf(0, it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }
            else -> null
        }
    }

    // ------------------------------------------------------------------ framing

    private fun hex(v: Int) = "%02X".format(v)

    private fun join(parts: List<String>) = if (spaces) parts.joinToString(" ") else parts.joinToString("")

    private fun format(ecu: String, payload: List<Int>): List<String> {
        if (legacy) {
            if (!headers) return listOf(join(payload.map(::hex)))
            val src = ecu.toInt(16)
            val head = when (protocol) {
                ObdProtocol.J1850_PWM -> listOf(0x41, 0x6B, src)
                ObdProtocol.ISO_14230_4_KWP_5BAUD, ObdProtocol.ISO_14230_4_KWP_FAST -> listOf(0x80 or payload.size, 0xF1, src)
                else -> listOf(0x48, 0x6B, src)
            }
            val frame = head + payload
            val check = if (protocol == ObdProtocol.J1850_PWM || protocol == ObdProtocol.J1850_VPW) j1850Crc(frame) else frame.sum() and 0xFF
            return listOf(join((frame + check).map(::hex)))
        }
        if (protocol.is29Bit && headers) {
            return formatCan(payload) { listOf("18", "DA", "F1", ecu) }
        }
        return formatCan(payload) { listOf(ecu) }
    }

    /** SAE J1850 CRC-8 (polynomial 0x1D, init 0xFF, final xor 0xFF). */
    private fun j1850Crc(bytes: List<Int>): Int {
        var crc = 0xFF
        for (b in bytes) {
            crc = crc xor b
            repeat(8) { crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x1D) and 0xFF else (crc shl 1) and 0xFF }
        }
        return crc xor 0xFF
    }

    private fun formatCan(payload: List<Int>, headerTokens: () -> List<String>): List<String> {
        if (!headers) {
            if (payload.size <= 7) return listOf(join(payload.map(::hex)))
            val lines = mutableListOf("%03X".format(payload.size))
            lines += "0: " + join(payload.take(6).map(::hex))
            payload.drop(6).chunked(7).forEachIndexed { i, c -> lines += "%X: ".format((i + 1) % 16) + join(c.map(::hex)) }
            return lines
        }
        if (payload.size <= 7) {
            val frame = listOf(payload.size) + payload + List(7 - payload.size) { 0 }
            return listOf(join(headerTokens() + frame.map(::hex)))
        }
        val lines = mutableListOf<String>()
        val first = listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6)
        lines += join(headerTokens() + first.map(::hex))
        payload.drop(6).chunked(7).forEachIndexed { i, c ->
            val frame = listOf(0x20 or ((i + 1) and 0x0F)) + c + List(7 - c.size) { 0 }
            lines += join(headerTokens() + frame.map(::hex))
        }
        return lines
    }
}
