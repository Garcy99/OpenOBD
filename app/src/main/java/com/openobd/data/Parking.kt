package com.openobd.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where the car was left at the end of its last trip (from the trip's GPS track, else the phone's position then). */
class Parking(context: Context) {
    data class Spot(val lat: Double, val lon: Double, val at: Long, val vehicleId: String)

    private val prefs = context.getSharedPreferences("parking", Context.MODE_PRIVATE)
    private val _spot = MutableStateFlow(read())
    val spot: StateFlow<Spot?> = _spot

    private fun read(): Spot? {
        if (!prefs.contains("lat")) return null
        return Spot(Double.fromBits(prefs.getLong("lat", 0)), Double.fromBits(prefs.getLong("lon", 0)), prefs.getLong("at", 0), prefs.getString("car", "").orEmpty())
    }

    fun set(s: Spot) {
        prefs.edit().putLong("lat", s.lat.toRawBits()).putLong("lon", s.lon.toRawBits()).putLong("at", s.at).putString("car", s.vehicleId).apply()
        _spot.value = s
    }

    fun clear() { prefs.edit().clear().apply(); _spot.value = null }

    companion object {
        /** Walking directions to the car in the phone's maps app (any app that opens geo: links otherwise). */
        fun walkTo(context: Context, s: Spot) {
            val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${s.lat},${s.lon}&mode=w")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(nav) }.onFailure { runCatching { context.startActivity(geo) } }
        }
    }
}
