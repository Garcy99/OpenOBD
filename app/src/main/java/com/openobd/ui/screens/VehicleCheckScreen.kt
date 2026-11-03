package com.openobd.ui.screens

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.report.CheckItem
import com.openobd.core.report.CheckLevel
import com.openobd.core.report.CheckSection
import com.openobd.core.report.ItvVerdict
import com.openobd.core.report.Verdict
import com.openobd.core.report.VehicleCheck
import com.openobd.core.report.VehicleCheckReport
import com.openobd.session.Op
import com.openobd.session.VehicleSnapshot
import com.openobd.ui.LocalApp
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.LoadingBox
import com.openobd.ui.components.NotConnected
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.connectedVehicle
import com.openobd.ui.shareFile
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.theme.Warn
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val InfoBlue = Color(0xFF3B82F6)
private val Grey = Color(0xFF9CA3AF)

private fun levelColor(l: CheckLevel) = when (l) {
    CheckLevel.OK -> Good; CheckLevel.INFO -> InfoBlue; CheckLevel.UNKNOWN -> Grey; CheckLevel.WARN -> Warn; CheckLevel.FAIL -> Bad
}

fun itemText(item: CheckItem) = tr(item.text, *item.args.toTypedArray())

@Composable
fun VehicleCheckScreen(onBack: () -> Unit, onConnect: () -> Unit) {
    val app = LocalApp.current
    val session = app.session
    val vehicle = connectedVehicle()
    val op by session.check.result.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Used car & pre-ITV check"), onBack)
        if (vehicle == null) { NotConnected(onConnect); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (op !is Op.Done) {
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FactCheck, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text(tr("Before buying a car or taking it to the ITV"), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(tr("Reads every control module, looks for codes cleared to hide faults, checks monitor readiness, VIN and odometer consistency and the battery. ") +
                        tr("Takes about a minute with the ignition on (engine running is best)."),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when (val s = op) {
                is Op.Loading -> LoadingBox(s.progress)
                is Op.Error -> { ErrorText(s.message); Button(onClick = session.check::run) { Text(tr("Try again")) } }
                Op.Idle -> Button(onClick = session.check::run, modifier = Modifier.fillMaxWidth()) { Text(tr("Run the check")) }
                is Op.Done -> {
                    val r = s.value
                    VerdictCard(tr("As a used car"), tr(r.verdict.label), tr(r.verdict.detail), when (r.verdict) { Verdict.GOOD -> Good; Verdict.CHECK -> Warn; Verdict.BAD -> Bad })
                    VerdictCard(tr("Pre-ITV (emissions, estimate)"), tr(r.itv.label), tr(r.itv.detail),
                        when (r.itv) { ItvVerdict.PASS -> Good; ItvVerdict.NOT_READY -> Warn; ItvVerdict.FAIL -> Bad })
                    for (section in CheckSection.entries) {
                        val items = r.section(section)
                        if (items.isEmpty()) continue
                        SectionCard(title = tr(section.label)) {
                            items.forEach { CheckRow(it) }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val f = withContext(Dispatchers.IO) { writeVehicleCheckPdf(context, r, vehicle, app.garage.data.value.active?.name) }
                                shareFile(context, f, "application/pdf")
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PictureAsPdf, null); Spacer(Modifier.width(6.dp)); Text(tr("Share PDF"))
                        }
                        OutlinedButton(onClick = session.check::run, modifier = Modifier.weight(1f)) { Text(tr("Run again")) }
                    }
                    Text(tr(VehicleCheck.DISCLAIMER), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun VerdictCard(title: String, verdict: String, detail: String, color: Color) {
    Column(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.14f), RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(verdict, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Icon(
            when (item.level) {
                CheckLevel.OK -> Icons.Default.CheckCircle; CheckLevel.INFO -> Icons.Default.Info; CheckLevel.UNKNOWN -> Icons.Default.Help
                CheckLevel.WARN -> Icons.Default.Warning; CheckLevel.FAIL -> Icons.Default.Error
            },
            null, tint = levelColor(item.level), modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(itemText(item), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Renders the report as an A4 PDF in the shareable reports folder. */
fun writeVehicleCheckPdf(context: Context, r: VehicleCheckReport, vehicle: VehicleSnapshot, carName: String?): File {
    val doc = PdfDocument()
    val pageW = 595; val pageH = 842; val margin = 44f
    val width = (pageW - 2 * margin).toInt()
    var pageNo = 0
    var page: PdfDocument.Page? = null
    var y = 0f

    fun newPage() {
        page?.let { doc.finishPage(it) }
        pageNo++
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNo).create())
        y = margin
    }

    fun text(s: String, size: Float, color: Int = 0xFF111827.toInt(), bold: Boolean = false, indent: Float = 0f, gapAfter: Float = 4f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; isFakeBoldText = bold }
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, paint, width - indent.toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        if (y + layout.height > pageH - margin) newPage()
        val c = page!!.canvas
        c.save(); c.translate(margin + indent, y); layout.draw(c); c.restore()
        y += layout.height + gapAfter
    }

    fun dot(color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        page!!.canvas.drawCircle(margin + 5f, y + 7f, 4.5f, p)
    }

    newPage()
    text(tr("Used car & pre-ITV check"), 22f, bold = true, gapAfter = 2f)
    text(listOfNotNull(carName, vehicle.vin?.let { "VIN $it" }, vehicle.elm.protocol.label).joinToString(" · "), 10f, 0xFF4B5563.toInt())
    text(tr("Generated %1\$s with OpenOBD", DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.generatedAt))), 10f, 0xFF4B5563.toInt(), gapAfter = 14f)

    val vColor = when (r.verdict) { Verdict.GOOD -> Good; Verdict.CHECK -> Warn; Verdict.BAD -> Bad }.toArgb()
    val iColor = when (r.itv) { ItvVerdict.PASS -> Good; ItvVerdict.NOT_READY -> Warn; ItvVerdict.FAIL -> Bad }.toArgb()
    text(tr("As a used car") + ": " + tr(r.verdict.label), 14f, vColor, bold = true, gapAfter = 1f)
    text(tr(r.verdict.detail), 10f, gapAfter = 8f)
    text(tr("Pre-ITV (emissions, estimate)") + ": " + tr(r.itv.label), 14f, iColor, bold = true, gapAfter = 1f)
    text(tr(r.itv.detail), 10f, gapAfter = 14f)

    for (section in CheckSection.entries) {
        val items = r.section(section)
        if (items.isEmpty()) continue
        text(tr(section.label), 13f, bold = true, gapAfter = 4f)
        for (item in items) {
            if (y + 16 > pageH - margin) newPage()
            dot(levelColor(item.level).toArgb())
            text(itemText(item), 10.5f, indent = 16f, gapAfter = 5f)
        }
        y += 6f
    }
    y += 8f
    text(tr(VehicleCheck.DISCLAIMER), 8.5f, 0xFF6B7280.toInt())
    page?.let { doc.finishPage(it) }

    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    dir.listFiles()?.filter { it.name.startsWith("openobd-check-") }?.forEach { it.delete() }
    val f = File(dir, "openobd-check-${(vehicle.vin ?: "car").takeLast(6)}-${r.generatedAt / 1000}.pdf")
    f.outputStream().use { doc.writeTo(it) }
    doc.close()
    return f
}
