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
import android.graphics.Typeface
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
    private val profile = RouteGeometry.profile(pts)
    private val u = min(width, height) / 1080f

    private val overview: Float = min(width * 0.84f / max(routeBox.width(), 1f), height * 0.5f / max(routeBox.height(), 1f))
    // Close up: about 1.2 km across the screen, and always closer than the overview.
    private val closeUp: Float = max(overview * 1.8f, (width / (1200.0 / metresPerMapPx)).toFloat())

    private val full = Path().apply { moveTo(xs[0], ys[0]); for (i in 1 until xs.size) lineTo(xs[i], ys[i]) }
    private val bands = IntArray(pts.size) { RouteGeometry.band(pts[it].speedKmh) }
    private val bandColors = RouteGeometry.bandColors.map { Color.parseColor(it) }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val bold = Paint(text).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }
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

        hud(c, s)
    }

    private fun hud(c: Canvas, s: TripVideo.Shot) {
        // Shade at the top and bottom so the text reads over any map.
        fill.shader = LinearGradient(0f, 0f, 0f, height * 0.22f, Color.argb(170, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height * 0.22f, fill)
        fill.shader = LinearGradient(0f, height * 0.62f, 0f, height.toFloat(), Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, height * 0.62f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null
        val pad = 56 * u
        // Title, fading out after the opening.
        val ta = (s.title * 255).toInt().coerceIn(0, 255)
        if (ta > 0) {
            bold.textSize = 64 * u; bold.alpha = ta; c.drawText(title, pad, pad + 64 * u, bold)
            text.textSize = 34 * u; text.alpha = ta; c.drawText(subtitle, pad, pad + 112 * u, text)
            bold.alpha = 255; text.alpha = 255
        }
        // The car's values at this point.
        val p = video.pointAt(s)
        val base = height - pad - (if (profile != null) 150 * u else 0f)
        val speed = p.speedKmh?.let { units.speed(it.toDouble()) } ?: "—"
        val parts = speed.split(' ')
        bold.textSize = 150 * u; c.drawText(parts[0], pad, base - 70 * u, bold)
        text.textSize = 40 * u; c.drawText(parts.drop(1).joinToString(" "), pad + bold.measureText(parts[0]) + 16 * u, base - 70 * u, text)
        val small = listOfNotNull(
            p.rpm?.let { "%.0f rpm".format(it) },
            p.alt?.let { units.height(it.toDouble()) },
            units.distance(video.kmAt(s)) + " / " + units.distance(video.totalKm),
            fmt((p.t - pts.first().t) / 1000),
        ).joinToString("   ·   ")
        text.textSize = 36 * u; c.drawText(small, pad, base - 10 * u, text)
        profile?.let { pr -> drawProfile(c, pr, s, RectF(pad, height - pad - 120 * u, width - pad, height - pad)) }
        text.textSize = 22 * u; text.alpha = 170
        text.textAlign = Paint.Align.RIGHT
        c.drawText("OpenOBD", width - pad, pad + 24 * u, text)
        if (credit.isNotBlank()) c.drawText(credit, width - pad, height - 18 * u, text.apply { textSize = 16 * u })
        text.textAlign = Paint.Align.LEFT; text.alpha = 255
    }

    private fun drawProfile(c: Canvas, pr: RouteGeometry.Profile, s: TripVideo.Shot, r: RectF) {
        val tot = max(pr.km.last(), 1e-6); val lo = pr.min; val span = max(pr.max - lo, 10.0)
        fun x(km: Double) = (r.left + r.width() * km / tot).toFloat()
        fun y(e: Double) = (r.bottom - r.height() * (e - lo) / span).toFloat()
        val area = Path().apply { moveTo(r.left, r.bottom); for (i in pr.km.indices) lineTo(x(pr.km[i]), y(pr.elevation[i])); lineTo(r.right, r.bottom); close() }
        fill.color = Color.argb(70, 255, 255, 255); c.drawPath(area, fill)
        val doneKm = video.kmAt(s) * tot / max(video.totalKm, 1e-6)
        c.save(); c.clipRect(r.left, r.top - 10 * u, x(doneKm), r.bottom)
        fill.color = Color.argb(150, 255, 196, 61); c.drawPath(area, fill)
        c.restore()
        fill.color = Color.WHITE; c.drawCircle(x(doneKm), y(pr.elevation[min(s.index, pr.elevation.size - 1)]), 9 * u, fill)
    }

    private fun fmt(s: Long) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
