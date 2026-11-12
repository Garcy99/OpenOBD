package com.openobd.session

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.OpenObdApp
import com.openobd.core.brand.IdKind
import com.openobd.core.brand.ValueFinder
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The filter load another app shows is found on the (demo) engine computer and used by the filter watch from then on. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ValueFinderSessionTest {
    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    @Test fun findSaveAndWatch() {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            c.session.finder.rangesForTest = listOf(IdKind.UDS to 0x1900..0x190F)
            c.session.finder.search(37.4, ValueFinder.Quantity.DPF_SOOT)
            waitFor { !c.session.finder.state.value.scanning && c.session.finder.state.value.searched }
            val st = c.session.finder.state.value
            val best = st.candidates.first()
            assertEquals("${st.candidates}", 0x1902, best.id)
            assertEquals(37.4, best.value, 0.05)
            assertEquals(37.4, runBlocking { c.session.finder.readNow(best) }!!, 0.05)
            runBlocking { c.session.finder.save(best, ValueFinder.Quantity.DPF_SOOT, "Soot load") }
            assertTrue(c.garage.data.value.active!!.customPids.any { it.id == "gm_dpf_soot" && it.request == "221902" })
            // The filter watch reads it from now on (no reconnecting).
            waitFor { c.session.diesel.regen.value?.loadPct != null }
            assertEquals(37.4, c.session.diesel.regen.value!!.loadPct!!, 0.05)
        } finally {
            c.session.finder.rangesForTest = null
            runBlocking { c.garage.updateActive { v -> v.copy(customPids = v.customPids.filter { it.id != "gm_dpf_soot" }) } }
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
        }
    }
}
