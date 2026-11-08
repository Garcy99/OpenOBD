package com.openobd.core.garage

import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.EcuModule
import kotlinx.serialization.Serializable

@Serializable
data class Vehicle(
    val id: String,
    val name: String,
    val vin: String? = null,
    /** Manually chosen brand; null = detect from VIN. */
    val brandId: String? = null,
    val customPids: List<CustomPid> = emptyList(),
    val customModules: List<EcuModule> = emptyList(),
    val odometerKm: Double? = null,
    val odometerUpdatedAt: Long? = null,
    /** True when the odometer came from the car (PID A6) rather than typed in. */
    val odometerFromCar: Boolean = false,
    val createdAt: Long = 0,
    /** Model and specifications from a registry / catalog lookup, editable by the user. */
    val specs: com.openobd.core.specs.VehicleSpecs? = null,
    /** Settings this car can change (from packs or typed in), and routines it can run. */
    val codings: List<com.openobd.core.brand.Coding> = emptyList(),
    val routines: List<com.openobd.core.brand.Routine> = emptyList(),
)

@Serializable
data class MaintenanceItem(
    val id: String,
    val vehicleId: String,
    val name: String,
    val intervalKm: Int? = null,
    val intervalMonths: Int? = null,
    val lastKm: Double? = null,
    val lastDate: Long? = null,
    val notes: String = "",
    /** Fixed due date (e.g. inspection date from the registry or the Spanish ITV calendar); overrides the interval. */
    val dueDate: Long? = null,
)

data class MaintenanceStatus(val remainingKm: Double?, val remainingDays: Long?, val overdue: Boolean, val dueSoon: Boolean, val fraction: Float?) {
    val known: Boolean get() = remainingKm != null || remainingDays != null
}

object Maintenance {
    private const val DAY = 86_400_000L

    /** English name of the inspection preset (stored translated; the id of the auto-created one is "inspection_<car>"). */
    const val INSPECTION = "Inspection / MOT / ITV"

    fun isInspection(item: MaintenanceItem, translatedName: String = INSPECTION): Boolean =
        item.id.startsWith("inspection_") || item.name == INSPECTION || item.name == translatedName

    /**
     * Next inspection of a car: the date it was last passed (the reminder's "done" date) comes first, then a date
     * typed in the reminder or found in a registry, then the Spanish calendar from the first registration.
     */
    fun nextInspection(specs: com.openobd.core.specs.VehicleSpecs?, items: List<MaintenanceItem>, now: Long, translatedName: String = INSPECTION): Long? {
        val item = items.firstOrNull { isInspection(it, translatedName) }
        return item?.dueDate
            ?: item?.lastDate?.let { passed -> if (specs?.country == "ES" || specs == null) com.openobd.core.specs.Spain.nextItvAfter(passed, specs?.firstRegistration, null)
                else passed + (item.intervalMonths ?: 12) * 30.44 * DAY }?.toLong()
            ?: specs?.inspectionDue
            ?: specs?.takeIf { it.country == "ES" }?.firstRegistration?.let { com.openobd.core.specs.Spain.nextItv(it, now) }
    }

    /** The reminder after passing the inspection on [passed]: next date by the Spanish rules (or the item's interval elsewhere). */
    fun inspectionPassed(item: MaintenanceItem, specs: com.openobd.core.specs.VehicleSpecs?, passed: Long, km: Double?): MaintenanceItem {
        val spanish = specs == null || specs.country.isBlank() || specs.country == "ES"
        val next = if (spanish) com.openobd.core.specs.Spain.nextItvAfter(passed, specs?.firstRegistration, item.dueDate)
            else (passed + (item.intervalMonths ?: 12) * 30.44 * DAY).toLong()
        val months = if (spanish) com.openobd.core.specs.Spain.itvIntervalMonths(specs?.firstRegistration, passed) else item.intervalMonths
        return item.copy(lastDate = passed, lastKm = km ?: item.lastKm, dueDate = next, intervalMonths = months)
    }

    /** Typical intervals; the user adjusts them to the owner's manual. */
    val presets: List<Triple<String, Int?, Int?>> = listOf(
        Triple("Engine oil & filter", 10_000, 12),
        Triple("Tire rotation", 10_000, null),
        Triple("Air filter", 30_000, 24),
        Triple("Cabin filter", 20_000, 12),
        Triple("Brake fluid", null, 24),
        Triple("Spark plugs", 60_000, null),
        Triple("Coolant", 100_000, 60),
        Triple("Timing belt", 100_000, 72),
        Triple("Transmission fluid", 60_000, null),
        Triple("Brake pads (inspect)", 20_000, 12),
        Triple(INSPECTION, null, 12),
    )

    fun status(item: MaintenanceItem, currentKm: Double?, now: Long): MaintenanceStatus {
        val remKm = if (item.intervalKm != null && item.lastKm != null && currentKm != null) item.lastKm + item.intervalKm - currentKm else null
        val remDays = if (item.dueDate != null) (item.dueDate - now) / DAY
        else if (item.intervalMonths != null && item.lastDate != null) {
            val due = item.lastDate + item.intervalMonths * 30.44 * DAY
            ((due - now) / DAY).toLong()
        } else null
        val fracKm = if (remKm != null) (1 - remKm / item.intervalKm!!).toFloat() else null
        val fracDays = if (remDays != null) (1 - remDays / ((item.intervalMonths ?: 12) * 30.44)).toFloat() else null
        val fraction = listOfNotNull(fracKm, fracDays).maxOrNull()?.coerceIn(0f, 1f)
        val overdue = (remKm != null && remKm < 0) || (remDays != null && remDays < 0)
        val soon = !overdue && ((remKm != null && remKm < 1000) || (remDays != null && remDays < 30))
        return MaintenanceStatus(remKm, remDays, overdue, soon, fraction)
    }
}

@Serializable
data class FuelEntry(
    val id: String,
    val vehicleId: String,
    val date: Long,
    val odometerKm: Double,
    val liters: Double,
    val totalCost: Double,
    val fullTank: Boolean = true,
    val note: String = "",
) {
}

data class FuelSummary(
    val averageL100: Double?,
    val lastL100: Double?,
    val totalLiters: Double,
    val totalCost: Double,
    val distanceKm: Double,
    val costPerKm: Double?,
    /** Consumption for each full-tank entry that closes a measurable interval, by entry id. */
    val perEntry: Map<String, Double>,
)

object FuelStats {
    /**
     * Full-to-full method: consumption at a full fill = litres added since the previous full fill
     * (including partial fills in between) / distance driven since that fill.
     */
    fun compute(entries: List<FuelEntry>): FuelSummary {
        val sorted = entries.sortedBy { it.odometerKm }
        val per = LinkedHashMap<String, Double>()
        var lastFull: FuelEntry? = null
        var litersSince = 0.0
        var measuredLiters = 0.0
        var measuredKm = 0.0
        for (e in sorted) {
            if (lastFull == null) {
                if (e.fullTank) lastFull = e
                continue
            }
            litersSince += e.liters
            if (e.fullTank) {
                val km = e.odometerKm - lastFull.odometerKm
                if (km > 0) {
                    per[e.id] = litersSince / km * 100
                    measuredLiters += litersSince
                    measuredKm += km
                }
                lastFull = e
                litersSince = 0.0
            }
        }
        val distance = if (sorted.size >= 2) sorted.last().odometerKm - sorted.first().odometerKm else 0.0
        val cost = entries.sumOf { it.totalCost }
        // Cost per km excludes the first fill (that fuel was used before tracking started)
        val costAfterFirst = sorted.drop(1).sumOf { it.totalCost }
        return FuelSummary(
            averageL100 = if (measuredKm > 0) measuredLiters / measuredKm * 100 else null,
            lastL100 = per.values.lastOrNull(),
            totalLiters = entries.sumOf { it.liters },
            totalCost = cost,
            distanceKm = distance,
            costPerKm = if (distance > 0) costAfterFirst / distance else null,
            perEntry = per,
        )
    }
}

/** Real consumption of a car from its fuel log and recorded trips, and the calibration of the estimate. */
object RealConsumption {
    data class Result(val l100: Double, val fromFuelLog: Boolean, val km: Double)

    /** Average consumption: from the fuel log (full-to-full) when available, else from trips. */
    fun average(fuel: List<FuelEntry>, trips: List<TripRecord>): Result? {
        val f = FuelStats.compute(fuel)
        if (f.averageL100 != null && f.perEntry.isNotEmpty()) {
            return Result(f.averageL100, true, fuelLogKm(fuel))
        }
        val usable = trips.filter { it.fuelL != null && it.distanceKm > 0.5 && (it.fuelModel >= com.openobd.core.obd.DerivedSensors.FUEL_MODEL || it.fuelMeasured) }
        val km = usable.sumOf { it.distanceKm }
        if (km < 30) return null
        return Result(usable.sumOf { it.fuelL!! } / km * 100, false, km)
    }

    private fun fuelLogKm(fuel: List<FuelEntry>): Double {
        val full = fuel.filter { it.fullTank }.sortedBy { it.odometerKm }
        return if (full.size >= 2) full.last().odometerKm - full.first().odometerKm else 0.0
    }

    /**
     * Factor that turns the raw estimate into the real consumption: litres actually filled between full tanks,
     * compared with the uncalibrated estimate of the trips driven between those fill-ups. Null until there is
     * enough data (at least 100 km of estimated trips inside full-to-full intervals).
     */
    fun calibration(fuel: List<FuelEntry>, trips: List<TripRecord>): Double? {
        val sorted = fuel.sortedBy { it.date }
        val estimated = trips.filter { it.fuelL != null && !it.fuelMeasured && it.fuelModel >= com.openobd.core.obd.DerivedSensors.FUEL_MODEL }
        var realL = 0.0; var realKm = 0.0; var estL = 0.0; var estKm = 0.0
        var lastFull: FuelEntry? = null
        var litres = 0.0
        for (e in sorted) {
            if (lastFull == null) { if (e.fullTank) lastFull = e; continue }
            litres += e.liters
            if (!e.fullTank) continue
            val km = e.odometerKm - lastFull.odometerKm
            val inside = estimated.filter { it.start >= lastFull!!.date && it.end <= e.date }
            val tripKm = inside.sumOf { it.distanceKm }
            // Only intervals where most of the driving was recorded
            if (km > 0 && tripKm >= km * 0.6) {
                realL += litres; realKm += km
                estL += inside.sumOf { it.fuelL!! / it.fuelCalibration }; estKm += tripKm
            }
            lastFull = e; litres = 0.0
        }
        if (estKm < 100 || realKm <= 0 || estL <= 0) return null
        return ((realL / realKm) / (estL / estKm)).coerceIn(0.3, 3.0)
    }
}

@Serializable
data class TrackPoint(
    val t: Long,
    val lat: Double,
    val lon: Double,
    val speedKmh: Float? = null,
    // What the car was doing at this point (null in tracks saved by older versions)
    val rpm: Float? = null,
    val l100: Float? = null,
    val coolantC: Float? = null,
    val pedal: Float? = null,
    val boostBar: Float? = null,
) {
    companion object {
        /** Live values stored with each point of a GPS track. */
        val KEYS = listOf("010D", "010C", "CALC_CONS", "0105", "CALC_PEDAL", "CALC_BOOST")

        fun of(t: Long, lat: Double, lon: Double, gpsSpeedKmh: Float?, value: (String) -> Double?) = TrackPoint(
            t, lat, lon, value("010D")?.toFloat() ?: gpsSpeedKmh, value("010C")?.toFloat(), value("CALC_CONS")?.toFloat(),
            value("0105")?.toFloat(), value("CALC_PEDAL")?.toFloat(), value("CALC_BOOST")?.toFloat(),
        )
    }

    fun distanceMetersTo(lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat); val dLon = Math.toRadians(lon2 - lon)
        val a = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(a))
    }
}

/** The recorded point closest to where the user tapped the map, if within [maxMeters]. */
fun List<TrackPoint>.nearest(lat: Double, lon: Double, maxMeters: Double): TrackPoint? =
    minByOrNull { it.distanceMetersTo(lat, lon) }?.takeIf { it.distanceMetersTo(lat, lon) <= maxMeters }

@Serializable
data class TripRecord(
    val id: String,
    val vehicleId: String,
    val start: Long,
    val end: Long,
    val distanceKm: Double,
    val fuelL: Double? = null,
    val maxSpeed: Double = 0.0,
    val avgSpeed: Double = 0.0,
    val movingS: Double = 0.0,
    val idleS: Double = 0.0,
    val maxRpm: Double = 0.0,
    val cost: Double? = null,
    val trackPoints: Int = 0,
    val harshAccel: Int = 0,
    val harshBrake: Int = 0,
    val highRpmS: Double = 0.0,
    val longIdleS: Double = 0.0,
    /** Smooth-driving score 0-100; null for short trips and trips saved by older versions. */
    val ecoScore: Int? = null,
    /** Fuel estimate version ([com.openobd.core.obd.DerivedSensors.FUEL_MODEL]); 0 = older versions. */
    val fuelModel: Int = 0,
    /** Calibration factor applied to [fuelL] when it was recorded. */
    val fuelCalibration: Double = 1.0,
    /** True when the car reported its fuel rate (PID 5E) rather than it being estimated. */
    val fuelMeasured: Boolean = false,
) {
    val eco: com.openobd.core.trip.EcoStats get() = com.openobd.core.trip.EcoStats(harshAccel, harshBrake, highRpmS, longIdleS, movingS + idleS, distanceKm)
    val avgL100: Double? get() = if (fuelL != null && distanceKm > 0.5) fuelL / distanceKm * 100 else null
}

@Serializable
data class GarageData(
    val vehicles: List<Vehicle> = emptyList(),
    val activeVehicleId: String? = null,
    val maintenance: List<MaintenanceItem> = emptyList(),
    val fuel: List<FuelEntry> = emptyList(),
    val trips: List<TripRecord> = emptyList(),
    /** One sample per drive, for long-term health trends. */
    val health: List<com.openobd.core.health.HealthSample> = emptyList(),
    /** Battery and starting tests. */
    val batteryTests: List<com.openobd.core.battery.BatteryTestRecord> = emptyList(),
    /** Every coding write and routine, with the backup of what was there before (newest last). */
    val writes: List<com.openobd.core.brand.WriteRecord> = emptyList(),
) {
    val active: Vehicle? get() = vehicles.firstOrNull { it.id == activeVehicleId } ?: vehicles.firstOrNull()

    /**
     * Picks (or creates) the vehicle for a connection: an existing one with the same VIN, else the active one
     * if it has no VIN yet, else a new vehicle.
     */
    fun forConnection(vin: String?, suggestedName: String, newId: String, now: Long): GarageData {
        val v = vin?.takeIf { it.length >= 11 }
        v?.let { known -> vehicles.firstOrNull { it.vin == known } }?.let { return copy(activeVehicleId = it.id) }
        val current = active
        if (current != null && current.vin == null) {
            if (v == null) return copy(activeVehicleId = current.id)
            val renamed = if (current.name == DEFAULT_NAME) suggestedName else current.name
            return copy(vehicles = vehicles.map { if (it.id == current.id) it.copy(vin = v, name = renamed) else it }, activeVehicleId = current.id)
        }
        if (v == null && current != null) return this
        val created = Vehicle(newId, suggestedName, v, createdAt = now)
        return copy(vehicles = vehicles + created, activeVehicleId = created.id)
    }

    companion object {
        const val DEFAULT_NAME = "My car"
    }
}
