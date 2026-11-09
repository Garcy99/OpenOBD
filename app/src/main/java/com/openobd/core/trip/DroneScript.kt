package com.openobd.core.trip

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Flat coordinates around an area (minLat, minLon, maxLat, maxLon): x east and y north, in metres from its centre. */
class LocalFrame(val area: DoubleArray) {
    val lat0 = (area[0] + area[2]) / 2
    val lon0 = (area[1] + area[3]) / 2
    private val kx = 111_320.0 * cos(Math.toRadians(lat0))
    private val ky = 110_540.0
    fun x(lon: Double) = (lon - lon0) * kx
    fun y(lat: Double) = (lat - lat0) * ky
    fun lat(y: Double) = lat0 + y / ky
    fun lon(x: Double) = lon0 + x / kx
    val minX = x(area[1]); val maxX = x(area[3]); val minY = y(area[0]); val maxY = y(area[2])
    /** A part of the area as minLat, minLon, maxLat, maxLon. */
    fun box(x0: Double, y0: Double, x1: Double, y1: Double) = doubleArrayOf(lat(y0), lon(x0), lat(y1), lon(x1))
}

/**
 * The ground under the video in metres above its lowest point, from the elevation model (flat without one). Heights
 * are exaggerated a little ([exaggeration]) so hills read on screen as they feel from the car.
 */
class Ground(val frame: LocalFrame, private val terrain: Terrain?, val exaggeration: Double = 1.25, size: Int = 384) {
    private val n = size
    private val grid = FloatArray(n * n)
    /** Elevation (m) of the lowest point: heights are measured from here. */
    val base: Double

    init {
        val raw = DoubleArray(n * n) { Double.NaN }
        if (terrain != null) for (j in 0 until n) for (i in 0 until n) {
            val x = frame.minX + (frame.maxX - frame.minX) * i / (n - 1)
            val y = frame.minY + (frame.maxY - frame.minY) * j / (n - 1)
            terrain.elevation(frame.lat(y), frame.lon(x))?.takeIf { it.isFinite() && it > -500 && it < 9000 }?.let { raw[j * n + i] = it }
        }
        val known = raw.filter { !it.isNaN() }
        base = known.minOrNull() ?: 0.0
        val fill = if (known.isEmpty()) 0.0 else known.average()
        for (k in raw.indices) grid[k] = (((if (raw[k].isNaN()) fill else raw[k]) - base) * exaggeration).toFloat()
    }

    /** Height (m, exaggerated) from the sampled grid, smooth between samples. */
    fun at(x: Double, y: Double): Double {
        val fx = ((x - frame.minX) / (frame.maxX - frame.minX) * (n - 1)).coerceIn(0.0, n - 1.0)
        val fy = ((y - frame.minY) / (frame.maxY - frame.minY) * (n - 1)).coerceIn(0.0, n - 1.0)
        val i = min(fx.toInt(), n - 2); val j = min(fy.toInt(), n - 2)
        val u = fx - i; val v = fy - j
        val a = grid[j * n + i]; val b = grid[j * n + i + 1]; val c = grid[(j + 1) * n + i]; val d = grid[(j + 1) * n + i + 1]
        return (a * (1 - u) + b * u) * (1 - v) + (c * (1 - u) + d * u) * v
    }

    /** Height (m, exaggerated) from the elevation model itself where it has a value (finer than the grid). */
    fun exact(x: Double, y: Double): Double =
        terrain?.elevation(frame.lat(y), frame.lon(x))?.takeIf { it.isFinite() && it > -500 && it < 9000 }?.let { (it - base) * exaggeration } ?: at(x, y)
}

/**
 * A drone's flight over a trip: it opens high over the whole route, slowly turning, comes down behind the car,
 * follows it at a few hundred metres (turning with the road, rising over hills that would hide it) and climbs away
 * at the end. Positions are x east, y north, z up in metres ([LocalFrame], [Ground]). Pure maths, so it is tested
 * without a device.
 */
class DroneScript(val video: TripVideo, val frame: LocalFrame, val ground: Ground, aspect: Double) {
    data class Camera(
        val eyeX: Double, val eyeY: Double, val eyeZ: Double,
        val targetX: Double, val targetY: Double, val targetZ: Double,
        /** The car. */
        val carX: Double, val carY: Double, val carZ: Double,
        /** Distance from the eye to the target (m): sizes on screen scale with it. */
        val distance: Double,
        val fovY: Double, val near: Double, val far: Double,
        /** Haze: none closer than [fogStart], full at [fogEnd] (m). */
        val fogStart: Double, val fogEnd: Double,
        /** Metres of the route driven. */
        val doneMetres: Double,
        val shot: TripVideo.Shot,
    )

    /** A square of detailed ground (photo or relief) along the route, centred [along] metres from the start. */
    data class Window(val index: Int, val along: Double, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    val routeX = DoubleArray(video.points.size) { frame.x(video.points[it].lon) }
    val routeY = DoubleArray(video.points.size) { frame.y(video.points[it].lat) }
    /** Metres from the start of each route point. */
    val along = DoubleArray(routeX.size).also { a -> for (i in 1 until a.size) a[i] = a[i - 1] + hypot(routeX[i] - routeX[i - 1], routeY[i] - routeY[i - 1]) }
    val totalMetres = along.last()

    /** Field of view: wider up and down on a phone held upright. */
    val fovY = if (aspect < 1) 62.0 else 44.0
    private val fovX = Math.toDegrees(2 * kotlin.math.atan(tan(Math.toRadians(fovY / 2)) * aspect))

    /** Spacing of the detail windows along the route and their side (m): at most about 30 of them. */
    val windowStep = max(2000.0, totalMetres / 30)
    val windowSide = windowStep * 3
    val windows: List<Window> = (0..ceil(totalMetres / windowStep).toInt()).map { k ->
        val m = min(k * windowStep, totalMetres)
        val (x, y) = pointAt(m)
        val h = windowSide / 2
        Window(k, m, x - h, y - h, x + h, y + h)
    }

    private val cameras: List<Camera>

    init {
        val cx = (routeX.min() + routeX.max()) / 2; val cy = (routeY.min() + routeY.max()) / 2
        val halfW = max((routeX.max() - routeX.min()) / 2, 300.0); val halfH = max((routeY.max() - routeY.min()) / 2, 300.0)
        val radius = sqrt(halfW * halfW + halfH * halfH)
        // Far enough for the whole route to fit the narrower side of the picture, looking down steeply.
        val overview = radius / tan(Math.toRadians(min(fovX, fovY) / 2)) * 1.15
        val chase = 270.0
        val n = video.frames
        val yaw = DoubleArray(n); val dist = DoubleArray(n); val pitch = DoubleArray(n)
        val tx = DoubleArray(n); val ty = DoubleArray(n); val tz = DoubleArray(n)
        val shots = List(n) { video.shot(it) }
        var smoothYaw = Double.NaN
        for (f in 0 until n) {
            val s = shots[f]
            val c = s.close
            val carX = frame.x(s.lon); val carY = frame.y(s.lat)
            // While high up, a slow turn around the route: from 30° one way at the start to 30° the other at the end.
            val orbit = (1 - c) * 30 * (2.0 * f / max(1, n - 1) - 1)
            val wanted = (s.heading + orbit + 360) % 360
            smoothYaw = if (smoothYaw.isNaN()) wanted else mixAngle(smoothYaw, wanted, 0.07)
            yaw[f] = smoothYaw
            // Close up the drone looks a little ahead of the car, so the road it is about to take is on screen.
            val ahead = 80.0 * c
            val r = Math.toRadians(yaw[f])
            val cxNow = cx + (carX - cx) * c; val cyNow = cy + (carY - cy) * c
            tx[f] = cxNow + sin(r) * ahead; ty[f] = cyNow + cos(r) * ahead
            tz[f] = ground.at(tx[f], ty[f])
            dist[f] = exp(ln(overview) + (ln(chase) - ln(overview)) * c)
            pitch[f] = 62.0 + (22.0 - 62.0) * c
        }
        // Over hills: the drone rises in time (it sees them coming) and comes down gently after.
        val lift = DoubleArray(n)
        val eyes = Array(n) { f ->
            val r = Math.toRadians(yaw[f]); val p = Math.toRadians(pitch[f])
            doubleArrayOf(tx[f] - sin(r) * cos(p) * dist[f], ty[f] - cos(r) * cos(p) * dist[f], tz[f] + sin(p) * dist[f])
        }
        for (f in 0 until n) {
            val s = shots[f]
            val carX = frame.x(s.lon); val carY = frame.y(s.lat); val carZ = ground.at(carX, carY)
            val e = eyes[f]
            var need = ground.at(e[0], e[1]) + 40 + 0.08 * dist[f]
            // Nothing between the drone and the car.
            if (s.close > 0.3) for (k in 1..9) {
                val u = k / 10.0
                val g = ground.at(carX + (e[0] - carX) * u, carY + (e[1] - carY) * u) + 12
                need = max(need, carZ + (g - carZ) / u)
            }
            lift[f] = max(0.0, need - e[2])
        }
        val step = 4.0
        for (f in n - 2 downTo 0) lift[f] = max(lift[f], lift[f + 1] - step)
        for (f in 1 until n) lift[f] = max(lift[f], lift[f - 1] - step)
        cameras = List(n) { f ->
            val s = shots[f]
            val carX = frame.x(s.lon); val carY = frame.y(s.lat)
            val e = eyes[f]
            val d = dist[f]
            val diag = hypot(frame.maxX - frame.minX, frame.maxY - frame.minY)
            Camera(e[0], e[1], e[2] + lift[f], tx[f], ty[f], tz[f], carX, carY, ground.exact(carX, carY), d,
                fovY, near = max(2.0, d * 0.04), far = max(diag * 2, d * 30),
                fogStart = d * 4.5, fogEnd = max(d * 22, diag * (1 - s.close) * 1.4), doneMetres = s.done * totalMetres, shot = s)
        }
    }

    fun camera(frame: Int): Camera = cameras[frame.coerceIn(0, cameras.size - 1)]

    /** The detail windows to draw at a frame, most important first (the one the car is in, then the next). */
    fun windowsAt(frame: Int): List<Window> {
        val c = camera(frame)
        if (c.shot.close < 0.05 || windows.isEmpty()) return emptyList()
        val k = (c.doneMetres / windowStep).let { kotlin.math.round(it).toInt() }.coerceIn(0, windows.size - 1)
        return listOfNotNull(windows[k], windows.getOrNull(k + 1), windows.getOrNull(k - 1))
    }

    /** Point [m] metres along the route. */
    fun pointAt(m: Double): Pair<Double, Double> {
        var lo = 0; var hi = along.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (along[mid] <= m) lo = mid else hi = mid - 1 }
        val i = min(lo, along.size - 2)
        val seg = along[i + 1] - along[i]
        val t = if (seg <= 0) 0.0 else ((m - along[i]) / seg).coerceIn(0.0, 1.0)
        return (routeX[i] + (routeX[i + 1] - routeX[i]) * t) to (routeY[i] + (routeY[i + 1] - routeY[i]) * t)
    }

    /**
     * The route draped on the ground every few metres: x, y, z (exaggerated height), metres along and the speed band
     * of each point. Long routes get fewer points (at most about [maxPoints]).
     */
    fun drapedRoute(maxPoints: Int = 60_000): List<DoubleArray> {
        val stepM = max(15.0, totalMetres / maxPoints)
        val out = ArrayList<DoubleArray>()
        for (i in 0 until routeX.size - 1) {
            val seg = along[i + 1] - along[i]
            val parts = max(1, ceil(seg / stepM).toInt())
            val band = RouteGeometry.band(video.points[i].speedKmh).toDouble()
            for (p in 0 until parts) {
                val t = p.toDouble() / parts
                val x = routeX[i] + (routeX[i + 1] - routeX[i]) * t; val y = routeY[i] + (routeY[i + 1] - routeY[i]) * t
                if (out.isNotEmpty() && hypot(x - out.last()[0], y - out.last()[1]) < 0.5) continue
                out += doubleArrayOf(x, y, ground.exact(x, y), along[i] + seg * t, band)
            }
        }
        val l = routeX.size - 1
        out += doubleArrayOf(routeX[l], routeY[l], ground.exact(routeX[l], routeY[l]), along[l], RouteGeometry.band(video.points[l].speedKmh).toDouble())
        return out
    }

    companion object {
        fun hypot(a: Double, b: Double) = sqrt(a * a + b * b)

        /** From heading [a] towards [b] by [t], the short way round (degrees). */
        fun mixAngle(a: Double, b: Double, t: Double): Double {
            var d = (b - a) % 360; if (d > 180) d -= 360; if (d < -180) d += 360
            return (a + d * t + 360) % 360
        }

        /** Heading (degrees clockwise from north) of a direction. */
        fun headingOf(dx: Double, dy: Double) = (Math.toDegrees(atan2(dx, dy)) + 360) % 360

        const val RAD = PI / 180
    }
}
