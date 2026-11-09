package com.openobd.core

import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.Gpx
import com.openobd.core.trip.LatLon
import com.openobd.core.trip.MapBuilding
import com.openobd.core.trip.MapFeatures
import com.openobd.core.trip.MapRoad
import com.openobd.core.trip.Overpass
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
import kotlin.math.abs
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
        val model = Route3mf.model(points)
        val meshes = model.meshes
        assertEquals(listOf("Base", "Route", "Start", "End"), meshes.map { it.name })
        // Flat ground in one colour (no terrain), plinth first so unpainted parts print in it.
        assertEquals(listOf(Route3mf.SIDES, Route3mf.GROUND, Route3mf.ROUTE, Route3mf.TEXT, Route3mf.END), model.filaments.map { it.roles.first() })
        assertEquals(model.filaments.map { it.color }.distinct(), model.filaments.map { it.color })
        meshes.forEach { assertSolid(it) }
        val base = meshes.first()
        val size = base.v.maxOf { it[0] } to base.v.maxOf { it[1] }
        assertTrue(size.first <= 150.01 && size.second <= 150.01)
        // Relief from GPS altitude: the route is higher where the road is.
        val route = meshes.filter { it.name.startsWith("Route") }.flatMap { it.v }
        assertTrue(route.maxOf { it[2] } - route.minOf { it[2] } > 5)
        assertTrue(route.all { it[0] in 0.0..size.first && it[1] in 0.0..size.second })

        val bytes = ByteArrayOutputStream().also { Route3mf.write(model, it, "Trip") }.toByteArray()
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { entries[it.name] = z.readBytes().toString(Charsets.UTF_8) } }
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "3D/3dmodel.model", "Metadata/Slic3r_PE_model.config"), entries.keys)
        val doc = xml(entries.getValue("3D/3dmodel.model"))
        val ns = "http://schemas.microsoft.com/3dmanufacturing/core/2015/02"
        // PrusaSlicer's dialect, which Bambu Studio recognises by the application name: one object, one build item.
        assertEquals(1, doc.getElementsByTagNameNS(ns, "object").length)
        assertEquals(1, doc.getElementsByTagNameNS(ns, "item").length)
        assertEquals(model.filaments.size, doc.getElementsByTagNameNS(ns, "base").length)
        val text = entries.getValue("3D/3dmodel.model")
        assertTrue(text.contains("<metadata name=\"Application\">OpenOBD (PrusaSlicer 3MF)</metadata>"))
        assertTrue(text.contains("<metadata name=\"slic3rpe:Version3mf\">1</metadata>"))
        assertEquals(model.triangles, doc.getElementsByTagNameNS(ns, "triangle").length)
        // Parts are triangle ranges with their filament, covering every triangle once.
        val config = xml(entries.getValue("Metadata/Slic3r_PE_model.config"))
        val volumes = config.getElementsByTagName("volume")
        assertEquals(meshes.size, volumes.length)
        var next = 0
        for (i in 0 until volumes.length) {
            val v = volumes.item(i) as org.w3c.dom.Element
            assertEquals(next, v.getAttribute("firstid").toInt())
            next = v.getAttribute("lastid").toInt() + 1
            val meta = (0 until v.getElementsByTagName("metadata").length).associate { k ->
                (v.getElementsByTagName("metadata").item(k) as org.w3c.dom.Element).let { it.getAttribute("key") to it.getAttribute("value") }
            }
            assertEquals(meshes[i].name, meta["name"])
            assertEquals(model.filamentOf(meshes[i].color).toString(), meta["extruder"])
        }
        assertEquals(model.triangles, next)
        assertEquals(listOf("4", "8", "0C", "1C", "2C", "3C"), (1..6).map { Route3mf.paintCode(it) })
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
        for (shape in Route3mf.Shape.entries) {
            val meshes = Route3mf.meshes(points, Route3mf.Options(shape = shape), hill, null, letterO)
            assertEquals(listOf("Terrain", "Route", "Start", "End", "Plaque", "Title"), meshes.map { it.name })
            meshes.forEach { assertSolid(it) }
            val ground = meshes.first()
            val w = ground.v.maxOf { it[0] } - ground.v.minOf { it[0] }; val h = ground.v.maxOf { it[1] } - ground.v.minOf { it[1] }
            assertEquals(150.0, max(w, h), 0.01)
            when (shape) {
                Route3mf.Shape.HEXAGON -> assertEquals(1 / cos(Math.PI / 6), w / h, 0.01)
                else -> assertEquals(1.0, w / h, 0.01)
            }
            // Relief: the hill shows (exaggerated, capped) and the ground never gets thinner than the base.
            val tops = ground.v.filter { it[2] > 0 }
            assertTrue(tops.maxOf { it[2] } - tops.minOf { it[2] } in 5.0..40.0)
            assertTrue(tops.minOf { it[2] } >= 3.0 - 1e-9)
            // Coloured by altitude: lowlands, slopes and the peak of the hill; sides in the plinth colour.
            val th = Route3mf.Theme.ALPINE
            assertTrue(ground.c.containsAll(listOf(th.low, th.mid, th.high, th.side)))
            val peak = ground.t.indices.maxBy { k -> ground.t[k].sumOf { ground.v[it][2] } }
            assertEquals(th.high, ground.c[peak])
            // The route sits on the ground: its top is above the ground under it.
            val route = meshes.filter { it.name.startsWith("Route") }.flatMap { it.v }
            assertTrue(route.maxOf { it[2] } > 3.0 + 1.0)
            // Title on the plaque, in front of the ground.
            val title = meshes.last(); val plaque = meshes[meshes.size - 2]
            assertTrue(title.v.minOf { it[1] } >= plaque.v.minOf { it[1] } && title.v.maxOf { it[1] } <= plaque.v.maxOf { it[1] })
            assertTrue(plaque.v.minOf { it[1] } < ground.v.minOf { it[1] })
        }
    }

    /** Lake, roads and a building around the start of the route, as OpenStreetMap would give them. */
    private fun features(): MapFeatures {
        val o = points.first()
        fun at(dx: Double, dy: Double) = LatLon(o.lat + dy / 111_000, o.lon + dx / 85_000)
        val lake = listOf(at(-1800.0, -400.0), at(-1200.0, -400.0), at(-1200.0, 200.0), at(-1800.0, 200.0))
        val road = MapRoad((0..20).map { at(-2000.0 + it * 150.0, -900.0) }, 0)
        val house = MapBuilding(listOf(at(-600.0, 300.0), at(-560.0, 300.0), at(-560.0, 330.0), at(-600.0, 330.0)), 30.0)
        return MapFeatures(listOf(lake), listOf(road, MapRoad(road.points.map { it.copy(lat = it.lat + 0.004) }, 3)), listOf(house))
    }

    @Test fun `water, roads and buildings from the map`() {
        val f = features()
        val small = points.map { it.copy(lat = points[0].lat + (it.lat - points[0].lat) / 4, lon = points[0].lon + (it.lon - points[0].lon) / 4) }
        // Buildings are only drawn for small areas; this one is ~3 km.
        assertTrue(Route3mf.spanMetres(small) < Overpass.MAX_BUILDINGS_SPAN_M)
        val model = Route3mf.model(small, Route3mf.Options(theme = Route3mf.Theme.FOREST), hill, f.copy(water = f.water.map { w -> w.map { LatLon(points[0].lat + (it.lat - points[0].lat) / 4, points[0].lon + (it.lon - points[0].lon) / 4) } }))
        val names = model.meshes.map { it.name }
        assertEquals(listOf("Terrain", "Roads", "Buildings", "Route", "Start", "End"), names)
        model.meshes.forEach { assertSolid(it) }
        val ground = model.meshes.first()
        assertTrue("lake painted", ground.c.contains(Route3mf.Theme.FOREST.water))
        // Wider roads for motorways than for minor roads, both on top of the ground.
        val roads = model.meshes.first { it.name == "Roads" }
        assertTrue(roads.v.minOf { it[2] } < roads.v.maxOf { it[2] })
        val house = model.meshes.first { it.name == "Buildings" }
        assertTrue(house.v.maxOf { it[2] } - house.v.minOf { it[2] } > 2)
        // One filament per colour, each with what it prints.
        val roles = model.filaments.flatMap { it.roles }
        assertTrue(roles.containsAll(listOf(Route3mf.SIDES, Route3mf.WATER, Route3mf.ROADS, Route3mf.BUILDINGS, Route3mf.ROUTE)))
        assertEquals(1, model.filamentOf(Route3mf.Theme.FOREST.side))
        // Layers can be switched off.
        val bare = Route3mf.model(small, Route3mf.Options(water = false, roads = false, buildings = false), hill, f)
        assertEquals(listOf("Terrain", "Route", "Start", "End"), bare.meshes.map { it.name })
        assertTrue(bare.filaments.none { Route3mf.WATER in it.roles })
        // Large areas leave buildings out even when asked.
        val big = points.map { it.copy(lat = points[0].lat + (it.lat - points[0].lat) * 3, lon = points[0].lon + (it.lon - points[0].lon) * 3) }
        assertTrue(Route3mf.model(big, Route3mf.Options(), hill, f).meshes.none { it.name == "Buildings" })
    }

    @Test fun `Overpass answers become water rings, ranked roads and building heights`() {
        val json = """
            {"elements":[
             {"type":"way","id":1,"tags":{"natural":"water"},"geometry":[{"lat":1,"lon":1},{"lat":1,"lon":2},{"lat":2,"lon":2},{"lat":1,"lon":1}]},
             {"type":"way","id":2,"tags":{"highway":"primary"},"geometry":[{"lat":0,"lon":0},{"lat":0,"lon":1}]},
             {"type":"way","id":3,"tags":{"highway":"residential"},"geometry":[{"lat":0,"lon":0},{"lat":0,"lon":1}]},
             {"type":"way","id":4,"tags":{"building":"yes","building:levels":"4"},"geometry":[{"lat":3,"lon":3},{"lat":3,"lon":4},{"lat":4,"lon":4},{"lat":3,"lon":3}]},
             {"type":"way","id":5,"tags":{"building":"yes","height":"12,5 m"},"geometry":[{"lat":3,"lon":3},{"lat":3,"lon":4},{"lat":4,"lon":4},{"lat":3,"lon":3}]},
             {"type":"relation","id":6,"tags":{"natural":"water"},"members":[
               {"type":"way","role":"outer","geometry":[{"lat":5,"lon":5},{"lat":5,"lon":6}]},
               {"type":"way","role":"outer","geometry":[{"lat":6,"lon":5},{"lat":6,"lon":6}]},
               {"type":"way","role":"outer","geometry":[{"lat":5,"lon":6},{"lat":6,"lon":6}]},
               {"type":"way","role":"outer","geometry":[{"lat":6,"lon":5},{"lat":5,"lon":5}]},
               {"type":"way","role":"inner","geometry":[{"lat":5.4,"lon":5.4},{"lat":5.5,"lon":5.5}]}]}
            ]}
        """.trimIndent()
        val f = Overpass.parse(json)
        assertEquals(2, f.water.size)
        assertEquals(3, f.water[0].size)
        assertEquals(4, f.water[1].size)
        assertEquals(listOf(1), f.roads.map { it.rank })
        assertEquals(listOf(13.0, 12.5), f.buildings.map { it.heightM })
        assertEquals(6.0, Overpass.height(emptyMap()), 0.0)
        val q = Overpass.query(40.0, -3.8, 40.1, -3.7, buildings = false)
        assertTrue(q.contains("(40.000000,-3.800000,40.100000,-3.700000)") && q.contains("highway") && !q.contains("building"))
        assertTrue(Overpass.query(40.0, -3.8, 40.1, -3.7, buildings = true).contains("way[\"building\"]"))
        assertTrue(Overpass.parse("{}").isEmpty)
    }

    @Test fun `poster with shaded relief embeds a valid PNG under the route`() {
        val poster = RoutePoster.layout(info, points, { it }, terrain = hill, pxPerMm = 1.0)
        val img = poster.shapes.filterIsInstance<RoutePoster.Image>().single()
        // The relief comes before the route, so the route is drawn on top.
        assertTrue(poster.shapes.indexOf(img) < poster.shapes.indexOfFirst { it is RoutePoster.Line })
        val shades = img.argb.filter { it ushr 24 == 0xFF }.map { it and 0xFF }.toSet()
        assertTrue("relief has shading", shades.size > 10)
        val png = com.openobd.core.trip.Png.encode(img.width, img.height, img.argb)
        assertEquals(listOf(0x89, 0x50, 0x4E, 0x47), png.take(4).map { it.toInt() and 0xFF })
        // IDAT inflates to one filter byte plus 4 bytes per pixel on each row.
        val idatAt = String(png, Charsets.ISO_8859_1).indexOf("IDAT")
        val len = java.nio.ByteBuffer.wrap(png, idatAt - 4, 4).int
        val raw = java.util.zip.InflaterInputStream(png.copyOfRange(idatAt + 4, idatAt + 4 + len).inputStream()).readBytes()
        assertEquals(img.height * (1 + img.width * 4), raw.size)
        val svg = RoutePoster.svg(poster)
        assertTrue(svg.contains("xlink:href=\"data:image/png;base64,") && svg.contains("Terrain Tiles"))
        xml(svg)
        // Without terrain: route only, no image.
        assertTrue(RoutePoster.layout(info, points, { it }).shapes.none { it is RoutePoster.Image })
    }

    @Test fun `colours by speed and coordinates under the title`() {
        val model = Route3mf.model(points, Route3mf.Options(routeBySpeed = true, reliefColours = false), hill, null, letterO, letterO.map { c -> c.map { (x, y) -> x * 3 to y } })
        val meshes = model.meshes
        assertEquals(listOf("Terrain", "Route up to 50 km/h", "Route 50-100 km/h", "Route over 100 km/h", "Start", "End", "Plaque", "Title"), meshes.map { it.name })
        // Plinth and plaque share filament 1, the title and start marker another.
        assertEquals(model.filamentOf(meshes[0].color), model.filamentOf(meshes[6].color))
        assertEquals(1, model.filamentOf(meshes[6].color))
        assertEquals(model.filamentOf(meshes[4].color), model.filamentOf(meshes[7].color))
        assertEquals(7, model.filaments.size) // sides, ground, 3 speeds, text, end
        meshes.forEach { assertSolid(it) }
        // Two lines of text: the coordinates sit below the title, both on the plaque.
        val title = meshes.last(); val plaque = meshes[meshes.size - 2]
        val ys = title.v.map { it[1] }.sorted()
        assertTrue(ys.first() >= plaque.v.minOf { it[1] } && ys.last() <= plaque.v.maxOf { it[1] })
        assertEquals("40.41680° N, 3.67380° W", RouteGeometry.coordinates(points[0].lat, points[0].lon))
    }

    @Test fun `the route curve passes through every GPS point`() {
        val curve = RouteGeometry.smooth(points, 3.0, 2.0)
        val m = RouteGeometry.metres(points)
        // Points at least 3 m apart are all kept, and the curve goes exactly through them.
        for (p in m) {
            val d = curve.minOf { (x, y, _) -> Math.hypot(x - p.first, y - p.second) }
            assertTrue("off by $d m", d < 1e-6)
        }
        // Between points it never strays far from the straight line (no overshoot loops).
        for ((x, y, i) in curve) {
            val j = (i + 1).coerceAtMost(m.lastIndex)
            val a = m[i]; val b = m[j]
            val dx = b.first - a.first; val dy = b.second - a.second
            val len = Math.hypot(dx, dy).coerceAtLeast(1e-9)
            val off = abs((x - a.first) * dy - (y - a.second) * dx) / len
            assertTrue("strays $off m", off < len * 0.5 + 0.5)
        }
        // GPS jitter while stopped (points under 3 m apart) is dropped.
        val jitter = points.take(3) + List(20) { k -> points[2].copy(t = points[2].t + k, lat = points[2].lat + (k % 2) * 0.000005) } + points.drop(3)
        assertTrue(RouteGeometry.smooth(jitter, 3.0, 2.0).size <= curve.size + 2)
    }

    @Test fun `routes without speed or altitude still export`() {
        val bare = points.map { it.copy(speedKmh = null, alt = null, rpm = null) }
        assertEquals(listOf("Base", "Route", "Start", "End"), Route3mf.meshes(bare).map { it.name })
        assertEquals(listOf("Base", "Route up to 50 km/h", "Start", "End"),
            Route3mf.meshes(bare, Route3mf.Options(routeBySpeed = true)).map { it.name })
        assertEquals(null, RouteGeometry.climb(bare))
        assertTrue(Gpx.write(info, bare).contains("<trkpt"))
        assertTrue(Route3mf.meshes(points.take(1)).isEmpty())
    }

    @Test fun `corrupt and empty elevation tiles are repaired or skipped`() {
        // A slope with a corrupt column thousands of metres off, as the service sometimes has.
        val tile = FloatArray(256 * 256) { 500f + (it / 256) * 2f }
        for (y in 0 until 256) tile[y * 256 + 112] = -7467f
        tile[40 * 256 + 40] = 9000f
        assertEquals(257, com.openobd.core.trip.Terrarium.despike(tile))
        assertTrue((0 until 256 * 256).all { abs(tile[it] - (500f + (it / 256) * 2f)) < 5f })
        val empty = FloatArray(256 * 256) { -32768f }
        assertTrue(com.openobd.core.trip.Terrarium.isEmpty(empty) && !com.openobd.core.trip.Terrarium.isEmpty(tile))
        // Empty pixels are unknown, and a coarser terrain fills them.
        val t = com.openobd.core.trip.TileTerrain(14, mapOf((7964 to 6001) to empty))
        assertEquals(null, t.elevation(43.2935, -5.0))
        assertEquals(800.0, com.openobd.core.trip.FallbackTerrain(t) { _, _ -> 800.0 }.elevation(43.2935, -5.0)!!, 0.0)
    }

    @Test fun `the model keeps to the maximum height and reports it`() {
        val tall = Route3mf.model(points, Route3mf.Options(exaggeration = 5.0, maxHeightMm = 20.0, baseMm = 2.0), hill)
        val ground = tall.meshes.first()
        assertEquals(20.0, ground.v.maxOf { it[2] }, 0.01)
        assertTrue(tall.relief.limited)
        assertEquals(2.0, tall.relief.baseMm, 0.0)
        assertEquals(18.0, tall.relief.reliefMm, 0.01)
        assertTrue(tall.size()[2] in 20.0..25.0) // the route and markers stand a little above the ground
        // Real scale fits easily: not limited, and lower than the cap.
        val flat = Route3mf.model(points, Route3mf.Options(exaggeration = 1.0, maxHeightMm = 60.0), hill)
        assertTrue(!flat.relief.limited && flat.meshes.first().v.maxOf { it[2] } < 60.0)
        // A gorge in one corner rests on the base instead of lifting everything else.
        val gorge = com.openobd.core.trip.Terrain { lat, lon ->
            val dx = (lon + 3.6705) * 85_000; val dy = (lat - 40.4168) * 111_000
            if (dx * dx + dy * dy < 250.0 * 250.0) 100.0 else hill.elevation(lat, lon)
        }
        val trimmed = Route3mf.model(points, Route3mf.Options(), gorge)
        assertTrue(trimmed.relief.trimmedM > 400)
        assertTrue(trimmed.meshes.first().v.filter { it[2] > 0 }.minOf { it[2] } >= 3.0 - 1e-9)
    }

    @Test fun `fewer filaments merge colours, and CMYKW mixes five spools on the ground`() {
        val f = features()
        fun count(c: Route3mf.Colours) = Route3mf.model(points, Route3mf.Options(colours = c), hill, f).filaments.size
        val all = count(Route3mf.Colours.ALL)
        assertTrue("all $all", all >= 8)
        assertTrue(count(Route3mf.Colours.SIX) <= 6)
        assertTrue(count(Route3mf.Colours.FOUR) <= 4)
        // Four: no slopes colour, and the route stands out in the plinth's colour.
        val four = Route3mf.model(points, Route3mf.Options(colours = Route3mf.Colours.FOUR), hill, f)
        assertTrue(four.filaments.none { Route3mf.MID in it.roles })
        assertEquals(four.filamentOf(four.meshes.first().color), four.filamentOf(four.meshes.first { it.name == "Route" }.color))

        val mix = Route3mf.model(points, Route3mf.Options(colours = Route3mf.Colours.CMYKW), hill, f)
        assertEquals(Route3mf.Colours.CMYKW.inks.map { it.hex }, mix.filaments.map { it.color })
        assertEquals(1, mix.filamentOf(Route3mf.Ink.BLACK.hex)) // black is also the plinth: filament 1
        mix.meshes.forEach { assertSolid(it) }
        val ground = mix.meshes.first()
        // The top is a halftone of every spool; where it is higher it is lighter (the theme's pale peaks).
        val top = ground.t.indices.filter { k -> ground.t[k].all { ground.v[it][2] > 0.01 } } // sides and bottom touch z = 0
        assertEquals(5, ground.c.toSet().size)
        fun light(k: Int) = ground.c[k].removePrefix("#").toInt(16).let { ((it shr 16 and 255) + (it shr 8 and 255) + (it and 255)) / 765.0 }
        val zTop = top.map { k -> ground.t[k].maxOf { ground.v[it][2] } }
        val zHigh = zTop.sorted()[zTop.size * 9 / 10]; val zLow = zTop.sorted()[zTop.size / 4]
        val high = top.filterIndexed { n, _ -> zTop[n] >= zHigh }.map(::light).average()
        val low = top.filterIndexed { n, _ -> zTop[n] <= zLow }.map(::light).average()
        assertTrue("peaks $high vs lowlands $low", high > low + 0.1)
        assertTrue(mix.meshes.none { it.name.startsWith("Route ") }) // no speed colours in mixing
    }

    @Test fun `landscape mixing gives green lowlands from green, brown, blue, black and white spools`() {
        val mix = Route3mf.model(points, Route3mf.Options(colours = Route3mf.Colours.LANDSCAPE), hill, features())
        // Loading order, the plinth's brown first.
        assertEquals(Route3mf.Colours.LANDSCAPE.inks.map { it.hex }, mix.filaments.map { it.color })
        assertEquals(Route3mf.Ink.BROWN.hex, mix.meshes.first().color)
        mix.meshes.forEach { assertSolid(it) }
        val ground = mix.meshes.first()
        val top = ground.t.indices.filter { k -> ground.t[k].all { ground.v[it][2] > 0.01 } }
        val zTop = top.map { k -> ground.t[k].maxOf { ground.v[it][2] } }
        val quarter = zTop.sorted()[zTop.size / 4]
        val low = top.filterIndexed { n, _ -> zTop[n] <= quarter }
        // Area-average of the lowlands: green dominates (the Alpine style's lowland colour is green).
        fun channel(k: Int, sh: Int) = (ground.c[k].removePrefix("#").toInt(16) shr sh) and 255
        val g = low.map { channel(it, 8) }.average(); val r = low.map { channel(it, 16) }.average(); val b = low.map { channel(it, 0) }.average()
        assertTrue("lowlands r=$r g=$g b=$b", g > r && g > b)
    }
}
