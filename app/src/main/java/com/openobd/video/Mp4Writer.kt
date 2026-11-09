package com.openobd.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.Closeable
import java.io.File

/**
 * Writes an H.264 MP4 from frames drawn on a canvas, with the phone's encoder. Frames are stamped 1 / [fps] apart
 * however long they take to draw, so the video plays at its real speed.
 *
 * Normally frames are drawn by the GPU straight into the encoder ([surfaceInput]). Devices without a real GPU
 * (some car head units, test emulators) never deliver those frames, so they draw in memory instead and hand the
 * encoder the pictures in YUV ([surfaceInput] false), slower but everywhere.
 */
class Mp4Writer(file: File, val width: Int, val height: Int, private val fps: Int, bitrate: Int, val surfaceInput: Boolean = true) : Closeable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface?
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    /** Frames written to the file so far. */
    var written = 0L
        private set
    private var queued = 0L
    private var finished = false
    private val bitmap: Bitmap? = if (surfaceInput) null else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val argb: IntArray? = if (surfaceInput) null else IntArray(width * height)

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT,
                if (surfaceInput) MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface else MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = if (surfaceInput) codec.createInputSurface() else null
            codec.start()
        } catch (e: Exception) {
            codec.release(); muxer.release(); bitmap?.recycle(); throw e
        }
    }

    /** Draws one frame. */
    fun frame(draw: (Canvas) -> Unit) {
        if (surface != null) {
            val c = surface.lockHardwareCanvas()
            try { draw(c) } finally { surface.unlockCanvasAndPost(c) }
        } else {
            val bmp = bitmap!!
            draw(Canvas(bmp))
            bmp.getPixels(argb!!, 0, width, 0, 0, width, height)
            queueYuv(argb, false)
        }
        drain(false)
    }

    /** Hands one picture to the encoder in its YUV layout (whatever its strides), or the end of the stream. */
    private fun queueYuv(px: IntArray?, end: Boolean) {
        var index: Int
        do { index = codec.dequeueInputBuffer(10_000); if (index < 0) drain(false) } while (index < 0)
        val pts = queued++ * 1_000_000L / fps
        if (end || px == null) { codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM); return }
        val image = codec.getInputImage(index) ?: throw IllegalStateException("The video encoder gave no picture buffer")
        val (yP, uP, vP) = image.planes.let { Triple(it[0], it[1], it[2]) }
        val y = yP.buffer; val u = uP.buffer; val v = vP.buffer
        for (row in 0 until height) {
            val base = row * width
            for (col in 0 until width) {
                val p = px[base + col]
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                y.put(row * yP.rowStride + col * yP.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (row % 2 == 0 && col % 2 == 0) {
                    val cu = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cv = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    u.put((row / 2) * uP.rowStride + (col / 2) * uP.pixelStride, cu.toByte())
                    v.put((row / 2) * vP.rowStride + (col / 2) * vP.pixelStride, cv.toByte())
                }
            }
        }
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
    }

    /** True once the encoder has turned frames into video (waiting up to [timeoutMs] for it). */
    fun producing(timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (written == 0L && System.currentTimeMillis() < end) drain(false)
        return written > 0
    }

    /** Ends the video and closes the file. */
    fun finish() {
        if (finished) return
        finished = true
        try {
            if (surface != null) codec.signalEndOfInputStream() else queueYuv(null, true)
            drain(true)
            check(written > 0) { "The video encoder produced no frames" }
            runCatching { codec.stop() }; runCatching { codec.release() }
            // Writes the index of the file: without it no player can open the video, so a failure here is reported.
            muxer.stop()
        } finally {
            runCatching { muxer.release() }; runCatching { surface?.release() }; runCatching { codec.release() }; bitmap?.recycle()
        }
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        runCatching { surface?.release() }
        bitmap?.recycle()
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 10_000)
            when {
                // At the end, wait for the encoder to finish (a software encoder can take seconds).
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++idle > 2000) return }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { if (!started) { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true } }
                i >= 0 -> {
                    val buf = codec.getOutputBuffer(i)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started && buf != null) {
                        info.presentationTimeUs = written++ * 1_000_000L / fps
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        /** A writer for the largest of [sizes] the phone's encoder takes (some refuse 1080 lines). */
        fun open(file: File, sizes: List<Pair<Int, Int>>, fps: Int, surfaceInput: Boolean = true): Mp4Writer {
            var last: Exception? = null
            for ((w, h) in sizes) {
                try { return Mp4Writer(file, w, h, fps, (w * h * fps * 0.2).toInt().coerceIn(2_000_000, 16_000_000), surfaceInput) } catch (e: Exception) { last = e }
            }
            throw last ?: IllegalStateException("No video encoder")
        }
    }
}
