package com.openobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.data.ChatTurn
import com.openobd.data.WarningLightAssistant
import com.openobd.ui.screens.loadPhotoJpeg
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class WarningLightTest {
    @Test
    fun photoIsShrunkToJpeg() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val big = Bitmap.createBitmap(4000, 3000, Bitmap.Config.ARGB_8888).also { Canvas(it).drawColor(Color.RED) }
        val f = File(context.cacheDir, "big.png").apply { outputStream().use { big.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val (bmp, jpeg) = loadPhotoJpeg(context, Uri.fromFile(f))!!
        assertTrue("${bmp.width}x${bmp.height}", maxOf(bmp.width, bmp.height) <= 1280 && bmp.width > bmp.height)
        assertEquals(0xFF.toByte(), jpeg[0]); assertEquals(0xD8.toByte(), jpeg[1]) // JPEG magic
        assertTrue("${jpeg.size} bytes", jpeg.size < 400_000)
        assertNull(loadPhotoJpeg(context, Uri.fromFile(File(context.cacheDir, "missing.jpg"))))
    }

    @Test
    fun photoGoesOnlyInTheFirstMessage() = runBlocking {
        val seen = mutableListOf<List<ChatTurn>>()
        var fail = false
        val a = WarningLightAssistant({ system, turns ->
            assertTrue(system.contains("Spanish") && system.contains("instrument"))
            seen += turns; if (fail) error("offline"); "respuesta ${turns.size}"
        }, "es")
        val photo = byteArrayOf(1, 2, 3)
        assertEquals("respuesta 1", a.start(photo, "Car data: Opel Astra; codes P2463", ""))
        val first = seen.last().single()
        assertTrue(first.imageJpeg!!.contentEquals(photo))
        assertTrue(first.text.contains("P2463") && first.text.contains("Which warning lights"))
        assertEquals("respuesta 3", a.ask("¿Puedo conducir?"))
        assertNull(seen.last()[2].imageJpeg)
        fail = true
        runCatching { a.ask("otra") }
        fail = false
        a.ask("otra")
        assertEquals(5, seen.last().size) // the failed question was not kept
    }
}
