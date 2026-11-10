package com.openobd.data

import android.content.Context
import com.openobd.core.trip.MapFeatures
import com.openobd.core.trip.Overpass
import com.openobd.core.trip.SceneFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Downloads water, main roads and (for small areas) buildings around a route from OpenStreetMap through the
 * Overpass API, keeping the answer in the cache folder. Only rectangles of the area are sent: no car or personal
 * data.
 */
class MapDataDownloader(context: Context, private val userAgent: String) {
    private val dir = File(context.cacheDir, "osm").apply { mkdirs() }

    /** [area]: minLat, minLon, maxLat, maxLon. Null when offline or every server failed. */
    suspend fun around(area: DoubleArray, buildings: Boolean): MapFeatures? =
        get(Overpass.query(area[0], area[1], area[2], area[3], buildings))

    /** Lakes, reservoirs and riverbanks only (for the poster's relief). */
    suspend fun water(area: DoubleArray): List<List<com.openobd.core.trip.LatLon>>? =
        get(Overpass.waterQuery(area[0], area[1], area[2], area[3]))?.water

    /**
     * Buildings, woods, trees and water in squares along a route (minLat, minLon, maxLat, maxLon each), a few squares
     * per request; null when none could be had. The squares are the ones the aerial photos are fetched for.
     */
    suspend fun scene(boxes: List<DoubleArray>, progress: (Int, Int) -> Unit = { _, _ -> }): SceneFeatures? = withContext(Dispatchers.IO) {
        val chunks = boxes.chunked(6)
        var out: SceneFeatures? = null
        for ((i, chunk) in chunks.withIndex()) {
            progress(i, chunks.size)
            val q = SceneFeatures.query(chunk)
            val text = cached(q) ?: Overpass.endpoints.firstNotNullOfOrNull { e -> runCatching { post(e, q) }.getOrNull()?.takeIf { it.trimStart().startsWith("{") } }
                ?: continue
            val parsed = runCatching { SceneFeatures.parse(text) }.getOrNull() ?: continue
            store(q, text)
            out = out?.plus(parsed) ?: parsed
        }
        progress(chunks.size, chunks.size)
        out
    }

    private fun cacheFile(query: String) = File(dir, Integer.toHexString(query.hashCode()) + "-" + query.length + ".json")
    private fun cached(query: String): String? = cacheFile(query).takeIf { it.exists() && it.length() > 20 }?.let { runCatching { it.readText() }.getOrNull() }
    private fun store(query: String, text: String) { runCatching { cacheFile(query).writeText(text) } }

    private suspend fun get(query: String): MapFeatures? = withContext(Dispatchers.IO) {
        val file = File(dir, Integer.toHexString(query.hashCode()) + ".json")
        if (file.exists() && file.length() > 20) runCatching { return@withContext Overpass.parse(file.readText()) }
        for (endpoint in Overpass.endpoints) {
            val text = runCatching { post(endpoint, query) }.getOrNull() ?: continue
            val parsed = runCatching { Overpass.parse(text) }.getOrNull() ?: continue
            runCatching { file.writeText(text) }
            return@withContext parsed
        }
        null
    }

    private fun post(endpoint: String, query: String): String? {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000; c.readTimeout = 100_000
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) return null
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally { c.disconnect() }
    }
}
