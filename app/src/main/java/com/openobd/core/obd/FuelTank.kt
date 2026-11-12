package com.openobd.core.obd

/**
 * How much fuel is in the tank, as steady and exact as the car allows. The level the car reports (PID 2F) sways with
 * every curve, brake and slope, and moves in coarse steps; the fuel burnt is known second by second. So the estimate
 * follows the fuel burnt and is pulled gently towards the level the sensor settles on (strongly while the car
 * stands still, when the fuel is level), and a refuel (the level jumping up while stopped) starts it again.
 *
 * Without the level PID it starts from what is known (the last estimate, or the last fill-up minus the trips since)
 * and follows the fuel burnt alone.
 */
class FuelTankEstimator(
    /** Tank capacity in litres (null: only the percentage is known). */
    private val capacityL: Double?,
    /** Litres to start from when the car does not report its level. */
    private val startL: Double? = null,
    /** Usual consumption in L/100 km, for the range. */
    private val l100: Double? = null,
) {
    data class Estimate(
        val pct: Double?, val litres: Double?, val rangeKm: Double?, val fromSensor: Boolean,
        /** Set on the cycle a refuel is recognised: litres added, and whether the tank was filled up. */
        val refuel: Refuel? = null,
    )

    data class Refuel(val litres: Double, val full: Boolean)

    private var estimateL: Double? = startL?.takeIf { capacityL != null }?.coerceIn(0.0, capacityL!!)
    private var estimatePct: Double? = null
    private var lastT: Long? = null
    private val window = ArrayDeque<Pair<Long, Double>>()
    private var refuelSince: Long? = null
    private var refuelFromL: Double? = null
    private var stillSince: Long? = null

    /** The settled sensor level: the median of the last half minute (sloshing cancels out). */
    private fun settled(): Double? = window.map { it.second }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    fun update(v: Map<String, Double>, now: Long): Estimate? {
        val dt = lastT?.let { ((now - it) / 1000.0).coerceIn(0.0, 10.0) } ?: 0.0
        lastT = now
        val pct = v[LEVEL]?.takeIf { it in 0.0..100.0 }
        val speed = v["010D"] ?: 0.0
        if (speed < 2) { if (stillSince == null) stillSince = now } else stillSince = null
        val still = stillSince != null && now - stillSince!! > 3_000
        if (pct != null) {
            window.addLast(now to pct)
            while (window.isNotEmpty() && now - window.first().first > WINDOW_MS) window.removeFirst()
        }
        val sensor = settled()
        val burnt = v["CALC_FUEL"]?.takeIf { it in 0.0..200.0 }?.let { it * dt / 3600 } ?: 0.0
        val cap = capacityL
        if (cap != null && cap > 0) {
            val sensorL = sensor?.let { it / 100 * cap }
            var e = estimateL
            if (e == null) {
                // A few seconds of readings before the first figure.
                if (sensorL == null || window.size < 5) return null
                e = sensorL
            }
            e -= burnt
            var refuel: Refuel? = null
            if (sensorL != null) {
                // A refuel: the settled level well above the estimate while standing still for a while.
                if (still && sensorL > e + cap * 0.08) {
                    // What was in the tank before (the estimate is pulled towards the new level while it settles).
                    if (refuelSince == null) { refuelSince = now; refuelFromL = e }
                    if (now - refuelSince!! > 20_000) {
                        refuel = Refuel(sensorL - (refuelFromL ?: e), sensorL >= cap * FULL_SHARE)
                        e = sensorL; refuelSince = null
                    }
                } else refuelSince = null
                val tau = if (still) TAU_STILL_S else TAU_MOVING_S
                e += (sensorL - e) * (dt / tau).coerceAtMost(1.0)
            }
            e = e.coerceIn(0.0, cap)
            estimateL = e
            return Estimate(e / cap * 100, e, l100?.takeIf { it > 0.5 }?.let { e / it * 100 }, sensorL != null, refuel)
        }
        // Without the capacity: the level alone, steadied.
        val s = sensor ?: return null
        val p = estimatePct?.let { it + (s - it) * (dt / (if (still) TAU_STILL_S else TAU_MOVING_S / 4)).coerceAtMost(1.0) } ?: s
        estimatePct = p
        return Estimate(p, null, null, true)
    }

    /** The litres estimated now (to start from next time). */
    val litres: Double? get() = estimateL

    companion object {
        const val LEVEL = "012F"
        private const val WINDOW_MS = 30_000L
        /** How fast the estimate gives way to the settled sensor: quickly when stopped, slowly while driving. */
        private const val TAU_STILL_S = 60.0
        private const val TAU_MOVING_S = 600.0
        /** A level this high after a refuel counts as a full tank (the gauge rarely reads 100 %). */
        private const val FULL_SHARE = 0.93

        /**
         * Litres in the tank by the fuel log: a full tank at the last fill-up, minus what the trips since burnt
         * (null without a full fill-up or without the capacity).
         */
        fun fromLog(capacityL: Double?, lastFullFillUp: Long?, partialLitresSince: Double, burntSince: Double): Double? {
            if (capacityL == null || lastFullFillUp == null) return null
            return (capacityL + partialLitresSince - burntSince).coerceIn(0.0, capacityL)
        }
    }
}
