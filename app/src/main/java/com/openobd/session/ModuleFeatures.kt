package com.openobd.session

import com.openobd.core.brand.ClearOutcome
import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.DtcDetails
import com.openobd.core.brand.EcuModule
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

    /** Reads one custom PID once, for the editor's "Test" button. */
    suspend fun testCustomPid(p: CustomPid): IntArray? = op { it.readCustom(listOf(p))[p.key] }

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

    /** Which data identifiers [module] answers in [ids]. */
    suspend fun scanIds(module: EcuModule, ids: IntRange, gmlanIds: Boolean, progress: (Int, Int) -> Unit): Map<Int, IntArray> =
        op { it.scanIds(module, ids, gmlanIds, progress) }

    suspend fun readIds(module: EcuModule, ids: Collection<Int>, gmlanIds: Boolean): Map<Int, IntArray> =
        op { it.readIds(module, ids, gmlanIds) }

    /** Saves an identifier found with the explorer as a custom PID of the active car. */
    suspend fun saveIdAsCustomPid(module: EcuModule, id: Int, gmlanIds: Boolean, length: Int) {
        val request = if (gmlanIds) "1A%02X".format(id) else "22%04X".format(id)
        val pid = CustomPid(
            id = s.garage.newId(), name = "${module.shortName} " + (if (gmlanIds) "%02X".format(id) else "%04X".format(id)),
            shortName = if (gmlanIds) "%02X".format(id) else "%04X".format(id), request = request,
            equation = if (length >= 2) "(A*256)+B" else "A", min = 0.0, max = if (length >= 2) 65535.0 else 255.0,
            header = "%03X".format(module.tx), receive = "%03X".format(module.rx),
        )
        s.garage.updateActive { it.copy(customPids = it.customPids + pid) }
    }
}
