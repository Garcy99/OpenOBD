package com.openobd.session

import android.content.Context
import com.openobd.core.brand.Brand
import com.openobd.core.brand.Brands
import com.openobd.core.brand.ClearOutcome
import com.openobd.core.brand.CustomSensors
import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ManufacturerCodes
import com.openobd.core.brand.ModuleClient
import com.openobd.core.brand.ModuleScanResult
import com.openobd.core.dtc.DtcDatabase
import com.openobd.core.dtc.DtcDescription
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.elm.ElmInfo
import com.openobd.core.obd.DerivedSensors
import com.openobd.core.obd.Dtc
import com.openobd.core.obd.DtcType
import com.openobd.core.obd.EcuInfo
import com.openobd.core.obd.FreezeFrame
import com.openobd.core.obd.ObdClient
import com.openobd.core.obd.Pids
import com.openobd.core.obd.ReadinessStatus
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.TestResult
import com.openobd.core.obd.VehicleInfo
import com.openobd.core.transport.DemoTransport
import com.openobd.core.transport.FaultPlan
import com.openobd.core.transport.FaultyTransport
import com.openobd.core.transport.ObdTransport
import com.openobd.data.AlertRule
import com.openobd.data.AppSettings
import com.openobd.data.ConnectionType
import com.openobd.data.FuelKind
import com.openobd.data.HistoryRepository
import com.openobd.data.LogRepository
import com.openobd.data.ScanRecord
import com.openobd.data.SettingsRepository
import com.openobd.data.StoredDtc
import com.openobd.transport.BleTransport
import com.openobd.transport.BluetoothClassicTransport
import com.openobd.transport.WifiTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class VehicleSnapshot(
    val transport: String,
    val elm: ElmInfo,
    val supportedPids: Set<Int>,
    val ecus: List<EcuInfo>,
    val vin: String?,
    val voltage: Double?,
    val isDemo: Boolean,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val step: String) : ConnectionState
    data class Connected(val vehicle: VehicleSnapshot) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** State of a one-shot diagnostic operation. */
sealed interface Op<out T> {
    data object Idle : Op<Nothing>
    data class Loading(val progress: String? = null) : Op<Nothing>
    data class Done<T>(val value: T, val time: Long = System.currentTimeMillis()) : Op<T>
    data class Error(val message: String) : Op<Nothing>
}

data class DtcScan(val codes: List<Dtc>, val errors: Map<DtcType, String>, val readiness: ReadinessStatus?)

data class ConsoleLine(val time: Long, val direction: Char, val text: String)

/** Which CAN bus the adapter is currently talking to. */
enum class Bus { HS, MS, RETURNING }

data class AlertEvent(val rule: AlertRule, val value: Double, val sensorName: String)

const val MS_CAN_BUSY = "The adapter is on MS-CAN. Go to Brand features and switch back to HS-CAN first."

class ObdSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    val history: HistoryRepository,
    val logs: LogRepository,
    val dtcDatabase: DtcDatabase,
    val garage: com.openobd.data.GarageRepository,
) {
    private val location = LocationTracker(context)
    @Volatile private var tripActive = false
    @Volatile private var tripStart = 0L
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console

    val live = LiveDataManager(scope)
    val trip = TripComputer()
    val perf = PerformanceTimer()

    val dtcScan = MutableStateFlow<Op<DtcScan>>(Op.Idle)
    val clearResult = MutableStateFlow<Op<String>>(Op.Idle)
    val readiness = MutableStateFlow<Op<Pair<ReadinessStatus?, ReadinessStatus?>>>(Op.Idle)
    val freezeFrame = MutableStateFlow<Op<FreezeFrame?>>(Op.Idle)
    val vehicleInfo = MutableStateFlow<Op<VehicleInfo>>(Op.Idle)
    val monitorTests = MutableStateFlow<Op<List<TestResult>>>(Op.Idle)
    val moduleScan = MutableStateFlow<Op<List<ModuleScanResult>>>(Op.Idle)
    val discovery = MutableStateFlow<Op<List<EcuModule>>>(Op.Idle)

    private val _bus = MutableStateFlow(Bus.HS)
    /** HS = normal OBD bus; MS = Ford/Mazda medium-speed CAN through a switch adapter (live data paused). */
    val bus: StateFlow<Bus> = _bus

    private val _brand = MutableStateFlow<Brand?>(null)
    /** The selected brand, or the one detected from the VIN when none was chosen. */
    val brand: StateFlow<Brand?> = _brand
    private val _brandDetected = MutableStateFlow(false)
    val brandAutoDetected: StateFlow<Boolean> = _brandDetected

    private val _alerts = MutableSharedFlow<AlertEvent>(extraBufferCapacity = 8)
    val alerts: SharedFlow<AlertEvent> = _alerts
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording

    private var transport: ObdTransport? = null
    private var elm: Elm327? = null
    var client: ObdClient? = null
        private set
    private var connectJob: Job? = null
    private var voltageJob: Job? = null
    private var userDisconnect = false

    val settings: StateFlow<AppSettings> get() = settingsRepo.settings

    val vehicle: VehicleSnapshot? get() = (state.value as? ConnectionState.Connected)?.vehicle

    init {
        live.addListener(trip)
        live.addListener(perf)
        live.addListener(alertChecker())
        live.addListener { values, _, _ -> if (logs.isRecording) logs.append(values) }
        live.onAdapterLost = { scope.launch { linkLost("The adapter stopped responding.") } }
        scope.launch {
            settingsRepo.settings.collectLatest { s ->
                live.params = fuelParams(s, vehicle)
                updateAlertSubscriptions(s)
                updateBrand()
            }
        }
        scope.launch {
            garage.data.collect { g ->
                CustomSensors.update(g.active?.customPids.orEmpty())
                updateBrand()
                live.params = fuelParams(settingsRepo.settings.value, vehicle)
            }
        }
        location.speedProvider = { live.latestValue("010D") }
        live.addListener(demoGpsListener())
    }

    // ------------------------------------------------------------------ connection

    fun connect() {
        if (connectJob?.isActive == true) return
        userDisconnect = false
        connectJob = scope.launch {
            val s = settingsRepo.settings.value
            try {
                doConnect(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cleanup()
                _state.value = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun doConnect(s: AppSettings) {
        cleanup()
        val t: ObdTransport = when (s.connectionType) {
            ConnectionType.DEMO -> DemoTransport(latencyMs = if (s.demoProtocol.isCan) 35 else 90, protocol = s.demoProtocol).let {
                if (s.demoFaults) FaultyTransport(it, FaultPlan.NASTY.copy(jitterMs = 3), kotlin.random.Random(System.nanoTime())) else it
            }
            ConnectionType.WIFI -> WifiTransport(s.wifiHost, s.wifiPort)
            ConnectionType.BLUETOOTH -> BluetoothClassicTransport(context, s.deviceAddress ?: throw IllegalStateException("Choose a Bluetooth adapter first"), s.deviceName)
            ConnectionType.BLE -> BleTransport(context, s.deviceAddress ?: throw IllegalStateException("Choose a Bluetooth LE adapter first"), s.deviceName)
        }
        transport = t
        _state.value = ConnectionState.Connecting("Connecting to ${t.description}")
        withTimeout(30_000) { t.open() }
        t.onDisconnected = { err -> scope.launch { linkLost(err?.message ?: "Connection lost") } }
        val e = Elm327(t) { dir, text -> logConsole(dir, text) }
        elm = e
        val info = e.initialize(
            ElmConfig(protocol = s.protocol, adaptiveTiming = s.adaptiveTiming, timeoutMs = s.elmTimeoutMs.takeIf { it > 0 }),
        ) { step -> _state.value = ConnectionState.Connecting(step) }
        val c = ObdClient(e).apply {
            batchingEnabled = s.batching
            fastResponses = s.fastMode
            stn = info.isStn
        }
        client = c
        _state.value = ConnectionState.Connecting("Reading supported sensors")
        val supported = c.discover()
        _state.value = ConnectionState.Connecting("Reading VIN")
        val vin = runCatching { c.readVin() }.getOrNull()
        val voltage = e.readVoltage()
        val snapshot = VehicleSnapshot(t.description, info, supported, c.ecus, vin, voltage, s.connectionType == ConnectionType.DEMO)
        selectVehicleFor(vin)
        if (0xA6 in supported) readOdometer(c)
        _state.value = ConnectionState.Connected(snapshot)
        updateBrand()
        live.params = fuelParams(s, snapshot)
        live.start(c, supported)
        live.subscribe("trip", listOf("010D", "010C", "CALC_FUEL"))
        updateAlertSubscriptions(s)
        trip.reset()
        tripStart = System.currentTimeMillis()
        tripActive = true
        if (s.tripGps && s.connectionType != ConnectionType.DEMO) location.start()
        startVoltageMonitor()
        // Pre-load monitor status for the home screen
        scope.launch { runCatching { scanDtcsInternal(record = false) } }
    }

    private fun fuelParams(s: AppSettings, v: VehicleSnapshot?): DerivedSensors.Params {
        val specs = garage.data.value.active?.specs
        val specKind = specs?.fuel?.let { f ->
            when {
                f.burnsDiesel -> FuelKind.DIESEL
                f == com.openobd.core.specs.SpecFuel.LPG -> FuelKind.LPG
                f == com.openobd.core.specs.SpecFuel.CNG -> FuelKind.CNG
                f == com.openobd.core.specs.SpecFuel.E85 -> FuelKind.E85
                f.isElectricOnly || f == com.openobd.core.specs.SpecFuel.OTHER -> null
                else -> FuelKind.GASOLINE
            }
        }
        val displacement = specs?.displacementL ?: s.displacementL.toDouble()
        val kind = if (s.fuel == FuelKind.AUTO && specKind != null) specKind else if (s.fuel == FuelKind.AUTO) {
            val ft = live.latestValue("0151")?.toInt()
            when (ft) { 4, 19, 23 -> FuelKind.DIESEL; 3, 11, 18 -> FuelKind.E85; 5, 12 -> FuelKind.LPG; 6, 13 -> FuelKind.CNG; else -> FuelKind.GASOLINE }
        } else s.fuel
        return DerivedSensors.Params(kind.afr, kind.densityGPerL, displacement)
    }

    private fun startVoltageMonitor() {
        voltageJob?.cancel()
        voltageJob = scope.launch {
            while (isActive) {
                delay(10_000)
                val e = elm ?: break
                val v = e.readVoltage() ?: continue
                (state.value as? ConnectionState.Connected)?.let { _state.value = it.copy(vehicle = it.vehicle.copy(voltage = v)) }
            }
        }
    }

    fun disconnect() {
        userDisconnect = true
        connectJob?.cancel()
        scope.launch {
            cleanup()
            _state.value = ConnectionState.Disconnected
        }
    }

    private suspend fun linkLost(message: String) {
        if (userDisconnect || state.value !is ConnectionState.Connected) return
        cleanup()
        _state.value = ConnectionState.Failed(message)
        if (settingsRepo.settings.value.autoConnect) {
            delay(3000)
            if (state.value is ConnectionState.Failed && !userDisconnect) connect()
        }
    }

    private suspend fun cleanup() {
        finishTrip()
        live.stop()
        voltageJob?.cancel()
        if (logs.isRecording) stopRecording()
        runCatching { transport?.close() }
        transport = null
        elm = null
        client = null
        dtcScan.value = Op.Idle; readiness.value = Op.Idle; freezeFrame.value = Op.Idle
        vehicleInfo.value = Op.Idle; monitorTests.value = Op.Idle; clearResult.value = Op.Idle
        moduleScan.value = Op.Idle; discovery.value = Op.Idle
        _bus.value = Bus.HS
    }

    private fun logConsole(dir: Char, text: String) {
        val list = _console.value
        _console.value = (if (list.size > 600) list.drop(100) else list) + ConsoleLine(System.currentTimeMillis(), dir, text)
    }

    fun clearConsole() { _console.value = emptyList() }

    // ------------------------------------------------------------------ diagnostics

    private fun <T> runOp(target: MutableStateFlow<Op<T>>, progress: String? = null, block: suspend (ObdClient) -> T) {
        val c = client
        if (c == null) { target.value = Op.Error("Not connected to a vehicle"); return }
        if (bus.value != Bus.HS) { target.value = Op.Error(MS_CAN_BUSY); return }
        if (target.value is Op.Loading) return
        target.value = Op.Loading(progress)
        scope.launch {
            live.pause()
            try {
                target.value = Op.Done(block(c))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                target.value = Op.Error(e.message ?: "Failed")
            } finally {
                live.resume()
            }
        }
    }

    private suspend fun scanDtcsInternal(record: Boolean): DtcScan {
        val c = client ?: throw IllegalStateException("Not connected")
        dtcScan.value = Op.Loading("Reading trouble codes")
        live.pause()
        try {
            val results = c.readAllDtcs()
            val readinessStatus = runCatching { c.readReadiness() }.getOrNull()
            val scan = DtcScan(results.values.flatMap { it.codes }, results.mapNotNull { (k, v) -> v.error?.let { k to it } }.toMap(), readinessStatus)
            dtcScan.value = Op.Done(scan)
            if (record) {
                history.add(ScanRecord(System.currentTimeMillis(), vehicle?.vin, readinessStatus?.milOn ?: false, vehicleId = garage.data.value.active?.id, codes =
                    scan.codes.map { StoredDtc(it.code, it.type.name, it.ecu, describe(it.code).description) }))
            }
            return scan
        } catch (e: Exception) {
            dtcScan.value = Op.Error(e.message ?: "Failed to read codes")
            throw e
        } finally {
            live.resume()
        }
    }

    fun scanDtcs() {
        if (client == null) { dtcScan.value = Op.Error("Not connected to a vehicle"); return }
        if (bus.value != Bus.HS) { dtcScan.value = Op.Error(MS_CAN_BUSY); return }
        scope.launch { runCatching { scanDtcsInternal(record = true) } }
    }

    fun clearDtcs() = runOp(clearResult, "Clearing trouble codes") { c ->
        val r = c.clearDtcs()
        if (!r.success) throw IllegalStateException(r.message)
        history.add(ScanRecord(System.currentTimeMillis(), vehicle?.vin, false, emptyList(), cleared = true, note = r.message, vehicleId = garage.data.value.active?.id))
        delay(1500) // let ECUs settle before re-reading
        scope.launch { runCatching { scanDtcsInternal(record = false) } }
        r.message
    }

    fun loadReadiness() = runOp(readiness, "Reading monitors") { c ->
        c.readReadiness(0x01) to if (vehicle?.supportedPids?.contains(0x41) == true) c.readReadiness(0x41) else null
    }

    fun loadFreezeFrame() = runOp(freezeFrame, "Reading freeze frame") { c -> c.readFreezeFrame(0) }

    fun loadVehicleInfo() = runOp(vehicleInfo, "Reading vehicle information") { c -> c.readVehicleInfo() }

    fun loadMonitorTests() = runOp(monitorTests, "Reading on-board test results") { c ->
        c.readMonitorTests { i, n -> monitorTests.value = Op.Loading("Reading monitor $i of $n") }
    }

    /** Sends a raw command from the terminal. The reply appears in [console]. */
    fun sendRaw(cmd: String) {
        val e = elm ?: run { logConsole('!', "Not connected"); return }
        scope.launch {
            live.pause()
            try {
                e.command(cmd.trim(), 10_000)
            } catch (ex: Exception) {
                logConsole('!', ex.message ?: "error")
            } finally {
                live.resume()
            }
        }
    }

    // ------------------------------------------------------------------ brand features

    // ------------------------------------------------------------------ garage / trips

    private suspend fun selectVehicleFor(vin: String?) {
        if (!garage.loaded) garage.load(settingsRepo.readOnce())
        val info = vin?.let { com.openobd.core.obd.VinDecoder.decode(it) }
        val brandName = Brands.detect(vin)?.name ?: info?.manufacturer
        val name = listOfNotNull(info?.modelYear?.toString(), brandName).joinToString(" ").ifBlank { "Car ${garage.data.value.vehicles.size + 1}" }
        garage.update { it.forConnection(vin, name, garage.newId(), System.currentTimeMillis()) }
    }

    private suspend fun readOdometer(c: ObdClient) {
        val bytes = runCatching { c.readPids(listOf(0xA6))[0xA6] }.getOrNull() ?: return
        val km = Pids.sensors["01A6"]?.decodeSafe(bytes) ?: return
        if (km > 0) garage.updateActive { it.copy(odometerKm = km, odometerUpdatedAt = System.currentTimeMillis(), odometerFromCar = true) }
    }

    private suspend fun finishTrip() {
        if (!tripActive) return
        tripActive = false
        location.stop()
        val st = trip.stats.value
        val track = location.takePoints()
        val vehicle = garage.data.value.active ?: return
        if (st.distanceKm < 0.2 && st.movingS < 60) return
        client?.takeIf { 0xA6 in (this.vehicle?.supportedPids ?: emptySet()) }?.let { runCatching { readOdometer(it) } }
        val price = settingsRepo.settings.value.fuelPricePerL
        val fuel = if (st.hasFuelData) st.fuelL else null
        garage.saveTrip(
            com.openobd.core.garage.TripRecord(
                id = garage.newId(), vehicleId = vehicle.id, start = tripStart, end = System.currentTimeMillis(),
                distanceKm = st.distanceKm, fuelL = fuel, maxSpeed = st.maxSpeed, avgSpeed = st.avgSpeed, movingS = st.movingS,
                idleS = st.idleS, maxRpm = st.maxRpm, cost = fuel?.let { f -> if (price > 0) f * price else null }, trackPoints = track.size,
            ),
            track,
        )
        // Without an odometer PID, keep the vehicle's odometer roughly current from trip distance.
        val v = garage.data.value.active
        if (v != null && !v.odometerFromCar && v.odometerKm != null) garage.updateVehicle(v.id) { it.copy(odometerKm = it.odometerKm!! + st.distanceKm, odometerUpdatedAt = System.currentTimeMillis()) }
    }

    /** Demo mode has no real GPS; drive a gentle loop so the trip map can be tried. */
    private fun demoGpsListener(): CycleListener {
        var lat = 40.4168; var lon = -3.7038; var heading = 0.0; var last = 0L
        return CycleListener { values, _, now ->
            if (vehicle?.isDemo != true || !tripActive) { last = 0L; return@CycleListener }
            val speed = values["010D"] ?: return@CycleListener
            if (last != 0L) {
                val dt = (now - last) / 1000.0
                val d = speed / 3.6 * dt
                heading += 0.004 * d
                lat += d * Math.cos(heading) / 111_320.0
                lon += d * Math.sin(heading) / (111_320.0 * Math.cos(Math.toRadians(lat)))
            }
            if (now - last >= 2000 || last == 0L) {
                location.addSynthetic(com.openobd.core.garage.TrackPoint(now, lat, lon, speed.toFloat()))
            }
            last = now
        }
    }

    private fun updateBrand() {
        val active = garage.data.value.active
        val chosen = active?.brandId?.let { Brands.byId[it] } ?: active?.specs?.make?.let { com.openobd.core.specs.brandForMake(it) }
        val detected = Brands.detect(vehicle?.vin)
        _brand.value = chosen ?: detected
        _brandDetected.value = chosen == null && detected != null
    }

    /** Modules to scan for the current brand: the family's known modules plus saved / discovered ones. */
    fun modulesForBrand(): List<EcuModule> {
        val family = (brand.value?.family ?: com.openobd.core.brand.Families.GENERIC)
        val saved = garage.data.value.active?.customModules.orEmpty()
        return (family.modules + saved).distinctBy { it.id }
    }

    /** Describes a code using the brand's manufacturer table first, then the generic database. */
    fun describe(code: String): DtcDescription {
        val base = dtcDatabase.lookup(code.substringBefore('-'))
        val b = brand.value
        val mfr = ManufacturerCodes.lookup(b?.family, code) ?: return base
        return base.copy(description = mfr, known = true, category = "Manufacturer-specific (${b?.family?.label}). " + base.category)
    }

    private fun moduleClient(): ModuleClient {
        val e = elm ?: throw IllegalStateException("Not connected to a vehicle")
        val mc = ModuleClient(e)
        if (!mc.supported) throw IllegalStateException("Module access needs a CAN vehicle (most cars from 2008 onwards). This car uses ${e.protocol.label}.")
        return mc
    }

    /** Runs [block] in the app scope with live data paused, so leaving a screen never interrupts a module operation. */
    private suspend fun <T> moduleOp(block: suspend (ModuleClient) -> T): T {
        val mc = moduleClient()
        return scope.async {
            live.pause()
            try { block(mc) } finally { live.resume() }
        }.await()
    }

    /** Modules reachable on the bus the adapter is currently on. */
    fun modulesOnCurrentBus(): List<EcuModule> = modulesForBrand().filter { it.secondaryBus == (bus.value == Bus.MS) }

    fun scanAllModules() {
        if (moduleScan.value is Op.Loading) return
        val list = modulesOnCurrentBus()
        moduleScan.value = Op.Loading("Starting")
        scope.launch {
            try {
                val results = moduleOp { mc ->
                    list.mapIndexed { i, m ->
                        moduleScan.value = Op.Loading("Scanning ${m.name} (${i + 1}/${list.size})")
                        mc.scan(m)
                    }
                }
                moduleScan.value = Op.Done(results)
                val codes = results.flatMap { r -> r.dtcs.map { StoredDtc(it.fullCode, "MODULE", r.module.shortName, describe(it.code).description) } }
                history.add(ScanRecord(System.currentTimeMillis(), vehicle?.vin, false, codes,
                    note = "Module scan: ${results.count { it.online }} of ${results.size} modules answered", vehicleId = garage.data.value.active?.id))
            } catch (e: Exception) {
                moduleScan.value = Op.Error(e.message ?: "Scan failed")
            }
        }
    }

    suspend fun scanModule(m: EcuModule): ModuleScanResult = moduleOp { it.scan(m) }.also { r ->
        (moduleScan.value as? Op.Done)?.let { d -> moduleScan.value = Op.Done(d.value.map { if (it.module.id == m.id) r else it }) }
    }

    suspend fun clearModule(m: EcuModule): ClearOutcome = moduleOp { it.clear(m) }.also { r ->
        if (r.success) history.add(ScanRecord(System.currentTimeMillis(), vehicle?.vin, false, emptyList(), cleared = true, note = "${m.name}: ${r.message}", vehicleId = garage.data.value.active?.id))
    }

    suspend fun dtcDetails(m: EcuModule, dtc: com.openobd.core.brand.ModuleDtc): com.openobd.core.brand.DtcDetails = moduleOp { it.readDtcDetails(m, dtc) }

    suspend fun identifyModule(m: EcuModule): List<Pair<String, String>> = moduleOp { it.readIdentification(m) }

    suspend fun readDid(m: EcuModule, did: Int): IntArray = moduleOp { it.readDid(m, did) }

    // ------------------------------------------------------------------ HS / MS-CAN switching

    /**
     * Moves the adapter to MS-CAN (User1 CAN, 11-bit 125 kbaud). The user must have flipped the adapter's switch first.
     * Live data is paused until [returnToHsCan] succeeds. Returns the known MS-CAN modules that answered.
     */
    suspend fun enterMsCan(): List<EcuModule> {
        val e = elm ?: throw IllegalStateException("Not connected to a vehicle")
        if (!e.baseProtocol.isCan || e.baseProtocol.is29Bit) throw IllegalStateException("MS-CAN is only used on 11-bit CAN vehicles (Ford, Lincoln, Mazda).")
        if (bus.value == Bus.MS) return emptyList()
        live.pause()
        if (!e.setProtocol(com.openobd.core.elm.ObdProtocol.USER1_CAN)) {
            live.resume()
            throw IllegalStateException("This adapter rejected protocol B (User1 CAN 125k), so it can't talk to MS-CAN.")
        }
        _bus.value = Bus.MS
        moduleScan.value = Op.Idle; discovery.value = Op.Idle
        val known = modulesForBrand().filter { it.secondaryBus }
        return if (known.isEmpty()) emptyList() else ModuleClient(e).ping(known)
    }

    /** Switches back to the vehicle's normal bus. Returns false if the car doesn't answer (switch still on MS?). */
    suspend fun returnToHsCan(): Boolean {
        val e = elm ?: return true
        if (bus.value == Bus.HS) return true
        _bus.value = Bus.RETURNING
        e.setProtocol(e.baseProtocol)
        val ok = e.query("0100", 5000) is com.openobd.core.elm.ObdResult.Data
        if (ok) {
            _bus.value = Bus.HS
            moduleScan.value = Op.Idle; discovery.value = Op.Idle
            live.resume()
        }
        return ok
    }

    suspend fun speedTest(): ObdClient.Benchmark {
        val c = client ?: throw IllegalStateException("Connect to the car first")
        if (bus.value != Bus.HS) throw IllegalStateException(MS_CAN_BUSY)
        live.pause()
        try { return c.benchmark(vehicle?.supportedPids ?: emptySet()) } finally { live.resume() }
    }

    suspend fun checkAdapter(): List<Pair<String, Boolean>> {
        val e = elm ?: throw IllegalStateException("Connect to the adapter first")
        live.pause()
        try { return e.checkCapabilities() } finally { live.resume() }
    }

    fun findModule(id: String): EcuModule? =
        modulesForBrand().firstOrNull { it.id == id } ?: (discovery.value as? Op.Done)?.value?.firstOrNull { it.id == id }

    /** Reads one custom PID once, for the editor's "Test" button. */
    suspend fun testCustomPid(p: com.openobd.core.brand.CustomPid): IntArray? = moduleOp { it.readCustom(listOf(p))[p.key] }

    fun discoverModules() {
        if (discovery.value is Op.Loading) return
        discovery.value = Op.Loading("Probing CAN addresses")
        scope.launch {
            try {
                val ms = bus.value == Bus.MS
                val found = moduleOp { mc -> mc.discover(secondaryBus = ms) { i, n -> discovery.value = Op.Loading("Probing address %03X … %d%%".format(0x700 + i, i * 100 / n)) } }
                val known = modulesForBrand()
                discovery.value = Op.Done(found.map { f -> known.firstOrNull { it.tx == f.tx && !it.is29Bit && it.secondaryBus == ms } ?: f })
            } catch (e: Exception) {
                discovery.value = Op.Error(e.message ?: "Discovery failed")
            }
        }
    }

    // ------------------------------------------------------------------ recording

    fun startRecording(keys: List<String>) {
        val names = keys.map { k -> SensorCatalog.get(k)?.let { "${it.name} (${it.unit.symbol})" } ?: k }
        logs.start(keys, names, vehicle?.vin)
        live.subscribe("recorder", keys)
        _recording.value = true
    }

    fun stopRecording() {
        logs.stop()
        live.unsubscribe("recorder")
        _recording.value = false
    }

    // ------------------------------------------------------------------ alerts

    private fun updateAlertSubscriptions(s: AppSettings) {
        val keys = s.alerts.filter { it.enabled }.map { it.sensorKey } + if (s.shiftLightRpm > 0) listOf("010C") else emptyList()
        if (keys.isEmpty()) live.unsubscribe("alerts") else live.subscribe("alerts", keys)
    }

    private fun alertChecker(): CycleListener {
        val lastFired = HashMap<Long, Long>()
        val active = HashSet<Long>()
        return CycleListener { values, _, now ->
            for (rule in settingsRepo.settings.value.alerts) {
                if (!rule.enabled) continue
                val v = values[rule.sensorKey] ?: continue
                val triggered = if (rule.above) v > rule.threshold else v < rule.threshold
                if (triggered && rule.id !in active && now - (lastFired[rule.id] ?: 0) > 30_000) {
                    lastFired[rule.id] = now
                    _alerts.tryEmit(AlertEvent(rule, v, SensorCatalog.get(rule.sensorKey)?.name ?: rule.sensorKey))
                }
                if (triggered) active += rule.id else active -= rule.id
            }
        }
    }

    // ------------------------------------------------------------------ reports

    fun buildReport(): String {
        val v = vehicle
        val sb = StringBuilder()
        val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sb.appendLine("OpenOBD diagnostic report - ${df.format(Date())}")
        sb.appendLine("=".repeat(48))
        if (v != null) {
            sb.appendLine("VIN: ${v.vin ?: "not available"}")
            brand.value?.let { sb.appendLine("Brand: ${it.name}") }
            sb.appendLine("Protocol: ${v.elm.protocol.label}")
            sb.appendLine("Adapter: ${v.elm.version} via ${v.transport}")
            v.voltage?.let { sb.appendLine("Battery: %.1f V".format(it)) }
            sb.appendLine("ECUs: " + v.ecus.joinToString { "${it.address} ${it.name}" })
        }
        (dtcScan.value as? Op.Done)?.value?.let { scan ->
            scan.readiness?.let { r ->
                sb.appendLine()
                sb.appendLine("Check engine light: ${if (r.milOn) "ON" else "OFF"} (${r.dtcCount} confirmed)")
            }
            sb.appendLine()
            sb.appendLine("Trouble codes:")
            if (scan.codes.isEmpty()) sb.appendLine("  none")
            for (d in scan.codes) sb.appendLine("  ${d.code} [${d.type.label}, ECU ${d.ecu}] ${describe(d.code).description}")
            scan.readiness?.let { r ->
                sb.appendLine()
                sb.appendLine("Readiness monitors:")
                for (m in r.monitors) sb.appendLine("  ${m.name}: ${m.state.label}")
            }
        }
        (moduleScan.value as? Op.Done)?.value?.let { results ->
            sb.appendLine()
            sb.appendLine("Control modules (${brand.value?.family?.label ?: "generic"}):")
            for (r in results) {
                sb.appendLine("  ${r.module.name} [${r.module.addressLabel}]: " + when {
                    !r.online -> "no answer"
                    r.dtcs.isEmpty() -> "OK, no codes"
                    else -> "${r.dtcs.size} code(s)"
                })
                for (d in r.dtcs) sb.appendLine("    ${d.fullCode} ${describe(d.code).description} (${d.failureTypeText ?: d.statusText})")
            }
        }
        (freezeFrame.value as? Op.Done)?.value?.let { ff ->
            sb.appendLine()
            sb.appendLine("Freeze frame (DTC ${ff.dtc ?: "-"}):")
            for ((k, value) in ff.values) {
                val s = Pids.sensors[k] ?: continue
                sb.appendLine("  ${s.name}: ${s.labeler?.invoke(value) ?: "%.${s.decimals}f %s".format(value, s.unit.symbol)}")
            }
        }
        return sb.toString()
    }
}
