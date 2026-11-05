package com.openobd.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.LocalExtras
import com.openobd.ui.theme.Warn

/** Header of a main tab: large title with an optional line under it and actions on the right. */
@Composable
fun TabHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) Text(subtitle.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Small uppercase label above a group of cards, with an optional action on the right. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}

/** Icon on a soft tinted rounded square, the visual anchor of rows and tiles. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.32f)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * 0.52f), tint = tint)
    }
}

/** Card holding a list of [NavRow]s separated by inset dividers, like a settings group. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, LocalExtras.current.hairline, shape)
            .padding(vertical = 4.dp),
        content = content,
    )
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 70.dp, end = 16.dp), color = LocalExtras.current.hairline)
}

/** A tappable row: icon badge, title, optional subtitle, optional trailing content and a chevron. */
@Composable
fun NavRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).alpha(if (enabled) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}

/** Square-ish shortcut tile for action grids. */
@Composable
fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary, badge: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainer).border(1.dp, LocalExtras.current.hairline, shape)
            .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            IconBadge(icon, tint, 44.dp)
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2,
                overflow = TextOverflow.Ellipsis, minLines = 2)
        }
        if (badge != null) Box(
            Modifier.align(Alignment.TopEnd).clip(RoundedCornerShape(50)).background(Bad).padding(horizontal = 6.dp, vertical = 1.dp),
        ) { Text(badge, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
    }
}

/** Grid of [ActionTile]s with a fixed number of columns. */
@Composable
fun ActionGrid(columns: Int, items: List<@Composable (Modifier) -> Unit>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The signature card: soft brand gradient, a faint glow and a hairline border. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, contentPadding: Dp = 20.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val ex = LocalExtras.current
    val glow = ex.glow
    Box(modifier.fillMaxWidth().clip(shape).background(ex.brandSoft).border(1.dp, ex.hairline, shape)) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = if (ex.isDark) 0.22f else 0.18f), Color.Transparent),
                center = Offset(size.width * 0.92f, size.height * 0.05f), radius = size.width * 0.7f), radius = size.width * 0.7f,
                center = Offset(size.width * 0.92f, size.height * 0.05f))
        }
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Main call to action: a tall pill filled with the brand gradient. */
@Composable
fun GradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 54.dp).clip(shape)
            .background(if (enabled) LocalExtras.current.brand else Brush.linearGradient(listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surfaceVariant)))
            .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        val color = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
        FitText(text, color, MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
    }
}

/**
 * Button label that never breaks a word in the middle: when the longest word is wider than the room
 * (long German, French or Russian words on a narrow phone), the text is scaled down a little instead.
 */
@Composable
private fun FitText(text: String, color: Color, style: androidx.compose.ui.text.TextStyle) {
    androidx.compose.foundation.layout.BoxWithConstraints(contentAlignment = Alignment.Center) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxW = constraints.maxWidth
        val scale = remember(text, maxW, style) {
            // Scripts without spaces between words (Chinese) wrap anywhere, so they never need it.
            if (maxW == androidx.compose.ui.unit.Constraints.Infinity || text.any { it in '\u2E80'..'\u9FFF' || it in '\uF900'..'\uFAFF' }) 1f
            else {
                val widest = text.split(' ').maxOf { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width }
                if (widest <= maxW) 1f else (maxW.toFloat() / widest).coerceAtLeast(0.72f)
            }
        }
        val fitted = if (scale >= 1f) style else style.copy(
            fontSize = style.fontSize * scale,
            lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * scale else style.lineHeight,
        )
        Text(text, color = color, style = fitted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
    }
}

/** Secondary pill button on a tonal surface. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, color: Color = MaterialTheme.colorScheme.onSurface) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier.heightIn(min = 54.dp).clip(shape)
            .background(if (LocalExtras.current.isDark) MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.9f))
            .clickable(enabled = enabled, onClick = onClick).semantics { role = Role.Button }.padding(horizontal = 18.dp).alpha(if (enabled) 1f else 0.5f),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        FitText(text, color, MaterialTheme.typography.labelLarge)
    }
}

/** Small status label with a coloured dot. */
@Composable
fun StatusChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** A value with its label, for rows of key figures. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, unit: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.width(5.dp)) }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
            if (unit != null) { Spacer(Modifier.width(3.dp)); Text(unit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp)) }
        }
    }
}

fun scoreColor(score: Int): Color = when { score >= 80 -> Good; score >= 55 -> Warn; else -> Bad }

/** Circular score (0-100) with an animated arc. */
@Composable
fun ScoreRing(score: Int?, modifier: Modifier = Modifier, size: Dp = 108.dp, stroke: Dp = 10.dp, caption: String? = null) {
    val target = (score ?: 0) / 100f
    val sweep by animateFloatAsState(target, tween(900), label = "score")
    val color by animateColorAsState(score?.let(::scoreColor) ?: MaterialTheme.colorScheme.outline, tween(600), label = "scoreColor")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arc = Size(this.size.width - s, this.size.height - s)
            drawArc(track, -90f, 360f, false, Offset(s / 2, s / 2), arc, style = Stroke(s))
            drawArc(Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))), -90f, 360f * sweep, false,
                Offset(s / 2, s / 2), arc, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score?.toString() ?: "–", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Horizontally scrollable segmented pills for switching between views of one tab. */
@Composable
fun PillTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val bg by animateColorAsState(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceContainerHigh, label = "pill")
            val fg by animateColorAsState(if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant, label = "pillText")
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(bg).clickable { onSelect(i) }
                    .semantics { this.selected = on; role = Role.Tab }.padding(horizontal = 16.dp, vertical = 9.dp),
            ) { Text(label, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1) }
        }
    }
}

/** Notice in the home feed: tinted icon, text, optional action; whole card tappable. */
@Composable
fun NoticeCard(
    icon: ImageVector,
    title: String,
    text: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    // Last, so a trailing lambda at the call site is the click handler and never composable content.
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(tint.copy(alpha = 0.08f)).border(1.dp, tint.copy(alpha = 0.22f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, tint, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke() ?: if (onClick != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) else Unit
    }
}

/** Content area of a scrolling screen with the app's standard margins and spacing. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        content()
        Spacer(Modifier.height(24.dp))
    }
}

