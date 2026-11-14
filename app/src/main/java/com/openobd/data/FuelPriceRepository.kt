package com.openobd.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.openobd.core.fuel.FuelPricesEs
import com.openobd.core.fuel.PriceList
import com.openobd.core.fuel.PumpFuel
import com.openobd.core.fuel.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Pump prices around the car (Spain), from the ministry's open data, downloaded by province and kept for half an
 * hour (older copies are still used when offline). Only the province numbers are sent: no location or car data.
 */
class FuelPriceRepository(private val context: Context, private val userAgent: String) {
    private val dir = File(context.cacheDir, "fuel").apply { mkdirs() }
    private val memory = HashMap<String, Pair<Long, PriceList>>()

    /** Replaceable in tests: the body of a URL, or null when it cannot be had. */
    var fetch: suspend (String) -> String? = { url ->
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 15_000; c.readTimeout = 40_000
                    c.setRequestProperty("Accept", "application/json")
                    c.setRequestProperty("User-Agent", userAgent)
                    if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                } finally { c.disconnect() }
            }.getOrNull()
        }
    }

    /** One province's stations: from memory or the cache when fresh, else downloaded (or a stale copy offline). */
    private suspend fun province(id: String, maxAgeMs: Long): PriceList? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        memory[id]?.let { (at, list) -> if (now - at < maxAgeMs) return@withContext list }
        val file = File(dir, "$id.json")
        if (file.exists() && now - file.lastModified() < maxAgeMs) {
            runCatching { FuelPricesEs.parse(file.readText()) }.getOrNull()?.let { memory[id] = file.lastModified() to it; return@withContext it }
        }
        val text = fetch(FuelPricesEs.provinceUrl(id))
        val fresh = text?.let { t -> runCatching { FuelPricesEs.parse(t) }.getOrNull()?.takeIf { it.stations.isNotEmpty() } }
        if (fresh != null) {
            runCatching { file.writeText(text) }
            memory[id] = now to fresh
            return@withContext fresh
        }
        // Offline: yesterday's prices are better than none.
        runCatching { FuelPricesEs.parse(file.readText()) }.getOrNull()?.also { memory[id] = file.lastModified() to it }
    }

    private suspend fun provinces(ids: List<String>, maxAgeMs: Long): PriceList? {
        val lists = ids.mapNotNull { province(it, maxAgeMs) }
        if (lists.isEmpty()) return null
        return PriceList(lists.minOf { it.updatedAt }, lists.flatMap { it.stations }.distinctBy { it.id })
    }

    /** Stations within [radiusKm] of a point, or null when outside Spain or offline without a copy. */
    suspend fun around(lat: Double, lon: Double, radiusKm: Double, maxAgeMs: Long = FRESH): PriceList? {
        val ids = FuelPricesEs.provincesNear(lat, lon, radiusKm)
        if (ids.isEmpty()) return null
        return provinces(ids, maxAgeMs)
    }

    /** Stations near a route ([points]: latitude, longitude). */
    suspend fun along(points: List<Pair<Double, Double>>, maxAgeMs: Long = FRESH): PriceList? {
        val ids = FuelPricesEs.provincesAlong(points, 3.0)
        if (ids.isEmpty() || ids.size > 12) return null
        return provinces(ids, maxAgeMs)
    }

    /**
     * A province's prices on a past day: downloaded once and kept (a past day does not change); copies older than a
     * few months are removed. Only the province number and the date are sent.
     */
    private suspend fun provinceOn(id: String, day: Long): PriceList? = withContext(Dispatchers.IO) {
        val stamp = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("Europe/Madrid") }.format(java.util.Date(day))
        val file = File(dir, "hist-$id-$stamp.json")
        if (file.exists()) runCatching { FuelPricesEs.parse(file.readText()) }.getOrNull()?.takeIf { it.stations.isNotEmpty() }?.let { return@withContext it }
        val text = fetch(com.openobd.core.fuel.FuelPriceTrend.historyUrl(id, day)) ?: return@withContext null
        val list = runCatching { FuelPricesEs.parse(text) }.getOrNull()?.takeIf { it.stations.isNotEmpty() } ?: return@withContext null
        runCatching { dir.mkdirs(); file.writeText(text) }
        list
    }

    /**
     * The usual and cheapest price of [fuel] within [radiusKm] of a point over the last [days] days (today's from the
     * live prices). [progress] gets (days done, days).
     */
    suspend fun trend(lat: Double, lon: Double, radiusKm: Double, fuel: PumpFuel, days: Int,
                      progress: (Int, Int) -> Unit = { _, _ -> }): List<com.openobd.core.fuel.FuelPriceTrend.Day> {
        // Only the provinces that really have stations within the radius (today's prices say which): a town near a
        // border would otherwise download its neighbours' every day too.
        val live = around(lat, lon, radiusKm) ?: return emptyList()
        val near = FuelPricesEs.near(live.stations, lat, lon, fuel, radiusKm)
        val ids = near.map { it.station.province }.filter { it.isNotBlank() }.distinct()
            .ifEmpty { FuelPricesEs.provincesNear(lat, lon, radiusKm) }
        if (ids.isEmpty()) return emptyList()
        pruneHistory()
        val now = System.currentTimeMillis()
        val out = ArrayList<com.openobd.core.fuel.FuelPriceTrend.Day>()
        for (n in days - 1 downTo 1) {
            val day = com.openobd.core.fuel.FuelPriceTrend.dayStart(now, n)
            val stations = ids.mapNotNull { provinceOn(it, day) }.flatMap { it.stations }.distinctBy { it.id }
            com.openobd.core.fuel.FuelPriceTrend.day(day, stations, lat, lon, fuel, radiusKm)?.let { out += it }
            progress(days - n, days)
        }
        com.openobd.core.fuel.FuelPriceTrend.day(com.openobd.core.fuel.FuelPriceTrend.dayStart(now), live.stations, lat, lon, fuel, radiusKm)?.let { out += it }
        progress(days, days)
        return out
    }

    private fun pruneHistory() {
        val old = System.currentTimeMillis() - 120L * DAY
        dir.listFiles { f -> f.name.startsWith("hist-") && f.lastModified() < old }?.forEach { it.delete() }
    }

    /** The usual price of [fuel] around a point (median of the nearest stations), or null. */
    suspend fun localPrice(lat: Double, lon: Double, fuel: PumpFuel, maxAgeMs: Long = DAY): Double? =
        around(lat, lon, 20.0, maxAgeMs)?.let { FuelPricesEs.localPrice(it.stations, lat, lon, fuel) }

    companion object {
        const val FRESH = 30 * 60_000L
        const val DAY = 24 * 3600_000L

        /** The fuel to show for these settings: the chosen one, else the car's (diesel or petrol 95). */
        fun fuelFor(s: AppSettings, diesel: Boolean): PumpFuel = PumpFuel.of(s.pumpFuel) ?: when (s.fuel) {
            FuelKind.DIESEL -> PumpFuel.DIESEL
            FuelKind.LPG -> PumpFuel.LPG
            FuelKind.AUTO -> if (diesel) PumpFuel.DIESEL else PumpFuel.PETROL_95
            else -> PumpFuel.PETROL_95
        }

        /** Opens the station in the phone's maps app, ready to drive there. */
        fun directions(context: Context, s: Station) {
            val uri = android.net.Uri.parse("geo:${s.lat},${s.lon}?q=${s.lat},${s.lon}(" + android.net.Uri.encode(s.brand) + ")")
            runCatching {
                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** Where the phone is now, from the platform location service (no Google services needed). */
object CurrentLocation {
    /** A recent fix (under [maxAgeMs]), else a new one within [timeoutMs]; null without permission or signal. */
    @SuppressLint("MissingPermission")
    suspend fun get(context: Context, maxAgeMs: Long = 10 * 60_000L, timeoutMs: Long = 20_000L): Location? {
        val granted = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { androidx.core.content.ContextCompat.checkSelfPermission(context, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (!granted) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused").filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val last = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < maxAgeMs) return last
        if (providers.isEmpty()) return last
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listeners = mutableListOf<LocationListener>()
                fun done(l: Location?) {
                    listeners.forEach { runCatching { lm.removeUpdates(it) } }
                    if (cont.isActive) cont.resume(l)
                }
                for (p in providers) {
                    // Every callback overridden: before Android 11 they have no default (a lambda crashes there).
                    val l = object : LocationListener {
                        override fun onLocationChanged(location: Location) = done(location)
                        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    }
                    listeners += l
                    runCatching { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) }
                }
                cont.invokeOnCancellation { listeners.forEach { runCatching { lm.removeUpdates(it) } } }
            }
        }
        return fresh ?: last
    }
}
