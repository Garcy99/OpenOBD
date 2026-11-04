package com.openobd.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Blue
import com.openobd.ui.theme.Cyan
import com.openobd.ui.theme.Violet
import com.openobd.ui.theme.Warn
import kotlin.math.cos
import kotlin.math.sin

/**
 * Arc gauge with a 270° sweep: a slim track, the value arc in the brand gradient with a glowing tip, and the
 * reading in large tabular numerals. [redFrom] marks the start of the red zone as a fraction (0..1).
 */
@Composable
fun AnalogGauge(
    label: String,
    valueText: String,
    unit: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    redFrom: Float? = null,
    minText: String? = null,
    maxText: String? = null,
    highlight: Boolean = false,
) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(300), label = "arc")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tick = MaterialTheme.colorScheme.onSurfaceVariant
    val inRed = redFrom != null && fraction != null && fraction >= redFrom
    val colors = if (highlight || inRed) listOf(Warn, Bad) else listOf(Cyan, Blue, Violet)
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val stroke = size.minDimension * 0.075f
            val inset = stroke / 2 + 2f
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            val start = 135f
            val sweep = 270f
            val c = Offset(size.width / 2, size.height / 2)
            val r = arcSize.width / 2
            drawArc(track, start, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            redFrom?.let { rf ->
                drawArc(Bad.copy(alpha = 0.35f), start + sweep * rf, sweep * (1 - rf), false, topLeft, arcSize, style = Stroke(stroke * 0.35f, cap = StrokeCap.Round))
            }
            // Fine ticks inside the arc
            for (i in 0..20) {
                val a = Math.toRadians((start + sweep * i / 20f).toDouble())
                val r1 = r - stroke * 1.15f
                val r2 = r - stroke * (if (i % 5 == 0) 1.75f else 1.4f)
                drawLine(tick.copy(alpha = if (i % 5 == 0) 0.55f else 0.25f), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = if (i % 5 == 0) 3f else 2f, cap = StrokeCap.Round)
            }
            if (fraction != null && animated > 0.001f) {
                val brush = Brush.sweepGradient(
                    0f to colors.first(), (sweep / 360f * animated).coerceAtLeast(0.01f) to colors.last(), 1f to colors.last(), center = c,
                )
                rotate(start, c) {
                    drawArc(brush, 0f, sweep * animated, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                val a = Math.toRadians((start + sweep * animated).toDouble())
                val tip = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                drawCircle(Brush.radialGradient(listOf(colors.last().copy(alpha = 0.55f), Color.Transparent), tip, stroke * 1.8f), stroke * 1.8f, tip)
                drawCircle(Color.White, stroke * 0.32f, tip)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(valueText, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, letterSpacing = (-0.5).sp,
                style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                color = if (highlight || inRed) Bad else MaterialTheme.colorScheme.onSurface)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (minText != null && maxText != null) {
                Text("$minText – $maxText", style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Horizontal bar meter, used for compact displays. */
@Composable
fun BarMeter(fraction: Float?, modifier: Modifier = Modifier, warnFrom: Float? = null) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val f = (fraction ?: 0f).coerceIn(0f, 1f)
    val color = when {
        warnFrom != null && f >= warnFrom -> Warn
        else -> MaterialTheme.colorScheme.primary
    }
    Canvas(modifier) {
        val h = size.height
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        if (fraction != null) drawRoundRect(color, size = Size(size.width * f, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
    }
}

fun gaugeColor(fraction: Float): Color = when {
    fraction > 0.85f -> Bad
    fraction > 0.7f -> Warn
    else -> Color.Unspecified
}
