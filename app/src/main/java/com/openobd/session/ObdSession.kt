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
import com.openobd.core.elm.ElmError
import com.openobd.core.elm.ElmException
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
    /** After a lost link: the trip waits this long for the car to come back before it is saved. */
    private var tripEnd: Job? = null
    /** What the last connection learnt about the car, to come back quickly after a lost link. */
    private data class Known(val type: ConnectionType, val supported: Set<Int>, val ecus: List<com.openobd.core.obd.EcuInfo>, val vin: String?,
                             val info: com.openobd.core.elm.ElmInfo)
    private var known: Known? = null
    /** True while the app is getting a lost link back by itself (the trip goes on meanwhile). */
    @Volatile var reconnecting = false
        private set
    /** Set when, on getting a lost link back, the adapter answered but the car did not (engine off). */
    @Volatile private var carSilent = false
    /** When the link was lost with a trip kept waiting: the trip ends there if the link does not come back. */
    @Volatile private var lostAt: Long? = null
    /** A trip waiting for a lost link to come back (kept alive meanwhile, saved when it does not). */
    val tripPending: Boolean get() = tripEnd?.isActive == true
    /** A trip being recorded (connected, or waiting for a lost link). */
    val tripInProgress: Boolean get() = tripActive
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console

    val live = LiveDataManager(scope)
    val trip = TripComputer()
    /** Ends automatic trips when the engine stays off. */
    val engineOff = EngineOffDetector()
    /** True while connected for an automatic trip (started by the car's Bluetooth): ends when the engine stays off. */
    @Volatile var autoTrip = false
    private val _savedTrips = kotlinx.coroutines.flow.MutableSharedFlow<com.openobd.core.garage.TripRecord>(extraBufferCapacity = 4)
    /** Every trip as it is saved (for the "trip saved" notification). */
    val savedTrips: kotlinx.coroutines.flow.SharedFlow<com.openobd.core.garage.TripRecord> = _savedTrips
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
        newestSession = this
        live.addListener(trip)
        live.addListener(engineOff)
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
                updateBrand()
                live.params = fuelParams(settingsRepo.settings.value, vehicle)
            }
        }
        location.valueProvider = { key -> live.latestValue(key) }
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

    /** Reason this phone cannot connect right now (its adapter is lent to the car screen); set by the app. */
    var blocker: (AppSettings) -> String? = { null }

    /**
     * The usual pump price for this car's fuel at a point (null, null = where the phone is now), or null when not
     * known; set by the app (Spanish stations).
     */
    var fuelPriceAt: suspend (Double?, Double?) -> Double? = { _, _ -> null }

    /** Pump price where the current trip started, for its running cost (null = the one in Settings). */
    val tripFuelPrice = MutableStateFlow<Double?>(null)

    /** Finds a phone running the bridge (address and port); replaceable in tests. */
    var findPhone: suspend () -> Pair<String, Int>? = { com.openobd.data.BridgeDiscovery.find(context) }

    fun connect() {
        if (connectJob?.isActive == true) return
        blocker(settingsRepo.settings.value)?.let { _state.value = ConnectionState.Failed(it); return }
        userDisconnect = false
        connectJob = scope.launch {
            val s = settingsRepo.settings.value
            try {
                doConnect(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                debug.error("Connection failed", e)
                cleanup(keepTrip = tripEnd?.isActive == true)
                _state.value = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * The link to the adapter for [s]: also used by the phone bridge, which opens this phone's adapter and lends it
     * to the car screen.
     */
    suspend fun createTransport(s: AppSettings): ObdTransport = when (s.connectionType) {
        ConnectionType.DEMO -> if (demoUnplugged) throw java.io.IOException("Adapter not found") else DemoTransport(latencyMs = if (s.demoProtocol.isCan) 35 else 90, protocol = s.demoProtocol).also { it.carAnswers = !demoCarOff }.let {
            if (s.demoFaults) FaultyTransport(it, FaultPlan.NASTY.copy(jitterMs = 3), kotlin.random.Random(System.nanoTime())) else it
        }
        ConnectionType.WIFI -> WifiTransport(s.wifiHost, s.wifiPort)
        ConnectionType.USB -> com.openobd.transport.UsbSerialTransport(context, s.usbDevice, s.usbBaud) { baud ->
            if (baud != s.usbBaud) scope.launch { settingsRepo.update { it.copy(usbBaud = baud) } }
        }
        ConnectionType.BLUETOOTH -> BluetoothClassicTransport(context, s.deviceAddress ?: throw IllegalStateException("Choose a Bluetooth adapter first"), s.deviceName)
        ConnectionType.BLE -> BleTransport(context, s.deviceAddress ?: throw IllegalStateException("Choose a Bluetooth LE adapter first"), s.deviceName)
        ConnectionType.PHONE -> {
            val (host, port) = findPhone() ?: throw IllegalStateException(PHONE_NOT_FOUND)
            WifiTransport(host, port, "Phone bridge ($host)")
        }
    }

    private suspend fun doConnect(s: AppSettings) {
        cleanup(keepTrip = tripEnd?.isActive == true)
        if (s.connectionType == ConnectionType.PHONE) _state.value = ConnectionState.Connecting("Looking for your phone")
        val t = createTransport(s)
        transport = t
        _state.value = ConnectionState.Connecting("Connecting to ${t.description}")
        withTimeout(30_000) { t.open() }
        t.onDisconnected = { err -> scope.launch { linkLost(err?.message ?: "Connection lost") } }
        val e = Elm327(t) { dir, text -> logConsole(dir, text) }
        elm = e
        val info = try {
            e.initialize(
                ElmConfig(protocol = s.protocol, adaptiveTiming = s.adaptiveTiming, timeoutMs = s.elmTimeoutMs.takeIf { it > 0 }),
            ) { step -> _state.value = ConnectionState.Connecting(step) }
        } catch (x: ElmException) {
            // The adapter answers but finds no car on the bus while a trip waits: the engine was switched off.
            if (tripEnd?.isActive == true && x.error in CAR_SILENT_ERRORS) { carSilent = true; throw IllegalStateException(CAR_SILENT) }
            throw x
        }
        val c = ObdClient(e).apply {
            batchingEnabled = s.batching
            fastResponses = s.fastMode
            stn = info.isStn
        }
        client = c
        // Coming back while a trip waits: the adapter answering is not enough (most stay powered with the engine off).
        // If the car itself is silent, the drive is over: the trip is saved now instead of waiting on and on.
        if (tripEnd?.isActive == true) {
            val alive = (1..3).any { n ->
                if (n > 1) delay(700)
                runCatching { c.readPids(listOf(0x0C)).isNotEmpty() }.getOrDefault(false)
            }
            if (!alive) { carSilent = true; throw IllegalStateException(CAR_SILENT) }
        }
        // Back after a lost link to the same car: what it supports is already known, so the gauges return at once.
        val again = known?.takeIf { it.type == s.connectionType && tripEnd?.isActive == true }
        val supported: Set<Int>
        val vin: String?
        if (again != null) {
            c.restore(again.ecus)
            supported = again.supported
            vin = again.vin
        } else {
            _state.value = ConnectionState.Connecting("Reading supported sensors")
            supported = c.discover()
            _state.value = ConnectionState.Connecting("Reading VIN")
            vin = runCatching { c.readVin() }.getOrNull()
        }
        known = Known(s.connectionType, supported, c.ecus, vin, info)
        // Compression ignition flag of the monitor status (PID 01, byte B bit 3): tells diesels apart for the fuel estimate.
        compressionIgnition = runCatching { c.readPids(listOf(0x01))[0x01]?.getOrNull(1)?.let { (it shr 3) and 1 == 1 } }.getOrNull()
        val voltage = e.readVoltage()
        val snapshot = VehicleSnapshot(t.description, info, supported, c.ecus, vin, voltage, s.connectionType == ConnectionType.DEMO)
        selectVehicleFor(vin)
        if (again == null && 0xA6 in supported) readOdometer(c)
        // Battery at rest is only meaningful with the engine off.
        val rpmAtConnect = runCatching { c.readPids(listOf(0x0C))[0x0C]?.takeIf { it.size >= 2 }?.let { ((it[0] shl 8) or it[1]) / 4.0 } }.getOrNull()
        health.start(garage.data.value.active?.id, voltage?.takeIf { rpmAtConnect == 0.0 })
        _state.value = ConnectionState.Connected(snapshot)
        updateBrand()
        live.params = fuelParams(s, snapshot)
        // GM / Opel diesels keep the filter's soot load and temperatures in the engine computer's own identifiers.
        val dpfKeys = _brand.value?.family?.builtinPids.orEmpty().filter { it in com.openobd.core.brand.GM_DPF_PIDS }.map { it.key }
            .takeIf { live.params.diesel || diesel.isDiesel || compressionIgnition == true }.orEmpty()
        diesel.onConnected(supported, dpfKeys)
        // The alerts know by now whether this is a diesel (it changes what counts as revving a cold engine hard).
        voice.configure(settingsRepo.settings.value)
        // The battery as the car stood: a warning when it is low (it might not start next time, above all in the cold).
        if (rpmAtConnect == 0.0 && voltage != null && again == null) warnLowBattery(voltage)
        setUpTank(supported)
        live.timeoutsBeforeLost = if (s.connectionType == ConnectionType.PHONE || s.connectionType == ConnectionType.WIFI) 12 else 4
        live.start(c, supported)
        live.subscribe("trip", listOf("010D", "010C", "CALC_FUEL"))
        live.subscribe("health", health.keys)
        updateAlertSubscriptions(s)
        trip.diesel = diesel.isDiesel
        val resumed = tripEnd?.isActive == true && tripActive
        tripEnd?.cancel(); tripEnd = null
        reconnecting = false
        lostAt = null
        if (resumed) {
            // The same trip goes on: a lost link no longer cuts a drive in pieces.
            debug.event("Link back: the trip goes on")
        } else {
            // A trip the app could not save last time (closed mid-drive) is kept before this one starts.
            runCatching { recoverTrip() }
            trip.reset()
            tripStart = System.currentTimeMillis()
            tripActive = true
            startCheckpoints()
            engineOff.reset(tripStart)
            tripFuelPrice.value = null
            if (s.connectionType != ConnectionType.DEMO) scope.launch { tripFuelPrice.value = runCatching { fuelPriceAt(null, null) }.getOrNull() }
        }
        // The same trip going on keeps the route recorded so far (the GPS kept recording through the gap).
        if (s.tripGps && s.connectionType != ConnectionType.DEMO) { if (!resumed || !location.isRecording) location.start(keepPoints = resumed) }
        // Values stored with each point of the GPS track (shown when the map is tapped)
        if (s.tripGps || s.connectionType == ConnectionType.DEMO) live.subscribe("track", com.openobd.core.garage.TrackPoint.KEYS)
        startVoltageMonitor()
        // Pre-load monitor status for the home screen (not again when only the link came back)
        if (!resumed) scope.launch { runCatching { scanDtcsInternal(record = false) } }
    }

    /** From the car's monitor status at connection; null when unknown. */
    @Volatile private var compressionIgnition: Boolean? = null

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
            if (ft == null && compressionIgnition == true) return dieselParams(displacement, specs?.powerKw)
            when (ft) { 4, 19, 23 -> FuelKind.DIESEL; 3, 11, 18 -> FuelKind.E85; 5, 12 -> FuelKind.LPG; 6, 13 -> FuelKind.CNG; else -> FuelKind.GASOLINE }
        } else s.fuel
        return DerivedSensors.Params(kind.afr, kind.densityGPerL, displacement, diesel = kind == FuelKind.DIESEL,
            maxPowerKw = specs?.powerKw, calibration = fuelCalibration())
    }

    private fun dieselParams(displacement: Double, powerKw: Double?) = DerivedSensors.Params(FuelKind.DIESEL.afr, FuelKind.DIESEL.densityGPerL, displacement,
        diesel = true, maxPowerKw = powerKw, calibration = fuelCalibration())

    /** Real / estimated consumption of the active car, learnt from its fuel log. */
    private fun fuelCalibration(): Double {
        val g = garage.data.value
        val car = g.active ?: return 1.0
        return com.openobd.core.garage.RealConsumption.calibration(g.fuel.filter { it.vehicleId == car.id }, g.trips.filter { it.vehicleId == car.id }) ?: 1.0
    }

    private fun startVoltageMonitor() {
        voltageJob?.cancel()
        voltageJob = scope.launch {
            while (isActive) {
                delay(10_000)
                if (autoTrip && engineOff.engineOff(System.currentTimeMillis())) { debug.add('!', "Engine off: ending the automatic trip"); disconnect(); break }
                val e = elm ?: break
                val v = e.readVoltage() ?: continue
                (state.value as? ConnectionState.Connected)?.let { _state.value = it.copy(vehicle = it.vehicle.copy(voltage = v)) }
            }
        }
    }

    fun disconnect() {
        userDisconnect = true
        autoTrip = false
        reconnecting = false
        connectJob?.cancel()
        scope.launch {
            tripEnd?.cancel(); tripEnd = null
            cleanup()
            _state.value = ConnectionState.Disconnected
        }
    }

    // ------------------------------------------------------------------ fuel tank

    private val tankPrefs by lazy { context.getSharedPreferences("tank", android.content.Context.MODE_PRIVATE) }

    /**
     * The tank gauge for this connection: the car's capacity, its usual consumption (fill-ups, else trips) for the
     * range, and where to start when the car does not report its level (the last estimate, else the fuel log).
     */
    private fun setUpTank(supported: Set<Int>) {
        val g = garage.data.value
        val car = g.active
        val cap = car?.tankL
        if (car == null || (0x2F !in supported && cap == null)) { live.tank = null; live.unsubscribe("tank"); return }
        val fills = g.fuel.filter { it.vehicleId == car.id }.sortedBy { it.date }
        val trips = g.trips.filter { it.vehicleId == car.id }
        val l100 = com.openobd.core.garage.FuelStats.compute(fills).averageL100
            ?: trips.filter { it.fuelL != null && it.distanceKm > 5 }.takeLast(20).takeIf { it.isNotEmpty() }?.let { t -> t.sumOf { it.fuelL!! } / t.sumOf { it.distanceKm } * 100 }
        val lastFull = fills.lastOrNull { it.fullTank }
        val savedAt = tankPrefs.getLong("${car.id}:at", 0)
        val start = if (savedAt > (fills.lastOrNull()?.date ?: 0)) tankPrefs.getFloat("${car.id}:l", -1f).takeIf { it >= 0 }?.toDouble()
            else com.openobd.core.obd.FuelTankEstimator.fromLog(cap, lastFull?.date,
                fills.filter { lastFull != null && it.date > lastFull.date }.sumOf { it.liters },
                trips.filter { lastFull != null && it.start > lastFull.date }.sumOf { it.fuelL ?: 0.0 })
        live.tank = com.openobd.core.obd.FuelTankEstimator(cap, start, l100)
        // Always followed while connected, so the estimate keeps up even when no gauge shows it.
        live.subscribe("tank", listOf(DerivedSensors.TANK.key))
    }

    /** Keeps the tank estimate for the next connection (when the car does not report its level). */
    private fun saveTank() {
        val car = garage.data.value.active ?: return
        val l = live.tank?.litres ?: return
        tankPrefs.edit().putFloat("${car.id}:l", l.toFloat()).putLong("${car.id}:at", System.currentTimeMillis()).apply()
    }

    private fun warnLowBattery(volts: Double) {
        val car = garage.data.value.active ?: return
        val pct = com.openobd.core.battery.BatteryHealth.chargePct(volts)
        if (pct >= 50) return
        val hours = com.openobd.core.battery.BatteryHealth.parkedHours(System.currentTimeMillis(), garage.data.value.trips.filter { it.vehicleId == car.id })
        // Fresh off a drive the reading means little (and a low one then is the alternator's business, warned while driving).
        if (hours != null && hours < 2) return
        voice.warn(com.openobd.core.battery.BatteryHealth.lowWarning(volts, hours, pct))
    }

    /** For tests: the demo car is switched off (its adapter keeps answering, as real ones do). */
    @Volatile internal var demoCarOff = false
    internal fun switchOffDemoCarForTest() { demoCarOff = true; (transport as? DemoTransport)?.carAnswers = false }

    /** For tests: the link drops as if the adapter or the Wi-Fi went away. */
    internal fun dropLinkForTest() { transport?.let { t -> t.onDisconnected?.invoke(null) } }

    /** For tests: the app dies mid-drive (nothing is saved, the checkpoint stays behind). */
    internal suspend fun dieForTest() {
        checkpointJob?.cancel(); checkpointJob = null
        tripActive = false
        live.stop()
        runCatching { transport?.close() }
        transport = null; client = null; elm = null
        location.takePoints()
        _state.value = ConnectionState.Disconnected
    }

    /** For tests: the demo adapter is pulled out (the link drops and it cannot be found again). */
    @Volatile internal var demoUnplugged = false
    internal fun unplugDemoForTest() { demoUnplugged = true; dropLinkForTest() }

    private suspend fun linkLost(message: String) {
        if (userDisconnect || state.value !is ConnectionState.Connected) return
        runCatching { voice.onLinkLost() }
        runCatching { diesel.onLinkLost(System.currentTimeMillis()) }
        // The trip is kept for a while: if the link comes back (a Bluetooth or Wi-Fi hiccup), it simply goes on.
        // Not when the engine had been switched off just before (the car stopped answering, or stood with the engine
        // stopped): then the adapter being pulled out or switched off ends the drive, and the trip is saved at once.
        val engineOff = live.carSilentSince != null ||
            (live.latestValue("010C")?.let { it < 300 } == true && (live.latestValue("010D") ?: 0.0) < 2)
        if (engineOff) debug.event("Link lost with the engine off: the trip ends here")
        val keepTrip = tripActive && settingsRepo.settings.value.autoConnect && !engineOff
        val autoConnect = settingsRepo.settings.value.autoConnect
        cleanup(keepTrip = keepTrip)
        // Marked as reconnecting (and the trip as waiting) before the state changes, so nothing watching the state
        // (the background service) takes the link for gone for good in between.
        if (autoConnect) {
            reconnecting = true
            carSilent = false
            if (keepTrip) {
                if (lostAt == null) lostAt = System.currentTimeMillis()
                tripEnd?.cancel()
                tripEnd = scope.launch {
                    delay(TRIP_GRACE_MS)
                    reconnecting = false
                    if (state.value !is ConnectionState.Connected) finishTrip()
                }
            }
        }
        _state.value = ConnectionState.Failed(message)
        if (!autoConnect) return
        // Tries again and again while the trip waits (or once, without a trip).
        val until = System.currentTimeMillis() + if (keepTrip) TRIP_GRACE_MS else 0
        do {
            delay(if (settingsRepo.settings.value.connectionType.let { it == ConnectionType.PHONE || it == ConnectionType.WIFI }) 1500 else 3000)
            if (userDisconnect || newestSession !== this || state.value is ConnectionState.Connected) break
            if (state.value !is ConnectionState.Connecting) connect()
            withTimeoutOrNull(45_000) { state.first { it is ConnectionState.Connected || it is ConnectionState.Failed } }
            // The adapter is there but the car is not: the engine was switched off. The drive ends here.
            if (carSilent) {
                debug.event("Car silent after a lost link: the trip is saved")
                tripEnd?.cancel(); tripEnd = null
                finishTrip()
                break
            }
        } while (!userDisconnect && newestSession === this && state.value !is ConnectionState.Connected && System.currentTimeMillis() < until)
        if (state.value !is ConnectionState.Connected) reconnecting = false
    }

    private suspend fun cleanup(keepTrip: Boolean = false) {
        // Nothing secondary may keep the trip from being saved.
        runCatching { saveTank() }.onFailure { debug.error("Tank estimate not saved", it) }
        compressionIgnition = null
        runCatching { health.finish() }.onFailure { debug.error("Health snapshot not saved", it) }
        if (!keepTrip) try { finishTrip() } catch (e: CancellationException) { throw e } catch (e: Exception) { debug.error("Trip not saved", e) }
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

    /** The trip so far as it would be saved (also written every minute, in case the app is closed mid-drive). */
    private fun tripRecord(id: String, endAt: Long, trackPoints: Int): com.openobd.core.garage.TripRecord? {
        val vehicle = garage.data.value.active ?: return null
        val st = trip.stats.value
        val price = settingsRepo.settings.value.fuelPricePerL
        val fuel = if (st.hasFuelData) st.fuelL else null
        val local = tripFuelPrice.value
        return com.openobd.core.garage.TripRecord(
            id = id, vehicleId = vehicle.id, start = tripStart, end = maxOf(endAt, tripStart),
            distanceKm = st.distanceKm, fuelL = fuel, maxSpeed = st.maxSpeed, avgSpeed = st.avgSpeed, movingS = st.movingS,
            idleS = st.idleS, maxRpm = st.maxRpm, cost = fuel?.let { f -> if (price > 0) f * price else null }, trackPoints = trackPoints,
            harshAccel = st.eco.harshAccel, harshBrake = st.eco.harshBrake, highRpmS = st.eco.highRpmS, longIdleS = st.eco.longIdleS,
            ecoScore = st.ecoScore,
            fuelModel = DerivedSensors.FUEL_MODEL, fuelCalibration = live.params.calibration,
            fuelMeasured = 0x5E in (this.vehicle?.supportedPids ?: emptySet()),
            fuelPrice = local ?: price.toDouble().takeIf { it > 0 }, fuelPriceLocal = local != null,
        ).let { t -> if (local != null && fuel != null) t.copy(cost = fuel * local) else t }
    }

    private suspend fun finishTrip() {
        if (!tripActive) return
        tripActive = false
        checkpointJob?.cancel(); checkpointJob = null
        val endAt = lostAt ?: System.currentTimeMillis()
        lostAt = null
        location.stop()
        val st = trip.stats.value
        val track = location.takePoints()
        try {
            if (garage.data.value.active == null || !worthSaving(st.distanceKm, st.movingS)) return
            client?.takeIf { 0xA6 in (this.vehicle?.supportedPids ?: emptySet()) }?.let { runCatching { readOdometer(it) } }
            val record = tripRecord(garage.newId(), endAt, track.size) ?: return
            saveTripRecord(record, track)
            // The price where the trip ended (from the stations there), when it can be had: the trip's real cost.
            val fuel = record.fuelL
            if (fuel != null) scope.launch {
                val end = track.lastOrNull()
                val p = runCatching { fuelPriceAt(end?.lat, end?.lon) }.getOrNull() ?: return@launch
                garage.updateTrip(record.id) { it.copy(cost = fuel * p, fuelPrice = p, fuelPriceLocal = true) }
            }
        } finally {
            // Saved (or not worth saving): nothing to recover any more.
            withContext(Dispatchers.IO) { checkpoints.clear() }
        }
    }

    /** Stores a finished trip and keeps the car's odometer roughly current without an odometer PID. */
    private suspend fun saveTripRecord(record: com.openobd.core.garage.TripRecord, track: List<com.openobd.core.garage.TrackPoint>) {
        garage.saveTrip(record, track)
        garage.data.value.trips.firstOrNull { it.id == record.id }?.let { _savedTrips.tryEmit(it) }
        val v = garage.data.value.vehicles.firstOrNull { it.id == record.vehicleId }
        if (v != null && !v.odometerFromCar && v.odometerKm != null) garage.updateVehicle(v.id) { it.copy(odometerKm = it.odometerKm!! + record.distanceKm, odometerUpdatedAt = System.currentTimeMillis()) }
    }

    private fun worthSaving(km: Double, movingS: Double) = km >= 0.2 || movingS >= 60

    // ------------------------------------------------------------------ trips that survive the app being closed

    private val checkpoints = com.openobd.data.TripCheckpoints(context)
    @Volatile private var checkpointJob: Job? = null
    private val recoverLock = kotlinx.coroutines.sync.Mutex()

    /** Every minute while a trip is recorded (also while it waits for a lost link), the trip so far goes to storage. */
    private fun startCheckpoints() {
        checkpointJob?.cancel()
        checkpointJob = scope.launch {
            while (isActive) {
                delay(CHECKPOINT_MS)
                if (!tripActive) break
                writeCheckpoint()
            }
        }
    }

    internal suspend fun writeCheckpoint() {
        if (!tripActive) return
        val track = location.peekPoints()
        val record = tripRecord("checkpoint", lostAt ?: System.currentTimeMillis(), track.size) ?: return
        withContext(Dispatchers.IO) { runCatching { checkpoints.write(record, track) }.onFailure { debug.error("Trip checkpoint not written", it) } }
    }

    /**
     * A trip left behind when the app was closed mid-drive: saved now as it was at its last checkpoint. Called when the
     * app starts and before a new trip begins (never while this session is recording one).
     */
    suspend fun recoverTrip(): com.openobd.core.garage.TripRecord? = recoverLock.withLock {
        if (tripActive) return null
        val cp = withContext(Dispatchers.IO) { checkpoints.read() } ?: run { withContext(Dispatchers.IO) { checkpoints.clear() }; return null }
        withContext(Dispatchers.IO) { checkpoints.clear() }
        val t = cp.trip
        if (!worthSaving(t.distanceKm, t.movingS) || garage.data.value.vehicles.none { it.id == t.vehicleId }) return null
        val record = t.copy(id = garage.newId(), trackPoints = cp.track.size)
        debug.event("Recovered a trip left by a closed app: %.1f km".format(t.distanceKm))
        saveTripRecord(record, cp.track)
        record
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
                // Madrid sits at ~650 m; the demo road rises and falls a little.
                val alt = (650 + 25 * Math.sin(heading / 2)).toFloat()
                location.addSynthetic(com.openobd.core.garage.TrackPoint.of(now, lat, lon, speed.toFloat(), alt) { k -> values[k] })
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
        // The car's own PIDs, plus the brand's built-in ones (oil temperature and pressure on GM) it does not override.
        val own = active?.customPids.orEmpty()
        // The table is shared: only the newest session fills it (in tests, earlier ones may still be running).
        if (newestSession === this) CustomSensors.update(own + _brand.value?.family?.builtinPids.orEmpty().filter { b -> own.none { it.id == b.id } })
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

/** The session made last (the app's; tests make one per app and earlier ones may still be running). */
@Volatile private var newestSession: ObdSession? = null

/** Messages about the phone bridge (English keys, translated by the app). */
/** How long a trip waits for a lost link to come back before it is saved (and a new one starts). */
const val TRIP_GRACE_MS = 5 * 60_000L
/** How often the trip in progress is written to storage. */
const val CHECKPOINT_MS = 60_000L
const val CAR_SILENT = "The adapter answers but the car does not (engine off?)."
private val CAR_SILENT_ERRORS = setOf(ElmError.UNABLE_TO_CONNECT, ElmError.NO_DATA, ElmError.CAN_ERROR, ElmError.BUS_INIT_ERROR, ElmError.BUS_ERROR)

const val PHONE_NOT_FOUND = "No phone with the OpenOBD bridge found. On the phone, open More → Car screen and start the bridge; connect this screen to the phone's Wi-Fi hotspot (or the same Wi-Fi)."
const val PHONE_BRIDGE_BUSY = "This phone is lending its adapter to the car screen. Stop the bridge (More → Car screen) to connect here."

