package com.openobd.video

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import com.openobd.core.trip.DroneScene
import com.openobd.core.trip.DroneScript
import com.openobd.core.trip.RouteGeometry
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the frames of the drone video with OpenGL ES 2: the ground in 3D (the elevation model) dressed with aerial
 * photos or relief, sharp near the car ([detail] windows along the route); buildings, trees and water standing on it
 * ([scene]); the route draped on the ground and coloured by speed as it is driven; the car; the sky and the haze of
 * the distance; and the [hud] over it all. Made, used and released on the thread of its OpenGL context.
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
    /** Buildings, trees and water (null: bare ground). */
    scene: DroneScene? = null,
) {
    private val ground = script.ground
    private val frame = script.frame
    private val terrainProgram = program(TERRAIN_VS, PHOTO + TERRAIN_FS)
    private val buildingProgram = program(BUILDING_VS, PHOTO + BUILDING_FS)
    private val treeProgram = program(TREE_VS, PHOTO + TREE_FS)
    private val waterProgram = program(WATER_VS, WATER_FS)
    private val ribbonProgram = program(RIBBON_VS, RIBBON_FS)
    private val carProgram = program(CAR_VS, CAR_FS)
    private val quadProgram = program(QUAD_VS, QUAD_FS)
    private val maxTexture = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0].coerceAtLeast(1024)
    private val maxPoint = FloatArray(2).also { GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, it, 0) }[1].coerceAtLeast(16f)
    private val anisotropy: Float = if (GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_EXT_texture_filter_anisotropic") == true)
        FloatArray(1).also { GLES20.glGetFloatv(0x84FF, it, 0) }[0].coerceIn(1f, 8f) else 1f

    private class Mesh(val vbo: Int, val ibo: Int, val count: Int)
    private val globalMesh = gridMesh(frame.minX, frame.minY, frame.maxX, frame.maxY, GLOBAL_GRID, skirt = true)
    private val windowIndices = gridIndices(WINDOW_GRID)
    private val globalTexture: Int = global?.let { upload(it) } ?: 0
    private val maxHeight = (0 until 64).maxOf { i -> (0 until 64).maxOf { j ->
        ground.at(frame.minX + (frame.maxX - frame.minX) * i / 63, frame.minY + (frame.maxY - frame.minY) * j / 63) } }.coerceAtLeast(50.0)

    /** Windows drawn lately (by [DroneScript.Window.key]): their mesh and picture (0 when it could not be had). */
    private val windows = LinkedHashMap<Int, Pair<Mesh, Int>>()

    private val route = script.drapedRoute()
    private val ribbon: Int
    private val ribbonCount: Int
    private val buildingBuffer: Int; private val buildingVertices: Int
    private val treeBuffer: Int; private val treeCount: Int
    private val waterBuffer: Int; private val waterVertices: Int
    private val hudBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    private val hudCanvas = Canvas(hudBitmap)
    private val hudTexture: Int
    private val quad: Int

    private val proj = FloatArray(16); private val view = FloatArray(16); private val mvp = FloatArray(16)
    private val bandColors = RouteGeometry.bandColors.map { Color.parseColor(it) }.flatMap { listOf(Color.red(it) / 255f, Color.green(it) / 255f, Color.blue(it) / 255f) }.toFloatArray()
    private val u = min(width, height) / 1080f
    private val pixels: ByteBuffer by lazy { ByteBuffer.allocateDirect(width * height * 4) }
    private val light = floatArrayOf(-0.45f, 0.75f, -0.48f).let { v -> val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]); floatArrayOf(v[0] / n, v[1] / n, v[2] / n) }

    /** The pictures to dress things with on this frame: up to two sharp windows, two wider ones and the whole area. */
    private class Photos(val near: List<Pair<DroneScript.Window, Int>>, val mid: List<Pair<DroneScript.Window, Int>>)
    private var photos = Photos(emptyList(), emptyList())

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
        fun arrayBuffer(a: FloatArray?) = if (a == null || a.isEmpty()) 0 else buffer(GLES20.GL_ARRAY_BUFFER, floats(a), a.size * 4)
        buildingBuffer = arrayBuffer(scene?.buildings); buildingVertices = (scene?.buildings?.size ?: 0) / 9
        treeBuffer = arrayBuffer(scene?.trees); treeCount = (scene?.trees?.size ?: 0) / 5
        waterBuffer = arrayBuffer(scene?.water); waterVertices = (scene?.water?.size ?: 0) / 3
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

        // The windows of this frame, made ready (mesh and picture).
        val wanted = if (detail != null) script.windowsAt(f) else emptyList()
        val ready = wanted.mapNotNull { w -> window(w)?.let { w to it } }
        photos = Photos(ready.filter { it.first.level == DroneScript.NEAR && it.second.second != 0 }.take(2).map { it.first to it.second.second },
            ready.filter { it.first.level == DroneScript.MID && it.second.second != 0 }.take(2).map { it.first to it.second.second })

        sky(cam)
        terrain(cam, ready.map { it.second.first })
        water(cam, f)
        buildings(cam)
        trees(cam)
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
        val buffers = (windows.values.map { it.first.vbo } + listOf(globalMesh.vbo, globalMesh.ibo, windowIndices, ribbon, quad, buildingBuffer, treeBuffer, waterBuffer))
            .filter { it != 0 }.toIntArray()
        GLES20.glDeleteBuffers(buffers.size, buffers, 0)
        windows.clear()
        listOf(terrainProgram, buildingProgram, treeProgram, waterProgram, ribbonProgram, carProgram, quadProgram).forEach { GLES20.glDeleteProgram(it) }
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

    /** The uniforms every program that looks at the ground shares: camera, haze, light and the pictures. */
    private fun common(program: Int, cam: DroneScript.Camera) {
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        uniform(program, "uEye") { GLES20.glUniform3f(it, cam.eyeX.toFloat(), cam.eyeZ.toFloat(), (-cam.eyeY).toFloat()) }
        uniform(program, "uFog") { GLES20.glUniform2f(it, cam.fogStart.toFloat(), cam.fogEnd.toFloat()) }
        uniform(program, "uHaze") { GLES20.glUniform3fv(it, 1, HAZE, 0) }
        uniform(program, "uLight") { GLES20.glUniform3fv(it, 1, light, 0) }
        uniform(program, "uMaxHeight") { GLES20.glUniform1f(it, maxHeight.toFloat()) }
        uniform(program, "uLightMix") { GLES20.glUniform2f(it, if (shadedPictures) 0.82f else 0.68f, if (shadedPictures) 0.25f else 0.42f) }
        // Units: 1 the whole area, 2-3 the wider windows, 4-5 the sharp ones.
        bind(program, 1, "uGlobalTex", globalTexture)
        uniform(program, "uGlobal") { GLES20.glUniform4f(it, frame.minX.toFloat(), frame.minY.toFloat(), frame.maxX.toFloat(), frame.maxY.toFloat()) }
        uniform(program, "uHasGlobal") { GLES20.glUniform1f(it, if (globalTexture != 0) 1f else 0f) }
        val slots = listOf(photos.mid.getOrNull(0), photos.mid.getOrNull(1), photos.near.getOrNull(1), photos.near.getOrNull(0))
        for ((i, s) in slots.withIndex()) {
            bind(program, 2 + i, "uTex$i", s?.second ?: 0)
            uniform(program, "uBox$i") {
                val w = s?.first
                if (w == null) GLES20.glUniform4f(it, 0f, 0f, -1f, -1f) else GLES20.glUniform4f(it, w.x0.toFloat(), w.y0.toFloat(), w.x1.toFloat(), w.y1.toFloat())
            }
        }
    }

    private fun bind(program: Int, unit: Int, name: String, texture: Int) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        uniform(program, name) { GLES20.glUniform1i(it, unit) }
    }

    private fun terrain(cam: DroneScript.Camera, meshes: List<Mesh>) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthFunc(GLES20.GL_LEQUAL); GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glEnable(GLES20.GL_CULL_FACE); GLES20.glCullFace(GLES20.GL_BACK); GLES20.glFrontFace(GLES20.GL_CCW)
        // Each mesh only where none before it was drawn (the finest first, the whole area last), but over itself
        // freely: mesh k marks its pixels 250 - k and may draw where the mark is 0 or its own.
        GLES20.glEnable(GLES20.GL_STENCIL_TEST)
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)
        GLES20.glStencilMask(0xFF)
        common(terrainProgram, cam)
        var layer = 250
        for (m in meshes) {
            GLES20.glStencilFunc(GLES20.GL_GEQUAL, layer--, 0xFF)
            drawMesh(m)
        }
        GLES20.glStencilFunc(GLES20.GL_GEQUAL, 100, 0xFF)
        drawMesh(globalMesh)
        GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
    }

    private fun water(cam: DroneScript.Camera, f: Int) {
        if (waterBuffer == 0) return
        GLES20.glUseProgram(waterProgram)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true); GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(waterProgram, "uMvp"), 1, false, mvp, 0)
        uniform(waterProgram, "uEye") { GLES20.glUniform3f(it, cam.eyeX.toFloat(), cam.eyeZ.toFloat(), (-cam.eyeY).toFloat()) }
        uniform(waterProgram, "uFog") { GLES20.glUniform2f(it, cam.fogStart.toFloat(), cam.fogEnd.toFloat()) }
        uniform(waterProgram, "uHaze") { GLES20.glUniform3fv(it, 1, HAZE, 0) }
        uniform(waterProgram, "uZenith") { GLES20.glUniform3fv(it, 1, ZENITH, 0) }
        uniform(waterProgram, "uLight") { GLES20.glUniform3fv(it, 1, light, 0) }
        uniform(waterProgram, "uTime") { GLES20.glUniform1f(it, f / script.video.fps.toFloat()) }
        // Pulled a little towards the camera, so the river wins over the ground it lies on.
        GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL); GLES20.glPolygonOffset(-1f, -4f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, waterBuffer)
        val a = attrib(waterProgram, "aPos", 3, 3, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, waterVertices)
        if (a >= 0) GLES20.glDisableVertexAttribArray(a)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
    }

    private fun buildings(cam: DroneScript.Camera) {
        if (buildingBuffer == 0) return
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true); GLES20.glDisable(GLES20.GL_BLEND)
        common(buildingProgram, cam)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buildingBuffer)
        val a = listOf(attrib(buildingProgram, "aPos", 3, 9, 0), attrib(buildingProgram, "aNormal", 3, 9, 3), attrib(buildingProgram, "aWall", 3, 9, 6))
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, buildingVertices)
        a.forEach { if (it >= 0) GLES20.glDisableVertexAttribArray(it) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun trees(cam: DroneScript.Camera) {
        if (treeBuffer == 0) return
        GLES20.glEnable(GLES20.GL_DEPTH_TEST); GLES20.glDepthMask(true); GLES20.glDisable(GLES20.GL_BLEND)
        common(treeProgram, cam)
        // Pixels per metre at one metre away: sizes on screen shrink with the distance.
        val scale = height / 2f / tan(Math.toRadians(cam.fovY / 2)).toFloat()
        uniform(treeProgram, "uScale") { GLES20.glUniform1f(it, scale) }
        uniform(treeProgram, "uMaxPoint") { GLES20.glUniform1f(it, maxPoint) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, treeBuffer)
        val a = listOf(attrib(treeProgram, "aTree", 4, 5, 0), attrib(treeProgram, "aShade", 1, 5, 4))
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, treeCount)
        a.forEach { if (it >= 0) GLES20.glDisableVertexAttribArray(it) }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
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
            val half = cam.distance * (if (pass == 0) 0.016 else 0.011) * (min(width, height) / 1080.0).coerceAtLeast(0.6)
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
        uniform(carProgram, "uSize") { GLES20.glUniform1f(it, min(46f * u, maxPoint)) }
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

    /** The mesh and picture of a window (made the first time; the least recently used of eight let go). */
    private fun window(w: DroneScript.Window): Pair<Mesh, Int>? {
        windows.remove(w.key)?.let { windows[w.key] = it; return it }
        val bmp = runCatching { detail?.invoke(w) }.getOrNull()
        val tex = bmp?.let { upload(it).also { _ -> it.recycle() } } ?: 0
        val mesh = gridMesh(w.x0, w.y0, w.x1, w.y1, WINDOW_GRID, skirt = false, sharedIndices = windowIndices)
        windows[w.key] = mesh to tex
        while (windows.size > 8) {
            val (k, v) = windows.entries.first()
            windows.remove(k)
            if (v.second != 0) GLES20.glDeleteTextures(1, intArrayOf(v.second), 0)
            GLES20.glDeleteBuffers(1, intArrayOf(v.first.vbo), 0)
        }
        return windows[w.key]
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

        /**
         * The best picture of the ground at a point (x east, y north): the sharp windows where there are, the wider
         * ones, then the whole area, each fading into the next near its edges.
         */
        private const val PHOTO = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uGlobalTex;
            uniform vec4 uGlobal;
            uniform float uHasGlobal;
            uniform sampler2D uTex0; uniform vec4 uBox0;
            uniform sampler2D uTex1; uniform vec4 uBox1;
            uniform sampler2D uTex2; uniform vec4 uBox2;
            uniform sampler2D uTex3; uniform vec4 uBox3;
            uniform float uMaxHeight;
            uniform vec3 uLight;
            uniform vec2 uLightMix;
            uniform vec2 uFog;
            uniform vec3 uHaze;
            vec2 boxUv(vec4 b, vec2 p) { return vec2((p.x - b.x) / (b.z - b.x), (b.w - p.y) / (b.w - b.y)); }
            float edge(vec2 uv) { return min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)); }
            vec3 over(vec3 c, sampler2D t, vec4 b, vec2 p) {
                if (b.z <= b.x) return c;
                vec2 uv = boxUv(b, p);
                float w = smoothstep(0.0, 0.08, edge(uv));
                if (w <= 0.0) return c;
                return mix(c, texture2D(t, uv).rgb, w);
            }
            vec3 heightColour(float h) {
                float t = clamp(h / uMaxHeight, 0.0, 1.0);
                vec3 c = mix(vec3(0.42, 0.56, 0.33), vec3(0.62, 0.55, 0.40), smoothstep(0.0, 0.6, t));
                return mix(c, vec3(0.88, 0.87, 0.84), smoothstep(0.75, 1.0, t));
            }
            vec3 photo(vec2 p, float h) {
                if (uHasGlobal < 0.5) return heightColour(h);
                vec3 c = texture2D(uGlobalTex, boxUv(uGlobal, p)).rgb;
                c = over(c, uTex0, uBox0, p);
                c = over(c, uTex1, uBox1, p);
                c = over(c, uTex2, uBox2, p);
                c = over(c, uTex3, uBox3, p);
                return c;
            }
            vec3 haze(vec3 c, float dist) { return mix(c, uHaze, smoothstep(uFog.x, uFog.y, dist)); }
        """

        private const val TERRAIN_VS = """
            uniform mat4 uMvp;
            uniform vec3 uEye;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            varying vec3 vPos;
            varying float vDist;
            void main() {
                vNormal = aNormal;
                vPos = aPos;
                vDist = distance(aPos, uEye);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        private const val TERRAIN_FS = """
            varying vec3 vNormal;
            varying vec3 vPos;
            varying float vDist;
            void main() {
                vec3 c = photo(vec2(vPos.x, -vPos.z), vPos.y);
                float lambert = max(dot(normalize(vNormal), uLight), 0.0);
                c *= uLightMix.x + uLightMix.y * lambert;
                gl_FragColor = vec4(haze(c, vDist), 1.0);
            }
        """

        private const val BUILDING_VS = """
            uniform mat4 uMvp;
            uniform vec3 uEye;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aWall;
            varying vec3 vNormal;
            varying vec3 vPos;
            varying vec3 vWall;
            varying float vDist;
            void main() {
                vNormal = aNormal; vPos = aPos; vWall = aWall;
                vDist = distance(aPos, uEye);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        /** Roofs from the photo itself; walls in plaster tones with rows of windows, darker towards the ground. */
        private const val BUILDING_FS = """
            varying vec3 vNormal;
            varying vec3 vPos;
            varying vec3 vWall;
            varying float vDist;
            void main() {
                vec3 n = normalize(vNormal);
                float lambert = max(dot(n, uLight), 0.0);
                vec3 c;
                if (vWall.x < 0.0) {
                    c = photo(vec2(vPos.x, -vPos.z), vPos.y) * (0.78 + 0.3 * lambert);
                } else {
                    vec3 plaster = mix(vec3(0.86, 0.80, 0.70), vec3(0.93, 0.90, 0.84), vWall.z);
                    plaster = mix(plaster, vec3(0.78, 0.52, 0.40), step(0.8, vWall.z) * 0.6);
                    c = plaster * (0.55 + 0.5 * lambert);
                    // Windows: a row every 3 m, one every 3.2 m along the wall, fading out with distance.
                    vec2 cell = vec2(fract(vWall.x / 3.2), fract(vWall.y / 3.0));
                    float win = step(0.3, cell.x) * step(cell.x, 0.72) * step(0.32, cell.y) * step(cell.y, 0.78) * step(2.0, vWall.y);
                    c = mix(c, vec3(0.20, 0.24, 0.30), win * 0.85 * (1.0 - smoothstep(250.0, 900.0, vDist)));
                    // Shade where the walls meet the ground.
                    c *= 0.7 + 0.3 * smoothstep(0.0, 4.0, vWall.y);
                }
                gl_FragColor = vec4(haze(c, vDist), 1.0);
            }
        """

        private const val TREE_VS = """
            uniform mat4 uMvp;
            uniform vec3 uEye;
            uniform float uScale;
            uniform float uMaxPoint;
            attribute vec4 aTree;
            attribute float aShade;
            varying vec2 vGround;
            varying float vShade;
            varying float vDist;
            varying float vHeight;
            void main() {
                gl_Position = uMvp * vec4(aTree.xyz, 1.0);
                gl_PointSize = clamp(2.4 * aTree.w * uScale / gl_Position.w, 0.0, uMaxPoint);
                vGround = vec2(aTree.x, -aTree.z);
                vShade = aShade;
                vHeight = aTree.y;
                vDist = distance(aTree.xyz, uEye);
            }
        """

        /** A round crown lit from the side: the photo's colour where the tree stands, made greener and darker. */
        private const val TREE_FS = """
            varying vec2 vGround;
            varying float vShade;
            varying float vDist;
            varying float vHeight;
            void main() {
                vec2 q = gl_PointCoord * 2.0 - 1.0;
                q.y = -q.y;
                // Slightly taller than wide, a little irregular.
                float r = length(vec2(q.x, q.y * 0.92)) + 0.06 * sin(atan(q.y, q.x) * 5.0 + vShade * 20.0);
                if (r > 1.0) discard;
                vec3 n = normalize(vec3(q.x, sqrt(max(0.0, 1.0 - r * r)), -q.y));
                float lambert = max(dot(n, normalize(vec3(uLight.x, uLight.y, -uLight.z))), 0.0);
                vec3 base = mix(vec3(0.16, 0.27, 0.12), vec3(0.30, 0.38, 0.18), vShade);
                vec3 c = mix(base, photo(vGround, vHeight) * vec3(0.8, 0.95, 0.75), 0.35);
                c *= 0.5 + 0.75 * lambert;
                // Darker at the bottom edge, where the crown meets its shadow.
                c *= 0.8 + 0.2 * smoothstep(-1.0, -0.3, q.y);
                gl_FragColor = vec4(haze(c, vDist), 1.0);
            }
        """

        private const val WATER_VS = """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            varying vec3 vPos;
            void main() {
                vPos = aPos;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        /** Deep green-blue, mirroring the sky at low angles, with small moving ripples catching the sun. */
        private const val WATER_FS = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform vec3 uEye;
            uniform vec2 uFog;
            uniform vec3 uHaze;
            uniform vec3 uZenith;
            uniform vec3 uLight;
            uniform float uTime;
            varying vec3 vPos;
            void main() {
                vec3 view = normalize(uEye - vPos);
                vec2 p = vPos.xz;
                vec3 n = normalize(vec3(0.04 * sin(p.x * 0.35 + uTime * 1.7) + 0.03 * sin(p.y * 0.5 - uTime * 1.3), 1.0,
                    0.04 * cos(p.y * 0.3 + uTime * 1.5) + 0.03 * cos(p.x * 0.45 + uTime)));
                float fresnel = pow(1.0 - max(dot(view, n), 0.0), 3.0);
                vec3 c = mix(vec3(0.10, 0.22, 0.26), mix(uHaze, uZenith, 0.4), 0.25 + 0.65 * fresnel);
                float spec = pow(max(dot(reflect(-uLight, n), view), 0.0), 80.0);
                c += vec3(1.0, 0.97, 0.9) * spec * 0.8;
                float d = distance(vPos, uEye);
                gl_FragColor = vec4(mix(c, uHaze, smoothstep(uFog.x, uFog.y, d)), 1.0);
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
