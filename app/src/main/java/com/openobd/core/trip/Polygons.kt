package com.openobd.core.trip

import kotlin.math.abs

typealias Pt = Pair<Double, Double>

/**
 * Plane polygons for embossed text: grouping glyph outlines into shapes with holes, and triangulating them
 * (holes bridged into the outline, then ear clipping). Points are (x, y) with y up.
 */
object Polygons {
    /** Signed area: positive when counter-clockwise. */
    fun area(p: List<Pt>): Double {
        var a = 0.0
        for (i in p.indices) { val (x1, y1) = p[i]; val (x2, y2) = p[(i + 1) % p.size]; a += x1 * y2 - x2 * y1 }
        return a / 2
    }

    fun contains(poly: List<Pt>, pt: Pt): Boolean {
        var inside = false; var j = poly.lastIndex
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[j]
            if ((yi > pt.second) != (yj > pt.second) && pt.first < (xj - xi) * (pt.second - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** A shape: outline counter-clockwise, holes clockwise. */
    data class Shape(val outer: List<Pt>, val holes: List<List<Pt>>)

    /**
     * Glyph contours (any winding) to shapes: a contour inside an odd number of others is a hole of the smallest
     * one containing it; letters inside holes (rare) become shapes again.
     */
    fun group(contours: List<List<Pt>>): List<Shape> {
        val cs = contours.map { dedupe(it) }.filter { it.size >= 3 && abs(area(it)) > 1e-9 }
        val depth = cs.map { c -> cs.count { o -> o !== c && contains(o, c[0]) && abs(area(o)) > abs(area(c)) } }
        val shapes = mutableListOf<Shape>()
        for ((i, c) in cs.withIndex()) {
            if (depth[i] % 2 != 0) continue
            val outer = if (area(c) < 0) c.reversed() else c
            val holes = cs.withIndex().filter { (j, h) ->
                depth[j] == depth[i] + 1 && contains(c, h[0]) &&
                    // the smallest even-depth contour containing it
                    cs.withIndex().none { (k, o) -> k != i && depth[k] == depth[i] && contains(o, h[0]) && abs(area(o)) < abs(area(c)) }
            }.map { (_, h) -> if (area(h) > 0) h.reversed() else h }
            shapes += Shape(outer, holes)
        }
        return shapes
    }

    /** Removes repeated points and points on a straight line between their neighbours. */
    private fun dedupe(p: List<Pt>): List<Pt> {
        val out = mutableListOf<Pt>()
        for (q in p) if (out.isEmpty() || dist2(out.last(), q) > 1e-12) out += q
        while (out.size > 1 && dist2(out.first(), out.last()) <= 1e-12) out.removeAt(out.lastIndex)
        var changed = true
        while (changed && out.size > 3) {
            changed = false
            var i = 0
            while (i < out.size && out.size > 3) {
                val a = out[(i - 1 + out.size) % out.size]; val b = out[i]; val c = out[(i + 1) % out.size]
                val len = kotlin.math.sqrt(dist2(a, c))
                if (len == 0.0 || abs(cross(a, b, c)) / len < 1e-4 * (1 + len)) { out.removeAt(i); changed = true } else i++
            }
        }
        return out
    }

    private fun dist2(a: Pt, b: Pt) = (a.first - b.first).let { it * it } + (a.second - b.second).let { it * it }

    /**
     * Triangles of a shape, as indices into [vertices] = outer + every hole, in order. Triangles are
     * counter-clockwise (facing +z).
     */
    fun triangulate(shape: Shape): Pair<List<Pt>, List<IntArray>> {
        val vertices = shape.outer + shape.holes.flatten()
        // Polygon as a list of vertex indices; holes are spliced in through bridges.
        var ring = shape.outer.indices.toMutableList()
        var offset = shape.outer.size
        val holeRings = shape.holes.map { h -> (offset until offset + h.size).toList().also { offset += h.size } }
        for (hole in holeRings.sortedByDescending { h -> h.maxOf { vertices[it].first } }) ring = bridge(vertices, ring, hole)
        return vertices to earClip(vertices, ring)
    }

    private fun cross(o: Pt, a: Pt, b: Pt) = (a.first - o.first) * (b.second - o.second) - (a.second - o.second) * (b.first - o.first)

    /**
     * Connects [hole] (clockwise) to [ring]: from the hole's right-most vertex to the nearest ring vertex it can see
     * (the segment crosses no edge of the ring or the hole), then around the hole and back.
     */
    private fun bridge(v: List<Pt>, ring: MutableList<Int>, hole: List<Int>): MutableList<Int> {
        val mIdx = hole.indices.maxBy { v[hole[it]].first }
        val m = v[hole[mIdx]]
        val edges = ring.indices.map { ring[it] to ring[(it + 1) % ring.size] } + hole.indices.map { hole[it] to hole[(it + 1) % hole.size] }
        fun visible(k: Int): Boolean {
            val q = v[ring[k]]
            return edges.none { (e1, e2) ->
                val a = v[e1]; val b = v[e2]
                if (a == q || b == q || a == m || b == m) false else segmentsCross(m, q, a, b)
            } && contains(ring.map { v[it] }, ((m.first + q.first) / 2) to ((m.second + q.second) / 2)) &&
                !contains(hole.map { v[it] }, ((m.first + q.first) / 2) to ((m.second + q.second) / 2))
        }
        val order = ring.indices.sortedBy { dist2(v[ring[it]], m) }
        val p = order.firstOrNull { visible(it) } ?: order.first()
        val out = mutableListOf<Int>()
        for (i in 0..p) out += ring[i]
        for (k in 0..hole.size) out += hole[(mIdx + k) % hole.size]
        out += ring[p]
        for (i in p + 1 until ring.size) out += ring[i]
        return out
    }

    private fun segmentsCross(p1: Pt, p2: Pt, q1: Pt, q2: Pt): Boolean {
        val d1 = cross(q1, q2, p1); val d2 = cross(q1, q2, p2); val d3 = cross(p1, p2, q1); val d4 = cross(p1, p2, q2)
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))
    }

    private fun inTriangle(a: Pt, b: Pt, c: Pt, p: Pt): Boolean {
        val d1 = cross(a, b, p); val d2 = cross(b, c, p); val d3 = cross(c, a, p)
        val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    private fun earClip(v: List<Pt>, start: List<Int>): List<IntArray> {
        val ring = start.toMutableList()
        val out = mutableListOf<IntArray>()
        var guard = 0
        while (ring.size > 3 && guard++ < 100_000) {
            var clipped = false
            for (i in ring.indices) {
                val ia = ring[(i - 1 + ring.size) % ring.size]; val ib = ring[i]; val ic = ring[(i + 1) % ring.size]
                val a = v[ia]; val b = v[ib]; val c = v[ic]
                if (cross(a, b, c) <= 1e-12) continue
                val blocked = ring.any { k ->
                    k != ia && k != ib && k != ic && v[k] != a && v[k] != b && v[k] != c && inTriangle(a, b, c, v[k])
                }
                if (blocked) continue
                out += intArrayOf(ia, ib, ic)
                ring.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) {
                // Degenerate leftovers (collinear runs): drop the flattest vertex and carry on.
                val i = ring.indices.minBy { k -> abs(cross(v[ring[(k - 1 + ring.size) % ring.size]], v[ring[k]], v[ring[(k + 1) % ring.size]])) }
                ring.removeAt(i)
            }
        }
        if (ring.size == 3 && cross(v[ring[0]], v[ring[1]], v[ring[2]]) > 1e-12) out += intArrayOf(ring[0], ring[1], ring[2])
        return out
    }
}
