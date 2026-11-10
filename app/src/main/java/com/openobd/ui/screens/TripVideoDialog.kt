package com.openobd.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openobd.AppContainer
import com.openobd.core.garage.TrackPoint
import com.openobd.core.garage.TripRecord
import com.openobd.core.obd.UnitSystem
import com.openobd.core.trip.Mapterhorn
import com.openobd.core.trip.RoutePoster
import com.openobd.core.trip.TripVideo
import com.openobd.core.trip.Units
import com.openobd.ui.LocalApp
import com.openobd.ui.appSettings
import com.openobd.ui.components.ErrorText
import com.openobd.ui.shareFile
import com.openobd.ui.tr
import com.openobd.core.trip.DroneScene
import com.openobd.core.trip.DroneScript
import com.openobd.core.trip.Imagery
import com.openobd.core.trip.Overpass
import com.openobd.core.trip.Terrarium
import com.openobd.core.trip.Ground
import com.openobd.core.trip.LocalFrame
import com.openobd.core.trip.Terrain
import com.openobd.video.DroneRenderer
import com.openobd.video.EglCore
import com.openobd.video.Mp4Writer
import com.openobd.video.TripVideoHud
import com.openobd.video.TripVideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlin.math.cos

/** Makes the video of a trip: the 3D scene (ground, photos, the drone's flight), the frames and the MP4. */
object TripVideoMaker {
    /** Photo tiles for the sharp squares near the car, at most (a few MB). */
    private const val MAX_NEAR_TILES = 900

    /** How the last video was drawn: "3d", "3d-memory", "2d" or "2d-memory" (for tests and the debug log). */
    @Volatile var lastPath: String = ""

    /** Everything the drone needs besides the frames: what it flies over and what is shown on it. */
    class Scene(
        val area: DoubleArray,
        val script: DroneScript,
        /** Photo or relief of the whole area, or null (none chosen, or offline). */
        val global: Bitmap?,
        /** Sharper photo or relief of a window along the route (made while drawing), or null. */
        val detail: ((DroneScript.Window) -> Bitmap?)?,
        val shaded: Boolean,
        val credit: String,
        /** Buildings, trees and water along the route (null: none could be had). */
        val things: DroneScene? = null,
    )

    /**
     * Downloads what the video shows: the elevation of the area (the drone flies over the real hills, whatever the
     * background), the photo or relief of the whole area and the sharper photos along the route.
     */
    suspend fun prepare(app: AppContainer, points: List<TrackPoint>, ground: PosterGround, video: TripVideo, vertical: Boolean,
                        status: (String) -> Unit): Scene {
        val area = TripVideo.area(points, minPad = 0.03)
        status(tr("Downloading terrain…"))
        // Points well inside the area's corners: the download adds room around them, so the area is covered.
        val cLat = (area[0] + area[2]) / 2; val cLon = (area[1] + area[3]) / 2
        val hLat = (area[2] - area[0]) / 2 / 2.4; val hLon = (area[3] - area[1]) / 2 / 2.4
        val corners = listOf(points.first().copy(lat = cLat - hLat, lon = cLon - hLon), points.first().copy(lat = cLat + hLat, lon = cLon + hLon))
        val terrain = runCatching { app.terrain(points + corners, 4000) { i, n -> status(tr("Downloading terrain… %1\$s of %2\$s", i, n)) } }.getOrNull()
        val frame = LocalFrame(area)
        val script = withContext(Dispatchers.Default) { DroneScript(video, frame, Ground(frame, terrain), if (vertical) 9.0 / 16 else 16.0 / 9) }
        val (global, credit) = picture(app, area, terrain, ground, 2048, status)
        val pxSide = 2048.0
        val at = { w: DroneScript.Window -> { x: Double, y: Double -> frame.lat(w.y1 - y / pxSide * (w.y1 - w.y0)) to frame.lon(w.x0 + x / pxSide * (w.x1 - w.x0)) } }
        // The sharp squares near the car: about a metre a pixel, or two on long routes, while the photo tiles stay
        // within a sensible download; none on very long routes.
        val nearMpp = if (ground != PosterGround.SATELLITE) null else listOf(1.0, 2.0).firstOrNull { mpp ->
            val src = Imagery.sourceFor(frame.lat0, frame.lon0)
            script.nearWindows.flatMap { w ->
                val b = w.box(frame)
                val z = Imagery.zoomFor(b[0], b[1], b[2], b[3], mpp, maxZoom = src.maxZoom)
                Terrarium.tiles(b[0], b[1], b[2], b[3], z).map { Triple(z, it.first, it.second) }
            }.toSet().size <= MAX_NEAR_TILES
        }
        val mpp = { w: DroneScript.Window -> if (w.level == DroneScript.NEAR) nearMpp else w.side / 1600 }
        val detail: ((DroneScript.Window) -> Bitmap?)? = when {
            ground == PosterGround.SATELLITE && global != null -> {
                // Fetched now (they stay in the cache folder), put together while drawing.
                val all = script.windows + (if (nearMpp != null) script.nearWindows else emptyList())
                for ((i, w) in all.withIndex()) {
                    coroutineContext.ensureActive()
                    status(tr("Downloading aerial photos… %1\$s of %2\$s", i + 1, all.size))
                    runCatching { app.imagery(w.box(frame), mpp(w)!!) { _, _ -> } }
                }
                { w ->
                    mpp(w)?.let { m -> runBlocking { app.imagery(w.box(frame), m) { _, _ -> } } }
                        ?.let { im -> RoutePoster.photo(im, pxSide, pxSide, 1.0, at(w)) }?.let { (pw, ph, px) -> Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888) }
                }
            }
            ground == PosterGround.RELIEF && terrain != null && global != null -> { w ->
                RoutePoster.relief(terrain, pxSide, pxSide, 1.0, contours = false, at = at(w))?.let { (pw, ph, px) -> Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888) }
            }
            else -> null
        }
        // Buildings, woods and water in the same squares as the sharp photos.
        coroutineContext.ensureActive()
        status(tr("Downloading buildings and trees…"))
        val features = runCatching {
            app.scene(script.nearWindows.map { it.box(frame) }) { i, n -> status(tr("Downloading buildings and trees… %1\$s of %2\$s", i, n)) }
        }.getOrNull()?.takeIf { !it.isEmpty }
        val things = features?.let { f -> withContext(Dispatchers.Default) { runCatching { DroneScene(f, script.ground, script) }.getOrNull() } }
        val credits = listOfNotNull(credit.takeIf { it.isNotBlank() }, terrain?.attribution?.let { tr(it) }, things?.let { tr(Overpass.ATTRIBUTION) })
            .distinct().joinToString(" · ")
        return Scene(area, script, global, detail, ground == PosterGround.RELIEF, credits, things)
    }

    /** The relief or aerial photo of an area as a bitmap (null for none or when it cannot be had), and its credit. */
    private suspend fun picture(app: AppContainer, a: DoubleArray, terrain: Terrain?, ground: PosterGround, longest: Int, status: (String) -> Unit): Pair<Bitmap?, String> {
        if (ground == PosterGround.NONE) return null to ""
        val k = cos(Math.toRadians((a[0] + a[2]) / 2))
        val aspect = (a[2] - a[0]) / ((a[3] - a[1]) * k)
        val gw = if (aspect <= 1) longest else (longest / aspect).toInt()
        val gh = if (aspect <= 1) (longest * aspect).toInt() else longest
        val at = { x: Double, y: Double -> (a[2] - y / gh * (a[2] - a[0])) to (a[1] + x / gw * (a[3] - a[1])) }
        val raster = when (ground) {
            PosterGround.RELIEF -> {
                val t = terrain ?: return null to ""
                withContext(Dispatchers.Default) { RoutePoster.relief(t, gw.toDouble(), gh.toDouble(), 1.0, contours = false, at = at) }?.let { it to "" }
            }
            PosterGround.SATELLITE -> {
                val mpp = (a[3] - a[1]) * 111_320 * k / gw
                val im = app.imagery(a, mpp) { i, n -> status(tr("Downloading aerial photos… %1\$s of %2\$s", i, n)) } ?: return null to ""
                withContext(Dispatchers.Default) { RoutePoster.photo(im, gw.toDouble(), gh.toDouble(), 1.0, at) }?.let { it to im.attributions.joinToString(" · ") { c -> tr(c) } }
            }
            PosterGround.NONE -> null
        } ?: return null to ""
        val (w, h, px) = raster.first
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to raster.second
    }

    /**
     * Draws and encodes every frame into [file]; [progress] 0..1. The drone in 3D first ([scene]; OpenGL straight into
     * the encoder, else read back into memory); devices where OpenGL fails get the flat flight ([renderer]), drawn by
     * the GPU or, without a real one, in memory.
     */
    suspend fun encode(file: File, scene: Scene?, renderer: (Int, Int) -> TripVideoRenderer, hud: (Int, Int) -> TripVideoHud,
                       vertical: Boolean, video: TripVideo, progress: (Float) -> Unit) {
        val sizes = if (vertical) listOf(1080 to 1920, 720 to 1280) else listOf(1920 to 1080, 1280 to 720)
        if (scene != null) {
            val gl = Executors.newSingleThreadExecutor { r -> Thread(r, "video-gl") }.asCoroutineDispatcher()
            val done = try {
                withContext(gl) {
                    when {
                        writeGl(file, sizes, true, scene, hud, video, progress) -> { lastPath = "3d"; true }
                        writeGl(file, sizes, false, scene, hud, video, progress) -> { lastPath = "3d-memory"; true }
                        else -> false
                    }
                }
            } finally { gl.close() }
            if (done) return
        }
        withContext(Dispatchers.Default) {
            if (write(file, sizes, true, renderer, video, progress)) lastPath = "2d"
            else {
                file.delete()
                write(file, sizes, false, renderer, video, progress)
                lastPath = "2d-memory"
            }
        }
    }

    /** One 3D attempt; false when OpenGL is missing or fails, or (straight into the encoder) delivers nothing. */
    private suspend fun writeGl(file: File, sizes: List<Pair<Int, Int>>, direct: Boolean, scene: Scene, hud: (Int, Int) -> TripVideoHud,
                                video: TripVideo, progress: (Float) -> Unit): Boolean {
        file.delete()
        val writer = try { Mp4Writer.open(file, sizes, video.fps, direct) } catch (e: Exception) { return false }
        var egl: EglCore? = null
        var r: DroneRenderer? = null
        var ok = false
        try {
            egl = EglCore(if (direct) writer.inputSurface else null, writer.width, writer.height)
            r = DroneRenderer(scene.script, writer.width, writer.height, scene.global, scene.detail, scene.shaded, hud(writer.width, writer.height), scene.things)
            val px = if (direct) null else IntArray(writer.width * writer.height)
            for (f in 0 until video.frames) {
                coroutineContext.ensureActive()
                r.draw(f)
                if (px == null) { egl.swap(f * 1_000_000_000L / video.fps); writer.frameDrawn() }
                else { r.read(px); writer.frameArgb(px) }
                if (direct && f == 15 && !writer.producing(3000)) return false
                if (f % 10 == 0) progress(f.toFloat() / video.frames)
            }
            r.release(); r = null
            egl.close(); egl = null
            writer.finish()
            ok = true
            return true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.w("TripVideo", "3D video failed (${if (direct) "GPU" else "memory"}): $e")
            return false
        } finally {
            runCatching { r?.release() }
            runCatching { egl?.close() }
            if (!ok) { runCatching { writer.close() }; file.delete() }
        }
    }

    /** One flat attempt; false when the GPU path delivers nothing to the encoder. */
    private suspend fun write(file: File, sizes: List<Pair<Int, Int>>, gpu: Boolean, renderer: (Int, Int) -> TripVideoRenderer,
                              video: TripVideo, progress: (Float) -> Unit): Boolean {
        val writer = Mp4Writer.open(file, sizes, video.fps, gpu)
        try {
            val r = renderer(writer.width, writer.height)
            for (f in 0 until video.frames) {
                coroutineContext.ensureActive()
                writer.frame { c -> r.draw(c, f) }
                if (gpu && f == 15 && !writer.producing(3000)) { writer.close(); return false }
                if (f % 10 == 0) progress(f.toFloat() / video.frames)
            }
            writer.finish()
            return true
        } catch (e: Exception) {
            writer.close(); file.delete(); throw e
        }
    }
}

/** The video of a drive: options, progress and sharing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripVideoDialog(trip: TripRecord, points: List<TrackPoint>, car: String, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = appSettings()
    val units = Units(imperial = settings.units == UnitSystem.IMPERIAL)
    var ground by remember { mutableStateOf(PosterGround.SATELLITE) }
    var vertical by remember { mutableStateOf(true) }
    var seconds by remember { mutableIntStateOf(30) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun make() {
        busy = true; error = null; progress = 0f
        job = scope.launch {
            runCatching {
                val video = TripVideo(points, seconds.toDouble())
                val scene = TripVideoMaker.prepare(app, points, ground, video, vertical) { status = it }
                val bmp = scene.global
                if (bmp == null && ground != PosterGround.NONE) error = tr(if (ground == PosterGround.SATELLITE) "The aerial photos could not be downloaded: exported without them."
                    else "The terrain could not be downloaded: exported without relief.")
                val title = trip.name.ifBlank { tr("Drive on %1\$s", DateFormat.getDateInstance(DateFormat.LONG).format(Date(trip.start))) }
                val subtitle = listOf(car, units.distance(trip.distanceKm)).filter { it.isNotBlank() }.joinToString("  ·  ")
                val dir = File(context.cacheDir, "reports").apply { mkdirs() }
                val file = File(dir, com.openobd.core.trip.fileName(com.openobd.core.trip.RouteInfo(title, car, trip.start, trip.end, trip.distanceKm)) + ".mp4")
                status = null
                try {
                    TripVideoMaker.encode(file, scene, { w, h ->
                        TripVideoRenderer(video, bmp, scene.area, w, h, title, subtitle, units, { tr(it) }, scene.credit)
                    }, { w, h -> TripVideoHud(video, w, h, title, subtitle, units, scene.credit) }, vertical, video) { p -> progress = p }
                } finally { bmp?.recycle() }
                file
            }.onSuccess { shareFile(context, it, "video/mp4"); if (error == null) onDismiss() }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) error = tr("This phone cannot make videos.") + " (" + (it.message ?: it.javaClass.simpleName) + ")" }
            busy = false; status = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(tr("Video of the drive (MP4)")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("The drive as a short film: a drone follows the car over the hills in 3D, with aerial photos or relief, and its speed, engine speed and height. To share or keep."),
                    style = MaterialTheme.typography.bodySmall)
                Text(tr("Background"), fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PosterGround.entries.forEach { g -> FilterChip(ground == g, { ground = g }, label = { Text(tr(g.label)) }, enabled = !busy, modifier = Modifier.testTag("videoGround_${g.name}")) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vertical, { vertical = true }, label = { Text(tr("Vertical (phone)")) }, enabled = !busy)
                    FilterChip(!vertical, { vertical = false }, label = { Text(tr("Horizontal")) }, enabled = !busy)
                }
                Text(tr("Length"), fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { s -> FilterChip(seconds == s, { seconds = s }, label = { Text(tr("%1\$s s", s)) }, enabled = !busy) }
                }
                if (busy) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(status ?: tr("Making the video… %1\$s %%", (progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                }
                error?.let { ErrorText(it) }
                Text(tr(Mapterhorn.ATTRIBUTION), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = ::make, enabled = !busy, modifier = Modifier.testTag("makeVideo")) { Text(tr("Make the video")) } },
        dismissButton = { TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(tr(if (busy) "Cancel" else "Close")) } },
    )
}
