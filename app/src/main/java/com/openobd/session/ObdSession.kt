package com.openobd.session

import android.content.Context
import com.openobd.core.brand.Brand
import com.openobd.core.brand.Brands
import com.openobd.core.brand.CustomSensors
import com.openobd.core.brand.ManufacturerCodes
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

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
    /** Bounded record of adapter traffic and session events for debug reports. */
    val debug: com.openobd.core.debug.DebugLog = com.openobd.core.debug.DebugLog(),
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

    // Feature areas, each in its own file.
    val modules = ModuleFeatures(this)
    val diagnosis = DiagnosisFeatures(this)
    val check = VehicleCheckFeature(this)
    val specsCheck = SpecsCheckFeature(this)
    val voice = VoiceFeature(this)
    val battery = BatteryTestFeature(this)
    val health = HealthFeature(this)
    val diesel = DieselFeature(this)
    val reports = SessionReports(this)

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

    // Access for the feature classes.
    internal val adapter: Elm327? get() = elm
    internal val appScope: CoroutineScope get() = scope

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
                voice.configure(s)
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
        live.addListener { values, _, now -> health.onCycle(values, now) }
        live.addListener { values, _, now -> diesel.onCycle(values, now) }
        live.addListener { values, _, now -> voice.onCycle(values, now) }
        scope.launch {
            var last: String? = null
            _state.collect { s ->
                val text = when (s) {
                    ConnectionState.Disconnected -> "Disconnected"
                    is ConnectionState.Connecting -> "Connecting: ${s.step}"
                    is ConnectionState.Connected -> "Connected: ${s.vehicle.transport}, ${s.vehicle.elm.version}, ${s.vehicle.elm.protocol.label}, " +
                        "${s.vehicle.supportedPids.size} PIDs, ECUs ${s.vehicle.ecus.joinToString { it.address }}"
                    is ConnectionState.Failed -> "Failed: ${s.message}"
                }
                if (text != last) debug.event(text)
                last = text
            }
        }
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
                debug.error("Connection failed", e)
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
            ConnectionType.USB -> com.openobd.transport.UsbSerialTransport(context, s.usbDevice, s.usbBaud) { baud ->
                if (baud != s.usbBaud) scope.launch { settingsRepo.update { it.copy(usbBaud = baud) } }
            }
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
        // Battery at rest is only meaningful with the engine off.
        val rpmAtConnect = runCatching { c.readPids(listOf(0x0C))[0x0C]?.takeIf { it.size >= 2 }?.let { ((it[0] shl 8) or it[1]) / 4.0 } }.getOrNull()
        health.start(garage.data.value.active?.id, voltage?.takeIf { rpmAtConnect == 0.0 })
        _state.value = ConnectionState.Connected(snapshot)
        updateBrand()
        live.params = fuelParams(s, snapshot)
        live.start(c, supported)
        live.subscribe("trip", listOf("010D", "010C", "CALC_FUEL"))
        live.subscribe("health", health.keys)
        updateAlertSubscriptions(s)
        trip.diesel = diesel.isDiesel
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
        voice.onLinkLost()
        cleanup()
        _state.value = ConnectionState.Failed(message)
        if (settingsRepo.settings.value.autoConnect) {
            delay(3000)
            if (state.value is ConnectionState.Failed && !userDisconnect) connect()
        }
    }

    private suspend fun cleanup() {
        health.finish()
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
        modules.reset()
        specsCheck.observed.value = Op.Idle
        battery.onDisconnected()
    }

    private fun logConsole(dir: Char, text: String) {
        debug.add(dir, text)
        val list = _console.value
        _console.value = (if (list.size > 600) list.drop(100) else list) + ConsoleLine(System.currentTimeMillis(), dir, text)
    }

    fun clearConsole() { _console.value = emptyList() }

    // ------------------------------------------------------------------ diagnostics

    internal fun <T> runOp(target: MutableStateFlow<Op<T>>, progress: String? = null, block: suspend (ObdClient) -> T) {
        val c = client
        if (c == null) { target.value = Op.Error("Not connected to a vehicle"); return }
        if (modules.bus.value != Bus.HS) { target.value = Op.Error(MS_CAN_BUSY); return }
        if (target.value is Op.Loading) return
        target.value = Op.Loading(progress)
        scope.launch {
            live.pause()
            try {
                target.value = Op.Done(block(c))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debug.error("Operation failed", e)
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
        if (modules.bus.value != Bus.HS) { dtcScan.value = Op.Error(MS_CAN_BUSY); return }
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
                harshAccel = st.eco.harshAccel, harshBrake = st.eco.harshBrake, highRpmS = st.eco.highRpmS, longIdleS = st.eco.longIdleS,
                ecoScore = st.ecoScore,
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
        // Cars that do not report their VIN over OBD (common before ~2005) can still use the one typed in the garage.
        val detected = Brands.detect(vehicle?.vin ?: active?.vin)
        _brand.value = chosen ?: detected
        _brandDetected.value = chosen == null && detected != null
    }

    /** Describes a code using the brand's manufacturer table first, then the generic database. */
    fun describe(code: String): DtcDescription {
        val base = dtcDatabase.lookup(code.substringBefore('-'))
        val b = brand.value
        val mfr = ManufacturerCodes.lookup(b?.family, code) ?: return base
        return base.copy(description = mfr, known = true, category = "Manufacturer-specific (${b?.family?.label}). " + base.category)
    }

    suspend fun speedTest(): ObdClient.Benchmark {
        val c = client ?: throw IllegalStateException("Connect to the car first")
        if (modules.bus.value != Bus.HS) throw IllegalStateException(MS_CAN_BUSY)
        live.pause()
        try {
            return c.benchmark(vehicle?.supportedPids ?: emptySet()).also { b -> debug.event("Speed test: $b") }
        } finally { live.resume() }
    }

    suspend fun checkAdapter(): List<Pair<String, Boolean>> {
        val e = elm ?: throw IllegalStateException("Connect to the adapter first")
        live.pause()
        try {
            return e.checkCapabilities().also { r -> debug.event("Adapter check: " + r.joinToString { "${it.first}=${if (it.second) "ok" else "MISSING"}" }) }
        } finally { live.resume() }
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
                    val event = AlertEvent(rule, v, SensorCatalog.get(rule.sensorKey)?.name ?: rule.sensorKey)
                    _alerts.tryEmit(event)
                    voice.onAlert(event)
                }
                if (triggered) active += rule.id else active -= rule.id
            }
        }
    }
}
