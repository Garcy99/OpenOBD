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
    val eco: com.openobd.core.trip.EcoStats = com.openobd.core.trip.EcoStats(),
) {
    val ecoScore: Int? get() = com.openobd.core.trip.EcoScorer.score(eco)
    val avgSpeed: Double get() = if (movingS > 0) distanceKm / (movingS / 3600.0) else 0.0
    val avgConsumption: Double? get() = if (hasFuelData && distanceKm > 0.1) fuelL / distanceKm * 100.0 else null
}

/** Integrates speed and fuel flow into a trip computer (distance, fuel used, averages). */
class TripComputer : CycleListener {
    private val _stats = MutableStateFlow(TripStats())
    val stats: StateFlow<TripStats> = _stats
    private var lastTime = 0L
    /** Diesel engines are judged against a lower economical engine speed. */
    @Volatile var diesel = false
    private var eco = com.openobd.core.trip.EcoScorer(diesel)

    /** When the engine first ran in this trip (the trip starts there, not when the app connected with the ignition on). */
    @Volatile var firstRunningAt: Long? = null
        private set
    /**
     * When the engine stopped, while it stays stopped (ignition still on); null while it runs. Known only from the
     * engine speed, so a car standing at a light with the engine running never counts.
     */
    private val _engineStoppedAt = MutableStateFlow<Long?>(null)
    val engineStoppedAt: StateFlow<Long?> = _engineStoppedAt
    private var lastRunningAt: Long? = null

    fun reset() {
        _stats.value = TripStats()
        lastTime = 0L
        eco = com.openobd.core.trip.EcoScorer(diesel)
        firstRunningAt = null
        lastRunningAt = null
        _engineStoppedAt.value = null
    }

    override fun onCycle(values: Map<String, Double>, fresh: Set<String>, timeMs: Long) {
        val dt = if (lastTime == 0L) 0.0 else (timeMs - lastTime) / 1000.0
        lastTime = timeMs
        if (dt <= 0 || dt > 5) return // ignore gaps (paused polling, reconnects)
        val speed = values["010D"] ?: return
        val rpmKnown = values["010C"]
        val rpm = rpmKnown ?: 0.0
        // The engine runs (or, without the engine speed, the car moves): the trip's time counts.
        val running = if (rpmKnown != null) rpm > 300 || speed > 2 else speed > 2
        if (running) {
            if (firstRunningAt == null) firstRunningAt = timeMs
            lastRunningAt = timeMs
            if (_engineStoppedAt.value != null) _engineStoppedAt.value = null
        } else if (rpmKnown != null && _engineStoppedAt.value == null && lastRunningAt != null) {
            // Stopped (ignition still on): from the last moment it was seen running.
            _engineStoppedAt.value = lastRunningAt
        }
        val fuel = values["CALC_FUEL"]
        val s = _stats.value
        eco.onSample(speed, rpm, timeMs)
        _stats.value = s.copy(
            eco = eco.current,
            durationS = s.durationS + if (running || rpmKnown == null) dt else 0.0,
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
