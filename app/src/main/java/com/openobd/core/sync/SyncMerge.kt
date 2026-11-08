package com.openobd.core.sync

import com.openobd.core.garage.GarageData

/**
 * Three-way merge for syncing between devices: [base] is what both sides had at the last sync, [local] this device
 * now, [remote] what the other devices left in the cloud. Records are matched by key; whoever changed a record since
 * the last sync wins, so edits on different devices to different records all survive, and deletions travel too.
 *
 * When both sides changed the same record, this device wins. A record deleted on one side but edited on the other
 * is kept (losing an edit is worse than keeping something the user can delete again).
 */
object SyncMerge {
    fun <T> list(base: List<T>, local: List<T>, remote: List<T>, key: (T) -> String): List<T> {
        val b = base.associateBy(key); val l = local.associateBy(key); val r = remote.associateBy(key)
        val keys = LinkedHashSet<String>().apply { local.forEach { add(key(it)) }; remote.forEach { add(key(it)) } }
        return keys.mapNotNull { k -> pick(b[k], l[k], r[k]) }
    }

    /** One value (or record) by the same rule: unchanged on one side means take the other side's. */
    fun <T> pick(base: T?, local: T?, remote: T?): T? = when {
        local == remote -> local
        local == base -> remote
        remote == base -> local
        local == null -> remote
        remote == null -> local
        else -> local
    }

    /**
     * The garage: every list record by record. The active car stays this device's choice (each phone may be in a
     * different car) as long as it still exists.
     */
    fun garage(base: GarageData?, local: GarageData, remote: GarageData?): GarageData {
        if (remote == null) return local
        val b = base ?: GarageData()
        val vehicles = list(b.vehicles, local.vehicles, remote.vehicles) { it.id }
        return GarageData(
            vehicles = vehicles,
            activeVehicleId = local.activeVehicleId?.takeIf { id -> vehicles.any { it.id == id } } ?: vehicles.firstOrNull()?.id,
            maintenance = list(b.maintenance, local.maintenance, remote.maintenance) { it.id },
            fuel = list(b.fuel, local.fuel, remote.fuel) { it.id }.sortedBy { it.date },
            trips = list(b.trips, local.trips, remote.trips) { it.id }.sortedByDescending { it.start },
            health = list(b.health, local.health, remote.health) { it.vehicleId + "@" + it.time }.sortedBy { it.time },
            batteryTests = list(b.batteryTests, local.batteryTests, remote.batteryTests) { it.id }.sortedBy { it.time },
            writes = list(b.writes, local.writes, remote.writes) { it.id }.sortedBy { it.time },
        )
    }
}
