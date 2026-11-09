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
import com.openobd.video.Mp4Writer
import com.openobd.video.TripVideoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.coroutineContext
import kotlin.math.cos

/** Makes the video of a trip: the map under it, the frames and the MP4. */
object TripVideoMaker {
    /**
     * The relief or aerial photo of the video's area as a bitmap (null for none or when it cannot be downloaded),
     * and its credit.
     */
    suspend fun ground(app: AppContainer, points: List<TrackPoint>, ground: PosterGround, status: (String) -> Unit): Pair<Bitmap?, String> {
        if (ground == PosterGround.NONE) return null to ""
        val a = TripVideo.area(points)
        val k = cos(Math.toRadians((a[0] + a[2]) / 2))
        val aspect = (a[2] - a[0]) / ((a[3] - a[1]) * k)
        val longest = 2200
        val gw = if (aspect <= 1) longest else (longest / aspect).toInt()
        val gh = if (aspect <= 1) (longest * aspect).toInt() else longest
        val at = { x: Double, y: Double -> (a[2] - y / gh * (a[2] - a[0])) to (a[1] + x / gw * (a[3] - a[1])) }
        val raster = when (ground) {
            PosterGround.RELIEF -> {
                status(tr("Downloading terrain…"))
                val t = app.terrain(points, 4000) { i, n -> status(tr("Downloading terrain… %1\$s of %2\$s", i, n)) } ?: return null to ""
                withContext(Dispatchers.Default) { RoutePoster.relief(t, gw.toDouble(), gh.toDouble(), 1.0, at = at) }?.let { it to t.attribution }
            }
            PosterGround.SATELLITE -> {
                val mpp = (a[3] - a[1]) * 111_320 * k / gw
                val im = app.imagery(a, mpp) { i, n -> status(tr("Downloading aerial photos… %1\$s of %2\$s", i, n)) } ?: return null to ""
                withContext(Dispatchers.Default) { RoutePoster.photo(im, gw.toDouble(), gh.toDouble(), 1.0, at) }?.let { it to im.attributions.joinToString(" · ") { c -> tr(c) } }
            }
            PosterGround.NONE -> null
        } ?: return null to ""
        val (w, h, px) = raster.first
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888) to tr(raster.second)
    }

    /**
     * Draws and encodes every frame into [file]; [progress] 0..1. Tries the GPU path first; when the encoder gets
     * nothing from it (devices without a real GPU), starts again drawing in memory.
     */
    suspend fun encode(file: File, renderer: (Int, Int) -> TripVideoRenderer, vertical: Boolean, video: TripVideo, progress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        val sizes = if (vertical) listOf(1080 to 1920, 720 to 1280) else listOf(1920 to 1080, 1280 to 720)
        if (!write(file, sizes, true, renderer, video, progress)) {
            file.delete()
            write(file, sizes, false, renderer, video, progress)
        }
    }

    /** One attempt; false when the GPU path delivers nothing to the encoder. */
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
                val (bmp, credit) = TripVideoMaker.ground(app, points, ground) { status = it }
                if (bmp == null && ground != PosterGround.NONE) error = tr(if (ground == PosterGround.SATELLITE) "The aerial photos could not be downloaded: exported without them."
                    else "The terrain could not be downloaded: exported without relief.")
                val video = TripVideo(points, seconds.toDouble())
                val title = trip.name.ifBlank { tr("Drive on %1\$s", DateFormat.getDateInstance(DateFormat.LONG).format(Date(trip.start))) }
                val subtitle = listOf(car, units.distance(trip.distanceKm)).filter { it.isNotBlank() }.joinToString("  ·  ")
                val dir = File(context.cacheDir, "reports").apply { mkdirs() }
                val file = File(dir, com.openobd.core.trip.fileName(com.openobd.core.trip.RouteInfo(title, car, trip.start, trip.end, trip.distanceKm)) + ".mp4")
                status = null
                TripVideoMaker.encode(file, { w, h ->
                    TripVideoRenderer(video, bmp, TripVideo.area(points), w, h, title, subtitle, units, { tr(it) }, credit)
                }, vertical, video) { p -> progress = p }
                bmp?.recycle()
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
                Text(tr("The drive as a short film: a flight over the relief or the aerial photos following the car, with its speed, engine speed and height. To share or keep."),
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
                if (ground == PosterGround.RELIEF) Text(tr(Mapterhorn.ATTRIBUTION), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = ::make, enabled = !busy, modifier = Modifier.testTag("makeVideo")) { Text(tr("Make the video")) } },
        dismissButton = { TextButton(onClick = { job?.cancel(); onDismiss() }) { Text(tr(if (busy) "Cancel" else "Close")) } },
    )
}
