package com.openobd.session

import com.openobd.core.brand.CarState
import com.openobd.core.brand.ClearOutcome
import com.openobd.core.brand.Coding
import com.openobd.core.brand.CodingEngine
import com.openobd.core.brand.CodingOption
import com.openobd.core.brand.Routine
import com.openobd.core.brand.WriteOutcome
import com.openobd.core.brand.WriteRecord
import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.DtcDetails
import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.IdKind
import com.openobd.core.brand.Families
import com.openobd.core.brand.ModuleClient
import com.openobd.core.brand.ModuleDtc
import com.openobd.core.brand.ModuleScanResult
import com.openobd.core.brand.SecondaryBus
import com.openobd.core.elm.ObdResult
import com.openobd.data.ScanRecord
import com.openobd.data.StoredDtc
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Control modules beyond the engine: brand module lists, scanning and clearing, identification, discovery,
 * the HS / MS-CAN switch and the identifier explorer. Every operation pauses live data while it runs.
 */
class ModuleFeatures internal constructor(private val s: ObdSession) {
    val moduleScan = MutableStateFlow<Op<List<ModuleScanResult>>>(Op.Idle)
    val discovery = MutableStateFlow<Op<List<EcuModule>>>(Op.Idle)

    private val _bus = MutableStateFlow(Bus.HS)
    /** HS = normal OBD bus; MS = medium-speed CAN through a switch adapter (live data paused). */
    val bus: StateFlow<Bus> = _bus

    /** Forgets everything about the previous connection. */
    internal fun reset() {
        moduleScan.value = Op.Idle; discovery.value = Op.Idle
        _bus.value = Bus.HS
    }

    /** Modules to scan for the current brand: the family's known modules plus saved / discovered ones. */
    fun modulesForBrand(): List<EcuModule> {
        val family = (s.brand.value?.family ?: Families.GENERIC)
        val saved = s.garage.data.value.active?.customModules.orEmpty()
        return (family.modules + saved).distinctBy { it.id }
    }

    /** Modules reachable on the bus the adapter is currently on. */
    fun modulesOnCurrentBus(): List<EcuModule> = modulesForBrand().filter { it.secondaryBus == (bus.value == Bus.MS) }

    fun findModule(id: String): EcuModule? =
        modulesForBrand().firstOrNull { it.id == id } ?: (discovery.value as? Op.Done)?.value?.firstOrNull { it.id == id }

    private fun usesGmlan() = s.brand.value?.family?.id in setOf("gm", "gm-europe")

    internal fun client(): ModuleClient {
        val e = s.adapter ?: throw IllegalStateException("Not connected to a vehicle")
        val mc = ModuleClient(e).apply { gmlan = usesGmlan() }
        if (!mc.supported) throw IllegalStateException("Module access needs a CAN vehicle (most cars from 2008 onwards). This car uses ${e.protocol.label}.")
        return mc
    }

    /** Runs [block] in the app scope with live data paused, so leaving a screen never interrupts a module operation. */
    internal suspend fun <T> op(block: suspend (ModuleClient) -> T): T {
        val mc = client()
        return s.appScope.async {
            s.live.pause()
            try { block(mc) } finally { s.live.resume() }
        }.await()
    }

    fun scanAllModules() {
        if (moduleScan.value is Op.Loading) return
        val list = modulesOnCurrentBus()
        moduleScan.value = Op.Loading("Starting")
        s.appScope.launch {
            try {
                val results = op { mc ->
                    list.mapIndexed { i, m ->
                        moduleScan.value = Op.Loading("Scanning ${m.name} (${i + 1}/${list.size})")
                        mc.scan(m)
                    }
                }
                moduleScan.value = Op.Done(results)
                val codes = results.flatMap { r -> r.dtcs.map { StoredDtc(it.fullCode, "MODULE", r.module.shortName, s.describe(it.code).description) } }
                s.history.add(ScanRecord(System.currentTimeMillis(), s.vehicle?.vin, false, codes,
                    note = "Module scan: ${results.count { it.online }} of ${results.size} modules answered", vehicleId = s.garage.data.value.active?.id))
            } catch (e: Exception) {
                moduleScan.value = Op.Error(e.message ?: "Scan failed")
            }
        }
    }

    suspend fun scanModule(m: EcuModule): ModuleScanResult = op { it.scan(m) }.also { r ->
        (moduleScan.value as? Op.Done)?.let { d -> moduleScan.value = Op.Done(d.value.map { if (it.module.id == m.id) r else it }) }
    }

    suspend fun clearModule(m: EcuModule): ClearOutcome = op { it.clear(m) }.also { r ->
        if (r.success) s.history.add(ScanRecord(System.currentTimeMillis(), s.vehicle?.vin, false, emptyList(), cleared = true, note = "${m.name}: ${r.message}",
            vehicleId = s.garage.data.value.active?.id))
    }

    suspend fun dtcDetails(m: EcuModule, dtc: ModuleDtc): DtcDetails = op { it.readDtcDetails(m, dtc) }

    suspend fun identifyModule(m: EcuModule): List<Pair<String, String>> = op { it.readIdentification(m) }

    suspend fun readDid(m: EcuModule, did: Int): IntArray = op { it.readDid(m, did) }

    // ------------------------------------------------------------------ codings: every write is backed up, checked and undoable

    /** Speed, engine speed and battery voltage for the write checks; null when the car does not report them. */
    suspend fun carState(): CarState {
        val e = s.adapter ?: return CarState(null, null, null)
        // The demo car is always "driving"; for trying codings it is treated as parked with the ignition on.
        if (s.vehicle?.isDemo == true) return CarState(rpm = 0.0, speedKmh = 0.0, volts = 12.6)
        val c = s.client
        return s.appScope.async {
            s.live.pause()
            try {
                val pids = if (bus.value == Bus.MS || c == null) emptyMap() else runCatching { c.readPids(listOf(0x0C, 0x0D)) }.getOrDefault(emptyMap())
                CarState(
                    rpm = pids[0x0C]?.takeIf { it.size >= 2 }?.let { (it[0] * 256 + it[1]) / 4.0 },
                    speedKmh = pids[0x0D]?.firstOrNull()?.toDouble(),
                    volts = e.readVoltage(),
                )
            } finally { s.live.resume() }
        }.await()
    }

    private fun engine(mc: ModuleClient) = CodingEngine(mc, save = { s.garage.saveWrite(it) }, newId = s.garage::newId)
    private fun vehicleId() = s.garage.data.value.active?.id.orEmpty()

    /** Current value of a coding's identifier. */
    suspend fun readCoding(c: Coding): IntArray = op { it.read(c.module(), c.didId, c.kind) }
    suspend fun applyCoding(c: Coding, option: CodingOption): WriteOutcome = op { engine(it).apply(vehicleId(), c, option) }
    suspend fun undoWrite(r: WriteRecord): WriteOutcome = op { engine(it).undo(r) }
    suspend fun restoreWrite(r: WriteRecord): WriteOutcome = op { engine(it).retryRestore(r) }
    suspend fun runRoutine(r: Routine): WriteOutcome = op { engine(it).run(vehicleId(), r) }
    suspend fun stopRoutine(r: Routine): Boolean = op { engine(it).stop(r) }

    /** Reads one custom PID once, for the editor's "Test" button. */
    suspend fun testCustomPid(p: CustomPid): IntArray? = op { it.readCustom(listOf(p))[p.key] }

    /** Reads and decodes several custom PIDs at once (value null = no answer). */
    suspend fun readCustomValues(pids: List<CustomPid>): Map<String, Double?> {
        val raw = op { it.readCustom(pids) }
        return pids.associate { p -> p.key to raw[p.key]?.let { bytes -> p.toSensor().decode(bytes) } }
    }

    fun discoverModules() {
        if (discovery.value is Op.Loading) return
        discovery.value = Op.Loading("Probing CAN addresses")
        s.appScope.launch {
            try {
                val ms = bus.value == Bus.MS
                val ranges = (s.brand.value?.family ?: Families.GENERIC).discoveryRanges
                val total = ranges.sumOf { it.first.count() }
                var done = 0
                val found = mutableListOf<EcuModule>()
                for ((range, filter) in ranges) {
                    found += op { mc ->
                        mc.discover(range, secondaryBus = ms, rxFilter = filter) { i, _ ->
                            discovery.value = Op.Loading("Probing address %03X … %d%%".format(range.first + i, (done + i) * 100 / total))
                        }
                    }.filter { f -> found.none { it.rx == f.rx } }
                    done += range.count()
                }
                val known = modulesForBrand()
                discovery.value = Op.Done(found.map { f -> known.firstOrNull { it.tx == f.tx && !it.is29Bit && it.secondaryBus == ms } ?: f })
            } catch (e: Exception) {
                discovery.value = Op.Error(e.message ?: "Discovery failed")
            }
        }
    }

    // ------------------------------------------------------------------ HS / MS-CAN switching

    /** The medium-speed bus of the current brand (Ford-style 125 kbps when the brand has none listed). */
    fun secondaryBus(): SecondaryBus = s.brand.value?.family?.secondaryBus ?: Families.FORD_MS

    /**
     * Moves the adapter to the brand's medium-speed CAN. The user must have flipped the adapter's switch first.
     * Live data is paused until [returnToHsCan] succeeds. Returns the known MS-CAN modules that answered.
     */
    suspend fun enterMsCan(): List<EcuModule> {
        val e = s.adapter ?: throw IllegalStateException("Not connected to a vehicle")
        if (!e.baseProtocol.isCan || e.baseProtocol.is29Bit) throw IllegalStateException("MS-CAN is only used on 11-bit CAN vehicles (Ford, Lincoln, Mazda, Opel).")
        if (bus.value == Bus.MS) return emptyList()
        val sb = secondaryBus()
        s.live.pause()
        if (!e.setUserCan(sb.atpb, needsCustomSpeed = sb.pbDivisor != 0x04 || sb.pbOptions and 0x10 != 0)) {
            s.live.resume()
            throw IllegalStateException(
                if (sb.pbDivisor != 0x04) "This adapter cannot set a custom CAN speed (ATPB), which ${sb.kbps} kbps MS-CAN needs."
                else "This adapter rejected protocol B (User1 CAN 125k), so it can't talk to MS-CAN.",
            )
        }
        s.debug.event("Switched to ${sb.label} ${sb.kbps} kbps (${sb.atpb})")
        _bus.value = Bus.MS
        moduleScan.value = Op.Idle; discovery.value = Op.Idle
        val known = modulesForBrand().filter { it.secondaryBus }
        return if (known.isEmpty()) emptyList() else ModuleClient(e).apply { gmlan = usesGmlan() }.ping(known)
    }

    /** Switches back to the vehicle's normal bus. Returns false if the car doesn't answer (switch still on MS?). */
    suspend fun returnToHsCan(): Boolean {
        val e = s.adapter ?: return true
        if (bus.value == Bus.HS) return true
        _bus.value = Bus.RETURNING
        e.setProtocol(e.baseProtocol)
        val ok = e.query("0100", 5000) is ObdResult.Data
        if (ok) {
            _bus.value = Bus.HS
            moduleScan.value = Op.Idle; discovery.value = Op.Idle
            s.live.resume()
        }
        return ok
    }

    // ------------------------------------------------------------------ identifier explorer

    /** Which identifiers of [kind] [module] answers in [ids]. */
    suspend fun scanIds(module: EcuModule, ids: IntRange, kind: IdKind, progress: (Int, Int) -> Unit): Map<Int, IntArray> =
        op { it.scanIds(module, ids, kind, progress) }

    suspend fun readIds(module: EcuModule, ids: Collection<Int>, kind: IdKind): Map<Int, IntArray> =
        op { it.readIds(module, ids, kind) }

    /** Saves an identifier found with the explorer as a custom PID of the active car. */
    suspend fun saveIdAsCustomPid(module: EcuModule, id: Int, kind: IdKind, length: Int) {
        val (header, receive) = com.openobd.core.brand.CodingFinder.addressOf(module)
        val pid = CustomPid(
            id = s.garage.newId(), name = "${module.shortName} " + kind.idHex(id), shortName = kind.idHex(id), request = kind.readRequest(id),
            equation = if (length >= 2) "(A*256)+B" else "A", min = 0.0, max = if (length >= 2) 65535.0 else 255.0,
            header = header, receive = receive, extAddr = com.openobd.core.brand.CodingFinder.extOf(module),
        )
        s.garage.updateActive { it.copy(customPids = it.customPids + pid) }
    }
}
