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
import androidx.compose.foundation.layout.FlowRow
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
        "Anyone can open it in a browser, tap the route to see what the car was doing and save the GPX for their GPS. Map, aerial photos and relief to choose from; the relief is inside the file and shows even offline."),
    PDF("pdf", "application/pdf", "Poster to print (PDF, A3)",
        "The route over a detailed relief with contour lines and lakes, or over aerial photos: prints sharp on A3 and scales down to A4."),
    SVG("svg", "image/svg+xml", "Vector image (SVG)",
        "The same poster to edit or send to a print shop."),
    THREE_MF("3mf", "model/3mf", "3D map to print (3MF, multicolour)",
        "The terrain around the route coloured by altitude, with lakes, roads and buildings, cut as a hexagon, circle or square. See it in 3D before saving; every part comes painted with its filament."),
    ;

    companion object {
        val texts = entries.flatMap { listOf(it.title, it.description) }
    }
}

/** What goes under the route on the poster (and which layer the web map opens with). */
enum class PosterGround(val label: String) {
    NONE("None"), RELIEF("Relief"), SATELLITE("Satellite");

    companion object {
        val texts = entries.map { it.label } + listOf(
            "Background", "Detailed relief from national elevation models (2 m in Spain), or aerial photos where you can see single trees. Downloads map tiles of the area.",
            "Downloading aerial photos… %1\$s of %2\$s", "The aerial photos could not be downloaded: exported without them.",
        )
    }
}

/** A JPEG data: URI of a raster (much smaller than PNG for relief and photos; transparent pixels become white). */
fun jpegDataUri(width: Int, height: Int, argb: IntArray): String {
    val opaque = IntArray(argb.size) { i -> val p = argb[i]; if (p ushr 24 == 0xFF) p else 0xFFFFFFFF.toInt() }
    val bmp = android.graphics.Bitmap.createBitmap(opaque, width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val out = java.io.ByteArrayOutputStream()
    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
    bmp.recycle()
    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
}

/** The area whose lakes go on the relief (as the elevation download), or null if too big to ask for. */
private fun waterArea(points: List<TrackPoint>): DoubleArray? {
    if (points.size < 2) return null
    val minLat = points.minOf { it.lat }; val maxLat = points.maxOf { it.lat }
    val minLon = points.minOf { it.lon }; val maxLon = points.maxOf { it.lon }
    val k = kotlin.math.cos(Math.toRadians(minLat))
    val spanM = maxOf((maxLat - minLat) * 111_000, (maxLon - minLon) * 111_000 * k, 300.0)
    if (spanM > 60_000) return null
    val padLat = spanM * 0.7 / 111_000; val padLon = padLat / k
    return doubleArrayOf(minLat - padLat, minLon - padLon, maxLat + padLat, maxLon + padLon)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RouteExportDialog(trip: TripRecord, points: List<TrackPoint>, car: String, onDismiss: () -> Unit, onModel3d: () -> Unit = {}) {
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
    var ground by remember { mutableStateOf(PosterGround.RELIEF) }
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
                val usesGround = (format == RouteFormat.PDF || format == RouteFormat.SVG || format == RouteFormat.HTML) && ground != PosterGround.NONE
                // The web map always carries the relief (it works offline); its aerial photos are online.
                val wantsRelief = usesGround && (ground == PosterGround.RELIEF || format == RouteFormat.HTML)
                val terrain = if (wantsRelief) {
                    status = tr("Downloading terrain…")
                    app.terrain(points, 4000) { i, n -> status = tr("Downloading terrain… %1\$s of %2\$s", i, n) }
                        .also { if (it == null) error = tr("The terrain could not be downloaded: exported without relief.") }
                } else null
                val water = if (terrain != null) waterArea(points)?.let { area -> runCatching { app.water(area) }.getOrNull() }.orEmpty() else emptyList()
                val imagery = if (usesGround && ground == PosterGround.SATELLITE && format != RouteFormat.HTML) {
                    RoutePoster.frame(points)?.let { fr ->
                        status = tr("Downloading aerial photos… %1\$s of %2\$s", 0, "…")
                        app.imagery(fr.area(), fr.metresPerMm / RoutePoster.PHOTO_PX_PER_MM) { i, n -> status = tr("Downloading aerial photos… %1\$s of %2\$s", i, n) }
                    }.also { if (it == null) error = tr("The aerial photos could not be downloaded: exported without them.") }
                } else null
                status = null
                withContext(Dispatchers.Default) {
                    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
                    val file = File(dir, fileName(info) + "." + format.ext)
                    when (format) {
                        RouteFormat.GPX -> file.writeText(Gpx.write(info, points))
                        RouteFormat.HTML -> file.writeText(RouteHtml.write(info, points, label, units, terrain, water, ::jpegDataUri, satelliteFirst = ground == PosterGround.SATELLITE))
                        RouteFormat.SVG -> file.writeText(RoutePoster.svg(RoutePoster.layout(info, points, label, units, terrain = terrain, imagery = imagery, water = water), ::jpegDataUri))
                        RouteFormat.PDF -> writePdf(file, RoutePoster.layout(info, points, label, units, terrain = terrain, imagery = imagery, water = water))
                        RouteFormat.THREE_MF -> error("3D models are made in their own screen")
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
                Text(tr("Background"), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Text(tr("Detailed relief from national elevation models (2 m in Spain), or aerial photos where you can see single trees. Downloads map tiles of the area."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosterGround.entries.forEach { g ->
                        FilterChip(ground == g, { ground = g }, label = { Text(tr(g.label)) }, enabled = busy == null, modifier = Modifier.testTag("ground_${g.name}"))
                    }
                }
                val credits = when (ground) {
                    PosterGround.NONE -> emptyList()
                    PosterGround.RELIEF -> listOf(com.openobd.core.trip.Mapterhorn.ATTRIBUTION)
                    PosterGround.SATELLITE -> com.openobd.core.trip.Imagery.Source.entries.map { it.attribution }
                }
                credits.forEach { Text(tr(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                RouteFormat.entries.forEach { f ->
                    val icon: ImageVector = when (f) {
                        RouteFormat.GPX -> Icons.Default.Navigation
                        RouteFormat.HTML -> Icons.Default.Public
                        RouteFormat.PDF -> Icons.Default.Print
                        RouteFormat.SVG -> Icons.Default.Map
                        RouteFormat.THREE_MF -> Icons.Default.ViewInAr
                    }
                    Row(Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                        if (f == RouteFormat.THREE_MF) onModel3d() else export(f)
                    }.padding(vertical = 8.dp).testTag("export_${f.ext}"), verticalAlignment = Alignment.Top) {
                        Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(tr(f.title), fontWeight = FontWeight.SemiBold)
                            Text(tr(f.description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (busy == f) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
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
