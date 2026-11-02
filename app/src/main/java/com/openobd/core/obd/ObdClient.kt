package com.openobd.core.obd

import com.openobd.core.elm.EcuMessage
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmError
import com.openobd.core.elm.ObdResult

data class DtcReadResult(val codes: List<Dtc>, val error: String? = null)

data class ClearResult(val success: Boolean, val message: String)

data class FreezeFrame(
    val dtc: String?,
    val frame: Int,
    /** sensor key -> value */
    val values: Map<String, Double>,
)

data class EcuInfo(val address: String, val name: String)

data class VehicleInfo(
    val vin: String?,
    val calibrationIds: List<Pair<String, String>>,
    val cvns: List<Pair<String, String>>,
    val ecuNames: List<Pair<String, String>>,
    val inUsePerformance: List<Pair<String, Int>>,
    val supportedInfoTypes: Set<Int>,
)

/** High level OBD-II services on top of an [Elm327] connection. */
class ObdClient(val elm: Elm327) {
    val isCan: Boolean get() = elm.protocol.isCan

    /** Request several PIDs per message on CAN (ISO 15765-4 allows up to 6). */
    var batchingEnabled = true
    /** Append the expected response count to single-PID requests so the ELM returns immediately. */
    var fastResponses = true
    private var batchFailures = 0

    /** ECU addresses that answered 0100 during discovery; the first one is treated as the engine ECU. */
    var ecus: List<EcuInfo> = emptyList()
        private set
    private val primaryEcu: String? get() = ecus.firstOrNull()?.address

    // ---------------------------------------------------------------- discovery

    /**
     * Reads the "supported PIDs" bitmaps. [service] 1, 2 (freeze frame, needs frame suffix), 6 or 9.
     * Returns per-ECU support, merged by the caller as needed.
     */
    suspend fun supportedPids(service: Int, suffix: String = ""): Map<String, Set<Int>> {
        val result = linkedMapOf<String, MutableSet<Int>>()
        var base = 0
        while (base <= 0xE0) {
            val r = elm.query("%02X%02X".format(service, base) + suffix, 8000)
            if (r !is ObdResult.Data) break
            var more = false
            for (m in r.positive(service)) {
                if (m.data.size < 6 || m.data[1] != base) continue
                val bits = m.data.copyOfRange(m.data.size - 4, m.data.size)
                val set = result.getOrPut(m.ecu) { sortedSetOf() }
                for (i in 0 until 32) {
                    if ((bits[i / 8] shr (7 - i % 8)) and 1 == 1) set += base + i + 1
                }
                if (base + 0x20 in set) more = true
            }
            if (!more) break
            base += 0x20
        }
        return result
    }

    suspend fun discover(): Set<Int> {
        val perEcu = supportedPids(1)
        // Sort so the ECU supporting the most PIDs (normally the engine) comes first.
        ecus = perEcu.entries.sortedByDescending { it.value.size }.map { EcuInfo(it.key, ecuName(it.key)) }
        return perEcu.values.flatten().toSortedSet()
    }

    fun ecuName(address: String): String = when (address.uppercase()) {
        "7E8", "10" -> "Engine control module (ECM)"
        "7E9", "18", "1A" -> "Transmission control module (TCM)"
        "7EA" -> "ECU #3 (hybrid / ABS)"
        "7EB" -> "ECU #4"
        "7EC" -> "ECU #5"
        "7ED" -> "ECU #6"
        "7EE" -> "ECU #7"
        "7EF" -> "ECU #8"
        "ECU" -> "ECU"
        else -> "ECU $address"
    }

    // ---------------------------------------------------------------- service 01

    /** Reads raw data bytes for the given PIDs. Unsupported / unanswered PIDs are omitted. */
    suspend fun readPids(pids: List<Int>): Map<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        if (pids.isEmpty()) return out
        if (isCan && batchingEnabled && pids.size > 1) {
            val batchable = pids.filter { it <= 0x64 && Pids.all[it] != null }
            for (chunk in batchable.chunked(6)) {
                val r = elm.query("01" + chunk.joinToString("") { "%02X".format(it) }, 3000)
                if (r is ObdResult.Data) parseMultiPid(r, out)
                val missing = chunk.filter { it !in out }
                if (missing.isNotEmpty() && chunk.size > 1) {
                    if (missing.size == chunk.size) batchFailures++
                    if (batchFailures >= 3) batchingEnabled = false
                }
            }
            for (pid in pids) if (pid !in out && pid !in batchable) readSingle(pid)?.let { out[pid] = it }
            return out
        }
        for (pid in pids) readSingle(pid)?.let { out[pid] = it }
        return out
    }

    private suspend fun readSingle(pid: Int): IntArray? {
        val cmd = "01%02X".format(pid)
        val fast = fastResponses && ecus.size <= 1
        var r = elm.query(if (fast) cmd + "1" else cmd, 3000)
        if (fast && r is ObdResult.Failure && r.error == ElmError.UNKNOWN_COMMAND) {
            fastResponses = false
            r = elm.query(cmd, 3000)
        }
        if (r !is ObdResult.Data) return null
        val msgs = r.positive(1).filter { it.data.size >= 2 && it.data[1] == pid }
        val msg = msgs.firstOrNull { it.ecu == primaryEcu } ?: msgs.firstOrNull() ?: return null
        return msg.data.copyOfRange(2, msg.data.size)
    }

    private fun parseMultiPid(r: ObdResult.Data, out: MutableMap<Int, IntArray>) {
        val msgs = r.positive(1).sortedBy { if (it.ecu == primaryEcu) 0 else 1 }
        for (m in msgs) {
            var i = 1
            while (i < m.data.size) {
                val pid = m.data[i]
                val len = Pids.all[pid]?.bytes ?: break
                if (i + 1 + len > m.data.size) break
                if (pid !in out) out[pid] = m.data.copyOfRange(i + 1, i + 1 + len)
                i += 1 + len
            }
        }
    }

    suspend fun readReadiness(pid: Int = 0x01): ReadinessStatus? =
        readSingle(pid)?.takeIf { it.size >= 4 }?.let { Readiness.decode(it) }

    // ---------------------------------------------------------------- DTCs

    suspend fun readDtcs(type: DtcType): DtcReadResult {
        val r = elm.query("%02X".format(type.mode), 10000)
        return when (r) {
            is ObdResult.Failure ->
                if (r.error == ElmError.NO_DATA) DtcReadResult(emptyList())
                else DtcReadResult(emptyList(), r.error.userMessage)
            is ObdResult.Data -> {
                val codes = r.positive(type.mode).flatMap { m ->
                    DtcDecoder.parseResponse(m.data, isCan).map { Dtc(it, type, m.ecu) }
                }.distinctBy { it.code to it.ecu }
                val neg = r.negative().firstOrNull()
                if (codes.isEmpty() && neg != null && r.positive(type.mode).isEmpty())
                    DtcReadResult(emptyList(), "ECU refused: ${negativeReason(neg)}")
                else DtcReadResult(codes)
            }
        }
    }

    suspend fun readAllDtcs(): Map<DtcType, DtcReadResult> = DtcType.entries.associateWith { readDtcs(it) }

    suspend fun clearDtcs(): ClearResult {
        val r = elm.query("04", 15000)
        return when (r) {
            is ObdResult.Failure -> ClearResult(false, r.error.userMessage)
            is ObdResult.Data -> {
                val ok = r.positive(4)
                val neg = r.negative()
                when {
                    ok.isNotEmpty() && neg.isEmpty() -> ClearResult(true, "Trouble codes cleared on ${ok.size} ECU(s).")
                    ok.isNotEmpty() -> ClearResult(true, "Cleared on ${ok.size} ECU(s); ${neg.size} refused: ${negativeReason(neg.first())}")
                    neg.isNotEmpty() -> ClearResult(false, "The ECU refused: ${negativeReason(neg.first())}")
                    else -> ClearResult(false, "Unexpected reply: ${r.raw}")
                }
            }
        }
    }

    fun negativeReason(m: EcuMessage): String = when (m.data.getOrNull(2)) {
        0x10 -> "general reject"
        0x11 -> "service not supported"
        0x12 -> "sub-function not supported"
        0x21 -> "busy, repeat request"
        0x22 -> "conditions not correct (switch the engine off, leave ignition on and retry)"
        0x31 -> "request out of range"
        0x33 -> "security access denied"
        0x78 -> "response pending"
        else -> "code 0x%02X".format(m.data.getOrNull(2) ?: 0)
    }

    // ---------------------------------------------------------------- freeze frame (service 02)

    suspend fun readFreezeFrame(frame: Int = 0): FreezeFrame? {
        val f = "%02X".format(frame)
        val dtcR = elm.query("0202$f", 5000)
        var dtc: String? = null
        if (dtcR is ObdResult.Data) {
            dtcR.positive(2).firstOrNull { it.data.size >= 5 && it.data[1] == 0x02 }?.let { m ->
                if (m.data[3] != 0 || m.data[4] != 0) dtc = DtcDecoder.decode(m.data[3], m.data[4])
            }
        }
        val supported = supportedPids(2, f).values.flatten().toSortedSet()
        if (dtc == null && supported.isEmpty()) return null
        val values = linkedMapOf<String, Double>()
        for (pid in supported) {
            if (Pids.isSupportRequest(pid) || pid == 0x02) continue
            val info = Pids.all[pid] ?: continue
            val r = elm.query("02%02X$f".format(pid), 3000) as? ObdResult.Data ?: continue
            val m = r.positive(2).firstOrNull { it.data.size >= 3 && it.data[1] == pid } ?: continue
            val bytes = m.data.copyOfRange(3, m.data.size)
            for (s in info.sensors) s.decodeSafe(bytes)?.let { values[s.key] = it }
        }
        return FreezeFrame(dtc, frame, values)
    }

    // ---------------------------------------------------------------- vehicle info (service 09)

    private fun mode09Items(r: ObdResult.Data, infoType: Int): Map<String, List<Int>> {
        val byEcu = r.positive(9).filter { it.data.size >= 3 && it.data[1] == infoType }.groupBy { it.ecu }
        return byEcu.mapValues { (_, msgs) ->
            if (isCan) msgs.first().data.drop(3)
            else msgs.sortedBy { it.data[2] }.flatMap { it.data.drop(3) }
        }
    }

    private fun ascii(bytes: List<Int>) = bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim()

    suspend fun readVin(): String? {
        val r = elm.query("0902", 8000) as? ObdResult.Data ?: return null
        val items = mode09Items(r, 0x02)
        val bytes = items[primaryEcu] ?: items.values.firstOrNull() ?: return null
        val text = ascii(bytes).filter { it.isLetterOrDigit() }
        return text.takeLast(17).takeIf { it.length >= 11 }
    }

    suspend fun readVehicleInfo(): VehicleInfo {
        val supported = supportedPids(9).values.flatten().toSet()
        val vin = if (supported.isEmpty() || 0x02 in supported) readVin() else null

        suspend fun items(type: Int): Map<String, List<Int>> {
            if (supported.isNotEmpty() && type !in supported) return emptyMap()
            val r = elm.query("09%02X".format(type), 8000) as? ObdResult.Data ?: return emptyMap()
            return mode09Items(r, type)
        }

        val cal = items(0x04).flatMap { (ecu, b) -> b.chunked(16).map { ecu to ascii(it) }.filter { it.second.isNotEmpty() } }
        val cvn = items(0x06).flatMap { (ecu, b) -> b.chunked(4).filter { it.size == 4 }.map { ecu to it.joinToString("") { v -> "%02X".format(v) } } }
        val names = items(0x0A).map { (ecu, b) -> ecu to ascii(b).replace(Regex("\\s+"), " ") }.filter { it.second.isNotEmpty() }
        val iptRaw = items(0x08).entries.firstOrNull()?.value?.let { it to sparkIpt }
            ?: items(0x0B).entries.firstOrNull()?.value?.let { it to dieselIpt }
        val ipt = iptRaw?.let { (bytes, labels) ->
            bytes.chunked(2).filter { it.size == 2 }.mapIndexed { i, p -> (labels.getOrNull(i) ?: "Counter ${i + 1}") to ((p[0] shl 8) or p[1]) }
        } ?: emptyList()
        return VehicleInfo(vin, cal, cvn, names, ipt, supported)
    }

    private val sparkIpt = listOf(
        "OBD monitoring conditions", "Ignition cycles", "Catalyst B1 completions", "Catalyst B1 conditions",
        "Catalyst B2 completions", "Catalyst B2 conditions", "O2 sensor B1 completions", "O2 sensor B1 conditions",
        "O2 sensor B2 completions", "O2 sensor B2 conditions", "EGR/VVT completions", "EGR/VVT conditions",
        "Secondary air completions", "Secondary air conditions", "EVAP completions", "EVAP conditions",
        "Secondary O2 B1 completions", "Secondary O2 B1 conditions", "Secondary O2 B2 completions", "Secondary O2 B2 conditions",
        "AFRI B1 completions", "AFRI B1 conditions", "AFRI B2 completions", "AFRI B2 conditions",
        "PM filter B1 completions", "PM filter B1 conditions", "PM filter B2 completions", "PM filter B2 conditions",
    )
    private val dieselIpt = listOf(
        "OBD monitoring conditions", "Ignition cycles", "NMHC catalyst completions", "NMHC catalyst conditions",
        "NOx catalyst completions", "NOx catalyst conditions", "NOx adsorber completions", "NOx adsorber conditions",
        "PM filter completions", "PM filter conditions", "Exhaust gas sensor completions", "Exhaust gas sensor conditions",
        "EGR/VVT completions", "EGR/VVT conditions", "Boost pressure completions", "Boost pressure conditions",
        "Fuel monitor completions", "Fuel monitor conditions",
    )

    // ---------------------------------------------------------------- service 06

    suspend fun readMonitorTests(progress: (Int, Int) -> Unit = { _, _ -> }): List<TestResult> {
        if (!isCan) return emptyList()
        val mids = supportedPids(6).values.flatten().toSortedSet().filterNot { Pids.isSupportRequest(it) }
        val out = mutableListOf<TestResult>()
        mids.forEachIndexed { i, mid ->
            progress(i + 1, mids.size)
            val r = elm.query("06%02X".format(mid), 5000) as? ObdResult.Data ?: return@forEachIndexed
            r.positive(6).forEach { out += Mode06.parse(it.data, it.ecu) }
        }
        return out
    }
}
