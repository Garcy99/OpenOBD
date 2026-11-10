package com.openobd.core.trip

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** What stands on the ground along a route, from OpenStreetMap: buildings, woods and parks, single trees and water. */
data class SceneFeatures(
    val buildings: List<MapBuilding>,
    /** Woods, forests, orchards and parks: [Vegetation.kind] sets how dense and how tall their trees are. */
    val vegetation: List<Vegetation>,
    /** Trees mapped one by one, and rows of trees along roads. */
    val trees: List<LatLon>,
    val treeRows: List<List<LatLon>>,
    /** Lakes, reservoirs and wide rivers (areas). */
    val water: List<List<LatLon>>,
    /** Rivers, streams and canals (lines). */
    val waterways: List<Waterway>,
) {
    enum class VegetationKind(val spacingM: Double, val radiusM: Double) { FOREST(8.5, 4.2), ORCHARD(6.5, 2.4), PARK(13.0, 3.6) }
    data class Vegetation(val outline: List<LatLon>, val kind: VegetationKind)
    data class Waterway(val points: List<LatLon>, val widthM: Double)

    val isEmpty: Boolean get() = buildings.isEmpty() && vegetation.isEmpty() && trees.isEmpty() && treeRows.isEmpty() && water.isEmpty() && waterways.isEmpty()

    operator fun plus(o: SceneFeatures) = SceneFeatures(
        (buildings + o.buildings).distinct(), (vegetation + o.vegetation).distinct(), (trees + o.trees).distinct(),
        (treeRows + o.treeRows).distinct(), (water + o.water).distinct(), (waterways + o.waterways).distinct(),
    )

    companion object {
        val NONE = SceneFeatures(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

        /**
         * Overpass query for squares along the route (minLat, minLon, maxLat, maxLon each): only those squares are
         * sent, the same ones the aerial photos are fetched for.
         */
        fun query(boxes: List<DoubleArray>): String = buildString {
            append("[out:json][timeout:120];(")
            for (a in boxes) {
                val b = String.format(java.util.Locale.US, "%.5f,%.5f,%.5f,%.5f", a[0], a[1], a[2], a[3])
                append("way[\"building\"]($b);")
                append("way[\"natural\"~\"^(wood|tree_row|water)$\"]($b);")
                append("way[\"landuse\"~\"^(forest|orchard|reservoir)$\"]($b);")
                append("way[\"leisure\"=\"park\"]($b);")
                append("node[\"natural\"=\"tree\"]($b);")
                append("way[\"waterway\"~\"^(river|stream|canal|riverbank)$\"]($b);")
                append("relation[\"natural\"=\"water\"]($b);")
            }
            append(");out geom qt;")
        }

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): SceneFeatures {
            val root = json.parseToJsonElement(text).jsonObject
            val buildings = ArrayList<MapBuilding>(); val veg = ArrayList<Vegetation>(); val trees = ArrayList<LatLon>()
            val rows = ArrayList<List<LatLon>>(); val water = ArrayList<List<LatLon>>(); val ways = ArrayList<Waterway>()
            for (el in (root["elements"] as? JsonArray).orEmpty()) {
                val e = el as? JsonObject ?: continue
                val tags = (e["tags"] as? JsonObject)?.mapValues { (it.value as? JsonPrimitive)?.content.orEmpty() }.orEmpty()
                when ((e["type"] as? JsonPrimitive)?.content) {
                    "node" -> if (tags["natural"] == "tree") {
                        val lat = (e["lat"] as? JsonPrimitive)?.doubleOrNull; val lon = (e["lon"] as? JsonPrimitive)?.doubleOrNull
                        if (lat != null && lon != null) trees += LatLon(lat, lon)
                    }
                    "way" -> {
                        val pts = geometry(e["geometry"])
                        if (pts.size < 2) continue
                        val closed = pts.size >= 4 && pts.first() == pts.last()
                        val ring = if (closed) pts.dropLast(1) else pts
                        when {
                            tags["building"] != null && closed -> buildings += MapBuilding(ring, Overpass.height(tags))
                            tags["natural"] == "tree_row" -> rows += pts
                            (tags["natural"] == "water" || tags["landuse"] == "reservoir" || tags["waterway"] == "riverbank") && closed -> water += ring
                            tags["waterway"] in setOf("river", "stream", "canal") ->
                                ways += Waterway(pts, tags["width"]?.toDoubleOrNull()?.coerceIn(1.0, 200.0) ?: when (tags["waterway"]) { "river" -> 14.0; "canal" -> 8.0; else -> 3.0 })
                            closed && (tags["natural"] == "wood" || tags["landuse"] == "forest") -> veg += Vegetation(ring, VegetationKind.FOREST)
                            closed && tags["landuse"] == "orchard" -> veg += Vegetation(ring, VegetationKind.ORCHARD)
                            closed && tags["leisure"] == "park" -> veg += Vegetation(ring, VegetationKind.PARK)
                        }
                    }
                    "relation" -> if (tags["natural"] == "water") {
                        val outers = (e["members"] as? JsonArray).orEmpty().mapNotNull { m ->
                            val mo = m as? JsonObject ?: return@mapNotNull null
                            if ((mo["role"] as? JsonPrimitive)?.content != "outer") null else geometry(mo["geometry"]).takeIf { it.size >= 2 }
                        }
                        water += Overpass.rings(outers)
                    }
                }
            }
            return SceneFeatures(buildings, veg, trees, rows, water, ways)
        }

        private fun geometry(g: kotlinx.serialization.json.JsonElement?): List<LatLon> = (g as? JsonArray).orEmpty().mapNotNull { p ->
            val o = p as? JsonObject ?: return@mapNotNull null
            val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull; val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull
            if (lat == null || lon == null) null else LatLon(lat, lon)
        }
    }
}

/**
 * The 3D things of the drone video as vertex arrays in the script's coordinates (OpenGL axes: x east, y up, z south;
 * metres, heights exaggerated like the ground's). Pure maths, tested without a device.
 */
class DroneScene(features: SceneFeatures, private val ground: Ground, route: DroneScript) {
    private val frame = ground.frame

    /** Buildings: per vertex x, y, z, normal (3), u (metres along the wall, -1 on roofs), v (metres above the base), seed. */
    val buildings: FloatArray
    /** Trees: per tree x, y (centre of the crown), z, radius, shade (0..1). */
    val trees: FloatArray
    /** Water: per vertex x, y, z (triangles). */
    val water: FloatArray
    val buildingCount: Int
    val treeCount: Int

    init {
        // Only what is near the route matters: the drone never flies far from it.
        val near = { x: Double, y: Double, r: Double -> route.distanceToRoute(x, y) <= r }
        val bOut = FloatArrayBuilder()
        var nb = 0
        val buildingsNear = features.buildings.asSequence()
            .map { b -> b to b.outline.map { frame.x(it.lon) to frame.y(it.lat) } }
            .filter { (_, p) -> p.size >= 3 && near(p.sumOf { it.first } / p.size, p.sumOf { it.second } / p.size, BUILDING_RANGE) }
            .take(MAX_BUILDINGS).toList()
        for ((b, pts) in buildingsNear) if (building(bOut, pts, b.heightM, nb)) nb++
        buildings = bOut.toArray(); buildingCount = nb

        val tOut = FloatArrayBuilder()
        var nt = 0
        fun tree(x: Double, y: Double, r: Double, seed: Int) {
            if (nt >= MAX_TREES || !near(x, y, TREE_RANGE)) return
            val h = hash(seed)
            val radius = r * (0.75 + 0.5 * h)
            tOut.add(x.toFloat(), (ground.exact(x, y) + radius * 1.35).toFloat(), (-y).toFloat(), radius.toFloat(), hash(seed * 7 + 3).toFloat())
            nt++
        }
        var seed = 1
        for (t in features.trees) tree(frame.x(t.lon), frame.y(t.lat), 3.6, seed++)
        for (row in features.treeRows) walk(row.map { frame.x(it.lon) to frame.y(it.lat) }, 8.0) { x, y -> tree(x, y, 3.2, seed++) }
        // Woods: as many trees as fit, more thinly when there is a lot of wood near the route.
        val vegNear = features.vegetation.map { v -> v to v.outline.map { frame.x(it.lon) to frame.y(it.lat) } }
            .filter { (_, p) -> p.size >= 3 && p.any { (x, y) -> near(x, y, TREE_RANGE) } }
        val woodArea = vegNear.sumOf { (v, p) -> abs(Polygons.area(p)) / (v.kind.spacingM * v.kind.spacingM) }
        val thin = max(1.0, sqrt(woodArea / (MAX_TREES * 0.9)))
        for ((v, p) in vegNear) {
            val step = v.kind.spacingM * thin
            val x0 = p.minOf { it.first }; val x1 = p.maxOf { it.first }; val y0 = p.minOf { it.second }; val y1 = p.maxOf { it.second }
            var y = y0 + step / 2
            var row = 0
            while (y < y1 && nt < MAX_TREES) {
                var x = x0 + step / 2 + (if (row % 2 == 0) 0.0 else step / 2)
                while (x < x1 && nt < MAX_TREES) {
                    // A little disorder, as in a real wood.
                    val jx = (hash(seed * 3) - 0.5) * step * 0.7; val jy = (hash(seed * 5 + 1) - 0.5) * step * 0.7
                    if (Polygons.contains(p, (x + jx) to (y + jy))) tree(x + jx, y + jy, v.kind.radiusM, seed)
                    seed++
                    x += step
                }
                y += step * 0.87; row++
            }
        }
        trees = tOut.toArray(); treeCount = nt

        // Water: flat at the lowest bank of each lake; rivers as ribbons on the ground.
        val wOut = FloatArrayBuilder()
        for (w in features.water) {
            val pts = simplify(w.map { frame.x(it.lon) to frame.y(it.lat) }, 4.0).let { if (it.size > 500) simplify(it, 15.0) else it }
            if (pts.size < 3 || pts.none { (x, y) -> near(x, y, TREE_RANGE * 2) }) continue
            val ccw = if (Polygons.area(pts) < 0) pts.reversed() else pts
            val level = ccw.minOf { (x, y) -> ground.exact(x, y) } + 0.4
            val (v, tris) = runCatching { Polygons.triangulate(Polygons.Shape(ccw, emptyList())) }.getOrNull() ?: continue
            for (t in tris) for (k in t) wOut.add(v[k].first.toFloat(), level.toFloat(), (-v[k].second).toFloat())
        }
        for (ww in features.waterways) {
            val pts = ww.points.map { frame.x(it.lon) to frame.y(it.lat) }
            val dense = ArrayList<Pair<Double, Double>>()
            walk(pts, 10.0) { x, y -> dense += x to y }
            for (i in 0 until dense.size - 1) {
                val (ax, ay) = dense[i]; val (bx, by) = dense[i + 1]
                if (!near(ax, ay, TREE_RANGE * 2)) continue
                val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy).takeIf { it > 0.01 } ?: continue
                val nx = -dy / len * ww.widthM / 2; val ny = dx / len * ww.widthM / 2
                val za = ground.exact(ax, ay) + 0.5; val zb = ground.exact(bx, by) + 0.5
                val q = listOf(Triple(ax - nx, ay - ny, za), Triple(bx - nx, by - ny, zb), Triple(bx + nx, by + ny, zb), Triple(ax + nx, ay + ny, za))
                for (k in intArrayOf(0, 1, 2, 0, 2, 3)) wOut.add(q[k].first.toFloat(), q[k].third.toFloat(), (-q[k].second).toFloat())
            }
        }
        water = wOut.toArray()
    }

    /** Walls and a flat roof; false when the outline cannot be made into one. */
    private fun building(out: FloatArrayBuilder, raw: List<Pair<Double, Double>>, heightM: Double, index: Int): Boolean {
        val pts = raw.let { if (Polygons.area(it) < 0) it.reversed() else it }
        if (abs(Polygons.area(pts)) < 6) return false
        val grounds = pts.map { (x, y) -> ground.exact(x, y) }
        // Outlines across a cliff or a whole hillside (walls, ruins, odd mapping) would stand as giant walls.
        val spread = grounds.max() - grounds.min()
        if (spread > MAX_SLOPE_SPREAD) return false
        // Walls go a little into the ground on slopes, so no building floats; the roof is level, over the highest
        // corner on gentle ground, over the middle on steeper ground (the uphill side then sits in the slope).
        val base = grounds.min() - 1.5
        val top = (if (spread < 6) grounds.max() else grounds.average()) + heightM
        val seed = hash(index * 13 + 7).toFloat()
        var along = 0.0
        for (i in pts.indices) {
            val (ax, ay) = pts[i]; val (bx, by) = pts[(i + 1) % pts.size]
            val dx = bx - ax; val dy = by - ay; val len = sqrt(dx * dx + dy * dy)
            if (len < 0.05) continue
            // Outward normal of a counter-clockwise outline, in OpenGL axes (z south).
            val nx = (dy / len).toFloat(); val nz = (dx / len).toFloat()
            val ua = along.toFloat(); val ub = (along + len).toFloat()
            val hv = (top - base).toFloat()
            fun v(x: Double, y: Double, z: Double, u: Float, vv: Float) = out.add(x.toFloat(), z.toFloat(), (-y).toFloat(), nx, 0f, nz, u, vv, seed)
            v(ax, ay, base, ua, 0f); v(bx, by, base, ub, 0f); v(bx, by, top, ub, hv)
            v(ax, ay, base, ua, 0f); v(bx, by, top, ub, hv); v(ax, ay, top, ua, hv)
            along += len
        }
        val (vs, tris) = runCatching { Polygons.triangulate(Polygons.Shape(pts, emptyList())) }.getOrNull() ?: return true
        for (t in tris) for (k in t) out.add(vs[k].first.toFloat(), top.toFloat(), (-vs[k].second).toFloat(), 0f, 1f, 0f, -1f, (top - base).toFloat(), seed)
        return true
    }

    companion object {
        /** Metres from the route within which buildings and trees are made (the drone sees little further). */
        const val BUILDING_RANGE = 1300.0
        const val TREE_RANGE = 1100.0
        const val MAX_BUILDINGS = 30_000
        const val MAX_TREES = 120_000
        /** Metres of height difference under one building beyond which it is left out. */
        const val MAX_SLOPE_SPREAD = 20.0

        /** A steady pseudo-random number 0..1 for [i]. */
        fun hash(i: Int): Double {
            val s = sin(i * 12.9898 + 78.233) * 43758.5453
            return s - kotlin.math.floor(s)
        }

        /** Points every [step] metres along a line. */
        fun walk(pts: List<Pair<Double, Double>>, step: Double, f: (Double, Double) -> Unit) {
            if (pts.isEmpty()) return
            f(pts[0].first, pts[0].second)
            var carry = 0.0
            for (i in 0 until pts.size - 1) {
                val (ax, ay) = pts[i]; val (bx, by) = pts[i + 1]
                val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                var d = step - carry
                while (d <= len) { f(ax + (bx - ax) * d / len, ay + (by - ay) * d / len); d += step }
                carry = (carry + len) % step
            }
        }

        /** Fewer points, none further than [tolerance] metres from the outline (Douglas-Peucker). */
        fun simplify(p: List<Pair<Double, Double>>, tolerance: Double): List<Pair<Double, Double>> {
            if (p.size < 4) return p
            val keep = BooleanArray(p.size); keep[0] = true; keep[p.size - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>(); stack.add(0 to p.size - 1)
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var best = -1; var bestD = tolerance
                val (ax, ay) = p[a]; val (bx, by) = p[b]
                val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                for (i in a + 1 until b) {
                    val (x, y) = p[i]
                    val d = if (len < 1e-9) sqrt((x - ax) * (x - ax) + (y - ay) * (y - ay)) else abs((bx - ax) * (ay - y) - (ax - x) * (by - ay)) / len
                    if (d > bestD) { bestD = d; best = i }
                }
                if (best >= 0) { keep[best] = true; stack.add(a to best); stack.add(best to b) }
            }
            return p.filterIndexed { i, _ -> keep[i] }
        }
    }
}

/** A growing float array without boxing. */
class FloatArrayBuilder {
    private var a = FloatArray(4096)
    var size = 0
        private set

    fun add(vararg v: Float) {
        if (size + v.size > a.size) a = a.copyOf(max(a.size * 2, size + v.size))
        for (x in v) a[size++] = x
    }

    fun toArray(): FloatArray = a.copyOf(size)
}
