package com.openobd.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.openobd.core.trip.RouteGeometry
import com.openobd.core.trip.TripVideo
import com.openobd.core.trip.Units
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Draws the frames of a trip's video: the map ([ground]: relief or aerial photo of [area], minLat, minLon, maxLat,
 * maxLon; null = plain) seen from a camera that follows the car, the route coloured by speed, and the car's values.
 */
class TripVideoRenderer(
    private val video: TripVideo,
    private val ground: Bitmap?,
    private val area: DoubleArray,
    val width: Int,
    val height: Int,
    private val title: String,
    private val subtitle: String,
    private val units: Units,
    private val label: (String) -> String,
    private val credit: String,
) {
    private val pts = video.points
    // Map pixels: the ground image, or a virtual one of the same proportions.
    private val k = cos(Math.toRadians((area[0] + area[2]) / 2))
    private val mapW: Float = ground?.width?.toFloat() ?: 2000f
    private val mapH: Float = ground?.height?.toFloat() ?: (2000f * ((area[2] - area[0]) / ((area[3] - area[1]) * k))).toFloat()
    private fun mx(lon: Double) = ((lon - area[1]) / (area[3] - area[1]) * mapW).toFloat()
    private fun my(lat: Double) = ((area[2] - lat) / (area[2] - area[0]) * mapH).toFloat()
    private val metresPerMapPx = (area[3] - area[1]) * 111_320.0 * k / mapW

    private val xs = FloatArray(pts.size) { mx(pts[it].lon) }
    private val ys = FloatArray(pts.size) { my(pts[it].lat) }
    private val routeBox = RectF(xs.min(), ys.min(), xs.max(), ys.max())
    private val u = min(width, height) / 1080f

    private val overview: Float = min(width * 0.84f / max(routeBox.width(), 1f), height * 0.5f / max(routeBox.height(), 1f))
    // Close up: about 1.2 km across the screen, and always closer than the overview.
    private val closeUp: Float = max(overview * 1.8f, (width / (1200.0 / metresPerMapPx)).toFloat())

    private val full = Path().apply { moveTo(xs[0], ys[0]); for (i in 1 until xs.size) lineTo(xs[i], ys[i]) }
    private val bands = IntArray(pts.size) { RouteGeometry.band(pts[it].speedKmh) }
    private val bandColors = RouteGeometry.bandColors.map { Color.parseColor(it) }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val m = Matrix()
    private val plan = Matrix()
    private val tilt = Matrix()

    fun draw(c: Canvas, frame: Int) {
        val s = video.shot(frame)
        // Haze towards the horizon, for what lies beyond the map when the camera tilts.
        fill.shader = LinearGradient(0f, 0f, 0f, height * 0.5f, Color.rgb(120, 140, 160), Color.rgb(18, 22, 28), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
        // Camera: from the whole route (north up, from above) to close behind the car (road ahead up, tilted).
        val scale = overview * (closeUp / overview).toDouble().pow(s.close).toFloat()
        val carX = mx(s.lon); val carY = my(s.lat)
        val cx = routeBox.centerX() + (carX - routeBox.centerX()) * s.close.toFloat()
        val cy = routeBox.centerY() + (carY - routeBox.centerY()) * s.close.toFloat()
        val anchorY = height * (0.45f + 0.17f * s.close.toFloat())
        // Map to screen in two steps: the plan (centre on the camera, zoom, turn) and the tilt of a camera flying
        // behind the car, so the road ahead (up) recedes into the distance.
        plan.reset()
        plan.postTranslate(-cx, -cy)
        plan.postScale(scale, scale)
        plan.postRotate(-s.heading.toFloat())
        val angle = Math.toRadians(52 * s.close)
        val d = height * 1.1f
        val sin = kotlin.math.sin(angle).toFloat()
        tilt.setValues(floatArrayOf(1f, 0f, 0f, 0f, kotlin.math.cos(angle).toFloat(), 0f, 0f, -sin / d, 1f))
        m.set(plan); m.postConcat(tilt); m.postTranslate(width / 2f, anchorY)

        c.save()
        c.translate(width / 2f, anchorY)
        c.concat(tilt)
        // Nothing from behind the camera (it would come out upside down at the top).
        if (sin > 0.01f) c.clipRect(-1e5f, -1e5f, 1e5f, 0.8f * d / sin)
        c.concat(plan)
        if (ground != null) c.drawBitmap(ground, 0f, 0f, fill)
        // The whole route faint, the part driven bright and coloured by speed, over a dark edge.
        line.strokeWidth = 14 * u / scale; line.color = Color.argb(150, 0, 0, 0); c.drawPath(full, line)
        line.strokeWidth = 9 * u / scale; line.color = Color.argb(150, 255, 255, 255); c.drawPath(full, line)
        val end = s.index
        var i = 0
        while (i < end) {
            val b = bands[i]
            val p = Path().apply { moveTo(xs[i], ys[i]) }
            var j = i + 1
            while (j <= end && bands[j - 1] == b) { p.lineTo(xs[j], ys[j]); j++ }
            if (j > end) p.lineTo(carX, carY)
            line.color = bandColors[b]; line.strokeWidth = 11 * u / scale
            c.drawPath(p, line)
            i = j - 1
            if (j > end) break
        }
        c.restore()
        // The car (not tilted).
        val car = floatArrayOf(carX, carY); m.mapPoints(car)
        fill.color = Color.argb(90, 0, 0, 0); c.drawCircle(car[0], car[1] + 4 * u, 22 * u, fill)
        fill.color = Color.WHITE; c.drawCircle(car[0], car[1], 18 * u, fill)
        fill.color = bandColors[bands[min(s.index + 1, bands.size - 1)]]; c.drawCircle(car[0], car[1], 11 * u, fill)

        hud.draw(c, s)
    }

    private val hud = TripVideoHud(video, width, height, title, subtitle, units, credit)
}
