package com.openobd.core

import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.DroneScene
import com.openobd.core.trip.DroneScript
import com.openobd.core.trip.Ground
import com.openobd.core.trip.LatLon
import com.openobd.core.trip.LocalFrame
import com.openobd.core.trip.MapBuilding
import com.openobd.core.trip.Polygons
import com.openobd.core.trip.SceneFeatures
import com.openobd.core.trip.Terrain
import com.openobd.core.trip.TripVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DroneSceneTest {
    // A 3 km drive east through Segovia, on a gentle slope.
    private val pts = (0 until 60).map { i -> TrackPoint(1_700_000_000_000L + i * 3000L, 40.948, -4.13 + i * 0.0006, 50f, rpm = 1800f, alt = 1000f) }
    private val slope = Terrain { _, lon -> 1000 + (lon + 4.13) * 2000 }
    private val area = TripVideo.area(pts, minPad = 0.03)
    private val frame = LocalFrame(area)
    private val ground = Ground(frame, slope)
    private val script = DroneScript(TripVideo(pts, seconds = 10.0, fps = 30), frame, ground, 9.0 / 16)

    /** A square of [side] metres around a point [dx], [dy] metres from the start of the route. */
    private fun square(dx: Double, dy: Double, side: Double): List<LatLon> {
        val x0 = frame.x(pts[0].lon) + dx; val y0 = frame.y(pts[0].lat) + dy
        return listOf(x0 to y0, x0 + side to y0, x0 + side to y0 + side, x0 to y0 + side).map { (x, y) -> LatLon(frame.lat(y), frame.lon(x)) }
    }

    @Test
    fun `buildings stand on the ground with a level roof`() {
        val f = SceneFeatures.NONE.copy(buildings = listOf(MapBuilding(square(100.0, 30.0, 20.0), 12.0), MapBuilding(square(50_000.0, 0.0, 20.0), 9.0)))
        val s = DroneScene(f, ground, script)
        // The far one (50 km away) is left out.
        assertEquals(1, s.buildingCount)
        // Four walls of two triangles and a roof of two: 30 vertices of 9 numbers.
        assertEquals(30 * 9, s.buildings.size)
        val ys = (0 until 30).map { s.buildings[it * 9 + 1].toDouble() }
        val roof = (24 until 30).map { s.buildings[it * 9 + 1].toDouble() }
        assertTrue(roof.all { abs(it - roof[0]) < 1e-3 })
        // Roof 12 m over the highest corner; walls start below the lowest one (on a slope).
        val corners = square(100.0, 30.0, 20.0).map { ground.exact(frame.x(it.lon), frame.y(it.lat)) }
        assertEquals(corners.max() + 12, roof[0], 0.01)
        assertTrue(ys.min() < corners.min())
        // Wall normals point out, roof normals up.
        assertEquals(1.0, s.buildings[24 * 9 + 4].toDouble(), 1e-6)
        // Roofs are marked (u = -1) for the photo; walls carry metres along and up for the windows.
        assertEquals(-1f, s.buildings[24 * 9 + 6])
        assertTrue((0 until 24).all { s.buildings[it * 9 + 6] >= 0f })
    }

    @Test
    fun `woods fill with trees, rows and single trees too, all on the ground`() {
        val wood = square(-200.0, 50.0, 200.0)
        val f = SceneFeatures.NONE.copy(
            vegetation = listOf(SceneFeatures.Vegetation(wood, SceneFeatures.VegetationKind.FOREST)),
            trees = listOf(square(10.0, 10.0, 1.0)[0]),
            treeRows = listOf(listOf(square(0.0, -20.0, 1.0)[0], square(400.0, -20.0, 1.0)[0])),
        )
        val s = DroneScene(f, ground, script)
        // 200 x 200 m at about 8.5 m apart: several hundred trees; the row: about 50; and the single one.
        assertTrue("trees ${s.treeCount}", s.treeCount in 500..800)
        val woodXy = wood.map { frame.x(it.lon) to frame.y(it.lat) }
        var inWood = 0
        for (i in 0 until s.treeCount) {
            val x = s.trees[i * 5].toDouble(); val y = -s.trees[i * 5 + 2].toDouble(); val r = s.trees[i * 5 + 3].toDouble()
            assertTrue(r in 1.5..7.0)
            // The crown's centre above the ground by about its size.
            assertEquals(ground.exact(x, y) + r * 1.35, s.trees[i * 5 + 1].toDouble(), 0.05)
            if (Polygons.contains(woodXy, x to y)) inWood++
        }
        assertTrue(inWood > 400)
    }

    @Test
    fun `lakes lie flat at their lowest bank, rivers along the ground`() {
        val lake = square(300.0, 200.0, 150.0)
        val river = SceneFeatures.Waterway(listOf(square(0.0, 100.0, 1.0)[0], square(800.0, 100.0, 1.0)[0]), 14.0)
        val s = DroneScene(SceneFeatures.NONE.copy(water = listOf(lake), waterways = listOf(river)), ground, script)
        assertTrue(s.water.size % 9 == 0 && s.water.isNotEmpty())
        val low = lake.minOf { ground.exact(frame.x(it.lon), frame.y(it.lat)) }
        // The first two triangles are the lake: all at one level.
        for (k in 0 until 6) assertEquals(low + 0.4, s.water[k * 3 + 1].toDouble(), 1e-3)
    }

    @Test
    fun `OpenStreetMap answers are read`() {
        val json = """{"elements":[
            {"type":"way","tags":{"building":"yes","building:levels":"4"},"geometry":[{"lat":40.9,"lon":-4.1},{"lat":40.9,"lon":-4.0999},{"lat":40.9001,"lon":-4.0999},{"lat":40.9,"lon":-4.1}]},
            {"type":"way","tags":{"landuse":"forest"},"geometry":[{"lat":40.9,"lon":-4.1},{"lat":40.9,"lon":-4.09},{"lat":40.91,"lon":-4.09},{"lat":40.9,"lon":-4.1}]},
            {"type":"way","tags":{"leisure":"park"},"geometry":[{"lat":40.9,"lon":-4.1},{"lat":40.9,"lon":-4.09},{"lat":40.91,"lon":-4.09},{"lat":40.9,"lon":-4.1}]},
            {"type":"node","lat":40.95,"lon":-4.12,"tags":{"natural":"tree"}},
            {"type":"way","tags":{"natural":"tree_row"},"geometry":[{"lat":40.9,"lon":-4.1},{"lat":40.91,"lon":-4.1}]},
            {"type":"way","tags":{"waterway":"river"},"geometry":[{"lat":40.9,"lon":-4.1},{"lat":40.91,"lon":-4.1}]},
            {"type":"way","tags":{"natural":"water"},"geometry":[{"lat":40.9,"lon":-4.1},{"lat":40.9,"lon":-4.09},{"lat":40.91,"lon":-4.09},{"lat":40.9,"lon":-4.1}]}
        ]}"""
        val f = SceneFeatures.parse(json)
        assertEquals(1, f.buildings.size); assertEquals(13.0, f.buildings[0].heightM, 1e-9)
        assertEquals(listOf(SceneFeatures.VegetationKind.FOREST, SceneFeatures.VegetationKind.PARK), f.vegetation.map { it.kind })
        assertEquals(1, f.trees.size); assertEquals(1, f.treeRows.size); assertEquals(1, f.water.size)
        assertEquals(14.0, f.waterways.single().widthM, 1e-9)
        // Only the squares are sent, never the route itself.
        val q = SceneFeatures.query(listOf(doubleArrayOf(40.9, -4.2, 40.92, -4.18)))
        assertTrue(q.contains("40.90000,-4.20000,40.92000,-4.18000") && q.contains("way[\"building\"]"))
    }

    @Test
    fun `sharp squares cover the whole route and the distance to it is known`() {
        assertTrue(script.nearWindows.size >= 3)
        for (i in script.routeX.indices) {
            val x = script.routeX[i]; val y = script.routeY[i]
            assertTrue(script.nearWindows.any { x in it.x0..it.x1 && y in it.y0..it.y1 })
            assertTrue(script.distanceToRoute(x, y) < 150)
        }
        assertTrue(script.distanceToRoute(script.routeX[0], script.routeY[0] + 1000) in 850.0..1150.0)
        // Close to the car the sharp squares come first.
        val mid = script.video.frames / 2
        assertEquals(DroneScript.NEAR, script.windowsAt(mid).first().level)
    }

    @Test
    fun `buildings across a cliff are left out instead of standing as giant walls`() {
        // 400 m long over the slope of this ground (2000 m per degree of longitude, about 10 m per 400 m): kept;
        // on a cliff ten times steeper: left out.
        val cliff = Ground(frame, Terrain { _, lon -> 1000 + (lon + 4.13) * 40_000 })
        val long = MapBuilding(square(100.0, 30.0, 400.0), 9.0)
        assertEquals(1, DroneScene(SceneFeatures.NONE.copy(buildings = listOf(long)), ground, script).buildingCount)
        assertEquals(0, DroneScene(SceneFeatures.NONE.copy(buildings = listOf(long)), cliff, script).buildingCount)
    }
}
