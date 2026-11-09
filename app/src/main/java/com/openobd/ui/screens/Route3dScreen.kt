package com.openobd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.MapFeatures
import com.openobd.core.trip.Overpass
import com.openobd.core.trip.Route3mf
import com.openobd.core.trip.RouteGeometry
import com.openobd.core.trip.Terrain
import com.openobd.core.trip.Terrarium
import com.openobd.core.trip.fileName
import com.openobd.core.trip.RouteInfo
import com.openobd.ui.LocalApp
import com.openobd.ui.ModelPreview
import com.openobd.ui.components.CenteredMessage
import com.openobd.ui.components.ErrorText
import com.openobd.ui.components.ScreenTopBar
import com.openobd.ui.components.SectionCard
import com.openobd.ui.shareFile
import com.openobd.ui.textOutlines
import com.openobd.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private fun swatch(hex: String) = Color(android.graphics.Color.parseColor(hex))

/**
 * The 3D map of a trip: terrain coloured by altitude, water, roads and buildings from OpenStreetMap, the route,
 * markers and title; a live preview while choosing the options, the filament list, and the 3MF to share.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Route3dScreen(tripId: String, onBack: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val g by app.garage.data.collectAsStateWithLifecycle()
    val trip = g.trips.firstOrNull { it.id == tripId }
    var points by remember { mutableStateOf<List<TrackPoint>?>(null) }
    LaunchedEffect(tripId) { points = app.garage.loadTrack(tripId).orEmpty() }

    var shape by remember { mutableStateOf(Route3mf.Shape.HEXAGON) }
    var theme by remember { mutableStateOf(Route3mf.Theme.ALPINE) }
    var sizeMm by remember { mutableStateOf(150.0) }
    var exaggeration by remember { mutableStateOf(2.0) }
    var maxHeight by remember { mutableStateOf(45.0) }
    var baseMm by remember { mutableStateOf(3.0) }
    var detail by remember { mutableStateOf(Route3mf.Detail.NORMAL) }
    var water by remember { mutableStateOf(true) }
    var roads by remember { mutableStateOf(true) }
    var buildings by remember { mutableStateOf(true) }
    var bySpeed by remember { mutableStateOf(false) }
    var colours by remember { mutableStateOf(Route3mf.Colours.ALL) }
    var titleOn by remember { mutableStateOf(true) }
    var coords by remember { mutableStateOf(false) }

    var terrain by remember { mutableStateOf<Terrain?>(null) }
    var features by remember { mutableStateOf<MapFeatures?>(null) }
    var terrainStatus by remember { mutableStateOf<String?>(null) }
    var mapStatus by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf(emptyList<String>()) }
    var model by remember { mutableStateOf<Route3mf.Model?>(null) }
    var building by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val pts = points.orEmpty()
    val title = trip?.let { t -> t.name.ifBlank { tr("Drive on %1\$s", DateFormat.getDateInstance(DateFormat.LONG).format(Date(t.start))) } }.orEmpty()
    val canBuildings = pts.size >= 2 && Route3mf.spanMetres(pts) <= Overpass.MAX_BUILDINGS_SPAN_M

    // Elevation for the chosen detail, and the map data once.
    LaunchedEffect(pts.size, detail) {
        if (pts.size < 2) return@LaunchedEffect
        terrainStatus = tr("Downloading terrain…")
        val tr0 = app.terrain(pts, detail.terrainPx) { i, n -> terrainStatus = tr("Downloading terrain… %1\$s of %2\$s", i, n) }
        terrain = tr0
        terrainStatus = null
        val msg = tr("The terrain could not be downloaded: the model is flat.")
        notices = if (tr0 == null) (notices + msg).distinct() else notices - msg
    }
    LaunchedEffect(pts.size) {
        if (pts.size < 2) return@LaunchedEffect
        mapStatus = tr("Downloading map data…")
        val f = runCatching { app.mapFeatures(Route3mf.area(pts), canBuildings) }.getOrNull()
        features = f ?: MapFeatures.NONE
        mapStatus = null
        if (f == null) notices = (notices + tr("The map data could not be downloaded: the model has no water, roads or buildings.")).distinct()
    }
    val options = Route3mf.Options(
        shape = shape, theme = theme, sizeMm = sizeMm, exaggeration = exaggeration, detail = detail, maxHeightMm = maxHeight, baseMm = baseMm,
        water = water, roads = roads, buildings = buildings && canBuildings, routeBySpeed = bySpeed && colours == Route3mf.Colours.ALL, colours = colours,
    )
    val ready = pts.size >= 2 && terrainStatus == null && mapStatus == null
    LaunchedEffect(ready, options, terrain, features, titleOn, coords, title) {
        if (!ready) return@LaunchedEffect
        delay(250) // quick option changes rebuild once
        building = true
        model = withContext(Dispatchers.Default) {
            val t = if (titleOn) textOutlines(title) else emptyList()
            val c = if (titleOn && coords) textOutlines(RouteGeometry.coordinates(pts.first().lat, pts.first().lon)) else emptyList()
            Route3mf.model(pts, options, terrain, features, t, c)
        }
        building = false
    }

    fun save() {
        val m = model ?: return
        val t = trip ?: return
        saving = true; error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val info = RouteInfo(title, "", t.start, t.end, t.distanceKm, t.fuelL, t.avgSpeed, t.maxSpeed, t.avgL100)
                    val dir = File(context.cacheDir, "reports").apply { mkdirs() }
                    File(dir, fileName(info) + ".3mf").also { f -> f.outputStream().buffered().use { Route3mf.write(m, it, title, "OpenOBD " + com.openobd.BuildConfig.VERSION_NAME) } }
                }
            }.onSuccess { shareFile(context, it, "model/3mf") }
                .onFailure { error = tr("Could not create the file: %1\$s", it.message ?: "") }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(tr("3D model"), onBack)
        if (trip == null) { CenteredMessage(null, tr("Trip not found.")); return@Column }
        if (points != null && pts.size < 2) { CenteredMessage(null, tr("This trip has no GPS route.")); return@Column }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(22.dp)).testTag("preview3d")) {
                ModelPreview(model, Modifier.fillMaxSize())
                val busyText = terrainStatus ?: mapStatus ?: if (building || model == null) tr("Preparing the model…") else null
                if (busyText != null) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(10.dp)) {
                    Text(busyText, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            Text(tr("Drag to turn, pinch to zoom."), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            notices.forEach { ErrorText(it) }
            model?.let { m ->
                val (w, d, h) = m.size()
                SectionCard(Modifier.testTag("modelSize")) {
                    Text(tr("Total height: %1\$s mm", "%.0f".format(h)), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Hint(tr("%1\$s × %2\$s mm footprint · base %3\$s mm + relief %4\$s mm, plus the route and markers on top.",
                        "%.0f".format(w), "%.0f".format(d), "%.0f".format(m.relief.baseMm), "%.0f".format(m.relief.reliefMm)))
                    if (m.relief.limited) Hint(tr("The relief was scaled down to fit the maximum height; raise it for steeper mountains."))
                    if (m.relief.trimmedM >= 20) Hint(tr("The lowest %1\$s m of ground (a valley or the sea at the edge) rests on the base, so the rest of the model needs less material.", "%.0f".format(m.relief.trimmedM)))
                }
            }

            SectionCard(title = tr("Look")) {
                Label("Colours")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Route3mf.Theme.entries.forEach { th ->
                        FilterChip(theme == th, { theme = th }, label = { Text(tr(th.label)) }, modifier = Modifier.testTag("theme_${th.name}"), leadingIcon = {
                            Row { listOf(th.low, th.mid, th.high, th.water).forEach { Box(Modifier.size(10.dp).background(swatch(it))) } }
                        })
                    }
                }
                Label("Filaments")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Route3mf.Colours.entries.forEach { c -> FilterChip(colours == c, { colours = c }, label = { Text(tr(c.label)) }, modifier = Modifier.testTag("colours_${c.name}")) }
                }
                Hint(tr(when (colours) {
                    Route3mf.Colours.ALL -> "One filament per colour of the style."
                    Route3mf.Colours.SIX -> "Roads, markers, buildings and title share colours with the base and the peaks."
                    Route3mf.Colours.FOUR -> "Base, lowlands, peaks and water; the route prints in the base colour, standing above the ground."
                    Route3mf.Colours.CMYKW -> "Load black, cyan, magenta, yellow and white. The ground becomes a fine pattern of the five that blends into every shade of the style, like a printed photo; best seen from arm's length, with a 0.4 mm nozzle or finer. Always uses high detail."
                    Route3mf.Colours.LANDSCAPE -> "Load brown, green, blue, black and white. Like CMYKW, the ground is a fine pattern of the five, but made for nature: deep greens for valleys and forests, browns for slopes, white peaks and blue water. Always uses high detail."
                }))
                Label("Shape")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Route3mf.Shape.entries.forEach { sh -> FilterChip(shape == sh, { shape = sh }, label = { Text(tr(sh.label)) }, modifier = Modifier.testTag("shape_${sh.name}")) }
                }
                Label("Size")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100.0, 150.0, 200.0).forEach { s -> FilterChip(sizeMm == s, { sizeMm = s }, label = { Text("${s.toInt()} mm") }) }
                }
                Label("Relief exaggeration")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(1.0, 1.5, 2.0, 3.0, 5.0).forEach { x -> FilterChip(exaggeration == x, { exaggeration = x }, label = { Text("×" + (if (x % 1.0 == 0.0) x.toInt().toString() else x.toString())) }) }
                }
                Label("Maximum height")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(20.0, 30.0, 45.0, 60.0).forEach { x -> FilterChip(maxHeight == x, { maxHeight = x }, label = { Text("${x.toInt()} mm") }, modifier = Modifier.testTag("maxHeight_${x.toInt()}")) }
                }
                Label("Base thickness")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2.0, 3.0, 5.0).forEach { x -> FilterChip(baseMm == x, { baseMm = x }, label = { Text("${x.toInt()} mm") }) }
                }
                Label("Detail")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Route3mf.Detail.entries.forEach { d -> FilterChip(detail == d, { detail = d }, label = { Text(tr(d.label)) }, modifier = Modifier.testTag("detail_${d.name}")) }
                }
            }

            SectionCard(title = tr("On the map")) {
                Toggle("Water", water, "layerWater") { water = it }
                Toggle("Roads", roads, "layerRoads") { roads = it }
                Toggle("Buildings", buildings && canBuildings, "layerBuildings", enabled = canBuildings) { buildings = it }
                if (!canBuildings) Hint(tr("Buildings are only added for areas up to %1\$s km across.", (Overpass.MAX_BUILDINGS_SPAN_M / 1000).toInt()))
                if (colours == Route3mf.Colours.ALL) Toggle("Route coloured by speed", bySpeed, "bySpeed") { bySpeed = it }
                Toggle("Title on the model", titleOn, "titleOn") { titleOn = it }
                if (titleOn) Toggle("GPS coordinates under the title", coords, "coordsSwitch") { coords = it }
                if (titleOn) Hint(tr("Title of every export. Change it with the trip's name.") + " " + title)
            }

            model?.let { m ->
                SectionCard(title = tr("Filaments")) {
                    Hint(tr("Bambu Studio, PrusaSlicer and Orca load each part and the painting and add these filaments, but with colours of their own: give each number the colour shown here."))
                    m.filaments.forEach { f ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("filament_${f.number}"), verticalAlignment = Alignment.CenterVertically) {
                            Text("${f.number}", fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
                            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(swatch(f.color)).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp)))
                            Spacer(Modifier.width(10.dp))
                            Text(f.roles.joinToString(", ") { tr(it) }, Modifier.weight(1f))
                            Text(f.color, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Hint(tr("%1\$s triangles", m.triangles))
                }
            }
            Button(onClick = { save() }, enabled = model != null && !building && !saving, modifier = Modifier.fillMaxWidth().testTag("make3d")) {
                Text(tr("Create 3MF file"))
            }
            error?.let { ErrorText(it) }
            Hint(tr(Terrarium.ATTRIBUTION) + " · " + tr(Overpass.ATTRIBUTION))
        }
    }
}

@Composable
private fun Label(text: String) = Text(tr(text), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(text: String, value: Boolean, tag: String, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(tr(text), Modifier.weight(1f), color = if (enabled) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(value, onChange, enabled = enabled, modifier = Modifier.testTag(tag))
    }
}

/** Translation keys used only by this screen. */
val route3dTexts = listOf(
    "3D model", "Look", "Colours", "Shape", "Size", "Relief exaggeration", "Detail", "On the map", "Water", "Roads", "Buildings",
    "Route coloured by speed", "Title on the model", "GPS coordinates under the title", "Filaments",
    "Bambu Studio, PrusaSlicer and Orca load each part and the painting and add these filaments, but with colours of their own: give each number the colour shown here.",
    "%1\$s triangles", "Create 3MF file", "Maximum height", "Base thickness", "Drag to turn, pinch to zoom.", "Preparing the model…",
    "Downloading map data…", "The terrain could not be downloaded: the model is flat.",
    "The map data could not be downloaded: the model has no water, roads or buildings.",
    "Buildings are only added for areas up to %1\$s km across.", "This trip has no GPS route.",
    "One filament per colour of the style.", "Roads, markers, buildings and title share colours with the base and the peaks.",
    "Base, lowlands, peaks and water; the route prints in the base colour, standing above the ground.",
    "Load brown, green, blue, black and white. Like CMYKW, the ground is a fine pattern of the five, but made for nature: deep greens for valleys and forests, browns for slopes, white peaks and blue water. Always uses high detail.",
    "Load black, cyan, magenta, yellow and white. The ground becomes a fine pattern of the five that blends into every shade of the style, like a printed photo; best seen from arm's length, with a 0.4 mm nozzle or finer. Always uses high detail.",
)
