package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.obd.SensorDef
import com.openobd.core.obd.UnitSystem
import com.openobd.session.Reading
import com.openobd.ui.components.AnalogGauge
import com.openobd.ui.components.BarGauge
import com.openobd.ui.components.DigitalTile
import com.openobd.ui.components.GaugeColors
import com.openobd.ui.components.GaugeSpec
import com.openobd.ui.components.GaugeStyle
import com.openobd.ui.components.GaugeTheme
import com.openobd.ui.components.GradientButton
import com.openobd.ui.components.NeedleGauge
import com.openobd.ui.components.displayRange
import com.openobd.ui.components.displayValue
import com.openobd.ui.components.formatNumber
import com.openobd.ui.components.formatSensor
import com.openobd.ui.tr
import java.util.Locale

private fun rgb(hex: String) = Color(0xFF000000 or hex.toLong(16))

/** Small colour dot for theme and colour pickers. */
@Composable
fun Swatch(color: Long, face: Long? = null, size: Int = 18) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(Color(face ?: 0xFF202020))
            .border(2.dp, Color(color), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.size((size / 2.6).dp).clip(CircleShape).background(Color(color))) }
}

/** Short scale numbers for a classic dial ("0", "2k", "4k" ...). */
private fun scaleLabel(v: Double, span: Double): String = when {
    span >= 2000 -> if (v == 0.0) "0" else "%.0fk".format(v / 1000)
    span >= 10 -> "%.0f".format(v)
    else -> formatNumber(v, 1)
}

/** One dashboard gauge drawn the way [spec] and the dashboard [theme] say. */
@Composable
fun GaugeView(spec: GaugeSpec, theme: GaugeTheme, def: SensorDef, reading: Reading?, units: UnitSystem, supportedHere: Boolean, shift: Boolean = false) {
    val style = spec.style ?: theme.defaultStyle
    val custom = spec.appliesTo(units)
    val (dlo, dhi) = displayRange(def, units)
    val lo = (if (custom) spec.min else null) ?: dlo
    val hi = ((if (custom) spec.max else null) ?: dhi).let { if (it <= lo) lo + 1 else it }
    val v = reading?.value
    val dv = v?.let { displayValue(def, it, units) }
    val fraction = if (def.labeler != null) null else dv?.let { ((it - lo) / (hi - lo)).toFloat() }
    val f = formatSensor(def, v, units)
    fun text(x: Double?): String? = x?.let { raw ->
        if (spec.decimals != null && def.labeler == null) formatNumber(displayValue(def, raw, units), spec.decimals) else formatSensor(def, raw, units).value
    }
    val valueText = if (!supportedHere) "N/A" else text(v) ?: f.value
    val warnAbove = if (custom) spec.warnAbove else null
    val warnBelow = if (custom) spec.warnBelow else null
    val warn = shift || (dv != null && ((warnAbove != null && dv >= warnAbove) || (warnBelow != null && dv <= warnBelow)))
    val redFrom = warnAbove?.let { ((it - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) } ?: redZone(spec.key).takeIf { !custom || (spec.min == null && spec.max == null) }
    val accent = spec.color?.let { listOf(rgb(it).copy(alpha = 0.7f), rgb(it)) } ?: theme.accent.map { Color(it) }
    val ink = theme.ink?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurface
    val minText = reading?.let { text(it.min) }
    val maxText = reading?.let { text(it.max) }
    val label = tr(def.shortName)
    // Tiles have room for two lines: long names ("Boost / vacuum (MAP - barometric)") use the short one.
    val tileLabel = tr(def.name).let { if (it.length <= 26) it else label }
    when (style) {
        GaugeStyle.ARC -> AnalogGauge(label, valueText, f.unit, fraction, Modifier.fillMaxWidth(), redFrom, minText, maxText, highlight = warn,
            accent = accent, ink = ink)
        GaugeStyle.NEEDLE -> NeedleGauge(label, valueText, f.unit, fraction, (0..4).map { scaleLabel(lo + (hi - lo) * it / 4, hi - lo) },
            Modifier.fillMaxWidth(), redFrom, warn, ink = ink, needle = spec.color?.let(::rgb) ?: Color(theme.needle), accent = accent.last())
        GaugeStyle.BAR -> BarGauge(tileLabel, valueText, f.unit, fraction, Modifier.fillMaxWidth(), redFrom, warn, minText, maxText, accent, ink)
        GaugeStyle.DIGITAL -> DigitalTile(tileLabel, valueText, f.unit, minText, maxText, warn, ink)
    }
}

/** Sensor, style, colour, range, warnings and decimals of one gauge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GaugeEditor(
    initial: GaugeSpec,
    theme: GaugeTheme,
    units: UnitSystem,
    onChangeSensor: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (GaugeSpec) -> Unit,
    onRemove: () -> Unit,
) {
    val def = SensorCatalog.get(initial.key)
    val keep = initial.appliesTo(units)
    var style by remember { mutableStateOf(initial.style) }
    var color by remember { mutableStateOf(initial.color) }
    fun num(d: Double?) = d?.let { if (it == Math.floor(it)) "%.0f".format(Locale.US, it) else it.toString() }.orEmpty()
    var min by remember { mutableStateOf(if (keep) num(initial.min) else "") }
    var max by remember { mutableStateOf(if (keep) num(initial.max) else "") }
    var warnAbove by remember { mutableStateOf(if (keep) num(initial.warnAbove) else "") }
    var warnBelow by remember { mutableStateOf(if (keep) num(initial.warnBelow) else "") }
    var decimals by remember { mutableStateOf(initial.decimals) }
    fun parse(s: String) = s.replace(',', '.').trim().toDoubleOrNull()
    val draft = GaugeSpec(initial.key, style, color, parse(min), parse(max), parse(warnAbove), parse(warnBelow), decimals, units)
    val unit = def?.let { formatSensor(it, null, units).unit }.orEmpty()
    val (dlo, dhi) = def?.let { displayRange(it, units) } ?: (0.0 to 100.0)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(tr("Customise gauge"), style = MaterialTheme.typography.headlineSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(def?.let { tr(it.name) } ?: initial.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                TextButton(onClick = onChangeSensor) { Text(tr("Change")) }
            }
            // Live preview with the draft settings
            if (def != null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.width(190.dp),
                    colors = CardDefaults.cardColors(containerColor = theme.face?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    val sample = def.min + (def.max - def.min) * 0.62
                    GaugeView(draft, theme, def, Reading(sample, def.min + (def.max - def.min) * 0.2, def.max * 0.8, sample, 1, 0), units, true)
                }
            }
            Text(tr("Style"), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item { FilterChip(style == null, { style = null }, label = { Text(tr("Theme") + " (" + tr(theme.defaultStyle.label) + ")") }) }
                items(GaugeStyle.entries.size) { i ->
                    val st = GaugeStyle.entries[i]
                    FilterChip(style == st, { style = st }, label = { Text(tr(st.label)) })
                }
            }
            Text(tr("Colour"), style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                item { FilterChip(color == null, { color = null }, label = { Text(tr("Theme")) }) }
                items(GaugeColors.size) { i ->
                    val c = GaugeColors[i]
                    Box(Modifier.size(34.dp).clip(CircleShape).background(rgb(c))
                        .border(if (color == c) 3.dp else 1.dp, if (color == c) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape)
                        .clickable { color = c })
                }
            }
            Text(tr("Range (%1\$s)", unit.ifBlank { "—" }), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(min, { min = it }, label = { Text(tr("Min")) }, placeholder = { Text(num(dlo)) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(max, { max = it }, label = { Text(tr("Max")) }, placeholder = { Text(num(dhi)) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Text(tr("Turn red"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(warnAbove, { warnAbove = it }, label = { Text(tr("At or above")) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(warnBelow, { warnBelow = it }, label = { Text(tr("At or below")) }, singleLine = true, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Text(tr("Decimals"), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(decimals == null, { decimals = null }, label = { Text(tr("Auto")) })
                (0..2).forEach { d -> FilterChip(decimals == d, { decimals = d }, label = { Text(d.toString()) }) }
            }
            GradientButton(tr("Save"), { onSave(draft) }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSave(GaugeSpec(initial.key)) }, modifier = Modifier.weight(1f)) { Text(tr("Reset")) }
                OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text(tr("Remove"), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
