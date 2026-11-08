package com.openobd.core.trip

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.tan

/** Ground elevation in metres at a latitude / longitude, or null when unknown. */
fun interface Terrain {
    fun elevation(lat: Double, lon: Double): Double?
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

/** Bilinear elevation from decoded Terrarium tiles ([tiles]: (x, y) -> 256 * 256 metres, row by row). */
class TileTerrain(private val z: Int, private val tiles: Map<Pair<Int, Int>, FloatArray>) : Terrain {
    private fun at(px: Int, py: Int): Double? {
        val tx = Math.floorDiv(px, 256); val ty = Math.floorDiv(py, 256)
        val t = tiles[tx to ty] ?: return null
        return t[(py - ty * 256) * 256 + (px - tx * 256)].toDouble()
    }

    override fun elevation(lat: Double, lon: Double): Double? {
        val fx = Terrarium.tileX(lon, z) * 256 - 0.5; val fy = Terrarium.tileY(lat, z) * 256 - 0.5
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val ax = fx - x0; val ay = fy - y0
        val e00 = at(x0, y0); val e10 = at(x0 + 1, y0); val e01 = at(x0, y0 + 1); val e11 = at(x0 + 1, y0 + 1)
        val known = listOfNotNull(e00, e10, e01, e11)
        if (known.isEmpty()) return null
        val f = known.average()
        return (e00 ?: f) * (1 - ax) * (1 - ay) + (e10 ?: f) * ax * (1 - ay) + (e01 ?: f) * (1 - ax) * ay + (e11 ?: f) * ax * ay
    }
}
