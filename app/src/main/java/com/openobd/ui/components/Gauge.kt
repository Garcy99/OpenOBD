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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.drawText
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
    accent: List<Color> = listOf(Cyan, Blue, Violet),
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(300), label = "arc")
    val track = ink.copy(alpha = 0.12f)
    val tick = ink.copy(alpha = 0.7f)
    val inRed = redFrom != null && fraction != null && fraction >= redFrom
    val colors = if (highlight || inRed) listOf(Warn, Bad) else accent
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
                color = if (highlight || inRed) Bad else ink)
            Text(unit, style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.7f))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = ink)
            if (minText != null && maxText != null) {
                Text("$minText – $maxText", style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = ink.copy(alpha = 0.7f))
            }
        }
    }
}

/**
 * Classic round dial: a 240° scale with numbers, a red zone, and a needle. [scale] are the labels at the five
 * major ticks (min to max).
 */
@Composable
fun NeedleGauge(
    label: String,
    valueText: String,
    unit: String,
    fraction: Float?,
    scale: List<String>,
    modifier: Modifier = Modifier,
    redFrom: Float? = null,
    warn: Boolean = false,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    needle: Color = Bad,
    accent: Color = ink,
) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(250), label = "needle")
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val scaleStyle = MaterialTheme.typography.labelSmall.copy(color = ink.copy(alpha = 0.85f), fontFeatureSettings = "tnum")
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2
            val start = 150f; val sweep = 240f
            // Rim
            drawCircle(ink.copy(alpha = 0.18f), r, c, style = Stroke(r * 0.03f))
            // Red zone
            redFrom?.let { rf ->
                val rr = r * 0.88f
                drawArc(Bad.copy(alpha = 0.8f), start + sweep * rf, sweep * (1 - rf), false, Offset(c.x - rr, c.y - rr), Size(rr * 2, rr * 2), style = Stroke(r * 0.07f))
            }
            // Ticks: 4 major sections with minor ticks between
            for (i in 0..40) {
                val major = i % 10 == 0
                val a = Math.toRadians((start + sweep * i / 40f).toDouble())
                val r1 = r * 0.92f
                val r2 = r * (if (major) 0.78f else if (i % 5 == 0) 0.83f else 0.87f)
                drawLine(ink.copy(alpha = if (major) 0.9f else 0.5f), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()),
                    Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), strokeWidth = if (major) r * 0.025f else r * 0.012f)
            }
            // Scale numbers at the major ticks
            scale.take(5).forEachIndexed { i, text ->
                val a = Math.toRadians((start + sweep * i / 4f).toDouble())
                val rt = r * 0.62f
                val layout = measurer.measure(text, scaleStyle)
                drawText(layout, topLeft = Offset(c.x + rt * cos(a).toFloat() - layout.size.width / 2f, c.y + rt * sin(a).toFloat() - layout.size.height / 2f))
            }
            // Needle
            if (fraction != null) {
                val a = Math.toRadians((start + sweep * animated).toDouble())
                val tip = Offset(c.x + r * 0.86f * cos(a).toFloat(), c.y + r * 0.86f * sin(a).toFloat())
                val tail = Offset(c.x - r * 0.15f * cos(a).toFloat(), c.y - r * 0.15f * sin(a).toFloat())
                drawLine(if (warn) Bad else needle, tail, tip, strokeWidth = r * 0.04f, cap = StrokeCap.Round)
            }
            drawCircle(accent, r * 0.07f, c)
            drawCircle(ink.copy(alpha = 0.3f), r * 0.07f, c, style = Stroke(r * 0.015f))
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(valueText, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, color = if (warn) Bad else ink,
                style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
            Text("$label · $unit", style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Big number over a horizontal bar: readable at a glance, with the range underneath. */
@Composable
fun BarGauge(
    label: String,
    valueText: String,
    unit: String,
    fraction: Float?,
    modifier: Modifier = Modifier,
    redFrom: Float? = null,
    warn: Boolean = false,
    minText: String? = null,
    maxText: String? = null,
    accent: List<Color> = listOf(Cyan, Blue, Violet),
    ink: Color = MaterialTheme.colorScheme.onSurface,
) {
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(250), label = "bar")
    val inRed = warn || (redFrom != null && fraction != null && fraction >= redFrom)
    Column(modifier.aspectRatio(1f).padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.8f), maxLines = 2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valueText, style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (inRed) Bad else ink)
            Text(" $unit", style = MaterialTheme.typography.titleSmall, color = ink.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 6.dp))
        }
        Column {
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val h = size.height
                drawRoundRect(ink.copy(alpha = 0.12f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                redFrom?.let { rf -> drawRect(Bad.copy(alpha = 0.35f), topLeft = Offset(size.width * rf, 0f), size = Size(size.width * (1 - rf), h)) }
                if (fraction != null && animated > 0f) {
                    val colors = if (inRed) listOf(Warn, Bad) else if (accent.size == 1) accent + accent else accent
                    drawRoundRect(Brush.horizontalGradient(colors, 0f, size.width), size = Size(size.width * animated, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
                }
            }
            if (minText != null || maxText != null) Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(minText.orEmpty(), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.6f), modifier = Modifier.weight(1f))
                Text(maxText.orEmpty(), style = MaterialTheme.typography.labelSmall, color = ink.copy(alpha = 0.6f))
            }
        }
    }
}

/** Large number tile: easier to read at a glance than a dial. */
@Composable
fun DigitalTile(label: String, valueText: String, unit: String, minText: String?, maxText: String?, warn: Boolean = false,
                ink: Color = MaterialTheme.colorScheme.onSurface, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().aspectRatio(1f).padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = 0.75f), maxLines = 2)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(valueText, style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold, maxLines = 1,
                color = if (warn) Bad else ink)
            Text(" $unit", style = MaterialTheme.typography.titleMedium, color = ink.copy(alpha = 0.7f), modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(listOfNotNull(minText?.let { "↓ $it" }, maxText?.let { "↑ $it" }).joinToString("   "), style = MaterialTheme.typography.labelMedium, color = ink.copy(alpha = 0.7f))
    }
}
