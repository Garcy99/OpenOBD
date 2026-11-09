package com.openobd.core

import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.DroneScript
import com.openobd.core.trip.Ground
import com.openobd.core.trip.LocalFrame
import com.openobd.core.trip.Terrain
import com.openobd.core.trip.TripVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class DroneScriptTest {
    // A 12 km drive through hills near Segovia, with a ridge across the middle.
    private val pts = (0 until 300).map { i ->
        val t = i / 299.0
        TrackPoint(1_700_000_000_000L + i * 3000L, 40.90 + 0.08 * t + 0.01 * sin(t * 9), -4.20 + 0.06 * t, 60f + (i % 50), rpm = 2000f, alt = 1000f)
    }
    private val hills = Terrain { lat, lon -> 1000 + 250 * sin((lat - 40.9) * 60) * cos((lon + 4.2) * 40) + 300 * Math.exp(-((lat - 40.94) * 120).let { it * it }) }
    private val area = TripVideo.area(pts, minPad = 0.03)
    private val frame = LocalFrame(area)
    private val ground = Ground(frame, hills)
    private val video = TripVideo(pts, seconds = 30.0, fps = 30)
    private val script = DroneScript(video, frame, ground, 9.0 / 16)

    @Test
    fun `the drone stays above the hills and keeps the car in sight`() {
        for (f in 0 until video.frames) {
            val c = script.camera(f)
            assertTrue("frame $f: ${c.eyeZ} over ${ground.at(c.eyeX, c.eyeY)}", c.eyeZ > ground.at(c.eyeX, c.eyeY) + 30)
            if (c.shot.close > 0.999) {
                // Nothing between the drone and the car (a few metres of slack for the smoothing).
                for (k in 1..19) {
                    val u = k / 20.0
                    val x = c.carX + (c.eyeX - c.carX) * u; val y = c.carY + (c.eyeY - c.carY) * u
                    val line = c.carZ + (c.eyeZ - c.carZ) * u
                    assertTrue("frame $f: hill in the way at $u", ground.at(x, y) < line + 6)
                }
                // Behind the car, a few hundred metres away.
                val d = sqrt((c.eyeX - c.carX).let { it * it } + (c.eyeY - c.carY).let { it * it })
                assertTrue("frame $f: $d m", d in 120.0..600.0)
            }
        }
    }

    @Test
    fun `the flight is smooth`() {
        for (f in 1 until video.frames) {
            val a = script.camera(f - 1); val b = script.camera(f)
            // The direction of view turns by at most a few degrees a frame.
            fun yaw(c: DroneScript.Camera) = DroneScript.headingOf(c.targetX - c.eyeX, c.targetY - c.eyeY)
            var turn = abs(yaw(b) - yaw(a)); if (turn > 180) turn = 360 - turn
            assertTrue("frame $f turns $turn°", turn < 4)
            // Height changes gently: a zoom from high up (in proportion), a few metres a frame over hills.
            assertTrue("frame $f climbs ${b.eyeZ - a.eyeZ} m", abs(b.eyeZ - a.eyeZ) < 0.12 * b.distance + 8)
        }
    }

    @Test
    fun `the opening shows the whole route`() {
        val c = script.camera(0)
        assertEquals(0.0, c.shot.close, 1e-9)
        // Every point of the route within the field of view (a simple pinhole camera).
        val fx = Math.toDegrees(2 * atan(tan(Math.toRadians(c.fovY / 2)) * 9.0 / 16))
        val fwd = norm(doubleArrayOf(c.targetX - c.eyeX, c.targetY - c.eyeY, c.targetZ - c.eyeZ))
        val right = norm(cross(fwd, doubleArrayOf(0.0, 0.0, 1.0)))
        val up = cross(right, fwd)
        for (i in pts.indices step 10) {
            val x = script.routeX[i]; val y = script.routeY[i]
            val v = doubleArrayOf(x - c.eyeX, y - c.eyeY, ground.at(x, y) - c.eyeZ)
            val z = dot(v, fwd)
            assertTrue(z > 0)
            assertTrue("point $i sideways", Math.toDegrees(atan(abs(dot(v, right)) / z)) < fx / 2)
            assertTrue("point $i up/down", Math.toDegrees(atan(abs(dot(v, up)) / z)) < c.fovY / 2)
        }
    }

    @Test
    fun `sharp windows follow the car and the route lies on the ground`() {
        assertTrue(script.windows.size >= 6)
        val mid = video.frames / 2
        val c = script.camera(mid)
        val w = script.windowsAt(mid).first()
        assertTrue("car in its window", c.carX in w.x0..w.x1 && c.carY in w.y0..w.y1)
        assertTrue(script.windowsAt(0).isEmpty())
        val route = script.drapedRoute()
        assertTrue(route.size > 500)
        for (p in route.indices step 50) assertEquals(ground.exact(route[p][0], route[p][1]), route[p][2], 1e-6)
        assertEquals(script.totalMetres, route.last()[3], 1.0)
        // Heights are measured from the lowest point and exaggerated a little.
        assertTrue(ground.base in 400.0..1000.0)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun norm(a: DoubleArray) = sqrt(dot(a, a)).let { l -> doubleArrayOf(a[0] / l, a[1] / l, a[2] / l) }
}
