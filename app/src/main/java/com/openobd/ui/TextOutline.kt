package com.openobd.ui

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.Typeface

/**
 * Outlines of [text] in a bold system font, as closed polygons with y pointing up (units: font pixels), for
 * embossing titles on 3D models. Any language the system font covers works.
 */
fun textOutlines(text: String, sizePx: Float = 120f): List<List<Pair<Double, Double>>> {
    if (text.isBlank()) return emptyList()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    val path = Path()
    paint.getTextPath(text, 0, text.length, 0f, 0f, path)
    val out = mutableListOf<List<Pair<Double, Double>>>()
    val measure = PathMeasure(path, false)
    val pos = FloatArray(2)
    do {
        val len = measure.length
        if (len <= 0f) continue
        // About one point per pixel: smooth curves at the sizes text is printed.
        val steps = (len / (sizePx / 120f)).toInt().coerceIn(8, 4000)
        val contour = (0 until steps).map { i ->
            measure.getPosTan(len * i / steps, pos, null)
            pos[0].toDouble() to -pos[1].toDouble()
        }
        out += contour
    } while (measure.nextContour())
    return out
}
