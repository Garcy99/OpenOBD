package com.openobd.core.brand

import com.openobd.core.elm.EcuMessage
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ObdResult
import com.openobd.core.obd.DtcDecoder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which diagnostic dialect a module answered with. */
enum class DiagProtocol(val label: String) { UDS("UDS (ISO 14229)"), KWP("KWP2000 (ISO 14230)"), OBD("OBD-II services") }

/** A trouble code read from a module, including the UDS failure type byte and status flags when available. */
data class ModuleDtc(val code: String, val failureType: Int?, val status: Int?, val protocol: DiagProtocol) {
    val fullCode: String get() = failureType?.let { "$code-%02X".format(it) } ?: code
    val failureTypeText: String? get() = failureType?.let { FailureTypes.describe(it) }

    val isActive: Boolean get() = status?.let { protocol == DiagProtocol.UDS && it and 0x01 != 0 } ?: false
    val isConfirmed: Boolean get() = status?.let { if (protocol == DiagProtocol.UDS) it and 0x08 != 0 else true } ?: true
    val isPending: Boolean get() = protocol == DiagProtocol.UDS && (status ?: 0) and 0x04 != 0
    val warningLamp: Boolean get() = protocol == DiagProtocol.UDS && (status ?: 0) and 0x80 != 0

    /** Human readable status summary. */
    val statusText: String
        get() {
            if (protocol != DiagProtocol.UDS || status == null) return "Stored"
            val parts = mutableListOf<String>()
            if (status and 0x01 != 0) parts += "Active now"
            if (status and 0x08 != 0) parts += "Confirmed"
            if (status and 0x04 != 0) parts += "Pending"
            if (status and 0x80 != 0) parts += "Warning lamp on"
            if (parts.isEmpty() && status and 0x20 != 0) parts += "Intermittent / history"
            if (parts.isEmpty()) parts += "Stored"
            return parts.joinToString(" · ")
        }
}

data class ModuleScanResult(
    val module: EcuModule,
    val online: Boolean,
    val protocol: DiagProtocol? = null,
    val dtcs: List<ModuleDtc> = emptyList(),
    val error: String? = null,
)

data class ClearOutcome(val success: Boolean, val message: String)

/** ISO 14229 data identifiers that are standardised across manufacturers. */
object StandardDids {
    val identification: List<Pair<Int, String>> = listOf(
        0xF187 to "Spare part number",
        0xF18A to "Supplier",
        0xF18C to "ECU serial number",
        0xF189 to "Software version",
        0xF195 to "Supplier software version",
        0xF191 to "Hardware number",
        0xF193 to "Hardware version",
        0xF197 to "System name",
        0xF190 to "VIN",
        0xF18B to "Manufacturing date",
    )
}

/** SAE J2012 / ISO 15031-6 failure type byte (the "-XX" suffix of UDS codes). */
object FailureTypes {
    private val names = mapOf(
        0x00 to "No sub-type information", 0x01 to "General electrical failure", 0x02 to "General signal failure",
        0x03 to "FM / PWM failure", 0x04 to "System internal failure", 0x05 to "System programming failure",
        0x06 to "Algorithm based failure", 0x07 to "Mechanical failure", 0x08 to "Bus signal / message failure",
        0x09 to "Component failure",
        0x11 to "Circuit short to ground", 0x12 to "Circuit short to battery", 0x13 to "Circuit open",
        0x14 to "Circuit short to ground or open", 0x15 to "Circuit short to battery or open", 0x16 to "Circuit voltage below threshold",
        0x17 to "Circuit voltage above threshold", 0x18 to "Circuit current below threshold", 0x19 to "Circuit current above threshold",
        0x1A to "Circuit resistance below threshold", 0x1B to "Circuit resistance above threshold", 0x1C to "Circuit voltage out of range",
        0x1D to "Circuit current out of range", 0x1E to "Circuit resistance out of range", 0x1F to "Circuit intermittent",
        0x21 to "Signal amplitude < minimum", 0x22 to "Signal amplitude > maximum", 0x23 to "Signal stuck low",
        0x24 to "Signal stuck high", 0x25 to "Signal shape / waveform failure", 0x26 to "Signal rate of change below threshold",
        0x27 to "Signal rate of change above threshold", 0x28 to "Signal bias level out of range / zero adjustment failure",
        0x29 to "Signal invalid", 0x2F to "Signal erratic",
        0x31 to "No signal", 0x32 to "Signal low time < minimum", 0x33 to "Signal low time > maximum", 0x34 to "Signal high time < minimum",
        0x35 to "Signal high time > maximum", 0x36 to "Signal frequency too low", 0x37 to "Signal frequency too high",
        0x38 to "Signal frequency incorrect", 0x39 to "Incorrect has too few pulses", 0x3A to "Incorrect has too many pulses",
        0x41 to "General checksum failure", 0x42 to "General memory failure", 0x43 to "Special memory failure",
        0x44 to "Data memory failure", 0x45 to "Program memory failure", 0x46 to "Calibration / parameter memory failure",
        0x47 to "Watchdog / safety µC failure", 0x48 to "Supervision software failure", 0x49 to "Internal electronic failure",
        0x4A to "Incorrect component installed", 0x4B to "Over temperature",
        0x51 to "Not programmed", 0x52 to "Not activated", 0x53 to "Deactivated", 0x54 to "Missing calibration",
        0x55 to "Not configured", 0x56 to "Invalid / incompatible configuration", 0x57 to "Invalid / incompatible software component",
        0x61 to "Signal calculation failure", 0x62 to "Signal compare failure", 0x63 to "Circuit / component protection time-out",
        0x64 to "Signal plausibility failure", 0x65 to "Signal has too few transitions / events", 0x66 to "Signal has too many transitions / events",
        0x67 to "Signal incorrect after event", 0x68 to "Event information",
        0x71 to "Actuator stuck", 0x72 to "Actuator stuck open", 0x73 to "Actuator stuck closed", 0x74 to "Actuator slipping",
        0x75 to "Emergency position not reachable", 0x76 to "Wrong mounting position", 0x77 to "Commanded position not reachable",
        0x78 to "Alignment or adjustment incorrect", 0x79 to "Mechanical linkage failure", 0x7A to "Fluid leak or seal failure",
        0x7B to "Low fluid level",
        0x81 to "Invalid serial data received", 0x82 to "Alive / sequence counter incorrect / not updated",
        0x83 to "Value of signal protection calculation incorrect", 0x84 to "Signal below allowable range",
        0x85 to "Signal above allowable range", 0x86 to "Signal invalid", 0x87 to "Missing message", 0x88 to "Bus off",
        0x8F to "Erratic",
        0x91 to "Parametric", 0x92 to "Performance or incorrect operation", 0x93 to "No operation", 0x94 to "Unexpected operation",
        0x95 to "Incorrect assembly", 0x96 to "Component internal failure", 0x97 to "Component or system operation obstructed or blocked",
        0x98 to "Component or system over temperature",
    )

    fun describe(ftb: Int): String = names[ftb] ?: if (ftb >= 0xF0) "Manufacturer-specific failure type" else "Failure type 0x%02X".format(ftb)
}

/**
 * Talks to individual control modules through an ELM327 by temporarily switching the CAN header and
 * receive filter. Every operation runs inside [Elm327.exclusive] and restores OBD defaults afterwards,
 * so live data polling can safely continue between operations.
 */
class ModuleClient(private val elm: Elm327) {

    val supported: Boolean get() = elm.protocol.isCan

    private suspend fun Elm327.Locked.select(m: EcuModule) {
        if (m.is29Bit) {
            at("ATCP18")
            at("ATSH DA%02XF1".format(m.tx))
            if (!at("ATCRA 18DAF1%02X".format(m.tx))) { at("ATCF 18DAF1%02X".format(m.tx)); at("ATCM 1FFFFFFF") }
            at("ATFCSH 18DA%02XF1".format(m.tx))
        } else {
            at("ATSH %03X".format(m.tx))
            if (!at("ATCRA %03X".format(m.rx))) { at("ATCF %03X".format(m.rx)); at("ATCM 7FF") }
            at("ATFCSH %03X".format(m.tx))
        }
        at("ATFCSD 300000")
        at("ATFCSM1")
    }

    private suspend fun Elm327.Locked.restore() {
        at("ATFCSM0")
        at("ATCRA")
        if (elm.protocol.is29Bit) { at("ATCP18"); at("ATSH DB33F1") } else at("ATSH 7DF")
        at("ATST%02X".format(elm.configuredTimeout ?: 0x32))
    }

    private suspend fun <T> withModule(m: EcuModule, block: suspend Elm327.Locked.() -> T): T = elm.exclusive {
        try {
            select(m)
            block()
        } finally {
            withContext(NonCancellable) { restore() }
        }
    }

    /** Sends [req] and returns the module's reply payloads, retrying once when the module answers "response pending". */
    private suspend fun Elm327.Locked.request(req: String, timeout: Long = 4000): List<EcuMessage> {
        repeat(3) {
            val r = query(req, timeout)
            val msgs = (r as? ObdResult.Data)?.messages.orEmpty()
            val pending = msgs.isNotEmpty() && msgs.all { it.isNegative && it.data.getOrNull(2) == 0x78 }
            if (!pending) return msgs
            delay(800)
        }
        return emptyList()
    }

    private fun positive(msgs: List<EcuMessage>, service: Int) = msgs.firstOrNull { it.service == service + 0x40 }
    private fun negative(msgs: List<EcuMessage>, service: Int) = msgs.firstOrNull { it.isNegative && it.data.getOrNull(1) == service }

    // ------------------------------------------------------------------ reading codes

    private suspend fun Elm327.Locked.readDtcsLocked(): Pair<DiagProtocol, List<ModuleDtc>>? {
        // UDS ReadDTCInformation / reportDTCByStatusMask
        val uds = request("1902FF")
        positive(uds, 0x19)?.let { return DiagProtocol.UDS to parseUds(it.data) }
        // KWP2000 ReadDiagnosticTroubleCodesByStatus
        val kwp = request("1802FF00")
        positive(kwp, 0x18)?.let { return DiagProtocol.KWP to parseKwp(it.data) }
        // Physically addressed OBD service 03 (works on engine / transmission)
        val obd = request("03")
        positive(obd, 0x03)?.let { msg ->
            return DiagProtocol.OBD to DtcDecoder.parseResponse(msg.data, true).map { ModuleDtc(it, null, null, DiagProtocol.OBD) }
        }
        // Module present but refused everything: report it online with no readable codes.
        if (uds.isNotEmpty() || kwp.isNotEmpty() || obd.isNotEmpty()) return DiagProtocol.UDS to emptyList()
        return null
    }

    fun parseUds(d: IntArray): List<ModuleDtc> {
        val out = mutableListOf<ModuleDtc>()
        var i = 3 // 59 02 <availability mask>
        while (i + 3 < d.size) {
            val status = d[i + 3]
            if (status != 0 && !(d[i] == 0 && d[i + 1] == 0)) {
                out += ModuleDtc(DtcDecoder.decode(d[i], d[i + 1]), d[i + 2], status, DiagProtocol.UDS)
            }
            i += 4
        }
        return out
    }

    fun parseKwp(d: IntArray): List<ModuleDtc> {
        val out = mutableListOf<ModuleDtc>()
        var i = 2 // 58 <count>
        while (i + 2 < d.size) {
            if (!(d[i] == 0 && d[i + 1] == 0)) out += ModuleDtc(DtcDecoder.decode(d[i], d[i + 1]), null, d[i + 2], DiagProtocol.KWP)
            i += 3
        }
        return out
    }

    suspend fun scan(m: EcuModule): ModuleScanResult = try {
        withModule(m) {
            val r = readDtcsLocked()
            if (r == null) ModuleScanResult(m, online = false)
            else ModuleScanResult(m, online = true, protocol = r.first, dtcs = r.second)
        }
    } catch (e: Exception) {
        ModuleScanResult(m, online = false, error = e.message)
    }

    // ------------------------------------------------------------------ clearing

    suspend fun clear(m: EcuModule): ClearOutcome = withModule(m) {
        suspend fun tryClear(): Pair<Boolean, EcuMessage?> {
            val uds = request("14FFFFFF", 8000)
            if (positive(uds, 0x14) != null) return true to null
            val kwp = request("14FF00", 8000)
            if (positive(kwp, 0x14) != null) return true to null
            val obd = request("04", 8000)
            if (positive(obd, 0x04) != null) return true to null
            return false to (negative(uds, 0x14) ?: negative(kwp, 0x14) ?: negative(obd, 0x04))
        }
        var (ok, neg) = tryClear()
        if (!ok && neg != null && neg.data.getOrNull(2) in setOf(0x22, 0x7F, 0x33)) {
            // Some modules only allow clearing in the extended diagnostic session.
            request("1003")
            val retry = tryClear(); ok = retry.first; neg = retry.second
            request("1001")
        }
        if (ok) ClearOutcome(true, "Codes cleared in ${m.name}.")
        else ClearOutcome(false, neg?.let { "The module refused: ${nrcText(it.data.getOrNull(2) ?: 0)}" } ?: "The module did not answer.")
    }

    // ------------------------------------------------------------------ identification

    suspend fun readIdentification(m: EcuModule): List<Pair<String, String>> = withModule(m) {
        val out = mutableListOf<Pair<String, String>>()
        for ((did, label) in StandardDids.identification) {
            val msgs = request("22%04X".format(did), 3000)
            val p = positive(msgs, 0x22) ?: continue
            if (p.data.size <= 3) continue
            out += label to formatDidValue(p.data.copyOfRange(3, p.data.size))
        }
        out
    }

    /** Reads any data identifier. Returns the raw bytes after the DID echo, or throws with the NRC text. */
    suspend fun readDid(m: EcuModule, did: Int): IntArray = withModule(m) {
        val msgs = request("22%04X".format(did), 4000)
        positive(msgs, 0x22)?.let { return@withModule it.data.copyOfRange(3, it.data.size) }
        negative(msgs, 0x22)?.let { throw IllegalStateException(nrcText(it.data.getOrNull(2) ?: 0)) }
        throw IllegalStateException("No answer")
    }

    // ------------------------------------------------------------------ discovery

    /**
     * Probes every 11-bit diagnostic request ID in [range] with TesterPresent and returns the modules that answer.
     * Takes roughly 15-40 s depending on the adapter.
     */
    suspend fun discover(range: IntRange = 0x700..0x7F7, progress: (Int, Int) -> Unit = { _, _ -> }): List<EcuModule> = elm.exclusive {
        val found = mutableListOf<EcuModule>()
        try {
            at("ATST19") // ~100 ms per absent address
            if (!at("ATCRA 7XX")) { at("ATCF 700"); at("ATCM 700") }
            val total = range.count()
            range.forEachIndexed { index, tx ->
                if (index % 4 == 0) progress(index, total)
                if (tx in 0x7DF..0x7DF) return@forEachIndexed // functional broadcast address
                at("ATSH %03X".format(tx))
                val msgs = (query("3E00", 1500) as? ObdResult.Data)?.messages.orEmpty()
                val answer = msgs.firstOrNull { it.service == 0x7E || (it.isNegative && it.data.getOrNull(1) == 0x3E) }
                val rx = answer?.ecu?.toIntOrNull(16)
                if (answer != null && rx != null && rx != tx && found.none { it.rx == rx }) {
                    found += EcuModule("Module %03X".format(tx), "%03X".format(tx), tx, rx)
                }
            }
            progress(total, total)
        } finally {
            withContext(NonCancellable) { restore() }
        }
        found
    }

    // ------------------------------------------------------------------ custom / manufacturer PIDs

    private fun moduleFor(p: CustomPid): EcuModule? {
        val h = p.headerId ?: return null
        return if (h <= 0x7FF) EcuModule(p.name, p.name, h, p.receiveId ?: (h + 8))
        else EcuModule(p.name, p.name, (h shr 8) and 0xFF, 0xF1, is29Bit = true)
    }

    private fun extract(p: CustomPid, msgs: List<EcuMessage>): IntArray? {
        val req = p.requestBytes
        val m = msgs.firstOrNull { msg ->
            msg.service == req[0] + 0x40 && msg.data.size >= req.size && (1 until req.size).all { msg.data[it] == req[it] }
        } ?: return null
        return m.data.copyOfRange(req.size, m.data.size)
    }

    /** Reads custom PIDs, switching headers as needed. Returns raw data bytes by sensor key. */
    suspend fun readCustom(pids: List<CustomPid>): Map<String, IntArray> {
        val out = HashMap<String, IntArray>()
        for ((_, group) in pids.groupBy { it.header.trim().uppercase() to it.receive.trim().uppercase() }) {
            val module = moduleFor(group.first())
            if (module == null || !elm.protocol.isCan) {
                for (p in group) {
                    val r = elm.query(p.request, 3000) as? ObdResult.Data ?: continue
                    extract(p, r.messages)?.let { out[p.key] = it }
                }
            } else {
                withModule(module) {
                    for (p in group) extract(p, request(p.request, 3000))?.let { out[p.key] = it }
                }
            }
        }
        return out
    }

    companion object {
        fun nrcText(nrc: Int): String = when (nrc) {
            0x10 -> "general reject"
            0x11 -> "service not supported"
            0x12 -> "sub-function not supported"
            0x13 -> "incorrect message length"
            0x22 -> "conditions not correct (try engine off, ignition on)"
            0x31 -> "request out of range / identifier not supported"
            0x33 -> "security access denied (protected by the gateway)"
            0x7E, 0x7F -> "not supported in the current session"
            else -> "error 0x%02X".format(nrc)
        }

        fun formatDidValue(bytes: IntArray): String {
            val printable = bytes.count { it in 0x20..0x7E || it == 0 }
            return if (bytes.isNotEmpty() && printable == bytes.size && bytes.any { it in 0x30..0x7A }) {
                bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()
            } else bytes.joinToString(" ") { "%02X".format(it) }
        }
    }
}
