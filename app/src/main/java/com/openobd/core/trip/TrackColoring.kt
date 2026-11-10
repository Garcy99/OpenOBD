package com.openobd.core.trip

import com.openobd.core.garage.TrackPoint

/**
 * Colours for a trip's route on the map: by speed (the usual bands) or by consumption, where the trip's own average
 * splits it into thrifty, normal and thirsty stretches (so the map always shows where this drive spent most).
 */
object TrackColoring {
    enum class Mode { SPEED, CONSUMPTION }

    /** A run of consecutive points of the same colour (band). */
    data class Run(val from: Int, val to: Int, val band: Int)

    /** Consumption bands: 0 thrifty, 1 normal, 2 thirsty, 3 unknown (stopped, or no value). */
    val consumptionColors = listOf("#2E9E5B", "#F2A900", "#E0393E", "#9AA3AD")

    /** Thresholds (L/100 km) between the consumption bands for these points, or null without consumption data. */
    fun consumptionLimits(points: List<TrackPoint>): Pair<Double, Double>? {
        val moving = points.filter { (it.speedKmh ?: 0f) >= 5f }.mapNotNull { it.l100?.toDouble()?.takeIf { v -> v in 0.1..100.0 } }
        if (moving.size < 5) return null
        val avg = moving.average()
        return avg * 0.8 to avg * 1.3
    }

    fun band(p: TrackPoint, mode: Mode, limits: Pair<Double, Double>?): Int = when (mode) {
        Mode.SPEED -> RouteGeometry.band(p.speedKmh)
        Mode.CONSUMPTION -> {
            val v = p.l100?.toDouble()
            when {
                limits == null || v == null || (p.speedKmh ?: 0f) < 5f || v !in 0.1..100.0 -> 3
                v < limits.first -> 0
                v <= limits.second -> 1
                else -> 2
            }
        }
    }

    /** The route split into runs of one colour (each run shares its first point with the previous one's last). */
    fun runs(points: List<TrackPoint>, mode: Mode): List<Run> {
        if (points.size < 2) return emptyList()
        val limits = if (mode == Mode.CONSUMPTION) consumptionLimits(points) else null
        val bands = points.map { band(it, mode, limits) }
        val out = ArrayList<Run>()
        var start = 0
        for (i in 1 until points.size) {
            if (bands[i] != bands[start] || i == points.size - 1) {
                val end = i
                out += Run(start, end, bands[start])
                start = i
            }
        }
        return out
    }

    fun colors(mode: Mode) = if (mode == Mode.SPEED) RouteGeometry.bandColors else consumptionColors
}
