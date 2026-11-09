package com.openobd.core.trip

import com.openobd.core.garage.TrackPoint
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The script of a trip's video: where the camera is on every frame. It opens on the whole route, flies down to the
 * start, follows the car along the route (turned so the road ahead points up, tilted like a flight over the
 * relief) and rises again to the whole route at the end. Pure maths, so it can be tested without a device.
 */
class TripVideo(val points: List<TrackPoint>, val seconds: Double = 30.0, val fps: Int = 30) {
    /** Where the camera is on one frame. */
    data class Shot(
        /** Position of the car: point index and fraction to the next point. */
        val index: Int, val frac: Double,
        val lat: Double, val lon: Double,
        /** 0 = the whole route in view, 1 = following the car close up. */
        val close: Double,
        /** Direction of travel, degrees clockwise from north (blended to 0 when far). */
        val heading: Double,
        /** 0..1 opacity of the title. */
        val title: Double,
        /** Share of the route already driven (0..1). */
        val done: Double,
    )

    val frames: Int = max(2, (seconds * fps).toInt())
    private val n = points.size
    /** Distance from the start of each point, in metres. */
    private val along = DoubleArray(n)
    private val headings: DoubleArray

    init {
        require(n >= 2) { "A video needs a route" }
        val m = RouteGeometry.metres(points)
        for (i in 1 until n) along[i] = along[i - 1] + dist(m[i], m[i - 1])
        // Heading at each point, from the route about 150 m before and after (so curves do not shake the camera).
        headings = DoubleArray(n) { i ->
            val a = indexAt(max(0.0, along[i] - 150)); val b = indexAt(min(along.last(), along[i] + 150))
            val dx = m[b].first - m[a].first; val dy = m[b].second - m[a].second
            if (dx == 0.0 && dy == 0.0) 0.0 else (Math.toDegrees(atan2(dx, dy)) + 360) % 360
        }
    }

    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = sqrt((a.first - b.first).let { it * it } + (a.second - b.second).let { it * it })

    private fun indexAt(metres: Double): Int {
        var lo = 0; var hi = n - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (along[mid] <= metres) lo = mid else hi = mid - 1 }
        return lo
    }

    /** Smooth 0..1 ramp. */
    private fun ease(t: Double) = t.coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }

    /** Average of two headings in degrees, taking the short way round. */
    private fun mixAngle(a: Double, b: Double, t: Double): Double {
        var d = (b - a) % 360; if (d > 180) d -= 360; if (d < -180) d += 360
        return (a + d * t + 360) % 360
    }

    fun shot(frame: Int): Shot {
        val t = frame.toDouble() / (frames - 1) * seconds
        // Opening on the whole route, flight down, the drive, rising to the whole route.
        val intro = min(3.0, seconds * 0.12); val down = min(2.0, seconds * 0.08); val outro = min(3.5, seconds * 0.14)
        val driveStart = intro + down; val driveEnd = seconds - outro
        val done = ease(((t - driveStart) / max(0.1, driveEnd - driveStart)).coerceIn(0.0, 1.0)).let { e ->
            // Constant speed along the route, gently easing in and out.
            0.5 * e + 0.5 * ((t - driveStart) / max(0.1, driveEnd - driveStart)).coerceIn(0.0, 1.0)
        }
        val metres = done * along.last()
        val i = min(indexAt(metres), n - 2)
        val seg = along[i + 1] - along[i]
        val frac = if (seg <= 0) 0.0 else ((metres - along[i]) / seg).coerceIn(0.0, 1.0)
        val lat = points[i].lat + (points[i + 1].lat - points[i].lat) * frac
        val lon = points[i].lon + (points[i + 1].lon - points[i].lon) * frac
        val close = when {
            t < intro -> 0.0
            t < driveStart -> ease((t - intro) / down)
            t < driveEnd -> 1.0
            else -> 1 - ease((t - driveEnd) / outro)
        }
        val h = mixAngle(headings[i], headings[i + 1], frac)
        return Shot(i, frac, lat, lon, close, mixAngle(0.0, h, close), title = 1 - ease((t - intro + 0.8) / 0.8), done = done)
    }

    /** Values shown on screen at a shot: speed, rpm, height (GPS) of the nearest point. */
    fun pointAt(s: Shot): TrackPoint = points[if (s.frac < 0.5) s.index else s.index + 1]

    /** Distance driven at a shot, in km. */
    fun kmAt(s: Shot): Double = (along[s.index] + (along[min(s.index + 1, n - 1)] - along[s.index]) * s.frac) / 1000

    val totalKm: Double get() = along.last() / 1000

    companion object {
        const val DEG = PI / 180
        val texts = listOf(
            "Video of the drive (MP4)", "The drive as a short film: a drone follows the car over the hills in 3D, with aerial photos or relief, and its speed, engine speed and height. To share or keep.",
            "Vertical (phone)", "Horizontal", "Length", "%1\$s s", "Making the video… %1\$s %%", "This phone cannot make videos.",
        )

        /**
         * The area the video's map covers (minLat, minLon, maxLat, maxLon): the route with room around it (at least
         * [minPad] degrees of latitude; more for the drone, which sees towards the horizon).
         */
        fun area(points: List<TrackPoint>, minPad: Double = 0.015): DoubleArray {
            val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
            val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
            val k = cos(Math.toRadians((minLat + maxLat) / 2))
            val pad = max(max(maxLat - minLat, (maxLon - minLon) * k) * 0.6, minPad)
            return doubleArrayOf(minLat - pad, minLon - pad / k, maxLat + pad, maxLon + pad / k)
        }
    }
}
