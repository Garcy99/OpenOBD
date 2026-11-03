package com.openobd.session

import com.openobd.core.battery.BatteryTestRecord
import com.openobd.core.battery.CrankAnalysis
import com.openobd.core.battery.CrankResult
import com.openobd.core.battery.VoltSample
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface BatteryTestState {
    data object Idle : BatteryTestState
    /** Measuring the voltage before the start. */
    data class Resting(val volts: Double?) : BatteryTestState
    /** The driver should start the engine now. */
    data class WaitingStart(val volts: Double?, val samples: List<VoltSample>) : BatteryTestState
    /** Engine started; recording the charging voltage. */
    data class Charging(val volts: Double?, val samples: List<VoltSample>) : BatteryTestState
    data class Done(val result: CrankResult, val samples: List<VoltSample>) : BatteryTestState
    data class Failed(val message: String) : BatteryTestState
}

/**
 * Battery and starting test: samples the adapter's voltmeter as fast as the link allows while the driver
 * starts the engine. Live data is paused meanwhile so every request is a voltage reading.
 */
class BatteryTestFeature internal constructor(private val s: ObdSession) {
    private val _state = MutableStateFlow<BatteryTestState>(BatteryTestState.Idle)
    val state: StateFlow<BatteryTestState> = _state
    private var job: Job? = null

    /** Simulated start for the demo car: rest 12.5 V, a short inrush spike, ~10.3-10.8 V for 1.1 s, then 14.2 V. */
    private fun demoVolts(sinceWaitMs: Long?): Double {
        val noise = (Math.random() - 0.5) * 0.06
        return when {
            sinceWaitMs == null || sinceWaitMs < 3000 -> 12.5
            sinceWaitMs < 3050 -> 9.4
            sinceWaitMs < 4200 -> 10.3 + (sinceWaitMs - 3050) / 1150.0 * 0.5
            sinceWaitMs < 4600 -> 12.6
            else -> 14.2
        } + noise
    }

    fun start() {
        if (job?.isActive == true) return
        val e = s.adapter
        if (e == null) { _state.value = BatteryTestState.Failed("Not connected to a vehicle"); return }
        val demo = s.vehicle?.isDemo == true
        job = s.appScope.launch {
            s.live.pause()
            try {
                var waitStart: Long? = null
                suspend fun read(): Double? =
                    if (demo) { delay(60); demoVolts(waitStart?.let { System.currentTimeMillis() - it }) } else e.readVoltage()

                _state.value = BatteryTestState.Resting(null)
                val rest = mutableListOf<VoltSample>()
                val restUntil = System.currentTimeMillis() + 3000
                while (System.currentTimeMillis() < restUntil) {
                    read()?.let { v -> rest += VoltSample(System.currentTimeMillis(), v); _state.value = BatteryTestState.Resting(v) }
                }
                val restV = CrankAnalysis.restOf(rest)
                if (restV == null) { _state.value = BatteryTestState.Failed("The adapter does not report the battery voltage."); return@launch }
                if (restV > 13.2) { finish(restV, emptyList(), emptyList()); return@launch }

                waitStart = System.currentTimeMillis()
                val samples = mutableListOf<VoltSample>()
                val gaps = mutableListOf<Long>()
                var runningSince: Long? = null
                var rpmRunningAt: Long? = null
                var lastRpmCheck = 0L
                val client = if (demo) null else s.client
                val runningV = minOf(CrankAnalysis.RUNNING_V, restV + 0.3)
                var crankSeen = false
                while (isActive) {
                    val now = System.currentTimeMillis()
                    val v = read()
                    if (v == null) gaps += now else samples += VoltSample(now, v)
                    if (v != null && v < CrankAnalysis.CRANK_START_V) crankSeen = true
                    if ((crankSeen || gaps.isNotEmpty()) && v != null && v > runningV && runningSince == null) runningSince = now
                    // A dead alternator keeps the voltage low after the start: ask the engine computer whether it runs.
                    if ((crankSeen || gaps.isNotEmpty()) && rpmRunningAt == null && client != null && now - lastRpmCheck > 1000) {
                        lastRpmCheck = now
                        val rpm = runCatching { client.readPids(listOf(0x0C))[0x0C] }.getOrNull()?.takeIf { it.size >= 2 }?.let { ((it[0] shl 8) or it[1]) / 4 }
                        if (rpm != null && rpm > 400) { rpmRunningAt = now; if (runningSince == null) runningSince = now }
                    }
                    _state.value = if (runningSince != null) BatteryTestState.Charging(v, samples.toList()) else BatteryTestState.WaitingStart(v, samples.toList())
                    // 10 s of charging after the start, or give up 45 s after asking to start.
                    if (runningSince != null && now - runningSince > 10_000) break
                    if (runningSince == null && now - waitStart > 45_000) break
                }
                finish(restV, samples, gaps, rpmRunningAt)
            } catch (ex: Exception) {
                _state.value = BatteryTestState.Failed(ex.message ?: "Failed")
            } finally {
                s.live.resume()
            }
        }
    }

    private suspend fun finish(restV: Double, samples: List<VoltSample>, gaps: List<Long>, rpmRunningAt: Long? = null) {
        val result = CrankAnalysis.analyze(restV, samples, gaps, s.diesel.isDiesel, rpmRunningAt)
        _state.value = BatteryTestState.Done(result, samples)
        val car = s.garage.data.value.active ?: return
        if (result.minV != null || result.adapterDropped) {
            s.garage.addBatteryTest(BatteryTestRecord(s.garage.newId(), car.id, System.currentTimeMillis(), result.restV, result.minV, result.crankMs,
                result.chargingV, result.overall.name))
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = BatteryTestState.Idle
    }

    /** The link dropped. During a start that usually means the adapter lost power. */
    internal fun onDisconnected() {
        val during = _state.value is BatteryTestState.WaitingStart || _state.value is BatteryTestState.Charging
        job?.cancel()
        _state.value = if (during) BatteryTestState.Failed(LOST_DURING_START) else BatteryTestState.Idle
    }

    companion object {
        const val LOST_DURING_START = "The connection was lost during the start. Simple adapters switch off when the voltage falls very low " +
            "(below about 8 V), which can mean a weak battery. Reconnect and repeat the test."
        val texts = listOf(LOST_DURING_START, "The adapter does not report the battery voltage.")
    }

    fun reset() { if (job?.isActive != true) _state.value = BatteryTestState.Idle }
}
