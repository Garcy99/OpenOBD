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
        for (m in meshes) {
            // Every edge is used once in each direction: closed and consistently oriented.
            val edges = HashMap<Pair<Int, Int>, Int>()
            m.t.forEach { t -> for (k in 0..2) { val e = t[k] to t[(k + 1) % 3]; edges[e] = (edges[e] ?: 0) + 1 } }
            assertTrue(m.name, edges.all { (e, n) -> n == 1 && edges[e.second to e.first] == 1 })
            // Positive volume: normals point outwards.
            var vol = 0.0
            m.t.forEach { t ->
                val a = m.v[t[0]]; val b = m.v[t[1]]; val c = m.v[t[2]]
                vol += (a[0] * (b[1] * c[2] - b[2] * c[1]) - a[1] * (b[0] * c[2] - b[2] * c[0]) + a[2] * (b[0] * c[1] - b[1] * c[0])) / 6
            }
            assertTrue("${m.name} volume $vol", vol > 0)
        }
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
        assertEquals(7, model.getElementsByTagNameNS(ns, "object").length)
        assertEquals(7, model.getElementsByTagNameNS(ns, "base").length)
        assertEquals(7, model.getElementsByTagNameNS(ns, "item").length)
    }

    @Test fun `routes without speed or altitude still export`() {
        val bare = points.map { it.copy(speedKmh = null, alt = null, rpm = null) }
        assertEquals(listOf("Base", "Route", "Start", "End"), Route3mf.meshes(bare).map { it.name })
        assertEquals(null, RouteGeometry.climb(bare))
        assertTrue(Gpx.write(info, bare).contains("<trkpt"))
        assertTrue(Route3mf.meshes(points.take(1)).isEmpty())
    }
}
