package com.openobd.ui

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.viewinterop.AndroidView
import com.openobd.core.trip.Route3mf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.max
import kotlin.math.sqrt

/** Triangles ready to draw: position (3 floats) and lit colour (4 bytes) per vertex, plus the model's bounds. */
class PreviewMesh(val data: ByteBuffer, val vertices: Int, val centre: FloatArray, val radius: Float) {
    companion object {
        private const val STRIDE = 16

        /** Flat shading baked into the colours: a light from the upper left, so the relief reads at any angle. */
        fun of(model: Route3mf.Model): PreviewMesh {
            val n = model.triangles * 3
            val buf = ByteBuffer.allocateDirect(n * STRIDE).order(ByteOrder.nativeOrder())
            var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var minZ = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE; var maxZ = -Double.MAX_VALUE
            val lx = -0.45; val ly = 0.35; val lz = 0.82
            val rgb = HashMap<String, Int>()
            for (m in model.meshes) {
                for ((k, t) in m.t.withIndex()) {
                    val a = m.v[t[0]]; val b = m.v[t[1]]; val c = m.v[t[2]]
                    val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
                    val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
                    var nx = uy * vz - uz * vy; var ny = uz * vx - ux * vz; var nz = ux * vy - uy * vx
                    val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-12 } ?: 1.0
                    nx /= len; ny /= len; nz /= len
                    val light = 0.42 + 0.58 * max(0.0, nx * lx + ny * ly + nz * lz)
                    val col = rgb.getOrPut(m.c[k]) { android.graphics.Color.parseColor(m.c[k]) }
                    val r = ((col shr 16 and 0xFF) * light).toInt().coerceIn(0, 255)
                    val g = ((col shr 8 and 0xFF) * light).toInt().coerceIn(0, 255)
                    val bl = ((col and 0xFF) * light).toInt().coerceIn(0, 255)
                    for (p in listOf(a, b, c)) {
                        buf.putFloat(p[0].toFloat()); buf.putFloat(p[1].toFloat()); buf.putFloat(p[2].toFloat())
                        buf.put(r.toByte()); buf.put(g.toByte()); buf.put(bl.toByte()); buf.put(255.toByte())
                        minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0]); minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
                        minZ = minOf(minZ, p[2]); maxZ = maxOf(maxZ, p[2])
                    }
                }
            }
            buf.position(0)
            val centre = floatArrayOf(((minX + maxX) / 2).toFloat(), ((minY + maxY) / 2).toFloat(), ((minZ + maxZ) / 2).toFloat())
            val radius = (sqrt((maxX - minX) * (maxX - minX) + (maxY - minY) * (maxY - minY) + (maxZ - minZ) * (maxZ - minZ)) / 2).toFloat()
            return PreviewMesh(buf, n, centre, max(radius, 1f))
        }
    }
}

/** Robolectric has no OpenGL: tests see a placeholder instead of the live view. */
private val canUseGl = android.os.Build.FINGERPRINT != "robolectric"

/** A live, flat-shaded view of the model: drag to turn it, pinch to zoom. */
@Composable
fun ModelPreview(model: Route3mf.Model?, modifier: Modifier = Modifier) {
    val background = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val renderer = remember { PreviewRenderer() }
    LaunchedEffect(model) {
        val mesh = model?.let { withContext(Dispatchers.Default) { PreviewMesh.of(it) } }
        renderer.mesh = mesh
        renderer.request()
    }
    renderer.background = background
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (canUseGl) {
            AndroidView(factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(2)
                    setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                    preserveEGLContextOnPause = true
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    renderer.request = { requestRender() }
                }
            }, modifier = Modifier.fillMaxSize(), update = { it.requestRender() })
            DisposableEffect(Unit) { onDispose { renderer.request = {} } }
            Box(Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    renderer.yaw += pan.x * 0.4f
                    renderer.pitch = (renderer.pitch + pan.y * 0.3f).coerceIn(5f, 89f)
                    renderer.zoom = (renderer.zoom * zoom).coerceIn(0.5f, 2.2f)
                    renderer.request()
                }
            })
        } else if (model != null) {
            Text(tr("Drag to turn, pinch to zoom."), style = MaterialTheme.typography.bodySmall)
        }
    }
}

class PreviewRenderer : GLSurfaceView.Renderer {
    @Volatile var mesh: PreviewMesh? = null
        set(value) { field = value; dirty = true }
    @Volatile var yaw = -20f
    @Volatile var pitch = 50f
    @Volatile var zoom = 1f
    @Volatile var background = 0
    var request: () -> Unit = {}
    @Volatile private var dirty = true
    private var program = 0
    private var vbo = 0
    private var uploaded: PreviewMesh? = null
    private var aspect = 1f
    private val proj = FloatArray(16); private val viewM = FloatArray(16); private val modelM = FloatArray(16)
    private val tmp = FloatArray(16); private val mvp = FloatArray(16)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(
            """
            uniform mat4 uMvp;
            attribute vec3 aPos;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() { vColor = aColor; gl_Position = uMvp * vec4(aPos, 1.0); }
            """.trimIndent(),
            """
            precision mediump float;
            varying vec4 vColor;
            void main() { gl_FragColor = vColor; }
            """.trimIndent(),
        )
        val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); vbo = ids[0]
        uploaded = null; dirty = true
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / max(1, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val bg = background
        GLES20.glClearColor((bg shr 16 and 0xFF) / 255f, (bg shr 8 and 0xFF) / 255f, (bg and 0xFF) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val m = mesh ?: return
        if (dirty || uploaded !== m) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
            m.data.position(0)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, m.vertices * 16, m.data, GLES20.GL_STATIC_DRAW)
            uploaded = m; dirty = false
        }
        val distance = m.radius * 2.6f / zoom
        Matrix.perspectiveM(proj, 0, 35f, aspect, m.radius * 0.05f, m.radius * 10f)
        Matrix.setLookAtM(viewM, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        // Model: centred, tilted back by the pitch (0 = seen from the side, 90 = from above) and turned by the yaw.
        Matrix.setIdentityM(modelM, 0)
        Matrix.rotateM(modelM, 0, pitch - 90f, 1f, 0f, 0f)
        Matrix.rotateM(modelM, 0, yaw, 0f, 0f, 1f)
        Matrix.translateM(modelM, 0, -m.centre[0], -m.centre[1], -m.centre[2])
        Matrix.multiplyMM(tmp, 0, viewM, 0, modelM, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, tmp, 0)
        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, mvp, 0)
        val aPos = GLES20.glGetAttribLocation(program, "aPos"); val aColor = GLES20.glGetAttribLocation(program, "aColor")
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 16, 0)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_UNSIGNED_BYTE, true, 16, 12)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, m.vertices)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun link(vertex: String, fragment: String): Int {
        fun compile(type: Int, src: String) = GLES20.glCreateShader(type).also { GLES20.glShaderSource(it, src); GLES20.glCompileShader(it) }
        return GLES20.glCreateProgram().also { p ->
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vertex))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(p)
        }
    }
}
