package com.openobd.data

import android.content.Context
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The trip in progress, written to storage every minute. If the app is closed in the middle of a drive (Android
 * reclaiming memory, the phone switched off, a crash), the next start saves what was driven instead of losing it.
 */
class TripCheckpoints(context: Context) {
    @Serializable
    data class Checkpoint(val trip: TripRecord, val track: List<TrackPoint> = emptyList(), val savedAt: Long)

    private val dir = context.filesDir
    private val file = File(dir, "trip-checkpoint.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Synchronized
    fun write(trip: TripRecord, track: List<TrackPoint>, now: Long = System.currentTimeMillis()) {
        val tmp = File(dir, "trip-checkpoint.tmp")
        tmp.writeText(json.encodeToString(Checkpoint(trip, track, now)))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }

    @Synchronized
    fun read(): Checkpoint? = runCatching { json.decodeFromString<Checkpoint>(file.readText()) }.getOrNull()

    @Synchronized
    fun clear() { file.delete() }

    val exists: Boolean get() = file.isFile
}
