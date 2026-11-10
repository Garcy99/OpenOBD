package com.openobd.session

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.openobd.core.garage.TrackPoint

/** Records a GPS track for the current trip using the platform location service (no Google services needed). */
class LocationTracker(private val context: Context) {
    private val points = mutableListOf<TrackPoint>()
    private var listener: LocationListener? = null
    /** Latest live value for a sensor key, stored with each GPS point. */
    @Volatile var valueProvider: (String) -> Double? = { null }

    fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** True while the GPS is recording. */
    val isRecording: Boolean get() = listener != null

    /** Starts recording; [keepPoints] keeps the route recorded so far (the same trip going on after a lost link). */
    @SuppressLint("MissingPermission")
    fun start(keepPoints: Boolean = false): Boolean {
        stop()
        if (!keepPoints) synchronized(points) { points.clear() }
        if (!hasPermission()) return false
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
        // A full listener: before Android 11 the other callbacks have no default and a lambda crashes when they fire.
        val l = object : LocationListener {
            override fun onLocationChanged(loc: Location) {
                synchronized(points) {
                    points += TrackPoint.of(loc.time, loc.latitude, loc.longitude, (loc.speed * 3.6f).takeIf { loc.hasSpeed() },
                        loc.altitude.toFloat().takeIf { loc.hasAltitude() }, valueProvider)
                }
            }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        return runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 5f, l, Looper.getMainLooper())
            listener = l
            true
        }.getOrDefault(false)
    }

    fun stop() {
        listener?.let { l -> runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(l) } }
        listener = null
    }

    fun takePoints(): List<TrackPoint> = synchronized(points) { points.toList().also { points.clear() } }

    /** Used by demo mode so trips have a route to show. */
    fun addSynthetic(p: TrackPoint) = synchronized(points) { points += p }
}
