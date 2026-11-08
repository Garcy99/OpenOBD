package com.openobd.core

import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.Gpx
import com.openobd.core.trip.Route3mf
import com.openobd.core.trip.RouteGeometry
import com.openobd.core.trip.RouteHtml
import com.openobd.core.trip.RouteInfo
import com.openobd.core.trip.RoutePoster
import com.openobd.core.trip.Units
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

class RouteExportTest {
    /** A loop around Madrid: city, road and motorway speeds, rising 40 m, a few points without data. */
    private val points = (0 until 400).map { i ->
        val a = i / 400.0 * 2 * Math.PI
        val speed = when { i < 100 -> 35f; i < 250 -> 80f; i < 380 -> 115f; else -> null }
        TrackPoint(
            t = 1_700_000_000_000L + i * 2000L, lat = 40.4168 + 0.02 * sin(a), lon = -3.7038 + 0.03 * cos(a) + 0.00001 * (i % 3),
            speedKmh = speed, rpm = speed?.let { 900f + it * 20 }, l100 = 6.4f, coolantC = 88f, pedal = 22f, boostBar = 0.4f,
            alt = (650 + 40 * sin(a / 2)).toFloat(),
        )
    }
    private val info = RouteInfo("Trip to the office", "Opel Astra 1.6 CDTi", points.first().t, points.last().t, 12.3, 0.8, 54.0, 118.0, 6.5)

    private fun xml(text: String) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        .parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    @Test fun `GPX carries the track, waypoints and car data`() {
        val gpx = Gpx.write(info.copy(title = "Home & <office>"), points)
        val doc = xml(gpx)
        val pts = doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt")
        assertEquals(400, pts.length)
        assertEquals(2, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "wpt").length)
        assertEquals("40.4168000", pts.item(0).attributes.getNamedItem("lat").nodeValue)
        assertEquals("2023-11-14T22:13:20Z", doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "time").item(1).textContent)
        assertEquals("9.72", doc.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/TrackPointExtension/v2", "speed").item(0).textContent)
        assertEquals("1600", doc.getElementsByTagNameNS(Gpx.NS, "rpm").item(0).textContent)
        assertEquals(400, doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "ele").length)
        assertTrue(gpx.contains("Home &amp; &lt;office&gt;"))
    }

    @Test fun `HTML map embeds every point, the summary and the GPX`() {
        val html = RouteHtml.write(info, points, { it }, Units())
        assertTrue(html.contains("const D=[[40.416800,-3.673800,0,35.0,1600,6.4,88,22,0.40,650]"))
        assertTrue(html.contains("data:application/gpx+xml;base64,"))
        val b64 = html.substringAfter("base64,").substringBefore('"')
        assertTrue(String(java.util.Base64.getDecoder().decode(b64)).contains("<trkpt"))
        assertTrue(html.contains("12.3 km") && html.contains("6.5 L/100 km"))
        assertTrue(html.contains("leaflet@1.9.4"))
        // Text from the trip can't break out of the page.
        val evil = RouteHtml.write(info.copy(title = "</title><script>alert(1)</script>"), points, { it })
        assertTrue(!evil.contains("<script>alert(1)"))
        val imperial = RouteHtml.write(info, points, { it }, Units(imperial = true))
        assertTrue(imperial.contains("7.6 mi") && imperial.contains("mph"))
    }

    @Test fun `poster is valid SVG with the route coloured by speed`() {
        val poster = RoutePoster.layout(info, points, { it })
        val svg = RoutePoster.svg(poster)
        val doc = xml(svg)
        val lines = doc.getElementsByTagName("polyline")
        val colors = (0 until lines.length).map { lines.item(it).attributes.getNamedItem("stroke").nodeValue }.toSet()
        assertTrue(colors.containsAll(RouteGeometry.bandColors))
        assertTrue(svg.contains("Trip to the office"))
        assertTrue(svg.contains("Climb"))
        // Everything stays on the page.
        poster.shapes.filterIsInstance<RoutePoster.Line>().flatMap { it.points }.forEach { (x, y) ->
            assertTrue(x in 0.0..poster.widthMm && y in 0.0..poster.heightMm)
        }
    }

    @Test fun `3MF has watertight coloured parts that sit on the base`() {
        val meshes = Route3mf.meshes(points)
        assertEquals(listOf("Base", "Route up to 50 km/h", "Route 50-100 km/h", "Route over 100 km/h", "Route", "Start", "End"), meshes.map { it.name })
        meshes.forEach { assertSolid(it) }
        val base = meshes.first()
        val size = base.v.maxOf { it[0] } to base.v.maxOf { it[1] }
        assertTrue(size.first <= 150.01 && size.second <= 150.01)
        // Relief: the route is higher where the road is.
        val route = meshes.drop(1).dropLast(2).flatMap { it.v }
        assertTrue(route.maxOf { it[2] } - route.minOf { it[2] } > 5)
        assertTrue(route.all { it[0] in 0.0..size.first && it[1] in 0.0..size.second })

        val bytes = ByteArrayOutputStream().also { Route3mf.write(meshes, it, "Trip") }.toByteArray()
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { entries[it.name] = z.readBytes().toString(Charsets.UTF_8) } }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model"), entries.keys)
        val model = xml(entries.getValue("3D/3dmodel.model"))
        val ns = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
        // Seven parts plus the object that groups them, which is the one thing built.
        assertEquals(8, model.getElementsByTagNameNS(ns, "object").length)
        assertEquals(7, model.getElementsByTagNameNS(ns, "base").length)
        assertEquals(7, model.getElementsByTagNameNS(ns, "component").length)
        assertEquals(1, model.getElementsByTagNameNS(ns, "item").length)
    }

    /** Every edge used once in each direction (closed, consistently oriented) and positive volume (normals out). */
    private fun assertSolid(m: Route3mf.Mesh): Double {
        val edges = HashMap<Pair<Int, Int>, Int>()
        m.t.forEach { t -> for (k in 0..2) { val e = t[k] to t[(k + 1) % 3]; edges[e] = (edges[e] ?: 0) + 1 } }
        assertTrue(m.name, edges.all { (e, n) -> n == 1 && edges[e.second to e.first] == 1 })
        var vol = 0.0
        m.t.forEach { t ->
            val a = m.v[t[0]]; val b = m.v[t[1]]; val c = m.v[t[2]]
            vol += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6
        }
        assertTrue("${m.name} volume $vol", vol > 0)
        return vol
    }

    /** A hill 300 m high, 1 km east of the route's start. */
    private val hill = com.openobd.core.trip.Terrain { lat, lon ->
        val dx = (lon - (-3.7038 + 0.012)) * 85_000; val dy = (lat - 40.4168) * 111_000
        600 + 300 * Math.exp(-(dx * dx + dy * dy) / (2 * 900.0 * 900.0))
    }

    /** Outline of an "O": a square with a square hole. */
    private val letterO = listOf(
        listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 14.0, 0.0 to 14.0),
        listOf(3.0 to 3.0, 3.0 to 11.0, 7.0 to 11.0, 7.0 to 3.0),
    )

    @Test fun `text outlines with holes become solid letters`() {
        val shapes = com.openobd.core.trip.Polygons.group(letterO)
        assertEquals(1, shapes.size)
        assertEquals(1, shapes[0].holes.size)
        val (pts, tris) = com.openobd.core.trip.Polygons.triangulate(shapes[0])
        val area = tris.sumOf { t -> com.openobd.core.trip.Polygons.area(listOf(pts[t[0]], pts[t[1]], pts[t[2]])) }
        assertEquals(10.0 * 14 - 4 * 8, area, 1e-6) // every triangle counter-clockwise, hole left out
        // "B"-like: two holes, plus a separate dot; outlines given in the opposite winding.
        val b = listOf(
            listOf(0.0 to 0.0, 0.0 to 20.0, 12.0 to 20.0, 12.0 to 0.0),
            listOf(3.0 to 3.0, 9.0 to 3.0, 9.0 to 8.0, 3.0 to 8.0),
            listOf(3.0 to 11.0, 9.0 to 11.0, 9.0 to 17.0, 3.0 to 17.0),
            listOf(15.0 to 0.0, 15.0 to 3.0, 18.0 to 3.0, 18.0 to 0.0),
        )
        val groups = com.openobd.core.trip.Polygons.group(b)
        assertEquals(listOf(2, 0), groups.map { it.holes.size })
        val total = groups.sumOf { g -> com.openobd.core.trip.Polygons.triangulate(g).let { (p, t) -> t.sumOf { com.openobd.core.trip.Polygons.area(listOf(p[it[0]], p[it[1]], p[it[2]])) } } }
        assertEquals(12.0 * 20 - 30 - 36 + 9, total, 1e-6)
    }

    @Test fun `terrain model in every shape with the title`() {
        for (shape in com.openobd.core.trip.Route3mf.Shape.entries) {
            val meshes = Route3mf.meshes(points, Route3mf.Options(shape = shape), hill, letterO)
            assertEquals(listOf("Terrain", "Route up to 50 km/h", "Route 50-100 km/h", "Route over 100 km/h", "Route", "Start", "End", "Plaque", "Title"), meshes.map { it.name })
            meshes.forEach { assertSolid(it) }
            val ground = meshes.first()
            val w = ground.v.maxOf { it[0] } - ground.v.minOf { it[0] }; val h = ground.v.maxOf { it[1] } - ground.v.minOf { it[1] }
            assertEquals(150.0, max(w, h), 0.01)
            when (shape) {
                com.openobd.core.trip.Route3mf.Shape.HEXAGON -> assertEquals(1 / cos(Math.PI / 6), w / h, 0.01)
                else -> assertEquals(1.0, w / h, 0.01)
            }
            // Relief: the hill shows (exaggerated, capped) and the ground never gets thinner than the base.
            val tops = ground.v.filter { it[2] > 0 }
            assertTrue(tops.maxOf { it[2] } - tops.minOf { it[2] } in 5.0..40.0)
            assertTrue(tops.minOf { it[2] } >= 3.0 - 1e-9)
            // The route sits on the ground: its top is above the ground under it.
            val route = meshes.filter { it.name.startsWith("Route") }.flatMap { it.v }
            val routeTop = route.maxOf { it[2] }
            assertTrue(routeTop > 3.0 + 1.0)
            // Title on the plaque, in front of the ground.
            val title = meshes.last(); val plaque = meshes[meshes.size - 2]
            assertTrue(title.v.minOf { it[1] } >= plaque.v.minOf { it[1] } && title.v.maxOf { it[1] } <= plaque.v.maxOf { it[1] })
            assertTrue(plaque.v.minOf { it[1] } < ground.v.minOf { it[1] })
        }
    }

    @Test fun `routes without speed or altitude still export`() {
        val bare = points.map { it.copy(speedKmh = null, alt = null, rpm = null) }
        assertEquals(listOf("Base", "Route", "Start", "End"), Route3mf.meshes(bare).map { it.name })
        assertEquals(null, RouteGeometry.climb(bare))
        assertTrue(Gpx.write(info, bare).contains("<trkpt"))
        assertTrue(Route3mf.meshes(points.take(1)).isEmpty())
    }
}
