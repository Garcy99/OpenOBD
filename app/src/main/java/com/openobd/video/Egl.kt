package com.openobd.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import java.io.Closeable

/**
 * An OpenGL ES 2 context drawing into the video encoder's [surface], or into memory (a pbuffer of [width] x
 * [height]) when [surface] is null. Made, used and closed on one thread.
 */
class EglCore(surface: Surface?, width: Int, height: Int) : Closeable {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val target: EGLSurface
    /** Whether the frames are smoothed at the edges (multisampling). */
    val multisampled: Boolean

    init {
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val v = IntArray(2)
        check(EGL14.eglInitialize(display, v, 0, v, 1)) { "EGL cannot start" }
        var chosen: Pair<EGLConfig, Boolean>? = null
        // Best first: edges smoothed and a fine depth buffer; then plainer ones.
        for ((samples, depth) in listOf(4 to 24, 0 to 24, 4 to 16, 0 to 16)) {
            val attrs = ArrayList<Int>()
            attrs += listOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, depth, EGL14.EGL_STENCIL_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, if (surface != null) EGL14.EGL_WINDOW_BIT else EGL14.EGL_PBUFFER_BIT)
            if (surface != null) attrs += listOf(EGL_RECORDABLE_ANDROID, 1)
            if (samples > 0) attrs += listOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, samples)
            attrs += EGL14.EGL_NONE
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            if (EGL14.eglChooseConfig(display, attrs.toIntArray(), 0, configs, 0, 1, count, 0) && count[0] > 0 && configs[0] != null) {
                chosen = configs[0]!! to (samples > 0); break
            }
        }
        val (config, ms) = chosen ?: run { EGL14.eglTerminate(display); error("No suitable EGL configuration") }
        multisampled = ms
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT) { EGL14.eglTerminate(display); error("No OpenGL ES 2 context") }
        target = if (surface != null) EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        else EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE), 0)
        if (target == EGL14.EGL_NO_SURFACE) { close(); error("No EGL surface (0x%x)".format(EGL14.eglGetError())) }
        if (!EGL14.eglMakeCurrent(display, target, target, context)) { close(); error("EGL cannot draw (0x%x)".format(EGL14.eglGetError())) }
    }

    /** Sends the frame drawn to the encoder, stamped [nanos]. */
    fun swap(nanos: Long) {
        EGLExt.eglPresentationTimeANDROID(display, target, nanos)
        EGL14.eglSwapBuffers(display, target)
    }

    override fun close() {
        runCatching { EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(display, target) }
        runCatching { EGL14.eglDestroyContext(display, context) }
        runCatching { EGL14.eglReleaseThread() }
        runCatching { EGL14.eglTerminate(display) }
    }

    companion object {
        private const val EGL_RECORDABLE_ANDROID = 0x3142
    }
}
