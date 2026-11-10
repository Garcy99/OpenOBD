package com.openobd.ui.screens

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.garage.MonthlySummary
import com.openobd.core.obd.UnitSystem
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.KeyValueRow
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.components.formatDuration
import com.openobd.ui.shareFile
import com.openobd.ui.theme.Bad
import com.openobd.ui.theme.Good
import com.openobd.ui.tr
import java.io.File
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/** The month's name and year in the app's language, e.g. "October 2026". */
fun monthName(year: Int, month: Int): String {
    val locale = Locale.forLanguageTag(com.openobd.ui.I18n.language.takeIf { it != "system" } ?: Locale.getDefault().language)
    val name = DateFormatSymbols.getInstance(locale).standaloneMonthsOrMonths()[month]
    return name.replaceFirstChar { it.titlecase(locale) } + " " + year
}

private fun DateFormatSymbols.standaloneMonthsOrMonths(): Array<String> = months

/** The active car's month: figures, against the month before and the official consumption, and the possible saving. */
@Composable
fun MonthlySummaryScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val settings = appSettings()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val v = g.active
    val now = remember { Calendar.getInstance() }
    var ym by remember { mutableStateOf(now.get(Calendar.YEAR) to now.get(Calendar.MONTH)) }
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("Monthly summary"), onBack, actions = {
            if (v != null) IconButton(onClick = {
                val s = summaryFor(app, v.id, ym.first, ym.second)
                shareFile(context, writeMonthlyPdf(context, s, v.name, settings.units), "application/pdf")
            }, modifier = Modifier.testTag("monthPdf")) { Icon(Icons.Default.PictureAsPdf, tr("Export PDF")) }
        })
        if (v == null) { Text(tr("Add a car in the garage first."), Modifier.padding(16.dp)); return@Column }
        val s = remember(g, ym) { summaryFor(app, v.id, ym.first, ym.second) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { ym = MonthlySummary.previousMonth(ym.first, ym.second) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Previous month")) }
                Text(monthName(ym.first, ym.second), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                val isNow = ym == (now.get(Calendar.YEAR) to now.get(Calendar.MONTH))
                IconButton(onClick = { ym = if (ym.second == 11) ym.first + 1 to 0 else ym.first to ym.second + 1 }, enabled = !isNow) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, tr("Next month"))
                }
            }
            if (s.trips == 0) { Text(tr("No trips this month."), color = MaterialTheme.colorScheme.onSurfaceVariant); return@Column }
            SummaryBody(s, settings.units)
        }
    }
}

private fun summaryFor(app: com.openobd.AppContainer, vehicleId: String, year: Int, month: Int): MonthlySummary {
    val g = app.garage.data.value
    val car = g.vehicles.firstOrNull { it.id == vehicleId }
    val fuel = car?.specs?.fuel
    return MonthlySummary.of(year, month, g.trips.filter { it.vehicleId == vehicleId }, g.fuel.filter { it.vehicleId == vehicleId },
        diesel = fuel?.burnsDiesel == true, lpg = fuel == com.openobd.core.specs.SpecFuel.LPG,
        officialL100 = car?.specs?.consumptionL100, fallbackPrice = app.settings.settings.value.fuelPricePerL.toDouble())
}

/** "+12 %" / "−8 %" against the month before (better or worse as [lowerIsBetter] says). */
@Composable
private fun Delta(now: Double?, before: Double?, lowerIsBetter: Boolean) {
    if (now == null || before == null || before == 0.0) return
    val pct = (now / before - 1) * 100
    if (abs(pct) < 1) return
    val good = (pct < 0) == lowerIsBetter
    Text((if (pct > 0) "+" else "−") + "%.0f %%".format(abs(pct)), color = if (good) Good else Bad, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SummaryBody(s: MonthlySummary, units: UnitSystem) {
    val p = s.previous
    SectionCard(title = tr("The month")) {
        Row { KeyValueRow(tr("Trips"), s.trips.toString(), Modifier.weight(1f)) }
        Row(verticalAlignment = Alignment.CenterVertically) { KeyValueRow(tr("Distance"), fmtKm(s.km, units, 0), Modifier.weight(1f)); Delta(s.km, p?.km, false) }
        KeyValueRow(tr("Time driving"), formatDuration(s.driveSeconds))
        s.fuelL?.let { Row(verticalAlignment = Alignment.CenterVertically) { KeyValueRow(tr("Fuel used"), fmtLiters(it, units), Modifier.weight(1f)); Delta(it, p?.fuelL, true) } }
        s.avgL100?.let { Row(verticalAlignment = Alignment.CenterVertically) { KeyValueRow(tr("Average consumption"), fmtL100(it, units), Modifier.weight(1f)); Delta(it, p?.avgL100, true) } }
        s.cost?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                KeyValueRow(if (s.costFromRefuels) tr("Spent on fuel (fill-ups)") else tr("Fuel cost (trips)"), fmtMoney(it), Modifier.weight(1f)); Delta(it, p?.cost, true)
            }
        }
        s.co2Kg?.let { KeyValueRow(tr("CO₂ emitted"), "%.0f kg".format(it)) }
        s.ecoScore?.let { Row(verticalAlignment = Alignment.CenterVertically) { KeyValueRow(tr("Eco score"), "$it/100", Modifier.weight(1f)); Delta(it.toDouble(), p?.ecoScore?.toDouble(), false) } }
        if (p != null) Text(tr("Changes are against %1\$s.", monthName(p.year, p.month)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (s.officialL100 != null && s.avgL100 != null) SectionCard(title = tr("Against the official figure")) {
        KeyValueRow(tr("Yours"), fmtL100(s.avgL100!!, units))
        KeyValueRow(tr("Official (combined)"), fmtL100(s.officialL100, units))
        s.vsOfficialPct?.let { pct ->
            Text(if (pct >= 0) tr("You use %1\$s %% more than the official figure.", "%.0f".format(pct)) else tr("You use %1\$s %% less than the official figure.", "%.0f".format(-pct)),
                fontWeight = FontWeight.SemiBold, color = if (pct > 25) Bad else if (pct <= 5) Good else MaterialTheme.colorScheme.onSurface)
        }
        Text(tr("Official figures are measured in a lab cycle; real driving usually uses 10-30 % more."), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (s.bestL100 != null && s.avgL100 != null) SectionCard(title = tr("Driving like in your best trips")) {
        KeyValueRow(tr("Your best trips"), fmtL100(s.bestL100!!, units))
        val l = s.savingL
        if (l != null && l > 0.5) {
            Text(tr("This month you would have used %1\$s less", fmtLiters(l, units)) + (s.savingMoney?.let { " (" + fmtMoney(it) + ")" } ?: "") + ".",
                fontWeight = FontWeight.SemiBold, color = Good)
            Text(tr("Your thriftiest quarter of trips of the last three months: gentle starts, steady speeds, early gear changes."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text(tr("This month you drove as thriftily as in your best trips."), color = Good)
    }
}

/** The summary as an A4 PDF in the shareable reports folder. */
fun writeMonthlyPdf(context: Context, s: MonthlySummary, carName: String, units: UnitSystem): File {
    val doc = PdfDocument()
    val pageW = 595; val pageH = 842; val margin = 48f
    val page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
    var y = margin
    fun text(t: String, size: Float, bold: Boolean = false, color: Int = 0xFF111827.toInt(), gap: Float = 6f) {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; this.color = color; isFakeBoldText = bold }
        val layout = StaticLayout.Builder.obtain(t, 0, t.length, paint, (pageW - 2 * margin).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        page.canvas.save(); page.canvas.translate(margin, y); layout.draw(page.canvas); page.canvas.restore()
        y += layout.height + gap
    }
    fun row(k: String, v: String) = text("$k:  $v", 12f, gap = 4f)
    text(tr("Monthly summary") + " · " + monthName(s.year, s.month), 20f, bold = true)
    text(carName, 13f, color = 0xFF4B5563.toInt(), gap = 16f)
    row(tr("Trips"), s.trips.toString())
    row(tr("Distance"), fmtKm(s.km, units, 0))
    row(tr("Time driving"), formatDuration(s.driveSeconds))
    s.fuelL?.let { row(tr("Fuel used"), fmtLiters(it, units)) }
    s.avgL100?.let { row(tr("Average consumption"), fmtL100(it, units)) }
    s.cost?.let { row(if (s.costFromRefuels) tr("Spent on fuel (fill-ups)") else tr("Fuel cost (trips)"), fmtMoney(it)) }
    s.co2Kg?.let { row(tr("CO₂ emitted"), "%.0f kg".format(it)) }
    s.ecoScore?.let { row(tr("Eco score"), "$it/100") }
    y += 10
    if (s.officialL100 != null && s.avgL100 != null) {
        text(tr("Against the official figure"), 14f, bold = true)
        row(tr("Official (combined)"), fmtL100(s.officialL100, units))
        s.vsOfficialPct?.let { pct -> text(if (pct >= 0) tr("You use %1\$s %% more than the official figure.", "%.0f".format(pct)) else tr("You use %1\$s %% less than the official figure.", "%.0f".format(-pct)), 12f) }
        y += 6
    }
    s.savingL?.takeIf { it > 0.5 }?.let { l ->
        text(tr("Driving like in your best trips"), 14f, bold = true)
        text(tr("This month you would have used %1\$s less", fmtLiters(l, units)) + (s.savingMoney?.let { " (" + fmtMoney(it) + ")" } ?: "") + ".", 12f)
    }
    text("OpenOBD", 9f, color = 0xFF9CA3AF.toInt())
    doc.finishPage(page)
    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
    val file = File(dir, "OpenOBD-${s.year}-%02d.pdf".format(s.month + 1))
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
    return file
}
