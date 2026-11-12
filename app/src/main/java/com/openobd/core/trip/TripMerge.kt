package com.openobd.core.trip

import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord

/**
 * Joins trips that are really one drive (a link lost on the way, a stop for petrol...) into one: distances, times and
 * fuel added up, the highest speed and engine speed kept, the smooth-driving score worked out again for the whole.
 */
object TripMerge {
    /** Gaps up to this long between one trip's end and the next one's start look like the same drive. */
    const val SUGGEST_GAP_MS = 15 * 60_000L

    fun merge(trips: List<TripRecord>, id: String): TripRecord {
        require(trips.size >= 2) { "Two or more trips are needed" }
        require(trips.map { it.vehicleId }.distinct().size == 1) { "Trips of different cars cannot be joined" }
        val t = trips.sortedBy { it.start }
        val km = t.sumOf { it.distanceKm }
        val moving = t.sumOf { it.movingS }
        val idle = t.sumOf { it.idleS }
        // Fuel and money only when every piece knows them (else the average would be wrong).
        val fuel = if (t.all { it.fuelL != null }) t.sumOf { it.fuelL!! } else null
        val cost = if (t.all { it.cost != null }) t.sumOf { it.cost!! } else null
        val eco = EcoStats(t.sumOf { it.harshAccel }, t.sumOf { it.harshBrake }, t.sumOf { it.highRpmS }, t.sumOf { it.longIdleS }, moving + idle, km)
        return TripRecord(
            id = id, name = t.firstOrNull { it.name.isNotBlank() }?.name.orEmpty(), vehicleId = t.first().vehicleId,
            start = t.first().start, end = t.maxOf { it.end }, distanceKm = km, fuelL = fuel,
            maxSpeed = t.maxOf { it.maxSpeed }, avgSpeed = if (moving > 0) km / (moving / 3600) else 0.0,
            movingS = moving, idleS = idle, maxRpm = t.maxOf { it.maxRpm }, cost = cost, trackPoints = t.sumOf { it.trackPoints },
            harshAccel = eco.harshAccel, harshBrake = eco.harshBrake, highRpmS = eco.highRpmS, longIdleS = eco.longIdleS,
            ecoScore = EcoScorer.score(eco), fuelModel = t.minOf { it.fuelModel }, fuelCalibration = t.first().fuelCalibration,
            fuelMeasured = t.all { it.fuelMeasured }, fuelPrice = if (fuel != null && cost != null && fuel > 0) cost / fuel else t.firstNotNullOfOrNull { it.fuelPrice },
            fuelPriceLocal = t.all { it.fuelPriceLocal }, purpose = t.firstOrNull { it.purpose.isNotBlank() }?.purpose.orEmpty(),
        )
    }

    /** The routes of the pieces, one after the other. */
    fun mergeTracks(tracks: List<List<TrackPoint>>): List<TrackPoint> = tracks.flatten().sortedBy { it.t }

    /**
     * Runs of consecutive trips of the same car that look like one drive cut in pieces (each piece starting within
     * [maxGapMs] of the previous one's end), newest first.
     */
    fun suggestions(trips: List<TripRecord>, maxGapMs: Long = SUGGEST_GAP_MS): List<List<TripRecord>> {
        val out = ArrayList<List<TripRecord>>()
        for ((_, car) in trips.groupBy { it.vehicleId }) {
            val sorted = car.sortedBy { it.start }
            var run = arrayListOf(sorted.firstOrNull() ?: continue)
            for (t in sorted.drop(1)) {
                val gap = t.start - run.last().end
                if (gap in -60_000L..maxGapMs) run += t
                else { if (run.size >= 2) out += run; run = arrayListOf(t) }
            }
            if (run.size >= 2) out += run
        }
        return out.sortedByDescending { r -> r.first().start }
    }
}
