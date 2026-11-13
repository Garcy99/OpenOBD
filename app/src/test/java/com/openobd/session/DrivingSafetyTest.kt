package com.openobd.session

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openobd.OpenObdApp
import com.openobd.core.diagnosis.MilWatch
import com.openobd.core.obd.LinkQuality
import com.openobd.data.AutoBackup
import com.openobd.data.ConnectionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A fault lighting up while driving is told at once; the link quality; the weekly backup. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DrivingSafetyTest {
    private fun waitFor(timeoutMs: Long = 60_000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) { check(System.currentTimeMillis() < end) { "Timed out waiting" }; Thread.sleep(50) }
    }

    @Test fun aNewFaultWhileDrivingIsToldWithWhatItIs() {
        val c = ApplicationProvider.getApplicationContext<OpenObdApp>().container
        runBlocking { c.settings.update { it.copy(connectionType = ConnectionType.DEMO, autoConnect = false, tripGps = false, onboarded = true, language = "en") } }
        waitFor { c.garage.loaded && c.settings.settings.value.connectionType == ConnectionType.DEMO }
        c.session.connect()
        try {
            waitFor { c.session.state.value is ConnectionState.Connected }
            // The codes already there when the drive starts were read on connecting: no alert for them.
            waitFor { c.session.dtcScan.value is Op.Done && c.session.live.readings.value.containsKey(MilWatch.MIL) }
            Thread.sleep(2000)
            assertTrue(c.session.milAlert.lastAlert == null)
            // A misfire... no, a lean mixture appears on the way.
            c.session.demoFaultForTest("P0171")
            waitFor { c.session.milAlert.lastAlert != null }
            val a = c.session.milAlert.lastAlert!!
            assertEquals(MilWatch.LIT, a.text)
            assertEquals("P0171", a.args[0])
            assertTrue(a.notify)
        } finally {
            c.session.disconnect()
            waitFor { c.session.state.value is ConnectionState.Disconnected }
        }
    }

    @Test fun theWatchOnlyFiresOnSomethingNew() {
        val w = MilWatch()
        fun cycle(mil: Double, n: Double) = w.onCycle(mapOf(MilWatch.MIL to mil, MilWatch.COUNT to n), setOf(MilWatch.MIL, MilWatch.COUNT))
        assertFalse(cycle(1.0, 2.0)) // already on at the start
        assertFalse(cycle(1.0, 2.0))
        // A module answering 0 once (another ECU, a glitch) is not a change.
        assertFalse(cycle(0.0, 0.0)); assertFalse(cycle(1.0, 2.0))
        assertFalse(cycle(1.0, 3.0)) // one more code: seen once...
        assertTrue(cycle(1.0, 3.0)) // ...and confirmed
        assertFalse(cycle(1.0, 3.0))
        assertFalse(cycle(0.0, 0.0)); assertFalse(cycle(0.0, 0.0)) // cleared
        assertFalse(cycle(1.0, 1.0)); assertTrue(cycle(1.0, 1.0)) // lit again
        assertFalse(w.onCycle(mapOf(MilWatch.MIL to 1.0, MilWatch.COUNT to 5.0), emptySet())) // not a fresh reading
    }

    @Test fun linkQualityLevels() {
        assertEquals(LinkQuality.Level.GOOD, LinkQuality(6.0, 0, 0, 0).level)
        assertEquals(LinkQuality.Level.FAIR, LinkQuality(6.0, 4, 0, 0).level)
        assertEquals(LinkQuality.Level.FAIR, LinkQuality(6.0, 0, 1, 0).level)
        assertEquals(LinkQuality.Level.FAIR, LinkQuality(1.5, 0, 0, 0).level)
        assertEquals(LinkQuality.Level.POOR, LinkQuality(6.0, 0, 0, 2).level)
        assertEquals(LinkQuality.Level.POOR, LinkQuality(0.2, 0, 0, 0).level)
    }

    @Test fun theWeeklyBackupKeepsTheLastFour() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<OpenObdApp>()
        val dir = File(ctx.cacheDir, "backups-test").apply { deleteRecursively(); mkdirs() }
        File(dir, "openobd-backup-20260101.zip").writeText("by hand")
        val folder = object : AutoBackup.Folder {
            override fun create(name: String) = File(dir, name).outputStream()
            override fun names() = dir.list().orEmpty().toList()
            override fun delete(name: String) { File(dir, name).delete() }
        }
        val b = AutoBackup(ctx.getSharedPreferences("auto_backup_test", 0).also { it.edit().clear().commit() }, { out -> out.write("zip".toByteArray()); 7 }) { folder }
        val t0 = 1_800_000_000_000L
        assertFalse(b.runIfDue(t0)) // off
        b.enable("content://test/tree")
        assertTrue(b.runIfDue(t0))
        assertFalse(b.runIfDue(t0 + 86_400_000L)) // a day later: not due
        for (w in 1..6) assertTrue(b.runIfDue(t0 + w * AutoBackup.WEEK_MS))
        val auto = dir.list().orEmpty().filter { it.startsWith(AutoBackup.PREFIX) }
        assertEquals(AutoBackup.KEEP, auto.size)
        assertTrue(File(dir, "openobd-backup-20260101.zip").exists()) // ones saved by hand are not touched
        assertEquals(7, b.status.value.lastFiles)
        assertEquals(t0 + 6 * AutoBackup.WEEK_MS, b.status.value.lastAt)
    }
}
