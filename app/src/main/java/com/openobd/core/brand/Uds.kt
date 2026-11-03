package com.openobd.core.brand

import com.openobd.core.elm.EcuMessage
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ObdResult
import com.openobd.core.obd.DtcDecoder
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Which diagnostic dialect a module answered with. */
enum class DiagProtocol(val label: String) { UDS("UDS (ISO 14229)"), KWP("KWP2000 (ISO 14230)"), GMLAN("GMLAN (GM)"), OBD("OBD-II services") }

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

/** One value inside a UDS DTC snapshot record. */
data class SnapshotValue(val did: Int, val name: String, val value: String)

data class DtcSnapshot(val record: Int, val values: List<SnapshotValue>, val undecoded: String? = null)

/** Freeze frame ("snapshot") and extended data (counters) for one module trouble code. */
data class DtcDetails(val snapshots: List<DtcSnapshot>, val extended: List<Pair<Int, String>>)

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

    /** What to check first, based on the failure type alone (applies to any module and brand). */
    fun hint(ftb: Int): String? = when (ftb) {
        0x11, 0x14 -> "Look for a wire chafed against the body or a short to ground, and check the connector for water or corrosion."
        0x12, 0x15 -> "Look for a wire shorted to a power feed and check the connector; also test the component itself."
        0x13 -> "An open circuit: unplugged or loose connector, broken wire or a failed component (sensor, motor, squib)."
        0x16, 0x18, 0x1A -> "Value below normal: check battery and charging voltage, grounds and the connector first."
        0x17, 0x19, 0x1B -> "Value above normal: check for shorts to power, then the component."
        0x1C, 0x1D, 0x1E -> "Out of range: check wiring and connectors, then the component."
        0x1F, 0x2F, 0x8F -> "Intermittent: wiggle-test the harness and connector while watching live data; look for corrosion or loose pins."
        0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27, 0x28 -> "Signal problem: check the sensor, its mounting and air gap, and the wiring."
        0x29, 0x64 -> "Signal implausible: compare against related sensors in live data; often a failing sensor or wiring fault."
        0x31 -> "No signal: sensor unplugged or failed, broken wire, or (wheel speed sensors) debris or damage on the tone ring."
        0x36, 0x37, 0x38, 0x39, 0x3A, 0x65, 0x66 -> "Pulse / frequency signal wrong: check the sensor gap, tone wheel and wiring."
        in 0x41..0x49 -> "Internal module fault. Check its power supply and grounds first; if those are good the module may need repair or replacement."
        0x4A, 0x56, 0x57 -> "Wrong or incompatible part / software installed; usually after a replacement."
        0x4B, 0x98 -> "Over temperature: check cooling / airflow around the component and the load it drives."
        in 0x51..0x55 -> "The module needs programming or configuration (common after replacing a module or battery work)."
        0x71, 0x72, 0x73, 0x77, 0x97 -> "Actuator stuck or can't reach position: check for mechanical binding, then the motor and its wiring."
        0x7A, 0x7B -> "Check fluid level and look for leaks."
        0x81, 0x82, 0x83, 0x84, 0x85, 0x86 -> "Bad data received from another module: check that module's codes first."
        0x87 -> "Missing message: another module stopped sending. Check that module, its fuse/power and the network wiring."
        0x88 -> "Bus off: CAN wiring fault or a module disturbing the network; check connectors, termination and recently fitted accessories."
        0x92, 0x93, 0x94 -> "Component not working as expected: check its supply, ground and mechanical condition."
        0x96 -> "Component internal failure: usually needs replacement after confirming power and ground."
        else -> null
    }
}

/**
 * Talks to individual control modules through an ELM327 by temporarily switching the CAN header and
 * receive filter. Every operation runs inside [Elm327.exclusive] and restores OBD defaults afterwards,
 * so live data polling can safely continue between operations.
 */
class ModuleClient(private val elm: Elm327) {
    /** Also try GM's GMLAN diagnostic services (Opel, Vauxhall, Chevrolet, Holden...) on modules that ignore UDS. */
    var gmlan: Boolean = false

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
        at("ATCAF1")
        at("ATFCSM0")
        at("ATCRA")
        if (elm.protocol.is29Bit) { at("ATCP18"); at("ATSH DB33F1") } else at("ATSH 7DF")
        at("ATST%02X".format(elm.configuredTimeout ?: 0x32))
    }

    private suspend fun <T> withModule(m: EcuModule, block: suspend Elm327.Locked.() -> T): T = elm.exclusive {
        check(supported) { NOT_CAN }
        try {
            currentModule = m
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
        // GMLAN ReadDiagnosticInformation by status mask; codes arrive as separate frames on the UUDT ID
        if (gmlan) currentModule?.let { readGmlanLocked(it) }?.let { return DiagProtocol.GMLAN to it }
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

    /** Module selected by [withModule]; GMLAN replies arrive on a different CAN ID that is derived from it. */
    private var currentModule: EcuModule? = null

    /**
     * GMLAN \$A9 \$81 (report DTCs by status mask). Positive answers are UUDT frames, one code each:
     * `81 <DTC hi> <DTC lo> <failure type> <status>`, ending with code 0000. They come on the module's
     * UUDT ID (0x5xx) without ISO-TP framing, so CAN auto formatting is switched off while listening.
     */
    private suspend fun Elm327.Locked.readGmlanLocked(m: EcuModule): List<ModuleDtc>? {
        val uudt = gmlanUudtId(m) ?: return null
        if (!at("ATCRA %03X".format(uudt))) { at("ATCF %03X".format(uudt)); at("ATCM 7FF") }
        val lines = try {
            at("ATCAF0")
            command("03A98112", 4000)
        } catch (e: com.openobd.core.elm.ElmException) {
            emptyList()
        } finally {
            at("ATCAF1")
            if (!at("ATCRA %03X".format(m.rx))) { at("ATCF %03X".format(m.rx)); at("ATCM 7FF") }
        }
        return parseGmlan(lines, uudt)
    }

    fun parseGmlan(lines: List<String>, uudt: Int): List<ModuleDtc>? {
        var answered = false
        val out = mutableListOf<ModuleDtc>()
        for (line in lines) {
            val t = com.openobd.core.elm.ResponseParser.tokenize(line, com.openobd.core.elm.ObdProtocol.ISO_15765_4_CAN_11_500, true) ?: continue
            if (t.size < 6 || t[0].length != 3 || t[0].toIntOrNull(16) != uudt) continue
            val d = t.drop(1).map { it.toInt(16) }
            if (d[0] != 0x81) continue
            answered = true
            if (d[1] == 0 && d[2] == 0) break // end of list
            out += ModuleDtc(DtcDecoder.decode(d[1], d[2]), d[3], d[4], DiagProtocol.GMLAN)
        }
        return if (answered) out.distinctBy { it.code to it.failureType } else null
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

    // ------------------------------------------------------------------ DTC snapshot / extended data

    /** Reads freeze frame (UDS 19 04) and extended data records (UDS 19 06) for a UDS module code. */
    suspend fun readDtcDetails(m: EcuModule, dtc: ModuleDtc): DtcDetails = withModule(m) {
        val (a, b) = DtcDecoder.encode(dtc.code)
        val dtcHex = "%02X%02X%02X".format(a, b, dtc.failureType ?: 0)
        val snaps = positive(request("1904${dtcHex}FF", 4000), 0x19)?.let { parseSnapshots(it.data) } ?: emptyList()
        val ext = mutableListOf<Pair<Int, String>>()
        for (rec in 0x01..0x10) {
            val msg = positive(request("1906$dtcHex%02X".format(rec), 2000), 0x19) ?: continue
            if (msg.data.size <= 7) continue
            val bytes = msg.data.copyOfRange(7, msg.data.size) // 59 06 DTC(3) status record#
            ext += msg.data[6] to (if (bytes.size == 1) bytes[0].toString() else bytes.joinToString(" ") { "%02X".format(it) })
        }
        DtcDetails(snaps, ext)
    }

    /** Parses a 59 04 response. Values for standard DIDs are decoded; manufacturer DIDs are shown raw. */
    fun parseSnapshots(d: IntArray): List<DtcSnapshot> {
        val out = mutableListOf<DtcSnapshot>()
        var i = 6 // 59 04 DTC(3) status
        while (i + 1 < d.size) {
            val record = d[i]; val count = d[i + 1]
            i += 2
            val values = mutableListOf<SnapshotValue>()
            var undecoded: String? = null
            for (n in 0 until count) {
                if (i + 1 >= d.size) break
                val did = (d[i] shl 8) or d[i + 1]
                i += 2
                val len = DidCatalog.length(did)
                    ?: if (n == count - 1 && out.isEmpty() && d.size - i < 64) d.size - i else null
                if (len == null || i + len > d.size) {
                    undecoded = "DID %04X and following: ".format(did) + d.copyOfRange(i, d.size).joinToString(" ") { "%02X".format(it) }
                    i = d.size
                    break
                }
                values += DidCatalog.decode(did, d.copyOfRange(i, i + len))
                i += len
            }
            out += DtcSnapshot(record, values, undecoded)
            if (undecoded != null) break
        }
        return out
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

    /** VIN stored in the module (UDS DID F190, or GMLAN identifier \$90). Null when it does not report one. */
    suspend fun readVin(m: EcuModule): String? = withModule(m) {
        fun text(bytes: IntArray) = bytes.filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("").takeLast(17).takeIf { it.length == 17 }
        positive(request("22F190", 3000), 0x22)?.let { p -> text(p.data.copyOfRange(3, p.data.size))?.let { return@withModule it } }
        if (gmlan) positive(request("1A90", 3000), 0x1A)?.let { p -> if (p.data.getOrNull(1) == 0x90) return@withModule text(p.data.copyOfRange(2, p.data.size)) }
        null
    }

    /**
     * Finds which data identifiers a module answers in [ids]: UDS \$22 (2-byte ids) or, with [gmlanIds],
     * GMLAN \$1A (1-byte ids). Absent ids cost one short timeout each, so ranges should stay small (~256).
     */
    suspend fun scanIds(
        m: EcuModule, ids: IntRange, gmlanIds: Boolean = false,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Map<Int, IntArray> = withModule(m) {
        at("ATST19")
        val out = linkedMapOf<Int, IntArray>()
        val total = ids.count()
        ids.forEachIndexed { i, id ->
            if (i % 8 == 0) progress(i, total)
            readIdLocked(id, gmlanIds, 1500)?.let { out[id] = it }
        }
        progress(total, total)
        out
    }

    /** Reads several identifiers in one module session (for watching values change while driving). */
    suspend fun readIds(m: EcuModule, ids: Collection<Int>, gmlanIds: Boolean = false): Map<Int, IntArray> = withModule(m) {
        ids.mapNotNull { id -> readIdLocked(id, gmlanIds, 2000)?.let { id to it } }.toMap()
    }

    private suspend fun Elm327.Locked.readIdLocked(id: Int, gmlanIds: Boolean, timeout: Long): IntArray? {
        return if (gmlanIds) {
            positive(request("1A%02X".format(id and 0xFF), timeout), 0x1A)?.takeIf { it.data.getOrNull(1) == (id and 0xFF) }
                ?.let { it.data.copyOfRange(2, it.data.size) }
        } else {
            positive(request("22%04X".format(id and 0xFFFF), timeout), 0x22)
                ?.takeIf { it.data.size >= 3 && ((it.data[1] shl 8) or it.data[2]) == (id and 0xFFFF) }
                ?.let { it.data.copyOfRange(3, it.data.size) }
        }
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
    suspend fun discover(
        range: IntRange = 0x700..0x7F7,
        secondaryBus: Boolean = false,
        /** Receive filter for the replies, e.g. "7XX" (most brands) or "6XX" (GM 0x24x requests answered on 0x64x). */
        rxFilter: String = "7XX",
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): List<EcuModule> = elm.exclusive {
        check(supported) { NOT_CAN }
        val found = mutableListOf<EcuModule>()
        try {
            at("ATST19") // ~100 ms per absent address
            if (!at("ATCRA $rxFilter")) {
                at("ATCF " + rxFilter.replace('X', '0'))
                at("ATCM " + rxFilter.mapIndexed { i, c -> if (c == 'X') '0' else if (i == 0) '7' else 'F' }.joinToString(""))
            }
            val total = range.count()
            range.forEachIndexed { index, tx ->
                if (index % 4 == 0) progress(index, total)
                if (tx in 0x7DF..0x7DF) return@forEachIndexed // functional broadcast address
                at("ATSH %03X".format(tx))
                val msgs = (query("3E00", 1500) as? ObdResult.Data)?.messages.orEmpty()
                val answer = msgs.firstOrNull { it.service == 0x7E || (it.isNegative && it.data.getOrNull(1) == 0x3E) }
                val rx = answer?.ecu?.toIntOrNull(16)
                if (answer != null && rx != null && rx != tx && found.none { it.rx == rx }) {
                    found += EcuModule("Module %03X".format(tx), "%03X".format(tx), tx, rx, secondaryBus = secondaryBus)
                }
            }
            progress(total, total)
        } finally {
            withContext(NonCancellable) { restore() }
        }
        found
    }

    /** Sends TesterPresent to each module and returns those that answer (quick bus check). */
    suspend fun ping(modules: List<EcuModule>): List<EcuModule> = modules.filter { m ->
        runCatching {
            withModule(m) {
                request("3E00", 1500).any { it.service == 0x7E || (it.isNegative && it.data.getOrNull(1) == 0x3E) }
            }
        }.getOrDefault(false)
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
        const val NOT_CAN = "Module access needs a CAN vehicle (most cars from 2008 onwards)."

        /** GMLAN sends unsegmented replies on 0x5xx: 0x643 -> 0x543, and the engine's 0x7E8 -> 0x5E8. */
        fun gmlanUudtId(m: EcuModule): Int? = when {
            m.is29Bit -> null
            m.rx in 0x600..0x6FF -> m.rx - 0x100
            m.rx in 0x7E8..0x7EF -> m.rx - 0x200
            else -> null
        }

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
