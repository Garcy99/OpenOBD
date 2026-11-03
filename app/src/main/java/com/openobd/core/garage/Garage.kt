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
)

data class MaintenanceStatus(val remainingKm: Double?, val remainingDays: Long?, val overdue: Boolean, val dueSoon: Boolean, val fraction: Float?) {
    val known: Boolean get() = remainingKm != null || remainingDays != null
}

object Maintenance {
    private const val DAY = 86_400_000L

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
        Triple("Inspection / MOT / ITV", null, 12),
    )

    fun status(item: MaintenanceItem, currentKm: Double?, now: Long): MaintenanceStatus {
        val remKm = if (item.intervalKm != null && item.lastKm != null && currentKm != null) item.lastKm + item.intervalKm - currentKm else null
        val remDays = if (item.intervalMonths != null && item.lastDate != null) {
            val due = item.lastDate + item.intervalMonths * 30.44 * DAY
            ((due - now) / DAY).toLong()
        } else null
        val fracKm = if (remKm != null) (1 - remKm / item.intervalKm!!).toFloat() else null
        val fracDays = if (remDays != null) (1 - remDays / (item.intervalMonths!! * 30.44)).toFloat() else null
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
    val pricePerLiter: Double get() = if (liters > 0) totalCost / liters else 0.0
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

@Serializable
data class TrackPoint(val t: Long, val lat: Double, val lon: Double, val speedKmh: Float? = null)

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
) {
    val avgL100: Double? get() = if (fuelL != null && distanceKm > 0.5) fuelL / distanceKm * 100 else null
}

@Serializable
data class GarageData(
    val vehicles: List<Vehicle> = emptyList(),
    val activeVehicleId: String? = null,
    val maintenance: List<MaintenanceItem> = emptyList(),
    val fuel: List<FuelEntry> = emptyList(),
    val trips: List<TripRecord> = emptyList(),
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
