package com.openobd.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.MainActivity
import com.openobd.OpenObdApp
import com.openobd.data.ConnectionType
import com.openobd.data.ThemeMode
import com.openobd.session.ConnectionState
import com.openobd.session.Op
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Aftermarket Android head units: 1024x600 at 160 dpi and 1280x720 at 240 dpi, landscape. */
abstract class HeadUnitBase(private val tag: String) {
    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50); rule.mainClock.advanceTimeBy(50) }
        rule.waitForIdle()
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        // Robolectric only renders real pixels on Android 8+; older runs still check the screens work.
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val dir = File(System.getProperty("screenshot.dir") ?: "build/screenshots").apply { mkdirs() }
        rule.activityRule.scenario.onActivity { act ->
            val v = act.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            File(dir, "60_headunit_${tag}_$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test
    fun mainTabs() {
        val c = (rule.activity.application as OpenObdApp).container
        com.openobd.ui.screens.LivePages.selected = com.openobd.ui.screens.LivePages.GAUGES
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, theme = ThemeMode.DARK, language = "en", tripGps = false, onboarded = true) } }
        waitFor { c.settings.settings.value.theme == ThemeMode.DARK && I18n.language == "en" }
        c.session.connect()
        waitFor { c.session.state.value is ConnectionState.Connected && c.session.dtcScan.value is Op.Done }
        shot("home")
        rule.onAllNodes(hasText("Live") and isSelectable())[0].performClick()
        waitFor { c.session.live.readings.value.containsKey("010C") }
        Thread.sleep(1500); rule.mainClock.advanceTimeBy(1500)
        shot("live")
        rule.onAllNodes(hasText("Diagnose") and isSelectable())[0].performClick()
        shot("diagnose")
        c.session.disconnect()
        waitFor { c.session.state.value is ConnectionState.Disconnected }
        // USB cable chosen but not plugged in: the adapter sheet says so, and connecting fails with a clear message.
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.USB) } }
        rule.onAllNodes(hasText("Home") and isSelectable())[0].performClick()
        waitFor { rule.onAllNodes(hasText("USB cable")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodes(hasText("USB cable"))[0].performClick()
        waitFor { rule.onAllNodes(hasText(com.openobd.transport.UsbSerialTransport.NO_CABLE)).fetchSemanticsNodes().isNotEmpty() }
        c.session.connect()
        waitFor { (c.session.state.value as? ConnectionState.Failed)?.message == com.openobd.transport.UsbSerialTransport.NO_CABLE }
    }
}

/** Older head units (Rockchip PX3, MTK) still run Android 6: the app must start and work there too. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [23], qualifiers = "w1024dp-h600dp-land-mdpi")
class HeadUnitAndroid6Test : HeadUnitBase("android6")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1024dp-h600dp-land-mdpi")
class HeadUnit1024Test : HeadUnitBase("1024x600")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w853dp-h480dp-land-hdpi")
class HeadUnit1280Test : HeadUnitBase("1280x720")
