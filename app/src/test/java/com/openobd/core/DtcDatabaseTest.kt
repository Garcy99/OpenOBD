package com.openobd.core

import com.openobd.core.dtc.DtcDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DtcDatabaseTest {
    private val assets = File("src/main/assets/dtc").takeIf { it.exists() } ?: File("app/src/main/assets/dtc")
    private val db = DtcDatabase(File(assets, "generic.tsv").inputStream(), File(assets, "causes.tsv").inputStream())

    /** Well-known codes pinned so a bad data import can never ship silently again. */
    @Test
    fun wellKnownCodes() {
        val expected = mapOf(
            "P0100" to "Mass or Volume Air Flow",
            "P0128" to "Thermostat",
            "P0171" to "System Too Lean (Bank 1)",
            "P0174" to "System Too Lean (Bank 2)",
            "P0300" to "Random/Multiple Cylinder Misfire",
            "P0301" to "Cylinder 1 Misfire",
            "P0308" to "Cylinder 8 Misfire",
            "P0335" to "Crankshaft Position Sensor",
            "P0340" to "Camshaft Position Sensor",
            "P0401" to "Flow Insufficient",
            "P0420" to "Catalyst System Efficiency Below Threshold (Bank 1)",
            "P0430" to "Catalyst System Efficiency Below Threshold (Bank 2)",
            "P0442" to "Small Leak",
            "P0455" to "Large Leak",
            "P0500" to "Vehicle Speed Sensor",
            "P0505" to "Idle Air Control",
            "P0562" to "System Voltage Low",
            "P0700" to "Transmission Control System",
            "P0715" to "Input/Turbine Speed Sensor",
            "P0741" to "Torque Converter Clutch",
            "P2096" to "Post Catalyst Fuel Trim System Too Lean",
            "U0100" to "Lost Communication With ECM/PCM",
        )
        for ((code, text) in expected) {
            val d = db.lookup(code)
            assertTrue("$code should be known", d.known)
            assertTrue("$code: '${d.description}' should contain '$text'", d.description.contains(text, ignoreCase = true))
        }
    }

    @Test
    fun knowledgeAndFallback() {
        assertTrue(db.size > 1200)
        assertEquals(3, db.lookup("P0301").knowledge?.severity)
        val unknown = db.lookup("P1234")
        assertFalse(unknown.known)
        assertTrue(unknown.description.contains("Manufacturer-specific"))
        assertTrue(db.search("catalyst").any { it.code == "P0420" })
    }

    @Test
    fun spanishDescriptionsCoverEveryCode() {
        val en = File(assets, "generic.tsv").readLines().filter { !it.startsWith("#") && it.contains('\t') }.map { it.substringBefore('\t') }
        val es = File(assets, "generic.es.tsv").readLines().filter { !it.startsWith("#") && it.contains('\t') }.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals(en.toSet(), es.keys)
        es.forEach { (code, d) -> assertTrue("$code: $d", d.isNotBlank() && !d.contains("#") || code == "C0060") }
        val localized = DtcDatabase(File(assets, "generic.tsv").inputStream(), File(assets, "causes.tsv").inputStream(), File(assets, "generic.es.tsv").inputStream())
        assertEquals("Cylinder 1 Misfire Detected", localized.lookup("P0301").description)
        localized.useLocalized = true
        assertEquals("Fallo de encendido en el cilindro 1", localized.lookup("P0301").description)
        assertEquals("Filtro de partículas - acumulación de hollín", localized.lookup("P2463").description)
        assertTrue(localized.search("hollín").any { it.code == "P2463" })
    }
}
