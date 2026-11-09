package com.openobd.data

import android.content.Context
import android.graphics.BitmapFactory
import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.FallbackTerrain
import com.openobd.core.trip.Mapterhorn
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
 * Downloads the elevation around a route from Mapterhorn (or Terrain Tiles, AWS Open Data, when it cannot) for 3D
 * models and posters, keeping the tiles in the cache folder. Only the map tiles of the area are requested: no route,
 * car or personal data is sent.
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
        // Mapterhorn first (national LiDAR models where there are, e.g. 2 m in Spain): the most detailed zoom that
        // covers the whole area, going out a zoom while tiles are missing (zoom 12 and out cover the world).
        val budget = when { detailPx > 3000 -> 36; detailPx > 1000 -> 12; else -> 6 }
        var mz = Mapterhorn.zoomFor(box[0], box[1], box[2], box[3], detailPx, budget)
        while (mz >= 3) {
            val wanted = Terrarium.tiles(box[0], box[1], box[2], box[3], mz)
            val got = loadMapterhorn(mz, wanted) { i -> progress(i, wanted.size) }
            if (got != null) { progress(wanted.size, wanted.size); return@withContext TileTerrain(mz, got, Mapterhorn.SIZE, Mapterhorn.ATTRIBUTION) }
            if (unreachable || mz <= Mapterhorn.GLOBAL_ZOOM) break // offline or the service is down: try the other one
            mz--
        }
        val z = Terrarium.zoomFor(box[0], box[1], box[2], box[3], targetPx = detailPx, maxTiles = if (detailPx > 1000) 36 else 16)
        val wanted = Terrarium.tiles(box[0], box[1], box[2], box[3], z)
        // The service has a few empty tiles at high zooms: then the next zoom out is used (whole, so there are no
        // steps where the two meet), or only where it is needed if that one is incomplete too.
        val coarseWanted = Terrarium.tiles(box[0], box[1], box[2], box[3], z - 1)
        val total = wanted.size + coarseWanted.size
        val tiles = load(z, wanted) { i -> progress(i, total) }
        if (tiles.size == wanted.size) { progress(total, total); return@withContext TileTerrain(z, tiles) }
        val coarse = load(z - 1, coarseWanted) { i -> progress(wanted.size + i, total) }
        progress(total, total)
        when {
            tiles.size + coarse.size * 4 < (wanted.size + 1) / 2 -> null
            tiles.isEmpty() || coarse.size == coarseWanted.size -> TileTerrain(z - 1, coarse)
            else -> FallbackTerrain(TileTerrain(z, tiles), TileTerrain(z - 1, coarse))
        }
    }

    /** All the tiles, or null as soon as one is missing (no high-resolution data there, or offline). */
    private fun loadMapterhorn(z: Int, wanted: List<Pair<Int, Int>>, progress: (Int) -> Unit): Map<Pair<Int, Int>, FloatArray>? {
        val tiles = HashMap<Pair<Int, Int>, FloatArray>()
        for ((i, xy) in wanted.withIndex()) {
            progress(i)
            val t = fetch(File(dir, "mh-$z-${xy.first}-${xy.second}.webp"), Mapterhorn.url(z, xy.first, xy.second))
                ?.let { decode(it, Mapterhorn.SIZE) }?.takeIf { !Terrarium.isEmpty(it) } ?: return null
            Terrarium.despike(t); tiles[xy] = t
        }
        return tiles
    }

    /** Set when a download failed for another reason than "no such tile" (offline, server error). */
    private var unreachable = false

    /** The cached file, downloading it first if needed; null if it cannot be had. */
    private fun fetch(file: File, url: String): File? {
        if (file.exists() && file.length() >= 100) return file
        unreachable = false
        return runCatching {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", userAgent)
            try {
                if (c.responseCode != 200) { unreachable = c.responseCode != 404; return null }
                val tmp = File(dir, file.name + ".tmp")
                c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                file
            } finally { c.disconnect() }
        }.onFailure { unreachable = true }.getOrNull()
    }

    /** Elevations of a Terrarium-encoded image ([size] x [size]), or null if it does not decode. */
    private fun decode(file: File, size: Int): FloatArray? {
        val bmp = BitmapFactory.decodeFile(file.path)?.takeIf { it.width == size && it.height == size } ?: return null
        val px = IntArray(size * size)
        bmp.getPixels(px, 0, size, 0, 0, size, size)
        bmp.recycle()
        return FloatArray(px.size) { i -> val p = px[i]; Terrarium.decode((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF).toFloat() }
    }

    private fun load(z: Int, wanted: List<Pair<Int, Int>>, progress: (Int) -> Unit): Map<Pair<Int, Int>, FloatArray> {
        val tiles = HashMap<Pair<Int, Int>, FloatArray>()
        wanted.forEachIndexed { i, (x, y) ->
            progress(i)
            tile(z, x, y)?.takeIf { !Terrarium.isEmpty(it) }?.let { Terrarium.despike(it); tiles[x to y] = it }
        }
        return tiles
    }

    private fun tile(z: Int, x: Int, y: Int): FloatArray? =
        fetch(File(dir, "$z-$x-$y.png"), Terrarium.url(z, x, y))?.let { decode(it, 256) }
}
