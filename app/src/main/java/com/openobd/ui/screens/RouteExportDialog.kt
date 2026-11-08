package com.openobd.ui.screens

import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.core.obd.UnitSystem
import com.openobd.core.trip.Gpx
import com.openobd.core.trip.Route3mf
import com.openobd.core.trip.RouteHtml
import com.openobd.core.trip.RouteInfo
import com.openobd.core.trip.RoutePoster
import com.openobd.core.trip.Units
import com.openobd.core.trip.fileName
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.shareFile
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** The export formats, each with what it is for. */
enum class RouteFormat(val ext: String, val mime: String, val title: String, val description: String) {
    GPX("gpx", "application/gpx+xml", "GPS file (GPX)",
        "For Garmin, OsmAnd, Komoot, Google Earth and other GPS apps. The car's values travel with every point."),
    HTML("html", "text/html", "Interactive map (HTML)",
        "Anyone can open it in a browser, tap the route to see what the car was doing and save the GPX for their GPS."),
    PDF("pdf", "application/pdf", "Poster to print (PDF, A3)",
        "The route in vectors over the shaded relief: prints sharp on A3 and scales down to A4."),
    SVG("svg", "image/svg+xml", "Vector image (SVG)",
        "The same poster to edit or send to a print shop."),
    THREE_MF("3mf", "model/3mf", "3D print (3MF, multicolour)",
        "The route on the real terrain around it, cut as a hexagon, circle or square, with the title on a plaque. Ground, route (coloured by speed), markers and title are separate parts: give each a filament in your slicer."),
    ;

    companion object {
        val texts = entries.flatMap { listOf(it.title, it.description) }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RouteExportDialog(trip: TripRecord, points: List<TrackPoint>, car: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = com.openobd.ui.LocalApp.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    var busy by remember { mutableStateOf<RouteFormat?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val units = Units(imperial = settings.units == UnitSystem.IMPERIAL)
    val defaultTitle = tr("Drive on %1\$s", DateFormat.getDateInstance(DateFormat.LONG).format(Date(trip.start)))
    val title = trip.name.ifBlank { defaultTitle }
    var options3d by remember { mutableStateOf(false) }
    var shape by remember { mutableStateOf(Route3mf.Shape.HEXAGON) }
    var withTerrain by remember { mutableStateOf(true) }
    var relief by remember { mutableStateOf(2.0) }
    var titleOnModel by remember { mutableStateOf(true) }
    var withCoordinates by remember { mutableStateOf(false) }
    var colors by remember { mutableStateOf(Route3mf.Colors.FOUR) }
    val label: (String) -> String = { tr(it) }

    fun info() = RouteInfo(
        title = title.trim().ifBlank { defaultTitle }, car = car,
        start = trip.start, end = trip.end, distanceKm = trip.distanceKm, fuelL = trip.fuelL, avgSpeedKmh = trip.avgSpeed,
        maxSpeedKmh = trip.maxSpeed, avgL100 = trip.avgL100, creator = "OpenOBD " + com.openobd.BuildConfig.VERSION_NAME,
    )

    fun export(format: RouteFormat) {
        busy = format; error = null; status = null
        val info = info()
        scope.launch {
            runCatching {
                val usesTerrain = format == RouteFormat.THREE_MF || format == RouteFormat.PDF || format == RouteFormat.SVG
                val terrain = if (usesTerrain && withTerrain) {
                    status = tr("Downloading terrain…")
                    app.terrain(points, if (format == RouteFormat.THREE_MF) 640 else 1500) { i, n -> status = tr("Downloading terrain… %1\$s of %2\$s", i, n) }
                        .also { if (it == null) error = tr("The terrain could not be downloaded: exported without relief.") }
                } else null
                status = null
                withContext(Dispatchers.Default) {
                    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
                    val file = File(dir, fileName(info) + "." + format.ext)
                    when (format) {
                        RouteFormat.GPX -> file.writeText(Gpx.write(info, points))
                        RouteFormat.HTML -> file.writeText(RouteHtml.write(info, points, label, units))
                        RouteFormat.SVG -> file.writeText(RoutePoster.svg(RoutePoster.layout(info, points, label, units, terrain = terrain)))
                        RouteFormat.PDF -> writePdf(file, RoutePoster.layout(info, points, label, units, terrain = terrain))
                        RouteFormat.THREE_MF -> {
                            val outlines = if (titleOnModel) com.openobd.ui.textOutlines(info.title) else emptyList()
                            val coords = if (titleOnModel && withCoordinates) com.openobd.ui.textOutlines(
                                com.openobd.core.trip.RouteGeometry.coordinates(points.first().lat, points.first().lon)) else emptyList()
                            val meshes = Route3mf.meshes(points, Route3mf.Options(shape = shape, exaggeration = relief, colors = colors), terrain, outlines, coords)
                            file.outputStream().use { Route3mf.write(meshes, it, info.title) }
                        }
                    }
                    file
                }
            }.onSuccess { shareFile(context, it, format.mime); if (error == null) onDismiss() }
                .onFailure { error = tr("Could not create the file: %1\$s", it.message ?: "") }
            busy = null; status = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("Export route")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("routeTitle"))
                Text(tr("Title of every export. Change it with the trip's name."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Terrain around the route"))
                        Text(tr("Real relief under the poster and in the 3D model, from open elevation data (downloads a few map tiles)."),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(withTerrain, { withTerrain = it }, modifier = Modifier.testTag("terrainSwitch"))
                }
                if (withTerrain) Text(tr(com.openobd.core.trip.Terrarium.ATTRIBUTION), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                RouteFormat.entries.forEach { f ->
                    val icon: ImageVector = when (f) {
                        RouteFormat.GPX -> Icons.Default.Navigation
                        RouteFormat.HTML -> Icons.Default.Public
                        RouteFormat.PDF -> Icons.Default.Print
                        RouteFormat.SVG -> Icons.Default.Map
                        RouteFormat.THREE_MF -> Icons.Default.ViewInAr
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                        if (f == RouteFormat.THREE_MF) options3d = !options3d else export(f)
                    }.padding(vertical = 8.dp).testTag("export_${f.ext}"), verticalAlignment = Alignment.Top) {
                        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr(f.title), fontWeight = FontWeight.SemiBold)
                            Text(tr(f.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (busy == f) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                    if (f == RouteFormat.THREE_MF && options3d) Column(Modifier.padding(start = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(tr("Shape"), style = MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Route3mf.Shape.entries.forEach { sh -> FilterChip(shape == sh, { shape = sh }, label = { Text(tr(sh.label)) }, modifier = Modifier.testTag("shape_${sh.name}")) }
                        }
                        if (withTerrain) {
                            Text(tr("Relief exaggeration"), style = MaterialTheme.typography.labelLarge)
                            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(1.0, 2.0, 3.0, 5.0).forEach { x -> FilterChip(relief == x, { relief = x }, label = { Text("×" + x.toInt()) }) }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("Title on the model"), Modifier.weight(1f))
                            Switch(titleOnModel, { titleOnModel = it })
                        }
                        if (titleOnModel) Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("GPS coordinates under the title"), Modifier.weight(1f))
                            Switch(withCoordinates, { withCoordinates = it }, modifier = Modifier.testTag("coordsSwitch"))
                        }
                        Text(tr("Colours"), style = MaterialTheme.typography.labelLarge)
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Route3mf.Colors.entries.forEach { c -> FilterChip(colors == c, { colors = c }, label = { Text(tr(c.label)) }, modifier = Modifier.testTag("colors_${c.name}")) }
                        }
                        Text(tr("Each part comes painted with its filament number:") + " " +
                            Route3mf.legend(colors).joinToString(" · ") { (n, what) -> "$n " + tr(what).replaceFirstChar { it.lowercase() } },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { export(RouteFormat.THREE_MF) }, enabled = busy == null, modifier = Modifier.fillMaxWidth().testTag("make3d")) {
                            Text(tr("Create 3D model"))
                        }
                    }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("Close")) } },
    )
}

/** Draws the poster's vector shapes into a PDF page (1 mm = 72 / 25.4 points), so it stays sharp when printed. */
fun writePdf(file: File, poster: RoutePoster.Poster) {
    val k = 72.0 / 25.4
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder((poster.widthMm * k).toInt(), (poster.heightMm * k).toInt(), 1).create())
    val c = page.canvas
    fun color(hex: String) = android.graphics.Color.parseColor(hex)
    for (s in poster.shapes) when (s) {
        is RoutePoster.Rect -> c.drawRect((s.x * k).toFloat(), (s.y * k).toFloat(), ((s.x + s.w) * k).toFloat(), ((s.y + s.h) * k).toFloat(),
            Paint().apply { color = color(s.fill); style = Paint.Style.FILL })
        is RoutePoster.Line -> if (s.points.size >= 2) {
            val path = Path().apply {
                moveTo((s.points[0].first * k).toFloat(), (s.points[0].second * k).toFloat())
                s.points.drop(1).forEach { lineTo((it.first * k).toFloat(), (it.second * k).toFloat()) }
            }
            c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = color(s.color); style = Paint.Style.STROKE; strokeWidth = (s.width * k).toFloat()
                strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
            })
        }
        is RoutePoster.Circle -> {
            c.drawCircle((s.x * k).toFloat(), (s.y * k).toFloat(), (s.r * k).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply { color = color(s.fill) })
            s.stroke?.let { st ->
                c.drawCircle((s.x * k).toFloat(), (s.y * k).toFloat(), (s.r * k).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = color(st); style = Paint.Style.STROKE; strokeWidth = (s.strokeWidth * k).toFloat()
                })
            }
        }
        is RoutePoster.Image -> {
            val bmp = android.graphics.Bitmap.createBitmap(s.argb, s.width, s.height, android.graphics.Bitmap.Config.ARGB_8888)
            c.drawBitmap(bmp, null, android.graphics.RectF((s.x * k).toFloat(), (s.y * k).toFloat(), ((s.x + s.w) * k).toFloat(), ((s.y + s.h) * k).toFloat()),
                Paint(Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
        }
        is RoutePoster.Text -> c.drawText(s.text, (s.x * k).toFloat(), (s.y * k).toFloat(), Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = color(s.color); textSize = (s.size * k).toFloat()
            typeface = Typeface.create(Typeface.SANS_SERIF, if (s.bold) Typeface.BOLD else Typeface.NORMAL)
            textAlign = when (s.anchor) { 1 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
        })
    }
    doc.finishPage(page)
    file.outputStream().use { doc.writeTo(it) }
    doc.close()
}
