package com.openobd.session

import android.content.Context
import com.openobd.core.dtc.DtcDatabase
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

data class AlertEvent(val rule: AlertRule, val value: Double, val sensorName: String)

class ObdSession(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settingsRepo: SettingsRepository,
    val history: HistoryRepository,
    val logs: LogRepository,
    val dtcDatabase: DtcDatabase,
) {
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
                cleanup()
                _state.value = ConnectionState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun doConnect(s: AppSettings) {
        cleanup()
        val t: ObdTransport = when (s.connectionType) {
            ConnectionType.DEMO -> DemoTransport()
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
        }
        client = c
        _state.value = ConnectionState.Connecting("Reading supported sensors")
        val supported = c.discover()
        _state.value = ConnectionState.Connecting("Reading VIN")
        val vin = runCatching { c.readVin() }.getOrNull()
        val voltage = e.readVoltage()
        val snapshot = VehicleSnapshot(t.description, info, supported, c.ecus, vin, voltage, t is DemoTransport)
        _state.value = ConnectionState.Connected(snapshot)
        live.params = fuelParams(s, snapshot)
        live.start(c, supported)
        live.subscribe("trip", listOf("010D", "010C", "CALC_FUEL"))
        updateAlertSubscriptions(s)
        trip.reset()
        startVoltageMonitor()
        // Pre-load monitor status for the home screen
        scope.launch { runCatching { scanDtcsInternal(record = false) } }
    }

    private fun fuelParams(s: AppSettings, v: VehicleSnapshot?): DerivedSensors.Params {
        val kind = if (s.fuel == FuelKind.AUTO) {
            val ft = live.latestValue("0151")?.toInt()
            when (ft) { 4, 19, 23 -> FuelKind.DIESEL; 3, 11, 18 -> FuelKind.E85; 5, 12 -> FuelKind.LPG; 6, 13 -> FuelKind.CNG; else -> FuelKind.GASOLINE }
        } else s.fuel
        return DerivedSensors.Params(kind.afr, kind.densityGPerL, s.displacementL.toDouble())
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
        live.stop()
        voltageJob?.cancel()
        if (logs.isRecording) stopRecording()
        runCatching { transport?.close() }
        transport = null
        elm = null
        client = null
        dtcScan.value = Op.Idle; readiness.value = Op.Idle; freezeFrame.value = Op.Idle
        vehicleInfo.value = Op.Idle; monitorTests.value = Op.Idle; clearResult.value = Op.Idle
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
                history.add(ScanRecord(System.currentTimeMillis(), vehicle?.vin, readinessStatus?.milOn ?: false,
                    scan.codes.map { StoredDtc(it.code, it.type.name, it.ecu, dtcDatabase.lookup(it.code).description) }))
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
        scope.launch { runCatching { scanDtcsInternal(record = true) } }
    }

    fun clearDtcs() = runOp(clearResult, "Clearing trouble codes") { c ->
        val r = c.clearDtcs()
        if (!r.success) throw IllegalStateException(r.message)
        history.add(ScanRecord(System.currentTimeMillis(), vehicle?.vin, false, emptyList(), cleared = true, note = r.message))
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
            for (d in scan.codes) sb.appendLine("  ${d.code} [${d.type.label}, ECU ${d.ecu}] ${dtcDatabase.lookup(d.code).description}")
            scan.readiness?.let { r ->
                sb.appendLine()
                sb.appendLine("Readiness monitors:")
                for (m in r.monitors) sb.appendLine("  ${m.name}: ${m.state.label}")
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
