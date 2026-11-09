package com.openobd

import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openobd.core.garage.TrackPoint
import com.openobd.core.trip.TripVideo
import com.openobd.core.trip.Units
import com.openobd.ui.screens.TripVideoMaker
import com.openobd.video.TripVideoHud
import com.openobd.video.TripVideoRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The phone's own video encoder turns the drawn frames into a real MP4 that plays at the right speed. */
@RunWith(AndroidJUnit4::class)
class TripVideoDeviceTest {
    @Test
    fun encodesAPlayableMp4() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pts = (0 until 120).map { i ->
            val a = i / 120.0 * 2 * Math.PI
            TrackPoint(1_700_000_000_000L + i * 2000L, 40.41 + 0.01 * Math.sin(a), -3.70 + 0.015 * Math.cos(a), 30f + i % 60, rpm = 1500f, alt = 650f + i)
        }
        val video = TripVideo(pts, seconds = 3.0, fps = 30)
        val area = TripVideo.area(pts)
        // A ground with detail, like a relief or photo (a flat colour would compress to almost nothing).
        val rnd = java.util.Random(1)
        val ground = Bitmap.createBitmap(IntArray(400 * 300) { i ->
            val x = i % 400; val y = i / 400
            val v = (60 + (x * 7 + y * 3) % 90 + rnd.nextInt(40)).coerceIn(0, 255)
            (0xFF shl 24) or ((v / 2) shl 16) or (v shl 8) or (v / 3)
        }, 400, 300, Bitmap.Config.ARGB_8888)
        val file = File(context.cacheDir, "test-video.mp4").apply { delete() }
        var last = 0f
        for (vertical in listOf(true, false)) {
            file.delete()
            TripVideoMaker.encode(file, null, { w, h -> TripVideoRenderer(video, ground, area, w, h, "Test drive", "Demo", Units(), { it }, "") },
                { w, h -> TripVideoHud(video, w, h, "Test drive", "Demo", Units(), "") }, vertical, video) { last = it }
            assertTrue("video written: ${file.length()} bytes", file.length() > 2_000)
            val ex = MediaExtractor().apply { setDataSource(file.path) }
            assertEquals("tracks in a ${file.length()}-byte file", 1, ex.trackCount)
            val f = ex.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, f.getString(MediaFormat.KEY_MIME))
            val w = f.getInteger(MediaFormat.KEY_WIDTH); val h = f.getInteger(MediaFormat.KEY_HEIGHT)
            assertTrue("orientation $w x $h", if (vertical) h > w else w > h)
            // Every frame, 1/30 s apart: 3 s whatever the time it took to draw.
            ex.selectTrack(0)
            var frames = 0; var lastUs = 0L
            while (ex.sampleTime >= 0) { frames++; lastUs = ex.sampleTime; ex.advance() }
            ex.release()
            assertTrue("frames $frames", frames >= 85)
            assertTrue("duration $lastUs", lastUs in 2_800_000L..3_100_000L)
        }
        assertTrue(last > 0.5f)
        // The path for devices without a real GPU: drawn in memory, handed to the encoder in YUV.
        file.delete()
        val w = com.openobd.video.Mp4Writer.open(file, listOf(720 to 1280), 30, surfaceInput = false)
        val r = TripVideoRenderer(video, ground, area, w.width, w.height, "Test drive", "Demo", Units(), { it }, "")
        for (f in 0 until 30) w.frame { c -> r.draw(c, f * 3) }
        w.finish()
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        assertEquals("tracks (memory path) in a ${file.length()}-byte file", 1, ex.trackCount)
        ex.selectTrack(0)
        var frames = 0
        while (ex.sampleTime >= 0) { frames++; ex.advance() }
        ex.release()
        assertTrue("frames (memory path) $frames", frames >= 28)
        ground.recycle()
    }

    /**
     * The drone in 3D over hills: OpenGL draws into the encoder (or, where it cannot, into memory), and the MP4
     * plays. A few frames are kept as pictures (files/video-frames) to look at.
     */
    @Test
    fun dronePlaysIn3d() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pts = (0 until 160).map { i ->
            val a = i / 160.0 * 1.6 * Math.PI
            TrackPoint(1_700_000_000_000L + i * 2000L, 40.95 + 0.012 * Math.sin(a), -4.12 + 0.02 * Math.cos(a) + i * 0.0001, 40f + i % 70, rpm = 1800f, alt = 1000f + i)
        }
        // Hills: two ridges and a valley, so the drone has to climb.
        val terrain = com.openobd.core.trip.Terrain { lat, lon -> 1000 + 160 * Math.sin(lat * 250) * Math.cos(lon * 200) + 25 * Math.sin(lon * 700) }
        val area = TripVideo.area(pts, minPad = 0.03)
        val frame = com.openobd.core.trip.LocalFrame(area)
        val video = TripVideo(pts, seconds = 4.0, fps = 30)
        val script = com.openobd.core.trip.DroneScript(video, frame, com.openobd.core.trip.Ground(frame, terrain), 9.0 / 16)
        // A photo-like ground: fields in patches.
        fun patches(w: Int, h: Int, seed: Int): Bitmap {
            val rnd = java.util.Random(seed.toLong())
            return Bitmap.createBitmap(IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                val field = ((x / 37) * 31 + (y / 29) * 17) % 7
                val v = 70 + field * 18 + rnd.nextInt(30)
                (0xFF shl 24) or ((v * 0.8).toInt() shl 16) or (v shl 8) or (v / 2)
            }, w, h, Bitmap.Config.ARGB_8888)
        }
        val ground = patches(1024, 1024, 2)
        val scene = TripVideoMaker.Scene(area, script, ground, { w -> patches(512, 512, w.index) }, false, "Test credit")
        val file = File(context.cacheDir, "test-drone.mp4").apply { delete() }
        TripVideoMaker.encode(file, scene, { w, h -> TripVideoRenderer(video, ground, area, w, h, "Drone", "Demo", Units(), { it }, "") },
            { w, h -> TripVideoHud(video, w, h, "Drone", "Demo", Units(), "") }, true, video) { }
        val path = TripVideoMaker.lastPath
        assertTrue("drawn in 3D: $path", path.startsWith("3d"))
        val ex = MediaExtractor().apply { setDataSource(file.path) }
        assertEquals(1, ex.trackCount)
        ex.selectTrack(0)
        var frames = 0
        while (ex.sampleTime >= 0) { frames++; ex.advance() }
        ex.release()
        assertTrue("frames $frames ($path)", frames >= 115)
        // Pictures to look at: the opening, the chase and the end.
        val out = File(context.getExternalFilesDir(null), "video-frames").apply { mkdirs() }
        val mmr = android.media.MediaMetadataRetriever().apply { setDataSource(file.path) }
        for (t in listOf(300_000L, 2_000_000L, 3_000_000L)) {
            val bmp = mmr.getFrameAtTime(t, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: continue
            val small = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            File(out, "api${android.os.Build.VERSION.SDK_INT}-$path-${t / 1000}ms.jpg").outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            // Not a blank picture: the ground and the sky differ.
            val px = IntArray(small.width * small.height).also { small.getPixels(it, 0, small.width, 0, 0, small.width, small.height) }
            assertTrue("varied picture at $t", px.distinct().size > 500)
        }
        mmr.release()
        ground.recycle()
    }
}
