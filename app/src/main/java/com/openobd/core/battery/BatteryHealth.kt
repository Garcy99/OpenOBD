package com.openobd.core.battery

import com.openobd.core.garage.TripRecord
import com.openobd.core.health.HealthSample

/**
 * How the 12 V battery holds up while the car is parked, from the voltage the app reads when it connects with the
 * engine off (each drive's health sample) and how long the car had stood since the trip before.
 *
 * The voltage is read with the ignition on, so it is a little under the true open-circuit voltage: the charge shown
 * is an estimate, good for seeing a battery fade over weeks rather than for a single figure.
 */
object BatteryHealth {
    enum class Level { GOOD, FAIR, LOW }

    data class Reading(val at: Long, val restV: Double, val parkedHours: Double?)

    data class Assessment(
        val latest: Reading,
        /** Estimated state of charge of [latest] (%). */
        val chargePct: Int,
        val level: Level,
        /** Volts lost per day parked (positive = drops), from several readings; null when it cannot be told. */
        val dropPerDay: Double?,
        /** Average charging voltage with the engine running, recent drives. */
        val chargingV: Double?,
        val readings: List<Reading>,
    ) {
        /** Losing charge noticeably faster than a healthy battery and car do. */
        val drainsFast: Boolean get() = (dropPerDay ?: 0.0) > FAST_DROP_V_PER_DAY
        val alternatorWeak: Boolean get() = chargingV != null && chargingV < 13.2
    }

    /** About 0.03 V a day is normal (the car's modules sleeping); much more points to something left drawing current. */
    const val FAST_DROP_V_PER_DAY = 0.06
    /** Readings taken soon after a drive still carry surface charge and read high. */
    private const val SETTLED_HOURS = 2.0

    /** Open-circuit voltage to state of charge of a lead-acid car battery at about 20 °C. */
    private val table = listOf(10.5 to 0.0, 11.31 to 10.0, 11.58 to 20.0, 11.75 to 30.0, 11.9 to 40.0, 12.06 to 50.0, 12.2 to 60.0,
        12.32 to 70.0, 12.42 to 80.0, 12.5 to 90.0, 12.7 to 100.0)

    fun chargePct(restV: Double): Int {
        if (restV <= table.first().first) return 0
        if (restV >= table.last().first) return 100
        val i = table.indexOfFirst { it.first >= restV }
        val (v0, p0) = table[i - 1]; val (v1, p1) = table[i]
        return Math.round(p0 + (restV - v0) / (v1 - v0) * (p1 - p0)).toInt()
    }

    fun levelOf(pct: Int) = when { pct >= 75 -> Level.GOOD; pct >= 50 -> Level.FAIR; else -> Level.LOW }

    /** Hours the car had stood before [at]: since the end of its last trip before then (null without one). */
    fun parkedHours(at: Long, trips: List<TripRecord>): Double? =
        trips.filter { it.end <= at + 60_000 }.maxOfOrNull { it.end }?.let { ((at - it) / 3_600_000.0).coerceAtLeast(0.0) }

    fun assess(samples: List<HealthSample>, trips: List<TripRecord>, now: Long = System.currentTimeMillis()): Assessment? {
        val readings = samples.filter { it.restingV != null && it.restingV in 9.0..13.5 }.sortedBy { it.time }
            .map { Reading(it.time, it.restingV!!, parkedHours(it.time, trips)) }
            .filter { now - it.at < 120L * 86_400_000 }
        if (readings.isEmpty()) return null
        // The latest settled reading tells the charge best; a fresh one only when there is nothing else.
        val latest = readings.lastOrNull { (it.parkedHours ?: SETTLED_HOURS) >= SETTLED_HOURS } ?: readings.last()
        val pct = chargePct(latest.restV)
        val charging = samples.filter { it.chargingV != null && now - it.time < 30L * 86_400_000 }.mapNotNull { it.chargingV }
            .takeIf { it.size >= 2 }?.average()
        return Assessment(latest, pct, levelOf(pct), dropPerDay(readings), charging, readings)
    }

    /**
     * Volts lost per day parked: the slope of resting voltage against days parked over the readings that stood at
     * least a couple of hours (needs several, a day or more apart in how long they stood).
     */
    fun dropPerDay(readings: List<Reading>): Double? {
        val pts = readings.filter { (it.parkedHours ?: 0.0) >= SETTLED_HOURS }.map { it.parkedHours!! / 24 to it.restV }
        if (pts.size < 3) return null
        val xs = pts.map { it.first }
        if (xs.max() - xs.min() < 1.0) return null
        val mx = xs.average(); val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0) return null
        val slope = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
        return -slope
    }

    /** The warning on connecting with a low battery (how long it stood, in hours or days). */
    fun lowWarning(volts: Double, parkedHours: Double?, pct: Int): com.openobd.core.voice.Announcement {
        val v = "%.1f".format(volts)
        return when {
            parkedHours == null -> com.openobd.core.voice.Announcement(LOW, listOf(v, pct.toString()))
            parkedHours < 36 -> com.openobd.core.voice.Announcement(LOW_HOURS, listOf(v, "%.0f".format(parkedHours), pct.toString()))
            else -> com.openobd.core.voice.Announcement(LOW_DAYS, listOf(v, "%.0f".format(parkedHours / 24), pct.toString()))
        }
    }

    private const val LOW = "Battery low: %1\$s V (about %2\$s %%). Drive for at least half an hour or charge it."
    private const val LOW_HOURS = "Battery low: %1\$s V after %2\$s hours parked (about %3\$s %%). Drive for at least half an hour or charge it."
    private const val LOW_DAYS = "Battery low: %1\$s V after %2\$s days parked (about %3\$s %%). Drive for at least half an hour or charge it."

    /** Phrases shown and said about the battery, for translation. */
    val texts = listOf(
        "Battery", LOW, LOW_HOURS, LOW_DAYS,
        "%1\$s V after %2\$s hours parked · about %3\$s %%", "%1\$s V after %2\$s days parked · about %3\$s %%", "%1\$s V · about %2\$s %%", "Good", "Could do with a charge", "Low",
        "It loses charge fast while parked (%1\$s V a day): something may be left drawing current.",
        "The alternator charges little (%1\$s V): have it checked.",
        "Read when the app connects with the engine off: an estimate.", "Alerts while driving",
    )
}
