package com.openobd.video

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.openobd.core.trip.RouteGeometry
import com.openobd.core.trip.TripVideo
import com.openobd.core.trip.Units
import kotlin.math.max
import kotlin.math.min

/** What a trip's video shows over the picture: the title, the car's speed, engine speed, height and the height profile. */
class TripVideoHud(
    private val video: TripVideo,
    val width: Int,
    val height: Int,
    private val title: String,
    private val subtitle: String,
    private val units: Units,
    private val credit: String,
) {
    private val pts = video.points
    private val profile = RouteGeometry.profile(pts)
    private val u = min(width, height) / 1080f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL) }
    private val bold = Paint(text).apply { typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) }

    fun draw(c: Canvas, s: TripVideo.Shot) {
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
