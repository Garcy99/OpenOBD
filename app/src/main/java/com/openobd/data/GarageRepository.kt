package com.openobd.data

import android.content.Context
import com.openobd.core.garage.FuelEntry
import com.openobd.core.garage.GarageData
import com.openobd.core.garage.MaintenanceItem
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.core.garage.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** Vehicles and their maintenance, fuel log and trips, stored as JSON in app storage. */
class GarageRepository(context: Context) {
    private val file = File(context.filesDir, "garage.json")
    private val trackDir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mutex = Mutex()
    private val _data = MutableStateFlow(GarageData())
    val data: StateFlow<GarageData> = _data
    @Volatile var loaded = false
        private set

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Integer.toString((Math.random() * 1296).toInt(), 36)

    /** Loads the garage; on first run creates a default vehicle, migrating older global brand settings into it. */
    suspend fun load(legacy: AppSettings?) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val existing = runCatching { json.decodeFromString<GarageData>(file.readText()) }.getOrNull()
            _data.value = existing ?: run {
                val v = Vehicle(newId(), GarageData.DEFAULT_NAME, brandId = legacy?.brandId, customPids = legacy?.customPids.orEmpty(),
                    customModules = legacy?.customModules.orEmpty(), createdAt = System.currentTimeMillis())
                GarageData(vehicles = listOf(v), activeVehicleId = v.id).also { write(it) }
            }
            loaded = true
        }
    }

    private fun write(d: GarageData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(d))
        tmp.renameTo(file)
    }

    suspend fun update(transform: (GarageData) -> GarageData) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val d = transform(_data.value)
            _data.value = d
            runCatching { write(d) }
        }
    }

    suspend fun updateVehicle(id: String, transform: (Vehicle) -> Vehicle) =
        update { d -> d.copy(vehicles = d.vehicles.map { if (it.id == id) transform(it) else it }) }

    suspend fun updateActive(transform: (Vehicle) -> Vehicle) { _data.value.active?.let { updateVehicle(it.id, transform) } }

    suspend fun deleteVehicle(id: String) {
        val trips = _data.value.trips.filter { it.vehicleId == id }
        update { d ->
            val left = d.vehicles.filter { it.id != id }
            d.copy(
                vehicles = left,
                activeVehicleId = if (d.activeVehicleId == id) left.firstOrNull()?.id else d.activeVehicleId,
                maintenance = d.maintenance.filter { it.vehicleId != id },
                fuel = d.fuel.filter { it.vehicleId != id },
                trips = d.trips.filter { it.vehicleId != id },
                health = d.health.filter { it.vehicleId != id },
                batteryTests = d.batteryTests.filter { it.vehicleId != id },
            )
        }
        withContext(Dispatchers.IO) { trips.forEach { trackFile(it.id).delete() } }
    }

    /** Keeps the last 300 drives per car. */
    suspend fun addHealthSample(h: com.openobd.core.health.HealthSample) = update { d ->
        val mine = (d.health.filter { it.vehicleId == h.vehicleId } + h).takeLast(300)
        d.copy(health = d.health.filter { it.vehicleId != h.vehicleId } + mine)
    }

    /** Keeps the last 50 battery tests per car. */
    suspend fun addBatteryTest(r: com.openobd.core.battery.BatteryTestRecord) = update { d ->
        val mine = (d.batteryTests.filter { it.vehicleId == r.vehicleId } + r).takeLast(50)
        d.copy(batteryTests = d.batteryTests.filter { it.vehicleId != r.vehicleId } + mine)
    }

    suspend fun upsertMaintenance(item: MaintenanceItem) = update { d ->
        d.copy(maintenance = if (d.maintenance.any { it.id == item.id }) d.maintenance.map { if (it.id == item.id) item else it } else d.maintenance + item)
    }

    suspend fun deleteMaintenance(id: String) = update { d -> d.copy(maintenance = d.maintenance.filter { it.id != id }) }

    suspend fun upsertFuel(e: FuelEntry) {
        update { d -> d.copy(fuel = if (d.fuel.any { it.id == e.id }) d.fuel.map { if (it.id == e.id) e else it } else d.fuel + e) }
        // A fill-up with a higher odometer than we know also updates the vehicle's odometer.
        val v = _data.value.vehicles.firstOrNull { it.id == e.vehicleId }
        if (v != null && (v.odometerKm ?: 0.0) < e.odometerKm) updateVehicle(v.id) { it.copy(odometerKm = e.odometerKm, odometerUpdatedAt = e.date, odometerFromCar = false) }
    }

    suspend fun deleteFuel(id: String) = update { d -> d.copy(fuel = d.fuel.filter { it.id != id }) }

    private fun trackFile(tripId: String) = File(trackDir, "$tripId.json")

    suspend fun saveTrip(trip: TripRecord, track: List<TrackPoint>) {
        withContext(Dispatchers.IO) { if (track.isNotEmpty()) runCatching { trackFile(trip.id).writeText(json.encodeToString(track)) } }
        update { d -> d.copy(trips = (listOf(trip) + d.trips).take(1000)) }
    }

    suspend fun loadTrack(tripId: String): List<TrackPoint> = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString<List<TrackPoint>>(trackFile(tripId).readText()) }.getOrDefault(emptyList())
    }

    suspend fun deleteTrip(id: String) {
        update { d -> d.copy(trips = d.trips.filter { it.id != id }) }
        withContext(Dispatchers.IO) { trackFile(id).delete() }
    }
}
