package com.openobd.core.trip

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/** Ground elevation in metres at a latitude / longitude, or null when unknown. */
fun interface Terrain {
    fun elevation(lat: Double, lon: Double): Double?

    /** Metres between the data's samples at [lat], when known (to smooth away the steps between them). */
    fun cellMetres(lat: Double): Double? = null

    /** Credit for the elevation data, to show where it is used. */
    val attribution: String get() = Terrarium.ATTRIBUTION
}

/**
 * Mapterhorn: open terrain tiles in the same Terrarium encoding (512 x 512 lossless WebP), built from national
 * elevation models (in Spain the IGN's 2 m LiDAR model, MDT02) and Copernicus GLO-30 elsewhere. Zoom 13 and above
 * only exist where there is high-resolution data; zoom 12 and below cover the world.
 */
object Mapterhorn {
    const val ATTRIBUTION = "Elevation: © Mapterhorn, from open data (IGN España CC BY 4.0, Copernicus GLO-30 © DLR, Airbus, EU/ESA and others)"
    const val SIZE = 512
    const val MAX_ZOOM = 16
    const val GLOBAL_ZOOM = 12

    fun url(z: Int, x: Int, y: Int) = "https://tiles.mapterhorn.com/$z/$x/$y.webp"

    /** The most detailed zoom whose tiles covering the box stay within [maxTiles] (and no finer than needed). */
    fun zoomFor(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, targetPx: Int, maxTiles: Int): Int {
        for (z in MAX_ZOOM downTo 3) {
            val count = Terrarium.tiles(minLat, minLon, maxLat, maxLon, z).size
            val px = max(Terrarium.tileX(maxLon, z) - Terrarium.tileX(minLon, z), Terrarium.tileY(minLat, z) - Terrarium.tileY(maxLat, z)) * SIZE
            if (count <= maxTiles && px <= targetPx * 2) return z
        }
        return 3
    }
}

/**
 * Terrain Tiles (Terrarium encoding) from the AWS Registry of Open Data: 256 x 256 PNG tiles in the usual web map
 * grid, elevation = R * 256 + G + B / 256 - 32768 metres. Free, no key; built from SRTM, GMTED, ETOPO and national
 * elevation models.
 */
object Terrarium {
    const val ATTRIBUTION = "Elevation: Terrain Tiles (AWS Open Data; SRTM, GMTED, ETOPO and others)"

    fun url(z: Int, x: Int, y: Int) = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$z/$x/$y.png"

    fun decode(r: Int, g: Int, b: Int): Double = r * 256.0 + g + b / 256.0 - 32768.0

    /** Below this, a pixel is "no data" (the service has a few empty tiles, encoded as -32768). */
    const val NO_DATA = -11_000.0

    /** True when most of a decoded tile is empty, so a coarser zoom should be used there. */
    fun isEmpty(tile: FloatArray) = tile.count { it < NO_DATA } > tile.size / 2

    /**
     * Fixes glitches in a decoded 256 x 256 tile (the service has the odd corrupt pixel or column, thousands of
     * metres off): a pixel far from the median of its neighbours takes that median. A whole corrupt column or row
     * is then redone from the ones beside it, as the median leaves a faint line there. Returns how many were fixed.
     */
    fun despike(t: FloatArray, threshold: Float = 400f): Int {
        val size = sqrt(t.size.toDouble()).toInt(); val last = size - 1
        val src = t.copyOf()
        val nb = FloatArray(8)
        var fixed = 0
        val perCol = IntArray(size); val perRow = IntArray(size)
        for (y in 0 until size) for (x in 0 until size) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val xx = x + dx; val yy = y + dy
                if (xx in 0..last && yy in 0..last && src[yy * size + xx] > NO_DATA) nb[n++] = src[yy * size + xx]
            }
            if (n < 3) continue
            java.util.Arrays.sort(nb, 0, n)
            val median = if (n % 2 == 1) nb[n / 2] else (nb[n / 2 - 1] + nb[n / 2]) / 2
            val v = src[y * size + x]
            if (v <= NO_DATA || kotlin.math.abs(v - median) > threshold) { t[y * size + x] = median; fixed++; perCol[x]++; perRow[y]++ }
        }
        for (x in 1 until last) if (perCol[x] > size / 4) for (y in 0 until size) t[y * size + x] = (t[y * size + x - 1] + t[y * size + x + 1]) / 2
        for (y in 1 until last) if (perRow[y] > size / 4) for (x in 0 until size) t[y * size + x] = (t[(y - 1) * size + x] + t[(y + 1) * size + x]) / 2
        destripe(t, size, byColumn = true); destripe(t, size, byColumn = false)
        return fixed
    }

    /**
     * Smaller glitches the service has along whole columns (or rows) of a tile, a few metres off, show as straight
     * lines on a shaded relief. A column much more bent than the rest of the tile, all the way down, is redone
     * from the columns on either side.
     */
    private fun destripe(t: FloatArray, size: Int, byColumn: Boolean) {
        val last = size - 1
        fun at(line: Int, along: Int) = if (byColumn) t[along * size + line] else t[line * size + along]
        val bend = DoubleArray(size)
        for (c in 1 until last) { var sum = 0.0; for (r in 0 until size) sum += kotlin.math.abs(2 * at(c, r) - at(c - 1, r) - at(c + 1, r)); bend[c] = sum / size }
        val median = bend.copyOfRange(1, last).sorted()[(size - 2) / 2]
        val bad = BooleanArray(size) { it in 1 until last && bend[it] > 3 * median && bend[it] > 1.2 }
        var c = 1
        while (c < last) {
            if (!bad[c]) { c++; continue }
            var end = c; while (end + 1 < last && bad[end + 1]) end++
            if (end - c < 8) for (r in 0 until size) {
                val a = at(c - 1, r); val b = at(end + 1, r)
                for (k in c..end) { val v = a + (b - a) * (k - c + 1) / (end - c + 2); if (byColumn) t[r * size + k] = v else t[k * size + r] = v }
            }
            c = end + 1
        }
    }

    /** Fractional tile coordinates. */
    fun tileX(lon: Double, z: Int) = (lon + 180) / 360 * (1 shl z)
    fun tileY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)
    }
    fun lat(tileY: Double, z: Int) = Math.toDegrees(atan(kotlin.math.sinh(PI * (1 - 2 * tileY / (1 shl z)))))

    /** The most detailed zoom whose tiles covering the box stay within [maxTiles] (and no finer than needed). */
    fun zoomFor(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, targetPx: Int = 640, maxTiles: Int = 16): Int {
        for (z in 14 downTo 3) {
            val x0 = floor(tileX(minLon, z)).toInt(); val x1 = floor(tileX(maxLon, z)).toInt()
            val y0 = floor(tileY(maxLat, z)).toInt(); val y1 = floor(tileY(minLat, z)).toInt()
            val count = (x1 - x0 + 1) * (y1 - y0 + 1)
            val px = max(tileX(maxLon, z) - tileX(minLon, z), tileY(minLat, z) - tileY(maxLat, z)) * 256
            if (count <= maxTiles && px <= targetPx * 2) return z
        }
        return 3
    }

    fun tiles(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, z: Int): List<Pair<Int, Int>> {
        val x0 = floor(tileX(minLon, z)).toInt(); val x1 = floor(tileX(maxLon, z)).toInt()
        val y0 = floor(tileY(maxLat, z)).toInt(); val y1 = floor(tileY(minLat, z)).toInt()
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> x to y } }
    }
}

/** [fine] where it has data, else [coarse] (e.g. the next zoom out, to fill the service's empty tiles). */
class FallbackTerrain(private val fine: Terrain, private val coarse: Terrain) : Terrain {
    override fun elevation(lat: Double, lon: Double): Double? = fine.elevation(lat, lon) ?: coarse.elevation(lat, lon)
    override fun cellMetres(lat: Double): Double? = fine.cellMetres(lat)
    override val attribution: String get() = fine.attribution
}

/**
 * Bilinear elevation from decoded Terrarium-encoded tiles ([tiles]: (x, y) -> [size] * [size] metres, row by row).
 */
class TileTerrain(
    private val z: Int, private val tiles: Map<Pair<Int, Int>, FloatArray>, private val size: Int = 256,
    override val attribution: String = Terrarium.ATTRIBUTION,
) : Terrain {
    override fun cellMetres(lat: Double): Double = Imagery.metresPerPixel(lat, z) * 256 / size
    private fun at(px: Int, py: Int): Double? {
        val tx = Math.floorDiv(px, size); val ty = Math.floorDiv(py, size)
        val t = tiles[tx to ty] ?: return null
        return t[(py - ty * size) * size + (px - tx * size)].toDouble().takeIf { it > Terrarium.NO_DATA }
    }

    override fun elevation(lat: Double, lon: Double): Double? {
        val fx = Terrarium.tileX(lon, z) * size - 0.5; val fy = Terrarium.tileY(lat, z) * size - 0.5
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val ax = fx - x0; val ay = fy - y0
        val e00 = at(x0, y0); val e10 = at(x0 + 1, y0); val e01 = at(x0, y0 + 1); val e11 = at(x0 + 1, y0 + 1)
        val known = listOfNotNull(e00, e10, e01, e11)
        if (known.isEmpty()) return null
        val f = known.average()
        return (e00 ?: f) * (1 - ax) * (1 - ay) + (e10 ?: f) * ax * (1 - ay) + (e01 ?: f) * (1 - ax) * ay + (e11 ?: f) * ax * ay
    }
}
