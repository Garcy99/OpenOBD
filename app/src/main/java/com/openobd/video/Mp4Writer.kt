package com.openobd.video

import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.Closeable
import java.io.File

/**
 * Writes an H.264 MP4 from frames drawn on a canvas, with the phone's hardware encoder. Frames are stamped
 * 1 / [fps] apart however long they take to draw, so the video plays at its real speed.
 */
class Mp4Writer(file: File, val width: Int, val height: Int, private val fps: Int, bitrate: Int) : Closeable {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val surface: Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var started = false
    private var written = 0L
    private var finished = false

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
        } catch (e: Exception) {
            codec.release(); muxer.release(); throw e
        }
    }

    /** Draws one frame. */
    fun frame(draw: (Canvas) -> Unit) {
        val c = surface.lockHardwareCanvas()
        try { draw(c) } finally { surface.unlockCanvasAndPost(c) }
        drain(false)
    }

    /** Ends the video and closes the file. */
    fun finish() {
        if (finished) return
        finished = true
        codec.signalEndOfInputStream()
        drain(true)
        close()
    }

    override fun close() {
        runCatching { codec.stop() }; runCatching { codec.release() }
        runCatching { if (started) muxer.stop() }; runCatching { muxer.release() }
        runCatching { surface.release() }
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 10_000)
            when {
                i == MediaCodec.INFO_TRY_AGAIN_LATER -> { if (!end || ++idle > 300) return }
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start(); started = true }
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
        fun open(file: File, sizes: List<Pair<Int, Int>>, fps: Int): Mp4Writer {
            var last: Exception? = null
            for ((w, h) in sizes) {
                try { return Mp4Writer(file, w, h, fps, (w * h * fps * 0.2).toInt().coerceIn(2_000_000, 16_000_000)) } catch (e: Exception) { last = e }
            }
            throw last ?: IllegalStateException("No video encoder")
        }
    }
}
