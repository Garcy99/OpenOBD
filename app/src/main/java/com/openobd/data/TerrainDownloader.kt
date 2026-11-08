package com.openobd.data

import android.content.Context
import android.graphics.BitmapFactory
import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.Terrain
import com.openobd.core.trip.Terrarium
import com.openobd.core.trip.TileTerrain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.cos
import kotlin.math.max

/**
 * Downloads the elevation around a route from Terrain Tiles (AWS Open Data) for the 3D model, keeping the tiles in
 * the cache folder. Only the map tiles of the area are requested: no route, car or personal data is sent.
 */
class TerrainDownloader(private val context: Context, private val userAgent: String) {
    private val dir = File(context.cacheDir, "terrain").apply { mkdirs() }

    /**
     * Elevation for the area around [points] (with room for the hexagon / circle around it), or null if offline.
     * [detailPx]: pixels across the area to aim for (more for posters than for 3D models).
     */
    suspend fun around(points: List<TrackPoint>, detailPx: Int = 640, progress: (Int, Int) -> Unit = { _, _ -> }): Terrain? = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext null
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        // The shape around the route can reach ~70 % of the route's size beyond it on each side.
        val spanM = max((maxLat - minLat) * 111_000, (maxLon - minLon) * 111_000 * cos(Math.toRadians(minLat))).coerceAtLeast(300.0)
        val padLat = spanM * 0.7 / 111_000; val padLon = spanM * 0.7 / (111_000 * cos(Math.toRadians(minLat)))
        val box = doubleArrayOf(minLat - padLat, minLon - padLon, maxLat + padLat, maxLon + padLon)
        val z = Terrarium.zoomFor(box[0], box[1], box[2], box[3], targetPx = detailPx, maxTiles = if (detailPx > 1000) 36 else 16)
        val wanted = Terrarium.tiles(box[0], box[1], box[2], box[3], z)
        val tiles = HashMap<Pair<Int, Int>, FloatArray>()
        wanted.forEachIndexed { i, (x, y) ->
            progress(i, wanted.size)
            tile(z, x, y)?.let { tiles[x to y] = it }
        }
        progress(wanted.size, wanted.size)
        if (tiles.size < (wanted.size + 1) / 2) null else TileTerrain(z, tiles)
    }

    private fun tile(z: Int, x: Int, y: Int): FloatArray? {
        val file = File(dir, "$z-$x-$y.png")
        if (!file.exists() || file.length() < 100) {
            runCatching {
                val c = URL(Terrarium.url(z, x, y)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", userAgent)
                try {
                    if (c.responseCode != 200) return null
                    val tmp = File(dir, "$z-$x-$y.tmp")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(file)
                } finally { c.disconnect() }
            }.onFailure { return null }
        }
        val bmp = BitmapFactory.decodeFile(file.path)?.takeIf { it.width == 256 && it.height == 256 } ?: return null
        val px = IntArray(256 * 256)
        bmp.getPixels(px, 0, 256, 0, 0, 256, 256)
        bmp.recycle()
        return FloatArray(px.size) { i -> val p = px[i]; Terrarium.decode((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toFloat() }
    }
}
