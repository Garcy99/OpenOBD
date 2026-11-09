package com.openobd.data

import android.content.Context
import android.graphics.BitmapFactory
import com.openobd.core.trip.Imagery
import com.openobd.core.trip.Terrarium
import com.openobd.core.trip.TileImagery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads aerial photos of an area for the satellite poster: Spain's national orthophoto (PNOA) where it has
 * them, Esri World Imagery for the rest, keeping the tiles in the cache folder. Only the map tiles of the area are
 * requested: no route, car or personal data is sent.
 */
class ImageryDownloader(context: Context, private val userAgent: String) {
    private val dir = File(context.cacheDir, "imagery").apply { mkdirs() }

    /**
     * Photos of [area] (minLat, minLon, maxLat, maxLon) with pixels of about [metresPerPx], or null when offline or
     * when most of the area has none.
     */
    suspend fun around(area: DoubleArray, metresPerPx: Double, progress: (Int, Int) -> Unit = { _, _ -> }): TileImagery? = withContext(Dispatchers.IO) {
        val first = Imagery.sourceFor((area[0] + area[2]) / 2, (area[1] + area[3]) / 2)
        val z = Imagery.zoomFor(area[0], area[1], area[2], area[3], metresPerPx, maxZoom = first.maxZoom)
        val wanted = Terrarium.tiles(area[0], area[1], area[2], area[3], z)
        val tiles = HashMap<Pair<Int, Int>, IntArray>()
        val used = LinkedHashSet<Imagery.Source>()
        for ((i, xy) in wanted.withIndex()) {
            progress(i, wanted.size)
            var px = tile(first, z, xy.first, xy.second)?.takeIf { !TileImagery.isBlank(it) }
            if (px != null) used += first
            // Outside Spain's photos (or where a tile is cut at the border), Esri fills in.
            if (first != Imagery.Source.ESRI && (px == null || TileImagery.hasGaps(px))) {
                tile(Imagery.Source.ESRI, z, xy.first, xy.second)?.let { esri -> px = px?.let { TileImagery.under(it, esri) } ?: esri; used += Imagery.Source.ESRI }
            }
            px?.let { tiles[xy] = it }
        }
        progress(wanted.size, wanted.size)
        if (tiles.size * 2 < wanted.size) null
        else TileImagery(z, tiles, used.map { it.attribution })
    }

    private fun tile(source: Imagery.Source, z: Int, x: Int, y: Int): IntArray? {
        val file = File(dir, "${source.name.lowercase()}-$z-$x-$y")
        if (!file.exists() || file.length() < 100) {
            runCatching {
                val c = URL(Imagery.url(source, z, x, y)).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", userAgent)
                try {
                    if (c.responseCode != 200) return null
                    val tmp = File(dir, file.name + ".tmp")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    tmp.renameTo(file)
                } finally { c.disconnect() }
            }.onFailure { return null }
        }
        val bmp = BitmapFactory.decodeFile(file.path)?.takeIf { it.width == 256 && it.height == 256 } ?: return null
        val px = IntArray(256 * 256)
        bmp.getPixels(px, 0, 256, 0, 0, 256, 256)
        bmp.recycle()
        return px
    }
}
