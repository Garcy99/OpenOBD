package com.openobd.core.trip

import com.openobd.core.garage.TrackPoint
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A printable 3D map of a trip as a 3MF file (millimetres): the real terrain around the route, coloured by altitude
 * in a [Theme], cut to a [Shape], with water, main roads and (for towns) buildings from OpenStreetMap, the route on
 * top, start / end markers and a title plaque. Every triangle carries its colour, and every distinct colour is one
 * filament, numbered so that slicers (Bambu Studio, PrusaSlicer, Orca) load the painting.
 */
object Route3mf {
    enum class Shape(val label: String) { HEXAGON("Hexagon"), CIRCLE("Circle"), SQUARE("Square") }

    /** Colour schemes: ground by altitude (low, mid, high), plinth sides, water, route, roads, buildings, text. */
    enum class Theme(
        val label: String, val low: String, val mid: String, val high: String, val side: String, val water: String,
        val route: String, val roads: String, val buildings: String, val text: String,
    ) {
        ALPINE("Alpine", "#5E9E4F", "#C2A47A", "#F4F2EE", "#7A5A3A", "#3B7DD8", "#D7302A", "#2B2B2B", "#9EA3A8", "#F4F2EE"),
        FOREST("Forest", "#2E7D4F", "#8DB85A", "#D8C79C", "#6B4F33", "#3B7DD8", "#F08A1C", "#2B2B2B", "#9EA3A8", "#F4F2EE"),
        DESERT("Desert", "#E3BC8C", "#C77B4E", "#8C4A2F", "#5C3A26", "#3B7DD8", "#1E6FB8", "#2B2B2B", "#B9B2A8", "#F4E8D6"),
        STONE("Stone", "#D6D0C2", "#D6D0C2", "#D6D0C2", "#3A3A3A", "#7FA6D6", "#D7302A", "#3A3A3A", "#9EA3A8", "#F4F2EE"),
    }

    enum class Detail(val label: String, val rings: Int, val boundary: Int, val terrainPx: Int) {
        NORMAL("Normal", 90, 360, 700), HIGH("High", 160, 640, 1300),
    }

    data class Options(
        val shape: Shape = Shape.HEXAGON,
        val theme: Theme = Theme.ALPINE,
        val sizeMm: Double = 150.0,
        /** Ground thickness under its lowest point. */
        val baseMm: Double = 3.0,
        /** Vertical exaggeration of the terrain (real scale is usually too flat to see). */
        val exaggeration: Double = 2.0,
        /** Highest the ground may reach, base included (the relief is scaled down to fit). */
        val maxHeightMm: Double = 45.0,
        /** Route relief from GPS altitude when there is no terrain. */
        val gpsReliefMm: Double = 6.0,
        /** Space around the route inside the shape, as a fraction of its extent. */
        val margin: Double = 0.12,
        val detail: Detail = Detail.NORMAL,
        /** Ground coloured by altitude (otherwise one colour). */
        val reliefColours: Boolean = true,
        val water: Boolean = true,
        val roads: Boolean = true,
        val buildings: Boolean = true,
        /** Route in three colours by speed instead of the theme's one. */
        val routeBySpeed: Boolean = false,
    )

    /** Roles of the colours, for the filament list (English keys, translated by the app). */
    const val SIDES = "Base and sides"; const val LOW = "Lowlands"; const val MID = "Slopes"; const val HIGH = "Peaks"
    const val WATER = "Water"; const val ROUTE = "Route"; const val ROADS = "Roads"; const val BUILDINGS = "Buildings"
    const val TEXT = "Title and start"; const val END = "End"; const val GROUND = "Ground"
    private val rolePriority = listOf(SIDES, LOW, MID, HIGH, GROUND, WATER, ROUTE, "Route up to 50 km/h", "Route 50-100 km/h", "Route over 100 km/h", ROADS, BUILDINGS, TEXT, END)

    /** A part; [c] is each triangle's colour. */
    class Mesh(val name: String, val color: String, private val role: String) {
        val v = mutableListOf<DoubleArray>()
        val t = mutableListOf<IntArray>()
        val c = mutableListOf<String>()
        /** Colour -> roles, in order of use. */
        val roles = LinkedHashMap<String, LinkedHashSet<String>>()
        fun vertex(x: Double, y: Double, z: Double): Int { v += doubleArrayOf(x, y, z); return v.size - 1 }
        fun tri(a: Int, b: Int, cc: Int, color: String = this.color, role: String = this.role) {
            t += intArrayOf(a, b, cc); c += color; roles.getOrPut(color) { LinkedHashSet() } += role
        }
        fun quad(a: Int, b: Int, cc: Int, d: Int, color: String = this.color, role: String = this.role) { tri(a, b, cc, color, role); tri(a, cc, d, color, role) }
        fun translate(dx: Double, dy: Double) { v.forEach { it[0] += dx; it[1] += dy } }
    }

    /** One filament: its number in the slicer, colour and what it prints. */
    data class Filament(val number: Int, val color: String, val roles: List<String>)

    /**
     * How the height came out: [baseMm] under the lowest ground, [reliefMm] of terrain above it, whether the
     * relief was scaled down to the maximum height ([limited]) and how many metres of the deepest low ground were
     * left out of the base ([trimmedM]) so one valley at the edge does not thicken the whole model.
     */
    data class Relief(val baseMm: Double, val reliefMm: Double, val limited: Boolean, val trimmedM: Double)

    class Model(val meshes: List<Mesh>, val filaments: List<Filament>, val relief: Relief = Relief(0.0, 0.0, false, 0.0)) {
        fun filamentOf(color: String) = filaments.first { it.color == color }.number
        val triangles: Int get() = meshes.sumOf { it.t.size }
        /** Width, depth and height in mm. */
        fun size(): List<Double> = (0..2).map { k -> meshes.maxOf { m -> m.v.maxOf { it[k] } } - meshes.minOf { m -> m.v.minOf { it[k] } } }
    }

    /** Distinct colours become filaments, the plinth's first (slicers use filament 1 for anything unpainted). */
    fun filaments(meshes: List<Mesh>): List<Filament> {
        val roles = LinkedHashMap<String, LinkedHashSet<String>>()
        for (m in meshes) for ((color, r) in m.roles) roles.getOrPut(color) { LinkedHashSet() } += r
        fun rank(color: String) = roles.getValue(color).minOf { r -> rolePriority.indexOf(r).let { if (it < 0) 99 else it } }
        return roles.keys.sortedBy { rank(it) }.mapIndexed { i, color -> Filament(i + 1, color, roles.getValue(color).sortedBy { rolePriority.indexOf(it) }) }
    }

    /**
     * [title] / [subtitle]: outlines of the title and of a second line (e.g. coordinates), drawn by the app with a
     * real font; empty title = no plaque. [features]: water, roads and buildings (null = none).
     */
    fun model(
        points: List<TrackPoint>, o: Options = Options(), terrain: Terrain? = null, features: MapFeatures? = null,
        title: List<List<Pair<Double, Double>>> = emptyList(), subtitle: List<List<Pair<Double, Double>>> = emptyList(),
    ): Model {
        val (meshes, relief) = build(points, o, terrain, features, title, subtitle)
        return Model(meshes, filaments(meshes), relief)
    }

    /** The area the model covers (with room for any shape), for downloading terrain and map data. */
    fun area(points: List<TrackPoint>): DoubleArray {
        val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
        val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
        val k = cos(Math.toRadians((minLat + maxLat) / 2))
        val spanM = max((maxLat - minLat) * 111_000, (maxLon - minLon) * 111_000 * k).coerceAtLeast(300.0)
        val padLat = spanM * 0.7 / 111_000; val padLon = spanM * 0.7 / (111_000 * k)
        return doubleArrayOf(minLat - padLat, minLon - padLon, maxLat + padLat, maxLon + padLon)
    }

    /** Width of the area in metres (buildings are only offered for small areas). */
    fun spanMetres(points: List<TrackPoint>): Double {
        val a = area(points)
        return max((a[2] - a[0]) * 111_000, (a[3] - a[1]) * 111_000 * cos(Math.toRadians(a[0])))
    }

    fun meshes(
        points: List<TrackPoint>, o: Options = Options(), terrain: Terrain? = null, features: MapFeatures? = null,
        title: List<List<Pair<Double, Double>>> = emptyList(), subtitle: List<List<Pair<Double, Double>>> = emptyList(),
    ): List<Mesh> = build(points, o, terrain, features, title, subtitle).first

    private fun build(
        points: List<TrackPoint>, o: Options, terrain: Terrain?, features: MapFeatures?,
        title: List<List<Pair<Double, Double>>>, subtitle: List<List<Pair<Double, Double>>>,
    ): Pair<List<Mesh>, Relief> {
        if (points.size < 2) return emptyList<Mesh>() to Relief(0.0, 0.0, false, 0.0)
        val th = o.theme
        val sizeK = o.sizeMm / 150.0
        val origin = points.first()
        val kx = 111_320.0 * cos(Math.toRadians(origin.lat)); val ky = 110_540.0
        val raw = RouteGeometry.metres(points)
        val cx = (raw.minOf { it.first } + raw.maxOf { it.first }) / 2; val cy = (raw.minOf { it.second } + raw.maxOf { it.second }) / 2
        fun toLocal(p: LatLon) = ((p.lon - origin.lon) * kx - cx) to ((p.lat - origin.lat) * ky - cy)
        // Footprint around the route, in metres from its centre.
        val d = raw.map { (it.first - cx) to (it.second - cy) }
        val minExtent = 150.0
        val nB = o.detail.boundary
        val boundary: List<Pair<Double, Double>> = when (o.shape) {
            Shape.CIRCLE -> {
                val r = max(d.maxOf { sqrt(it.first * it.first + it.second * it.second) }, minExtent) * (1 + o.margin)
                (0 until nB).map { val an = 2 * Math.PI * it / nB; r * cos(an) to r * sin(an) }
            }
            Shape.SQUARE -> {
                val h = max(d.maxOf { max(abs(it.first), abs(it.second)) }, minExtent) * (1 + o.margin)
                val n = nB / 4
                val corners = listOf(h to -h, h to h, -h to h, -h to -h)
                (0 until 4).flatMap { side -> val (x0, y0) = corners[(side + 3) % 4]; val (x1, y1) = corners[side]; (0 until n).map { k -> (x0 + (x1 - x0) * k / n) to (y0 + (y1 - y0) * k / n) } }
            }
            Shape.HEXAGON -> {
                // Flat top and bottom: edge normals at 90°, 30° and -30°.
                val normals = listOf(0.0 to 1.0, cos(Math.PI / 6) to sin(Math.PI / 6), cos(Math.PI / 6) to -sin(Math.PI / 6))
                val apothem = max(d.maxOf { p -> normals.maxOf { abs(p.first * it.first + p.second * it.second) } }, minExtent) * (1 + o.margin)
                val r = apothem / cos(Math.PI / 6)
                val corners = (0 until 6).map { val an = Math.PI / 3 * it; r * cos(an) to r * sin(an) }
                val n = nB / 6
                (0 until 6).flatMap { side -> val (x0, y0) = corners[side]; val (x1, y1) = corners[(side + 1) % 6]; (0 until n).map { k -> (x0 + (x1 - x0) * k / n) to (y0 + (y1 - y0) * k / n) } }
            }
        }
        fun inside(x: Double, y: Double) = Polygons.contains(boundary, x to y)
        val spanM = max(boundary.maxOf { it.first } - boundary.minOf { it.first }, boundary.maxOf { it.second } - boundary.minOf { it.second })
        val s = o.sizeMm / spanM // mm per metre
        fun elevationAt(xm: Double, ym: Double): Double? =
            terrain?.elevation(origin.lat + (ym + cy) / ky, origin.lon + (xm + cx) / kx)?.let { max(it, 0.0) } // the sea is flat

        // Ground surface: rings from the centre out to the boundary (works for any convex shape).
        val rings = o.detail.rings
        val ringPts = (1..rings).map { k -> boundary.map { (bx, by) -> bx * k / rings to by * k / rings } }
        val centreElev = elevationAt(0.0, 0.0)
        val ringElev = ringPts.map { ring -> ring.map { (x, y) -> elevationAt(x, y) } }
        val all = (listOf(centreElev) + ringElev.flatten()).filterNotNull()
        val hasTerrain = all.size > (1 + ringPts.size * boundary.size) / 2
        val sorted = all.sorted()
        fun quantile(q: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * q).toInt()]
        val eMin = if (hasTerrain) sorted.first() else 0.0; val eMax = if (hasTerrain) sorted.last() else 0.0
        // The base starts at the low ground of most of the area: the lowest 2 % (a gorge or the sea at one edge)
        // sits on the base instead of making every other part of the model taller.
        val eFloor = if (hasTerrain) quantile(0.02) else 0.0
        val maxRelief = (o.maxHeightMm - o.baseMm).coerceAtLeast(1.0)
        val wanted = s * o.exaggeration
        val zScale = if (hasTerrain && eMax > eFloor) min(wanted, maxRelief / (eMax - eFloor)) else 0.0
        val relief = Relief(o.baseMm, (eMax - eFloor) * zScale, hasTerrain && zScale < wanted - 1e-12, eFloor - eMin)
        val fallback = if (hasTerrain) all.average() else 0.0
        fun height(e: Double?) = o.baseMm + max(0.0, (e ?: fallback) - eFloor) * zScale
        fun groundAt(xm: Double, ym: Double) = if (hasTerrain) height(elevationAt(xm, ym)) else o.baseMm

        // Water: OpenStreetMap lakes, reservoirs and riverbanks rasterised over the footprint, plus the sea.
        val mask = if (o.water && features != null && features.water.isNotEmpty()) WaterMask(boundary, features.water.map { w -> w.map(::toLocal) }) else null
        val seaLevel = hasTerrain && eMin <= 0.0 && o.water

        val out = mutableListOf<Mesh>()
        val ground = Mesh(if (hasTerrain) "Terrain" else "Base", th.side, SIDES)
        ground.apply {
            val elev = mutableListOf<Double>()
            fun vx(x: Double, y: Double, e: Double?): Int { elev += e ?: fallback; return vertex(x * s, y * s, height(e)) }
            val c0 = vx(0.0, 0.0, centreElev)
            val ring = ringPts.mapIndexed { k, r -> r.mapIndexed { j, (x, y) -> vx(x, y, ringElev[k][j]) } }
            // Bands by share of the area (not by height alone), so every map shows lowlands, slopes and peaks.
            val lowTop = max(quantile(0.45), eMin + (eMax - eMin) * 0.2); val midTop = max(quantile(0.82), lowTop + (eMax - eMin) * 0.1)
            fun topColour(a: Int, b: Int, cc: Int): Pair<String, String> {
                val xm = (v[a][0] + v[b][0] + v[cc][0]) / 3 / s; val ym = (v[a][1] + v[b][1] + v[cc][1]) / 3 / s
                val e = (elev[a] + elev[b] + elev[cc]) / 3
                if (mask?.isWater(xm, ym) == true || (seaLevel && max(elev[a], max(elev[b], elev[cc])) <= 0.5)) return th.water to WATER
                if (!hasTerrain || !o.reliefColours) return th.mid to GROUND
                return when { e < lowTop -> th.low to LOW; e < midTop -> th.mid to MID; else -> th.high to HIGH }
            }
            fun topTri(a: Int, b: Int, cc: Int) { val (col, role) = topColour(a, b, cc); tri(a, b, cc, col, role) }
            val n = boundary.size
            for (j in 0 until n) topTri(c0, ring[0][j], ring[0][(j + 1) % n])
            for (k in 0 until ring.lastIndex) for (j in 0 until n) {
                val a = ring[k][j]; val b = ring[k + 1][j]; val cc = ring[k + 1][(j + 1) % n]; val dd = ring[k][(j + 1) % n]
                topTri(a, b, cc); topTri(a, cc, dd)
            }
            val bc = vertex(0.0, 0.0, 0.0)
            val bottom = boundary.map { (x, y) -> vertex(x * s, y * s, 0.0) }
            val top = ring.last()
            for (j in 0 until n) {
                val jn = (j + 1) % n
                tri(bc, bottom[jn], bottom[j])
                quad(bottom[j], bottom[jn], top[jn], top[j])
            }
        }
        out += ground

        // Main roads, draped on the ground like the route but thin and low.
        if (o.roads && features != null && features.roads.isNotEmpty()) {
            val roads = Mesh("Roads", th.roads, ROADS)
            var budget = 25_000
            for (road in features.roads.sortedBy { it.rank }) {
                val w = (listOf(1.25, 1.0, 0.8, 0.65)[road.rank.coerceIn(0, 3)] * sizeK).coerceAtLeast(0.55)
                var run = mutableListOf<Pair<Double, Double>>()
                fun flush() {
                    if (run.size >= 2 && budget > 0) budget -= stroke(roads, run, w / 2, 0.45, 0.4, s, ::groundAt, 1.2, joint = 8)
                    run = mutableListOf()
                }
                for (p in road.points) { val q = toLocal(p); if (inside(q.first, q.second)) run += q else flush() }
                flush()
            }
            if (roads.t.isNotEmpty()) out += roads
        }

        // Buildings (towns): footprints extruded from the ground, a bit taller than real so they read at this scale.
        if (o.buildings && features != null && features.buildings.isNotEmpty() && spanM <= Overpass.MAX_BUILDINGS_SPAN_M * 1.2) {
            val b = Mesh("Buildings", th.buildings, BUILDINGS)
            val candidates = features.buildings.map { bd -> bd to bd.outline.map(::toLocal) }
                .filter { (_, pts) -> pts.size >= 3 && pts.all { inside(it.first, it.second) } }
                .map { (bd, pts) -> Triple(bd, pts, abs(Polygons.area(pts)) * s * s) }
                .filter { it.third >= 0.25 }
                .sortedByDescending { it.third }.take(6000)
            for ((bd, pts, _) in candidates) {
                val shape = Polygons.group(listOf(pts.map { it.first * s to it.second * s })).firstOrNull() ?: continue
                val gs = pts.map { groundAt(it.first, it.second) }
                val hMm = (bd.heightM * s * 1.5).coerceIn(0.6, 30.0)
                extrude(b, shape, 1.0, 0.0, 0.0, gs.min() - 0.4, gs.max() + hMm)
            }
            if (b.t.isNotEmpty()) out += b
        }

        // Route: a smooth curve through every GPS point, drawn like a pen stroke, on the highest ground under it.
        val hw = 1.1 * sizeK
        val samples = RouteGeometry.smooth(points, max(3.0, 0.2 / s), 0.5 / s).map { (x, y, i) -> Triple(x - cx, y - cy, i) }
        val alts = points.map { it.alt?.toDouble() }; val known = alts.filterNotNull()
        val altRange = if (known.size >= 2) known.max() - known.min() else 0.0
        fun gpsRelief(i: Int): Double {
            if (hasTerrain || altRange < 5) return 0.0
            val a = alts[i] ?: alts.indices.filter { alts[it] != null }.minBy { abs(it - i) }.let { alts[it]!! }
            return (a - known.min()) / altRange * o.gpsReliefMm
        }
        val names = listOf("Route up to 50 km/h", "Route 50-100 km/h", "Route over 100 km/h")
        val routeMeshes = if (o.routeBySpeed) (0..2).map { Mesh(names[it], RouteGeometry.bandColors[it], names[it]) } else listOf(Mesh("Route", th.route, ROUTE))
        fun bandOf(i: Int) = RouteGeometry.band(points[i].speedKmh).let { if (it == RouteGeometry.UNKNOWN) RouteGeometry.SLOW else it }
        val routeHeight = 1.2 * sizeK
        var piece = mutableListOf<Pair<Double, Double>>(); var pieceLift = mutableListOf<Double>(); var pieceMesh: Mesh? = null
        fun flushRoute() {
            val m = pieceMesh
            if (m != null && piece.size >= 2) stroke(m, piece, hw, routeHeight, 0.8, s, ::groundAt, 0.0, pieceLift)
            piece = mutableListOf(); pieceLift = mutableListOf()
        }
        for ((x, y, i) in samples) {
            val m = if (o.routeBySpeed) routeMeshes[bandOf(i)] else routeMeshes[0]
            if (m !== pieceMesh) {
                val last = piece.lastOrNull(); val lastLift = pieceLift.lastOrNull()
                flushRoute()
                if (last != null) { piece += last; pieceLift += lastLift!! }
                pieceMesh = m
            }
            piece += x to y; pieceLift += gpsRelief(i)
        }
        flushRoute()
        out += routeMeshes.filter { it.t.isNotEmpty() }
        fun topAt(x: Double, y: Double, i: Int) = groundAt(x, y) + routeHeight + gpsRelief(i)
        val (sx, sy, si) = samples.first(); val (ex, ey, ei) = samples.last()
        val marker = 1.55 * sizeK
        out += Mesh("Start", th.text, TEXT).also { addCylinder(it, sx * s, sy * s, marker, groundAt(sx, sy) - 0.8, topAt(sx, sy, si) + 1.6, 32) }
        out += Mesh("End", th.roads, END).also { addCylinder(it, ex * s, ey * s, marker, groundAt(ex, ey) - 0.8, topAt(ex, ey, ei) + 1.6, 32) }

        // Title plaque in front (towards the viewer, -y), joined to the base; optional second line.
        if (title.isNotEmpty()) {
            val front = boundary.minOf { it.second } * s
            val width = (boundary.maxOf { it.first } - boundary.minOf { it.first }) * s
            val pw = width * 0.78; val pd = o.sizeMm * (if (subtitle.isNotEmpty()) 0.19 else 0.15)
            val overlap = if (o.shape == Shape.CIRCLE) pd * 0.45 else 2.0
            val top = o.baseMm + 1.5
            out += box("Plaque", th.side, SIDES, -pw / 2, front - pd, 0.0, pw / 2, front + overlap, top)
            val text = Mesh("Title", th.text, TEXT)
            fun place(contours: List<List<Pair<Double, Double>>>, centreY: Double, maxH: Double) {
                val shapes = Polygons.group(contours)
                if (shapes.isEmpty()) return
                val xs = shapes.flatMap { it.outer.map { p -> p.first } }; val ys = shapes.flatMap { it.outer.map { p -> p.second } }
                val tw = xs.max() - xs.min(); val tht = ys.max() - ys.min()
                val k = min(pw * 0.88 / tw, maxH / tht)
                val ox = -(xs.min() + tw / 2) * k; val oy = centreY - (ys.min() + tht / 2) * k
                for (shape in shapes) extrude(text, shape, k, ox, oy, top - 0.3, top + 1.0)
            }
            if (subtitle.isEmpty()) place(title, front - pd / 2, pd * 0.55)
            else {
                place(title, front - pd * 0.36, pd * 0.42)
                place(subtitle, front - pd * 0.78, pd * 0.17)
            }
            if (text.t.isNotEmpty()) out += text
        }
        // Everything at positive coordinates.
        val minX = out.minOf { m -> m.v.minOf { it[0] } }; val minY = out.minOf { m -> m.v.minOf { it[1] } }
        out.forEach { it.translate(-minX, -minY) }
        return out to relief
    }

    /**
     * A pen stroke along [path] (metres): straight pieces with round joints, standing [height] mm above the
     * highest ground under its width and sunk [sink] mm below the lowest. Returns the number of pieces.
     */
    private fun stroke(
        mesh: Mesh, path: List<Pair<Double, Double>>, hw: Double, height: Double, sink: Double, s: Double,
        groundAt: (Double, Double) -> Double, densifyMm: Double, lift: List<Double>? = null, joint: Int = 12,
    ): Int {
        // Optionally densify so the stroke follows the relief between points.
        val pts = mutableListOf<Pair<Double, Double>>(); val lifts = mutableListOf<Double>()
        for (i in path.indices) {
            if (i > 0 && densifyMm > 0) {
                val (x0, y0) = path[i - 1]; val (x1, y1) = path[i]
                val n = max(1, kotlin.math.ceil(sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0)) * s / densifyMm).toInt())
                for (k in 1 until n) { val f = k.toDouble() / n; pts += (x0 + (x1 - x0) * f) to (y0 + (y1 - y0) * f); lifts += (lift?.get(i - 1) ?: 0.0) * (1 - f) + (lift?.get(i) ?: 0.0) * f }
            }
            pts += path[i]; lifts += lift?.get(i) ?: 0.0
        }
        val probe = hw / s
        val tops = DoubleArray(pts.size); val bottoms = DoubleArray(pts.size)
        for ((k, p) in pts.withIndex()) {
            val g = listOf(0.0 to 0.0, probe to 0.0, -probe to 0.0, 0.0 to probe, 0.0 to -probe).map { (dx, dy) -> groundAt(p.first + dx, p.second + dy) }
            tops[k] = g.max() + height + lifts[k]; bottoms[k] = g.min() - sink
        }
        val xy = pts.map { (it.first * s) to (it.second * s) }
        var count = 0
        for (k in 0 until xy.lastIndex) {
            if (dist(xy[k], xy[k + 1]) < 1e-4) continue
            val lo = min(bottoms[k], bottoms[k + 1])
            ribbon(mesh, listOf(xy[k], xy[k + 1]), listOf(lo, lo), listOf(tops[k], tops[k + 1]), hw)
            count++
            val turn = if (k == 0) Double.MAX_VALUE else {
                val (ax, ay) = xy[k - 1]; val (bx, by) = xy[k]; val (qx, qy) = xy[k + 1]
                abs(Math.IEEEremainder(Math.atan2(qy - by, qx - bx) - Math.atan2(by - ay, bx - ax), 2 * Math.PI))
            }
            if (turn > Math.toRadians(2.0)) addCylinder(mesh, xy[k].first, xy[k].second, hw, min(bottoms[k], lo), tops[k], joint)
        }
        val e = xy.lastIndex
        addCylinder(mesh, xy[e].first, xy[e].second, hw, bottoms[e], tops[e], joint)
        return count
    }

    /** Water polygons rasterised over the footprint, so each ground triangle is looked up in constant time. */
    private class WaterMask(boundary: List<Pair<Double, Double>>, polygons: List<List<Pair<Double, Double>>>) {
        private val n = 700
        private val x0 = boundary.minOf { it.first }; private val y0 = boundary.minOf { it.second }
        private val cell = max(boundary.maxOf { it.first } - x0, boundary.maxOf { it.second } - y0) / n
        private val bits = java.util.BitSet(n * n)

        init {
            for (poly in polygons) {
                if (poly.size < 3) continue
                val pyMin = ((poly.minOf { it.second } - y0) / cell).toInt().coerceIn(0, n - 1)
                val pyMax = ((poly.maxOf { it.second } - y0) / cell).toInt().coerceIn(0, n - 1)
                for (row in pyMin..pyMax) {
                    val y = y0 + (row + 0.5) * cell
                    val xs = mutableListOf<Double>()
                    for (i in poly.indices) {
                        val (ax, ay) = poly[i]; val (bx, by) = poly[(i + 1) % poly.size]
                        if ((ay > y) != (by > y)) xs += ax + (y - ay) * (bx - ax) / (by - ay)
                    }
                    xs.sort()
                    var k = 0
                    while (k + 1 < xs.size) {
                        val c0 = ((xs[k] - x0) / cell).toInt().coerceIn(0, n - 1); val c1 = ((xs[k + 1] - x0) / cell).toInt().coerceIn(0, n - 1)
                        bits.set(row * n + c0, row * n + c1 + 1)
                        k += 2
                    }
                }
            }
        }

        fun isWater(x: Double, y: Double): Boolean {
            val c = floor((x - x0) / cell).toInt(); val r = floor((y - y0) / cell).toInt()
            return c in 0 until n && r in 0 until n && bits[r * n + c]
        }
    }

    private fun extrude(mesh: Mesh, shape: Polygons.Shape, k: Double, ox: Double, oy: Double, z0: Double, z1: Double) {
        val (pts, tris) = Polygons.triangulate(shape)
        val bottom = pts.map { mesh.vertex(it.first * k + ox, it.second * k + oy, z0) }
        val top = pts.map { mesh.vertex(it.first * k + ox, it.second * k + oy, z1) }
        for (t in tris) { mesh.tri(top[t[0]], top[t[1]], top[t[2]]); mesh.tri(bottom[t[0]], bottom[t[2]], bottom[t[1]]) }
        // Walls: outline counter-clockwise and holes clockwise both face outwards with the same winding.
        var offset = 0
        for (ring in listOf(shape.outer) + shape.holes) {
            for (i in ring.indices) {
                val a = offset + i; val b = offset + (i + 1) % ring.size
                mesh.quad(bottom[a], bottom[b], top[b], top[a])
            }
            offset += ring.size
        }
    }

    private fun box(name: String, color: String, role: String, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = Mesh(name, color, role).apply {
        val p = listOf(
            vertex(x0, y0, z0), vertex(x1, y0, z0), vertex(x1, y1, z0), vertex(x0, y1, z0),
            vertex(x0, y0, z1), vertex(x1, y0, z1), vertex(x1, y1, z1), vertex(x0, y1, z1),
        )
        quad(p[0], p[3], p[2], p[1]) // bottom (normal down)
        quad(p[4], p[5], p[6], p[7]) // top
        quad(p[0], p[1], p[5], p[4]); quad(p[1], p[2], p[6], p[5]); quad(p[2], p[3], p[7], p[6]); quad(p[3], p[0], p[4], p[7])
    }

    /** A closed vertical cylinder added to [mesh] as its own shell. */
    private fun addCylinder(mesh: Mesh, cx: Double, cy: Double, r: Double, z0: Double, z1: Double, n: Int) = mesh.apply {
        val bc = vertex(cx, cy, z0); val tc = vertex(cx, cy, z1)
        val b = (0 until n).map { val a = 2 * Math.PI * it / n; vertex(cx + r * cos(a), cy + r * sin(a), z0) }
        val t = (0 until n).map { val a = 2 * Math.PI * it / n; vertex(cx + r * cos(a), cy + r * sin(a), z1) }
        for (i in 0 until n) {
            val j = (i + 1) % n
            tri(bc, b[j], b[i]); tri(tc, t[i], t[j]); quad(b[i], b[j], t[j], t[i])
        }
    }

    /** A closed strip along [path] (mm): half-width [hw], from [z0] to [z1] per point. */
    private fun ribbon(mesh: Mesh, path: List<Pair<Double, Double>>, z0: List<Double>, z1: List<Double>, hw: Double) {
        val keep = mutableListOf<Int>()
        path.indices.forEach { i -> if (keep.isEmpty() || dist(path[keep.last()], path[i]) > 0.05) keep += i }
        if (keep.size < 2) return
        val p = keep.map { path[it] }; val lo = keep.map { z0[it] }; val hi = keep.map { z1[it] }
        fun normal(a: Pair<Double, Double>, b: Pair<Double, Double>): Pair<Double, Double> {
            val dx = b.first - a.first; val dy = b.second - a.second; val l = sqrt(dx * dx + dy * dy)
            return -dy / l to dx / l
        }
        val left = mutableListOf<Pair<Double, Double>>(); val right = mutableListOf<Pair<Double, Double>>()
        for (i in p.indices) {
            val n1 = if (i > 0) normal(p[i - 1], p[i]) else normal(p[0], p[1])
            val n2 = if (i < p.lastIndex) normal(p[i], p[i + 1]) else n1
            var nx = n1.first + n2.first; var ny = n1.second + n2.second
            val l = sqrt(nx * nx + ny * ny)
            if (l < 1e-6) { nx = n1.first; ny = n1.second } else { nx /= l; ny /= l }
            val cosHalf = (nx * n1.first + ny * n1.second).coerceAtLeast(0.35)
            val k = hw / cosHalf
            left += (p[i].first + nx * k) to (p[i].second + ny * k)
            right += (p[i].first - nx * k) to (p[i].second - ny * k)
        }
        val lb = left.mapIndexed { i, it -> mesh.vertex(it.first, it.second, lo[i]) }; val rb = right.mapIndexed { i, it -> mesh.vertex(it.first, it.second, lo[i]) }
        val lt = left.mapIndexed { i, it -> mesh.vertex(it.first, it.second, hi[i]) }; val rt = right.mapIndexed { i, it -> mesh.vertex(it.first, it.second, hi[i]) }
        for (i in 0 until p.lastIndex) {
            val j = i + 1
            mesh.quad(lt[i], rt[i], rt[j], lt[j]); mesh.quad(lb[i], lb[j], rb[j], rb[i])
            mesh.quad(lb[i], lt[i], lt[j], lb[j]); mesh.quad(rb[i], rb[j], rt[j], rt[i])
        }
        val e = p.lastIndex
        mesh.quad(lb[0], rb[0], rt[0], lt[0]); mesh.quad(lb[e], lt[e], rt[e], rb[e])
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = sqrt((a.first - b.first).let { it * it } + (a.second - b.second).let { it * it })

    /** Slicers' triangle-painting code for a 1-based filament (PrusaSlicer TriangleSelector state, as Bambu uses too). */
    fun paintCode(filament: Int): String = when {
        filament <= 0 -> ""
        filament == 1 -> "4"
        filament == 2 -> "8"
        else -> "%XC".format(filament - 3)
    }

    /**
     * Written in PrusaSlicer's 3MF dialect, which Bambu Studio, PrusaSlicer and Orca all import with its colours: one
     * object whose parts are ranges of triangles (Metadata/Slic3r_PE_model.config), each part with its filament
     * ("extruder"), and the triangles of other colours inside a part (the terrain's altitude bands, water) painted
     * with their filament (slic3rpe:mmu_segmentation). Bambu Studio only reads parts and painting from 3MF files it
     * recognises (its own, or PrusaSlicer's by the "PrusaSlicer" in the application name), and adds the filaments
     * the model uses. Base materials carry the real colours for plain 3MF viewers.
     */
    fun write(model: Model, out: OutputStream, title: String, application: String = "OpenOBD") {
        val zip = ZipOutputStream(out)
        fun entry(name: String, text: String) { zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/><Default Extension="config" ContentType="text/xml"/></Types>""")
        entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
        zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
        val w = zip.bufferedWriter(Charsets.UTF_8)
        w.write("""<?xml version="1.0" encoding="UTF-8"?>""" + "\n")
        w.write("""<model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02" xmlns:slic3rpe="http://schemas.slic3r.org/3mf/2017/06">""" + "\n")
        w.write("""<metadata name="slic3rpe:Version3mf">1</metadata><metadata name="slic3rpe:MmPaintingVersion">1</metadata>""" + "\n")
        w.write("""<metadata name="Title">${xml(title)}</metadata><metadata name="Application">${xml(application)} (PrusaSlicer 3MF)</metadata>""" + "\n")
        w.write("""<metadata name="Description">${xml(model.filaments.joinToString("; ") { "${it.number} ${it.color} ${it.roles.joinToString(", ")}" })}</metadata>""" + "\n")
        w.write("<resources>\n<basematerials id=\"1\">")
        model.filaments.forEach { w.write("<base name=\"${xml(it.number.toString() + " " + it.roles.joinToString(", "))}\" displaycolor=\"${it.color}\"/>") }
        w.write("</basematerials>\n")
        w.write("<object id=\"2\" name=\"${xml(title)}\" type=\"model\" pid=\"1\" pindex=\"0\"><mesh><vertices>")
        model.meshes.forEach { mesh -> mesh.v.forEach { w.write("<vertex x=\"${f(it[0], 3)}\" y=\"${f(it[1], 3)}\" z=\"${f(it[2], 3)}\"/>") } }
        w.write("</vertices><triangles>")
        val ranges = mutableListOf<IntArray>() // first triangle, last triangle, filament
        var vOffset = 0; var tOffset = 0
        for (mesh in model.meshes) {
            val own = model.filamentOf(mesh.color)
            for ((k, t) in mesh.t.withIndex()) {
                val fil = model.filamentOf(mesh.c[k])
                w.write("<triangle v1=\"${t[0] + vOffset}\" v2=\"${t[1] + vOffset}\" v3=\"${t[2] + vOffset}\" p1=\"${fil - 1}\"")
                if (fil != own) w.write(" slic3rpe:mmu_segmentation=\"${paintCode(fil)}\"")
                w.write("/>")
            }
            ranges += intArrayOf(tOffset, tOffset + mesh.t.size - 1, own)
            vOffset += mesh.v.size; tOffset += mesh.t.size
        }
        w.write("</triangles></mesh></object>\n</resources>\n<build><item objectid=\"2\" printable=\"1\"/></build>\n</model>\n")
        w.flush()
        zip.closeEntry()
        val config = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n <object id=\"2\" instances_count=\"1\">\n")
            append("  <metadata type=\"object\" key=\"name\" value=\"${xml(title)}\"/>\n")
            append("  <metadata type=\"object\" key=\"extruder\" value=\"1\"/>\n")
            model.meshes.forEachIndexed { i, mesh ->
                val (first, last, fil) = ranges[i].let { Triple(it[0], it[1], it[2]) }
                append("  <volume firstid=\"$first\" lastid=\"$last\">\n")
                append("   <metadata type=\"volume\" key=\"name\" value=\"${xml(mesh.name)}\"/>\n")
                append("   <metadata type=\"volume\" key=\"volume_type\" value=\"ModelPart\"/>\n")
                append("   <metadata type=\"volume\" key=\"extruder\" value=\"$fil\"/>\n")
                append("   <metadata type=\"volume\" key=\"matrix\" value=\"1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1\"/>\n")
                append("  </volume>\n")
            }
            append(" </object>\n</config>\n")
        }
        entry("Metadata/Slic3r_PE_model.config", config)
        zip.finish()
    }

    val texts = Shape.entries.map { it.label } + Theme.entries.map { it.label } + Detail.entries.map { it.label } +
        listOf(SIDES, LOW, MID, HIGH, WATER, ROUTE, ROADS, BUILDINGS, TEXT, END, GROUND, Overpass.ATTRIBUTION)
}
