package com.openobd.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PerfTarget(val label: String, val speedKmh: Double? = null, val distanceM: Double? = null, val fromKmh: Double = 0.0) {
    KMH_0_60("0-60 km/h", speedKmh = 60.0),
    KMH_0_100("0-100 km/h", speedKmh = 100.0),
    MPH_0_60("0-60 mph", speedKmh = 96.5606),
    KMH_80_120("80-120 km/h", speedKmh = 120.0, fromKmh = 80.0),
    KMH_0_200("0-200 km/h", speedKmh = 200.0),
    EIGHTH_MILE("1/8 mile (201 m)", distanceM = 201.168),
    QUARTER_MILE("1/4 mile (402 m)", distanceM = 402.336),
}

enum class PerfPhase { IDLE, ARMED, RUNNING, FINISHED }

data class PerfState(
    val phase: PerfPhase = PerfPhase.IDLE,
    val elapsedS: Double = 0.0,
    val speed: Double = 0.0,
    val distanceM: Double = 0.0,
    val results: Map<PerfTarget, Double> = emptyMap(),
    val trapSpeeds: Map<PerfTarget, Double> = emptyMap(),
    val best: Map<PerfTarget, Double> = emptyMap(),
    val message: String = "Press Arm, stop the car and accelerate when ready.",
)

/** Acceleration timer driven by vehicle speed. Uses interpolation between samples for better precision. */
class PerformanceTimer : CycleListener {
    private val _state = MutableStateFlow(PerfState())
    val state: StateFlow<PerfState> = _state
    private var startTime = 0L
    private var lastTime = 0L
    private var lastSpeed = 0.0
    private var rollingStart = HashMap<PerfTarget, Double>()

    fun arm() {
        _state.value = _state.value.copy(phase = PerfPhase.ARMED, results = emptyMap(), trapSpeeds = emptyMap(), elapsedS = 0.0, distanceM = 0.0,
            message = "Armed. Timer starts as soon as the car moves.")
        rollingStart.clear()
    }

    fun cancel() { _state.value = _state.value.copy(phase = PerfPhase.IDLE, message = "Cancelled.") }

    override fun onCycle(values: Map<String, Double>, fresh: Set<String>, timeMs: Long) {
        if ("010D" !in fresh) return
        val speed = values["010D"] ?: return
        val s = _state.value
        when (s.phase) {
            PerfPhase.IDLE, PerfPhase.FINISHED -> _state.value = s.copy(speed = speed)
            PerfPhase.ARMED -> {
                if (speed <= 0.5) {
                    lastTime = timeMs; lastSpeed = 0.0
                    _state.value = s.copy(speed = speed, message = "Ready - go!")
                } else if (lastTime != 0L) {
                    // Estimate the moment we left 0 by linear extrapolation.
                    val dt = (timeMs - lastTime) / 1000.0
                    startTime = lastTime + (dt * 0.5 * 1000).toLong()
                    lastTime = timeMs; lastSpeed = speed
                    _state.value = s.copy(phase = PerfPhase.RUNNING, speed = speed, message = "Running...")
                } else {
                    _state.value = s.copy(speed = speed, message = "Stop the car completely to start.")
                }
            }
            PerfPhase.RUNNING -> {
                val dt = (timeMs - lastTime) / 1000.0
                val t0 = (lastTime - startTime) / 1000.0
                val distance = s.distanceM + (lastSpeed + speed) / 2.0 / 3.6 * dt
                val results = s.results.toMutableMap()
                val traps = s.trapSpeeds.toMutableMap()
                for (target in PerfTarget.entries) {
                    if (target in results) continue
                    target.speedKmh?.let { goal ->
                        if (target.fromKmh > 0 && target !in rollingStart && lastSpeed < target.fromKmh && speed >= target.fromKmh) {
                            rollingStart[target] = t0 + interp(lastSpeed, speed, target.fromKmh) * dt
                        }
                        if (lastSpeed < goal && speed >= goal) {
                            val tHit = t0 + interp(lastSpeed, speed, goal) * dt
                            val from = if (target.fromKmh > 0) rollingStart[target] else 0.0
                            if (from != null) results[target] = tHit - from
                        }
                    }
                    target.distanceM?.let { goal ->
                        if (s.distanceM < goal && distance >= goal) {
                            results[target] = t0 + interp(s.distanceM, distance, goal) * dt
                            traps[target] = speed
                        }
                    }
                }
                lastTime = timeMs; lastSpeed = speed
                val best = s.best.toMutableMap()
                for ((k, v) in results) if (best[k]?.let { v < it } != false) best[k] = v
                val done = speed <= 0.5 || (t0 + dt) > 90
                _state.value = s.copy(
                    phase = if (done) PerfPhase.FINISHED else PerfPhase.RUNNING,
                    elapsedS = t0 + dt, speed = speed, distanceM = distance, results = results, trapSpeeds = traps, best = best,
                    message = if (done) "Run finished." else "Running...",
                )
            }
        }
    }

    private fun interp(a: Double, b: Double, goal: Double) = if (b == a) 1.0 else ((goal - a) / (b - a)).coerceIn(0.0, 1.0)
}
