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
            TripVideoMaker.encode(file, { w, h -> TripVideoRenderer(video, ground, area, w, h, "Test drive", "Demo", Units(), { it }, "") }, vertical, video) { last = it }
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
        ground.recycle()
    }
}
