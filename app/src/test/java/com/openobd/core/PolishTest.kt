package com.openobd.core

import com.openobd.core.log.LogCsv
import com.openobd.data.AppSettings
import com.openobd.data.Backup
import com.openobd.data.ConnectionType
import com.openobd.data.ThemeMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PolishTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun backupRoundTrip() {
        val src = Files.createTempDirectory("src").toFile()
        java.io.File(src, "garage.json").writeText("""{"vehicles":[]}""")
        java.io.File(src, "scan_history.json").writeText("[]")
        java.io.File(src, "tracks").mkdirs(); java.io.File(src, "tracks/t1.json").writeText("[1]")
        java.io.File(src, "logs").mkdirs(); java.io.File(src, "logs/obd_1.csv").writeText("time_s,\"RPM\"\n0,800\n")
        java.io.File(src, "secret.txt").writeText("not included")
        val settings = AppSettings(connectionType = ConnectionType.DEMO, theme = ThemeMode.AMOLED, claudeApiKey = "")
        val out = ByteArrayOutputStream()
        val written = Backup.write(out, src, json.encodeToString(AppSettings.serializer(), settings), "1.5.0")
        assertEquals(6, written)

        val dst = Files.createTempDirectory("dst").toFile()
        val c = Backup.read(ByteArrayInputStream(out.toByteArray()), dst)
        assertEquals(4, c.files)
        assertEquals("[1]", java.io.File(dst, "tracks/t1.json").readText())
        assertTrue(java.io.File(dst, "logs/obd_1.csv").exists())
        assertFalse(java.io.File(dst, "secret.txt").exists())
        val restored = json.decodeFromString(AppSettings.serializer(), c.settingsJson!!)
        assertEquals(ConnectionType.DEMO, restored.connectionType)
        assertEquals(ThemeMode.AMOLED, restored.theme)
    }

    @Test
    fun maliciousOrForeignZipsAreRejected() {
        val dst = Files.createTempDirectory("dst").toFile()
        // Not a backup at all
        val foreign = ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { z -> z.putNextEntry(ZipEntry("x.txt")); z.write(1); z.closeEntry() } }
        try { Backup.read(ByteArrayInputStream(foreign.toByteArray()), dst); fail() } catch (e: IllegalArgumentException) { }
        // A backup with path traversal entries: those are ignored
        val evil = ByteArrayOutputStream().also { b ->
            ZipOutputStream(b).use { z ->
                fun put(n: String, s: String) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray()); z.closeEntry() }
                put(Backup.MANIFEST, """{"format":1,"app":"OpenOBD"}""")
                put("../evil.json", "x"); put("tracks/../../evil.json", "x"); put("logs/ok.csv", "time_s\n"); put("tracks/a.sh", "x")
            }
        }
        val c = Backup.read(ByteArrayInputStream(evil.toByteArray()), dst)
        assertEquals(1, c.files)
        assertFalse(java.io.File(dst.parentFile, "evil.json").exists())
        assertNull(c.settingsJson)
    }

    @Test
    fun parsesLoggerCsv() {
        val text = "# VIN: W0LPE8EE5E1000001\ntime_s,\"Engine RPM (rpm)\",\"Speed, vehicle (km/h)\"\n0.000,800,0\n0.500,,12\n1.000,1500,20\n"
        val log = LogCsv.parse(text)
        assertEquals("W0LPE8EE5E1000001", log.vin)
        assertEquals(listOf("Engine RPM (rpm)", "Speed, vehicle (km/h)"), log.columns)
        assertEquals(3, log.times.size)
        assertEquals(800.0, log.valueAt(0, 0.7)!!, 0.0) // empty cell -> last value
        assertEquals(1150.0, log.stats(0)!!.avg, 0.001)
        assertEquals(2, log.column(0).size)
        assertEquals(1.0, log.durationS, 0.0)
        try { LogCsv.parse("a,b\n1,2"); fail() } catch (e: IllegalArgumentException) { }
    }
}
