package com.openobd.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

val SeriesColors = listOf(
    Color(0xFF22D3EE), Color(0xFFF97316), Color(0xFFA3E635), Color(0xFFE879F9),
    Color(0xFFFACC15), Color(0xFF60A5FA),
)

class ChartSeries(val points: List<Pair<Long, Float>>, val color: Color, val min: Float? = null, val max: Float? = null)

/**
 * Time-series chart. Each series is scaled to its own range (so RPM and temperature can share a chart);
 * the x axis covers the last [windowMs] milliseconds.
 */
@Composable
fun LineChart(series: List<ChartSeries>, windowMs: Long, now: Long, modifier: Modifier = Modifier, gridLines: Int = 4) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        for (i in 0..gridLines) {
            val y = size.height * i / gridLines
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        for (i in 0..6) {
            val x = size.width * i / 6
            drawLine(grid.copy(alpha = 0.5f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        val start = now - windowMs
        for (s in series) {
            val pts = s.points.filter { it.first >= start }
            if (pts.size < 2) continue
            var lo = s.min ?: pts.minOf { it.second }
            var hi = s.max ?: pts.maxOf { it.second }
            if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
            val pad = (hi - lo) * 0.08f
            lo -= pad; hi += pad
            val path = Path()
            pts.forEachIndexed { i, (t, v) ->
                val x = ((t - start).toFloat() / windowMs) * size.width
                val y = size.height - (v - lo) / (hi - lo) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, s.color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun Sparkline(points: List<Pair<Long, Float>>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val t0 = points.first().first
        val span = (points.last().first - t0).coerceAtLeast(1)
        var lo = points.minOf { it.second }
        var hi = points.maxOf { it.second }
        if (hi - lo < 1e-3f) { lo -= 1f; hi += 1f }
        val path = Path()
        points.forEachIndexed { i, (t, v) ->
            val x = (t - t0).toFloat() / span * size.width
            val y = size.height - (v - lo) / (hi - lo) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
