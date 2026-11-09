package com.openobd.core.trip

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor

/**
 * Aerial photos as web map tiles, for the satellite poster and the web map: the Spanish national orthophoto (PNOA,
 * 25-50 cm per pixel, CC BY 4.0) where it exists, and Esri World Imagery elsewhere. Free and without a key.
 */
object Imagery {
    enum class Source(val attribution: String, val leafletUrl: String, val maxZoom: Int) {
        PNOA("Aerial photos: PNOA © Instituto Geográfico Nacional de España (CC BY 4.0)",
            "https://tms-pnoa-ma.idee.es/1.0.0/pnoa-ma/{z}/{x}/{-y}.jpeg", 19),
        ESRI("Aerial photos: Esri, Maxar, Earthstar Geographics",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19),
    }

    fun url(source: Source, z: Int, x: Int, y: Int): String = when (source) {
        Source.PNOA -> "https://tms-pnoa-ma.idee.es/1.0.0/pnoa-ma/$z/$x/${(1 shl z) - 1 - y}.jpeg"
        Source.ESRI -> "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
    }

    /** Spain, the Balearic and Canary Islands (and their neighbours: tiles outside Spain come back empty). */
    fun nearSpain(lat: Double, lon: Double): Boolean =
        (lat in 35.0..44.5 && lon in -10.0..5.0) || (lat in 27.4..29.5 && lon in -18.4..-13.2)

    /** The photos to try first for an area; [Source.ESRI] fills what they lack. */
    fun sourceFor(lat: Double, lon: Double): Source = if (nearSpain(lat, lon)) Source.PNOA else Source.ESRI

    /** Ground metres per tile pixel at [lat]. */
    fun metresPerPixel(lat: Double, z: Int) = 2 * PI * 6_378_137.0 * cos(Math.toRadians(lat)) / (256.0 * (1 shl z))

    /**
     * The zoom whose pixels are about as fine as [metresPerPx] (the poster's), within [maxTiles] tiles for the box:
     * finer would only download more, coarser would blur.
     */
    fun zoomFor(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, metresPerPx: Double, maxTiles: Int = 220, maxZoom: Int = 19): Int {
        val lat = (minLat + maxLat) / 2
        for (z in maxZoom downTo 3) {
            if (metresPerPixel(lat, z) < metresPerPx / 1.6) continue
            if (Terrarium.tiles(minLat, minLon, maxLat, maxLon, z).size <= maxTiles) return z
        }
        return 3
    }
}

/**
 * Decoded photo tiles of one zoom ([tiles]: (x, y) -> 256 * 256 ARGB pixels, row by row), read with bilinear
 * filtering. [attributions]: the credits the poster has to show.
 */
class TileImagery(private val z: Int, private val tiles: Map<Pair<Int, Int>, IntArray>, val attributions: List<String>) {
    private fun at(px: Int, py: Int): Int? {
        val tx = Math.floorDiv(px, 256); val ty = Math.floorDiv(py, 256)
        val t = tiles[tx to ty] ?: return null
        return t[(py - ty * 256) * 256 + (px - tx * 256)].takeIf { it ushr 24 != 0 }
    }

    /** Opaque RGB colour at a latitude / longitude, or null where there is no photo. */
    fun color(lat: Double, lon: Double): Int? {
        val fx = Terrarium.tileX(lon, z) * 256 - 0.5; val fy = Terrarium.tileY(lat, z) * 256 - 0.5
        val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
        val ax = fx - x0; val ay = fy - y0
        val c = arrayOf(at(x0, y0), at(x0 + 1, y0), at(x0, y0 + 1), at(x0 + 1, y0 + 1))
        val wt = doubleArrayOf((1 - ax) * (1 - ay), ax * (1 - ay), (1 - ax) * ay, ax * ay)
        var r = 0.0; var g = 0.0; var b = 0.0; var sum = 0.0
        for (k in 0 until 4) {
            val p = c[k] ?: continue
            r += ((p shr 16) and 0xFF) * wt[k]; g += ((p shr 8) and 0xFF) * wt[k]; b += (p and 0xFF) * wt[k]; sum += wt[k]
        }
        if (sum <= 1e-9) return null
        return (0xFF shl 24) or ((r / sum).toInt().coerceIn(0, 255) shl 16) or ((g / sum).toInt().coerceIn(0, 255) shl 8) or (b / sum).toInt().coerceIn(0, 255)
    }

    companion object {
        /** True when a downloaded tile has no photo (transparent or plain white, as PNOA outside Spain): try another source. */
        fun isBlank(px: IntArray): Boolean {
            var transparent = 0; var white = 0
            for (p in px) { if (p ushr 24 == 0) transparent++ else if (p and 0xFFFFFF == 0xFFFFFF) white++ }
            return transparent > px.size * 9 / 10 || transparent + white == px.size
        }

        /** True when part of a tile is missing (transparent), so another source should fill it. */
        fun hasGaps(px: IntArray): Boolean = px.any { it ushr 24 != 0xFF }

        /** [top] over [bottom]: [bottom] shows where [top] is transparent. */
        fun under(top: IntArray, bottom: IntArray): IntArray = IntArray(top.size) { i -> if (top[i] ushr 24 == 0xFF) top[i] else bottom[i] }
    }
}
