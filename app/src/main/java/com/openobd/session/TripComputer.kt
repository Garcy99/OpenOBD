package com.openobd.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class TripStats(
    val startedAt: Long = System.currentTimeMillis(),
    val durationS: Double = 0.0,
    val movingS: Double = 0.0,
    val idleS: Double = 0.0,
    val distanceKm: Double = 0.0,
    val fuelL: Double = 0.0,
    val maxSpeed: Double = 0.0,
    val maxRpm: Double = 0.0,
    val hasFuelData: Boolean = false,
) {
    val avgSpeed: Double get() = if (movingS > 0) distanceKm / (movingS / 3600.0) else 0.0
    val avgConsumption: Double? get() = if (hasFuelData && distanceKm > 0.1) fuelL / distanceKm * 100.0 else null
}

/** Integrates speed and fuel flow into a trip computer (distance, fuel used, averages). */
class TripComputer : CycleListener {
    private val _stats = MutableStateFlow(TripStats())
    val stats: StateFlow<TripStats> = _stats
    private var lastTime = 0L

    fun reset() {
        _stats.value = TripStats()
        lastTime = 0L
    }

    override fun onCycle(values: Map<String, Double>, fresh: Set<String>, timeMs: Long) {
        val dt = if (lastTime == 0L) 0.0 else (timeMs - lastTime) / 1000.0
        lastTime = timeMs
        if (dt <= 0 || dt > 5) return // ignore gaps (paused polling, reconnects)
        val speed = values["010D"] ?: return
        val rpm = values["010C"] ?: 0.0
        val fuel = values["CALC_FUEL"]
        val s = _stats.value
        _stats.value = s.copy(
            durationS = s.durationS + dt,
            movingS = s.movingS + if (speed > 1) dt else 0.0,
            idleS = s.idleS + if (speed <= 1 && rpm > 300) dt else 0.0,
            distanceKm = s.distanceKm + speed * dt / 3600.0,
            fuelL = s.fuelL + (fuel ?: 0.0) * dt / 3600.0,
            maxSpeed = maxOf(s.maxSpeed, speed),
            maxRpm = maxOf(s.maxRpm, rpm),
            hasFuelData = s.hasFuelData || fuel != null,
        )
    }
}
