package com.openobd.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.openobd.core.trip.DroneScript
import com.openobd.core.trip.RouteGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the frames of the drone video with OpenGL ES 2: the ground in 3D (the elevation model) dressed with aerial
 * photos or relief, sharper in windows along the route ([detail]), the route draped on it and coloured by speed as
 * it is driven, the car, the sky and the haze of the distance, and the [hud] over it all. Made, used and released on
 * the thread of its OpenGL context.
 *
 * World coordinates are the script's (x east, y north, z up, metres); OpenGL's are x east, y up, z south.
 */
class DroneRenderer(
    private val script: DroneScript,
    val width: Int,
    val height: Int,
    /** Photo or relief of the whole area (null: the ground is coloured by height). */
    global: Bitmap?,
    /** Sharper photo or relief of one window along the route, or null (made on demand, on this thread). */
    private val detail: ((DroneScript.Window) -> Bitmap?)?,
    /** True when the ground pictures already carry their own shading (relief), so the light is gentler. */
    private val shadedPictures: Boolean,
    private val hud: TripVideoHud,
) {
    private val ground = script.ground
    private val frame = script.frame
    private val terrainProgram = program(TERRAIN_VS, TERRAIN_FS)
    private val ribbonProgram = program(RIBBON_VS, RIBBON_FS)
    private val carProgram = program(CAR_VS, CAR_FS)
    private val quadProgram = program(QUAD_VS, QUAD_FS)
    private val maxTexture = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0].coerceAtLeast(1024)
    private val anisotropy: Float = if (GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_texture_filter_anisotropic") == true)
        FloatArray(1).also { GLES20.glGetFloatv(0x84FF, it, 0) }[0].coerceIn(1f, 8f) else 1f

    private class Mesh(val vbo: Int, val ibo: Int, val count: Int)
    private val globalMesh = gridMesh(frame.minX, frame.minY, frame.maxX, frame.maxY, GLOBAL_GRID, skirt = true)
    private val windowIndices = gridIndices(WINDOW_GRID)
    private val globalTexture: Int = global?.let { upload(it) } ?: 0
    private val maxHeight = (0 until 64).maxOf { i -> (0 until 64).maxOf { j ->
        ground.at(frame.minX + (frame.maxX - frame.minX) * i / 63, frame.minY + (frame.maxY - frame.minY) * j / 63) } }.coerceAtLeast(50.0)

    /** Windows drawn lately: their mesh and picture, the oldest let go first. */
    private val windows = LinkedHashMap<Int, Pair<Mesh, Int>>()

    private val route = script.drapedRoute()
    private val ribbon: Int
    private val ribbonCount: Int
    private val hudBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val hudCanvas = Canvas(hudBitmap)
    private val hudTexture: Int
    private val quad: Int

    private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16)
    private val bandColors = RouteGeometry.bandColors.map { Color.parseColor(it) }.flatMap { listOf(Color.red(it) / 255f, Color.green(it) / 255f, Color.blue(it) / 255f) }.toFloatArray()
    private val u = min(width, height) / 1080f
    private val pixels: ByteBuffer by lazy { ByteBuffer.allocateDirect(width * height * 4) }

    init {
        // The route: a strip two vertices wide, each with its side and how far along the route it is.
        val n = route.size
        val data = FloatArray(n * 2 * 8)
        for (i in 0 until n) {
            val a = route[max(0, i - 1)]; val b = route[min(n - 1, i + 1)]
            var dx = b[0] - a[0]; var dy = b[1] - a[1]
            val len = sqrt(dx * dx + dy * dy).takeIf { it > 1e-6 } ?: 1.0
            dx /= len; dy /= len
            // Sideways in OpenGL's x / z (z points south).
            val nx = (-dy).toFloat(); val nz = (-dx).toFloat()
            for (side in 0..1) {
                val o = (i * 2 + side) * 8
                data[o] = route[i][0].toFloat(); data[o + 1] = route[i][2].toFloat(); data[o + 2] = (-route[i][1]).toFloat()
                data[o + 3] = nx; data[o + 4] = nz; data[o + 5] = if (side == 0) -1f else 1f
                data[o + 6] = route[i][3].toFloat(); data[o + 7] = route[i][4].toFloat()
            }
        }
        ribbon = buffer(GLES20.GL_ARRAY_BUFFER, floats(data), data.size * 4)
        ribbonCount = n * 2
        quad = buffer(GLES20.GL_ARRAY_BUFFER, floats(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)), 8 * 4)
        hudTexture = texture()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, hudBitmap, 0)
    }

    fun draw(f: Int) {
        val cam = script.camera(f)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(HAZE[0], HAZE[1], HAZE[2], 1f)
        GLES20.glClearStencil(0)
        GLES20.glDepthMask(true)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_STENCIL_BUFFER_BIT)
        Matrix.perspectiveM(proj, 0, cam.fovY.toFloat(), width.toFloat() / height, cam.near.toFloat(), cam.far.toFloat())
        Matrix.setLookAtM(view, 0, cam.eyeX.toFloat(), cam.eyeZ.toFloat(), (-cam.eyeY).toFloat(),
            cam.targetX.toFloat(), cam.targetZ.toFloat(), (-cam.targetY).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        sky(cam)
        terrain(f, cam)
        route(cam)
        car(cam)
        overlay(cam.shot)
    }

    /** The frame just drawn, as ARGB pixels from the top row (for encoders fed from memory). */
    fun read(out: IntArray) {
        val buf = pixels.also { it.clear() }
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val ints = buf.order(ByteOrder.BIG_ENDIAN).asIntBuffer().also { it.position(0) }
        for (y in 0 until height) {
            val row = (height - 1 - y) * width
            for (x in 0 until width) {
                val rgba = ints.get(row + x)
                out[y * width + x] = (rgba ushr 8) or (0xFF shl 24)
            }
        }
    }

    fun release() {
        val textures = (windows.values.map { it.second } + listOf(globalTexture, hudTexture)).filter { it != 0 }.toIntArray()
        GLES20.glDeleteTextures(textures.size, textures, 0)
        val buffers = (windows.values.flatMap { listOf(it.first.vbo) } + listOf(globalMesh.vbo, globalMesh.ibo, windowIndices, ribbon, quad)).toIntArray()
        GLES20.glDeleteBuffers(buffers.size, buffers, 0)
        windows.clear()
        listOf(terrainProgram, ribbonProgram, carProgram, quadProgram).forEach { GLES20.glDeleteProgram(it) }
        hudBitmap.recycle()
    }

    // ------------------------------------------------------------------ parts of the frame

    private fun sky(cam: DroneScript.Camera) {
        // Where the horizon straight ahead falls on the screen.
        val dx = (cam.targetX - cam.eyeX).toFloat(); val dz = (-(cam.targetY - cam.eyeY)).toFloat()
        val clip = FloatArray(4)
        Matrix.multiplyMV(clip, 0, mvp, 0, floatArrayOf(dx, 0f, dz, 0f), 0)
        val horizon = if (clip[3] > 1e-6f) (clip[1] / clip[3]).coerceIn(-3f, 3f) else -3f
        GLES20.glDisable(GLES20.GL_DEPTH_TEST); GLES20.glDisable(GLES20.GL_STENCIL_TEST); GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(quadProgram)
        uniform(quadProgram, "uMode") { GLES20.glUniform1f(it, 0f) }
        uniform(quadProgram, "uHorizon") { GLES20.glUniform1f(it, horizon) }
        uniform(quadProgram, "uHaze") { GLES20.glUniform3fv(it, 1, HAZE, 0) }
        uniform(quadProgram, "uZenith") { GLES20.glUniform3fv(it, 1, ZENITH, 0) }
        drawQuad()
    }

    private fun terrain(f: Int, cam: DroneScript.Camera) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LEQUAL); GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE); GLES20.glCullFace(GLES20.GL_BACK); GLES20.glFrontFace(GLES20.GL_CCW)
        // Each layer only where none before it was drawn (the sharp windows first, the whole area after), but over
        // itself freely: layer k marks its pixels 250 - k and may draw where the mark is 0 or its own.
        GLES20.glEnable(GLES20.GL_STENCIL_TEST)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)
        GLES20.glStencilMask(0xFF)
        var layer = 250
        GLES20.glUseProgram(terrainProgram)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(terrainProgram, "uMvp"), 1, false, mvp, 0)
        uniform(terrainProgram, "uEye") { GLES20.glUniform3f(it, cam.eyeX.toFloat(), cam.eyeZ.toFloat(), (-cam.eyeY).toFloat()) }
        uniform(terrainProgram, "uFog") { GLES20.glUniform2f(it, cam.fogStart.toFloat(), cam.fogEnd.toFloat()) }
        uniform(terrainProgram, "uHaze") { GLES20.glUniform3fv(it, 1, HAZE, 0) }
        uniform(terrainProgram, "uGlobal") { GLES20.glUniform4f(it, frame.minX.toFloat(), frame.minY.toFloat(), frame.maxX.toFloat(), frame.maxY.toFloat()) }
        uniform(terrainProgram, "uMaxHeight") { GLES20.glUniform1f(it, maxHeight.toFloat()) }
        uniform(terrainProgram, "uLightMix") { GLES20.glUniform2f(it, if (shadedPictures) 0.82f else 0.68f, if (shadedPictures) 0.25f else 0.42f) }
        val l = floatArrayOf(-0.45f, 0.75f, -0.48f).let { v -> val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / n, v[1] / n, v[2] / n) }
        uniform(terrainProgram, "uLight") { GLES20.glUniform3fv(it, 1, l, 0) }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, globalTexture)
        uniform(terrainProgram, "uGlobalTex") { GLES20.glUniform1i(it, 1) }
        uniform(terrainProgram, "uGlobalOk") { GLES20.glUniform1f(it, if (globalTexture != 0) 1f else 0f) }
        uniform(terrainProgram, "uTex") { GLES20.glUniform1i(it, 0) }
        if (detail != null) for (w in script.windowsAt(f)) {
            val (mesh, tex) = window(w) ?: continue
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            uniform(terrainProgram, "uMode") { GLES20.glUniform1f(it, 1f) }
            uniform(terrainProgram, "uWindow") { GLES20.glUniform4f(it, w.x0.toFloat(), w.y0.toFloat(), w.x1.toFloat(), w.y1.toFloat()) }
            GLES20.glStencilFunc(GLES20.GL_GEQUAL, layer--, 0xFF)
            drawMesh(mesh)
        }
        uniform(terrainProgram, "uMode") { GLES20.glUniform1f(it, if (globalTexture != 0) 0f else 2f) }
        GLES20.glStencilFunc(GLES20.GL_GEQUAL, 100, 0xFF)
        drawMesh(globalMesh)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun route(cam: DroneScript.Camera) {
        GLES20.glUseProgram(ribbonProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(false)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(ribbonProgram, "uMvp"), 1, false, mvp, 0)
        uniform(ribbonProgram, "uBands") { GLES20.glUniform3fv(it, bandColors.size / 3, bandColors, 0) }
        uniform(ribbonProgram, "uDone") { GLES20.glUniform1f(it, cam.doneMetres.toFloat()) }
        uniform(ribbonProgram, "uLift") { GLES20.glUniform1f(it, max(1.5, cam.distance * 0.006).toFloat()) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ribbon)
        val pos = attrib(ribbonProgram, "aPos", 3, 8, 0)
        val nrm = attrib(ribbonProgram, "aSide", 3, 8, 3)
        val along = attrib(ribbonProgram, "aAlong", 2, 8, 6)
        // A dark edge first, then the line itself.
        for (pass in 0..1) {
            val half = cam.distance * (if (pass == 0) 0.0105 else 0.0068) * (min(width, height) / 1080.0).coerceAtLeast(0.6)
            uniform(ribbonProgram, "uHalfWidth") { GLES20.glUniform1f(it, half.toFloat()) }
            uniform(ribbonProgram, "uOutline") { GLES20.glUniform1f(it, if (pass == 0) 1f else 0f) }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, ribbonCount)
        }
        listOf(pos, nrm, along).forEach { if (it >= 0) GLES20.glDisableVertexAttribArray(it) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDepthMask(true)
    }

    private fun car(cam: DroneScript.Camera) {
        GLES20.glUseProgram(carProgram)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(carProgram, "uMvp"), 1, false, mvp, 0)
        val band = RouteGeometry.band(script.video.pointAt(cam.shot).speedKmh)
        uniform(carProgram, "uColor") { GLES20.glUniform3f(it, bandColors[band * 3], bandColors[band * 3 + 1], bandColors[band * 3 + 2]) }
        uniform(carProgram, "uSize") { GLES20.glUniform1f(it, 46f * u) }
        val lift = max(2.0, cam.distance * 0.01)
        val p = floats(floatArrayOf(cam.carX.toFloat(), (cam.carZ + lift).toFloat(), (-cam.carY).toFloat()))
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        val a = GLES20.glGetAttribLocation(carProgram, "aPos")
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, 3, GLES20.GL_FLOAT, false, 0, p)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, 1)
        GLES20.glDisableVertexAttribArray(a)
    }

    private fun overlay(shot: com.openobd.core.trip.TripVideo.Shot) {
        hudBitmap.eraseColor(Color.TRANSPARENT)
        hud.draw(hudCanvas, shot)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, hudTexture)
        GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, hudBitmap)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(quadProgram)
        uniform(quadProgram, "uMode") { GLES20.glUniform1f(it, 1f) }
        uniform(quadProgram, "uTex") { GLES20.glUniform1i(it, 0) }
        drawQuad()
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    // ------------------------------------------------------------------ geometry and pictures

    /** The mesh and picture of a window (made the first time; the least recently used of four let go). */
    private fun window(w: DroneScript.Window): Pair<Mesh, Int>? {
        windows.remove(w.index)?.let { windows[w.index] = it; return it }
        val bmp = runCatching { detail?.invoke(w) }.getOrNull() ?: return null
        val tex = upload(bmp)
        bmp.recycle()
        val mesh = gridMesh(w.x0, w.y0, w.x1, w.y1, WINDOW_GRID, skirt = false, sharedIndices = windowIndices)
        windows[w.index] = mesh to tex
        while (windows.size > 4) {
            val (k, v) = windows.entries.first()
            windows.remove(k)
            GLES20.glDeleteTextures(1, intArrayOf(v.second), 0)
            GLES20.glDeleteBuffers(1, intArrayOf(v.first.vbo), 0)
        }
        return windows[w.index]
    }

    /**
     * A grid of [n] x [n] points over a rectangle of the ground, with normals. With [skirt] its outer ring is pulled far
     * out, so the ground reaches the haze of the horizon instead of ending at a cliff.
     */
    private fun gridMesh(x0: Double, y0: Double, x1: Double, y1: Double, n: Int, skirt: Boolean, sharedIndices: Int = 0): Mesh {
        val h = FloatArray(n * n)
        for (j in 0 until n) for (i in 0 until n) {
            h[j * n + i] = ground.exact(x0 + (x1 - x0) * i / (n - 1), y0 + (y1 - y0) * j / (n - 1)).toFloat()
        }
        val dxCell = (x1 - x0) / (n - 1); val dyCell = (y1 - y0) / (n - 1)
        val data = FloatArray(n * n * 6)
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        for (j in 0 until n) for (i in 0 until n) {
            var x = x0 + dxCell * i; var y = y0 + dyCell * j
            if (skirt && (i == 0 || j == 0 || i == n - 1 || j == n - 1)) {
                // Out to 40 km beyond the edge, keeping the height.
                val ex = x - cx; val ey = y - cy; val len = sqrt(ex * ex + ey * ey).coerceAtLeast(1.0)
                x += ex / len * 40_000; y += ey / len * 40_000
            }
            val hl = h[j * n + max(0, i - 1)]; val hr = h[j * n + min(n - 1, i + 1)]
            val hd = h[max(0, j - 1) * n + i]; val hu = h[min(n - 1, j + 1) * n + i]
            // Slopes east and north; the normal in OpenGL's axes (z south).
            val sx = (hr - hl) / (dxCell * (min(n - 1, i + 1) - max(0, i - 1))).toFloat()
            val sy = (hu - hd) / (dyCell * (min(n - 1, j + 1) - max(0, j - 1))).toFloat()
            val nl = sqrt(sx * sx + sy * sy + 1)
            val o = (j * n + i) * 6
            data[o] = x.toFloat(); data[o + 1] = h[j * n + i]; data[o + 2] = (-y).toFloat()
            data[o + 3] = -sx / nl; data[o + 4] = 1 / nl; data[o + 5] = sy / nl
        }
        val vbo = buffer(GLES20.GL_ARRAY_BUFFER, floats(data), data.size * 4)
        val ibo = if (sharedIndices != 0) sharedIndices else gridIndices(n)
        return Mesh(vbo, ibo, (n - 1) * (n - 1) * 6)
    }

    private fun gridIndices(n: Int): Int {
        val idx = ShortArray((n - 1) * (n - 1) * 6)
        var k = 0
        for (j in 0 until n - 1) for (i in 0 until n - 1) {
            val a = (j * n + i); val b = a + 1; val c = a + n; val d = c + 1
            // Counter-clockwise seen from above (y north = -z).
            idx[k++] = a.toShort(); idx[k++] = b.toShort(); idx[k++] = c.toShort()
            idx[k++] = b.toShort(); idx[k++] = d.toShort(); idx[k++] = c.toShort()
        }
        val sb = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx).also { it.position(0) }
        return buffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, sb, idx.size * 2)
    }

    private fun drawMesh(m: Mesh) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        val pos = attrib(terrainProgram, "aPos", 3, 6, 0)
        val nrm = attrib(terrainProgram, "aNormal", 3, 6, 3)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, m.ibo)
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.count, GLES20.GL_UNSIGNED_SHORT, 0)
        if (pos >= 0) GLES20.glDisableVertexAttribArray(pos)
        if (nrm >= 0) GLES20.glDisableVertexAttribArray(nrm)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawQuad() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quad)
        val a = attrib(quadProgram, "aPos", 2, 2, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (a >= 0) GLES20.glDisableVertexAttribArray(a)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    /** A ground picture as a texture with mipmaps (square, a power of two, as large as the GPU allows up to 2048). */
    private fun upload(bmp: Bitmap): Int {
        val side = min(2048, Integer.highestOneBit(maxTexture))
        val square = if (bmp.width == side && bmp.height == side) bmp else Bitmap.createScaledBitmap(bmp, side, side, true)
        val t = texture()
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, square, 0)
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR)
        if (anisotropy > 1f) GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 0x84FE, anisotropy)
        if (square !== bmp) square.recycle()
        return t
    }

    private fun texture(): Int {
        val t = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t
    }

    private fun buffer(target: Int, data: java.nio.Buffer, bytes: Int): Int {
        val b = IntArray(1).also { GLES20.glGenBuffers(1, it, 0) }[0]
        GLES20.glBindBuffer(target, b)
        GLES20.glBufferData(target, bytes, data, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(target, 0)
        return b
    }

    private fun attrib(program: Int, name: String, size: Int, stride: Int, offset: Int): Int {
        val a = GLES20.glGetAttribLocation(program, name)
        if (a < 0) return a
        GLES20.glEnableVertexAttribArray(a)
        GLES20.glVertexAttribPointer(a, size, GLES20.GL_FLOAT, false, stride * 4, offset * 4)
        return a
    }

    private inline fun uniform(program: Int, name: String, set: (Int) -> Unit) {
        val l = GLES20.glGetUniformLocation(program, name)
        if (l >= 0) set(l)
    }

    private fun floats(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }

    companion object {
        const val GLOBAL_GRID = 241
        const val WINDOW_GRID = 201
        /** The haze of the distance, and the sky high up. */
        val HAZE = floatArrayOf(0.78f, 0.83f, 0.88f)
        val ZENITH = floatArrayOf(0.30f, 0.50f, 0.78f)

        private fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src)
            GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { val log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); error("Shader: $log") }
            return s
        }

        private fun program(vs: String, fs: String): Int {
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
            if (ok[0] == 0) error("Program: " + GLES20.glGetProgramInfoLog(p))
            return p
        }

        private const val TERRAIN_VS = """
            uniform mat4 uMvp;
            uniform vec3 uEye;
            uniform vec4 uWindow;
            uniform vec4 uGlobal;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec2 vUv;
            varying vec2 vGlobalUv;
            varying vec3 vNormal;
            varying float vDist;
            varying float vHeight;
            void main() {
                float north = -aPos.z;
                vUv = vec2((aPos.x - uWindow.x) / (uWindow.z - uWindow.x), (uWindow.w - north) / (uWindow.w - uWindow.y));
                vGlobalUv = vec2((aPos.x - uGlobal.x) / (uGlobal.z - uGlobal.x), (uGlobal.w - north) / (uGlobal.w - uGlobal.y));
                vNormal = aNormal;
                vDist = distance(aPos, uEye);
                vHeight = aPos.y;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val TERRAIN_FS = """
            precision mediump float;
            uniform sampler2D uTex;
            uniform sampler2D uGlobalTex;
            uniform float uMode;
            uniform vec3 uLight;
            uniform vec2 uLightMix;
            uniform vec2 uFog;
            uniform vec3 uHaze;
            uniform float uMaxHeight;
            uniform float uGlobalOk;
            varying vec2 vUv;
            varying vec2 vGlobalUv;
            varying vec3 vNormal;
            varying float vDist;
            varying float vHeight;
            void main() {
                vec3 c;
                if (uMode > 1.5) {
                    // No picture: greens low, browns higher, pale on the tops.
                    float t = clamp(vHeight / uMaxHeight, 0.0, 1.0);
                    c = mix(vec3(0.42, 0.56, 0.33), vec3(0.62, 0.55, 0.40), smoothstep(0.0, 0.6, t));
                    c = mix(c, vec3(0.88, 0.87, 0.84), smoothstep(0.75, 1.0, t));
                } else {
                    vec3 g = texture2D(uGlobalTex, vGlobalUv).rgb;
                    if (uMode > 0.5) {
                        // The sharp window fades into the whole area's picture at its edges.
                        float e = min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y));
                        c = mix(g, texture2D(uTex, vUv).rgb, uGlobalOk > 0.5 ? smoothstep(0.0, 0.12, e) : 1.0);
                    } else {
                        c = g;
                    }
                }
                float lambert = max(dot(normalize(vNormal), uLight), 0.0);
                c *= uLightMix.x + uLightMix.y * lambert;
                float fog = smoothstep(uFog.x, uFog.y, vDist);
                gl_FragColor = vec4(mix(c, uHaze, fog), 1.0);
            }
        """

        private const val RIBBON_VS = """
            uniform mat4 uMvp;
            uniform float uHalfWidth;
            uniform float uLift;
            uniform vec3 uBands[4];
            attribute vec3 aPos;
            attribute vec3 aSide;
            attribute vec2 aAlong;
            varying vec3 vColor;
            varying float vAlong;
            void main() {
                vec3 p = aPos + vec3(aSide.x, 0.0, aSide.y) * aSide.z * uHalfWidth + vec3(0.0, uLift, 0.0);
                vColor = uBands[int(clamp(aAlong.y, 0.0, 3.0))];
                vAlong = aAlong.x;
                gl_Position = uMvp * vec4(p, 1.0);
            }
        """

        private const val RIBBON_FS = """
            precision mediump float;
            uniform float uDone;
            uniform float uOutline;
            varying vec3 vColor;
            varying float vAlong;
            void main() {
                if (uOutline > 0.5) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 0.35);
                } else if (vAlong <= uDone) {
                    gl_FragColor = vec4(vColor, 1.0);
                } else {
                    gl_FragColor = vec4(0.6, 0.6, 0.6, 0.6);
                }
            }
        """

        private const val CAR_VS = """
            uniform mat4 uMvp;
            uniform float uSize;
            attribute vec3 aPos;
            void main() {
                gl_Position = uMvp * vec4(aPos, 1.0);
                gl_PointSize = uSize;
            }
        """

        private const val CAR_FS = """
            precision mediump float;
            uniform vec3 uColor;
            void main() {
                float r = length(gl_PointCoord - vec2(0.5)) * 2.0;
                if (r > 1.0) discard;
                // A soft shadow, a white ring and the speed colour inside.
                vec4 c = r < 0.5 ? vec4(uColor, 1.0) : (r < 0.78 ? vec4(1.0) : vec4(0.0, 0.0, 0.0, 0.45 * (1.0 - r) / 0.22));
                gl_FragColor = vec4(c.rgb * c.a, c.a);
            }
        """

        private const val QUAD_VS = """
            attribute vec2 aPos;
            varying vec2 vPos;
            void main() {
                vPos = aPos;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        private const val QUAD_FS = """
            precision mediump float;
            uniform float uMode;
            uniform float uHorizon;
            uniform vec3 uHaze;
            uniform vec3 uZenith;
            uniform sampler2D uTex;
            varying vec2 vPos;
            void main() {
                if (uMode > 0.5) {
                    // The picture drawn over it all (row 0 at the top).
                    gl_FragColor = texture2D(uTex, vec2(vPos.x * 0.5 + 0.5, 0.5 - vPos.y * 0.5));
                } else {
                    float t = clamp((vPos.y - uHorizon) / 1.4, 0.0, 1.0);
                    gl_FragColor = vec4(mix(uHaze, uZenith, sqrt(t)), 1.0);
                }
            }
        """
    }
}
