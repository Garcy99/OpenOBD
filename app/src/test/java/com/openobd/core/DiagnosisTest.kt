package com.openobd.core

import com.openobd.core.diagnosis.AssistantContext
import com.openobd.core.diagnosis.Diagnosis
import com.openobd.core.diagnosis.DiagnosisContext
import com.openobd.core.diagnosis.DriveAdvice
import com.openobd.core.dtc.DtcDatabase
import com.openobd.core.obd.TestResult
import com.openobd.data.ChatTurn
import com.openobd.data.DiagnosisAssistant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DiagnosisTest {
    private val assets = File("src/main/assets/dtc").takeIf { it.exists() } ?: File("app/src/main/assets/dtc")
    private val db = DtcDatabase(File(assets, "generic.tsv").inputStream(), File(assets, "causes.tsv").inputStream())

    private fun explain(code: String, ctx: DiagnosisContext = DiagnosisContext()) = Diagnosis.explain(code, db.lookup(code).knowledge, ctx)
    private fun misfires(vararg perCyl: Pair<Int, Double>) = perCyl.map { (cyl, n) -> TestResult(0xA1 + cyl, 0x0B, 0x24, n, 0.0, 65535.0, "count", "7E8") }

    @Test
    fun leanAtIdleOnlyPointsToAVacuumLeak() {
        val e = explain("P0171", DiagnosisContext(freezeFrame = mapOf("0107" to 18.0, "010C" to 780.0), live = mapOf("0107" to 2.0, "010C" to 2500.0)))
        assertTrue(e.causes.first().cause, e.causes.first().cause.contains("Vacuum leak"))
        assertTrue(e.causes.first().evidence.isNotEmpty())
        assertEquals(DriveAdvice.OK, e.advice)
    }

    @Test
    fun leanEverywherePointsToFuelOrMaf() {
        val e = explain("P0171", DiagnosisContext(freezeFrame = mapOf("0107" to 18.0, "010C" to 780.0), live = mapOf("0107" to 16.0, "010C" to 2600.0)))
        val top = e.causes.first().cause
        assertTrue(top, Regex("(?i)MAF|fuel pump").containsMatchIn(top))
    }

    @Test
    fun singleCylinderMisfireBlamesCoilOrPlugAndSaysStop() {
        val e = explain("P0301", DiagnosisContext(mode06 = misfires(1 to 34.0)))
        assertTrue(Regex("(?i)coil|plug").containsMatchIn(e.causes.first().cause))
        assertEquals(DriveAdvice.STOP, e.advice)
    }

    @Test
    fun misfireOnManyCylindersPointsToACommonCause() {
        val e = explain("P0300", DiagnosisContext(mode06 = misfires(1 to 5.0, 2 to 7.0, 4 to 6.0)))
        assertTrue(e.causes.first().cause, Regex("(?i)vacuum|fuel|compression").containsMatchIn(e.causes.first().cause))
    }

    @Test
    fun catalystCodeWithMisfireFixesTheMisfireFirst() {
        val e = explain("P0420", DiagnosisContext(otherCodes = listOf("P0301")))
        assertTrue(e.causes.first().cause, Regex("(?i)misfire").containsMatchIn(e.causes.first().cause))
        assertEquals(DriveAdvice.OK, e.advice)
    }

    @Test
    fun dieselParticulateFilterWithShortTrips() {
        val e = explain("P2463", DiagnosisContext(isDiesel = true, averageTripKm = 6.5))
        assertEquals(DriveAdvice.CAUTION, e.advice)
        assertTrue(e.adviceReason.contains("regenerate"))
        assertTrue(e.causes.first().cause, e.causes.first().cause.contains("short trips"))
        assertEquals(listOf("6.5"), e.causes.first().evidence.single().args)
    }

    @Test
    fun thermostatStuckOpen() {
        val e = explain("P0128", DiagnosisContext(live = mapOf("0105" to 62.0), runMinutes = 25.0))
        assertTrue(e.causes.first().cause.contains("Thermostat"))
    }

    @Test
    fun overheatingMeansStop() {
        assertEquals(DriveAdvice.STOP, explain("P0128", DiagnosisContext(live = mapOf("0105" to 118.0))).advice)
    }

    @Test
    fun lowVoltageExplainsCommunicationCodes() {
        val e = explain("U0100", DiagnosisContext(otherCodes = listOf("U0121", "U0140"), batteryV = 11.4))
        assertTrue(e.causes.first().cause.contains("battery voltage", ignoreCase = true))
        assertEquals(1, e.causes.count { it.cause.contains("battery voltage", ignoreCase = true) })
    }

    @Test
    fun absCodeIsCaution() {
        assertEquals(DriveAdvice.CAUTION, explain("C0035").advice)
        assertEquals(DriveAdvice.CAUTION, explain("C0561").advice)
    }

    @Test
    fun everyTextIsListedForTranslation() {
        val cases = listOf(
            "P0171" to DiagnosisContext(freezeFrame = mapOf("0107" to 18.0, "010C" to 780.0), live = mapOf("0107" to 16.0, "010C" to 2600.0)),
            "P0172" to DiagnosisContext(freezeFrame = mapOf("0107" to -15.0)),
            "P0300" to DiagnosisContext(mode06 = misfires(1 to 5.0, 2 to 7.0, 4 to 6.0)),
            "P0420" to DiagnosisContext(otherCodes = listOf("P0301"), mode06 = listOf(TestResult(0x21, 0x80, 0x24, 82.0, 0.0, 64.0, "", "7E8"))),
            "P2463" to DiagnosisContext(averageTripKm = 3.0), "P0128" to DiagnosisContext(live = mapOf("0105" to 60.0), runMinutes = 30.0),
            "U0100" to DiagnosisContext(otherCodes = listOf("U0121", "U0140"), batteryV = 11.0), "B0001" to DiagnosisContext(),
            "P0016" to DiagnosisContext(), "P0520" to DiagnosisContext(), "P0299" to DiagnosisContext(), "P1234" to DiagnosisContext(), "B1A4F" to DiagnosisContext(),
        )
        for ((code, ctx) in cases) {
            val e = explain(code, ctx)
            assertTrue(e.adviceReason, e.adviceReason in Diagnosis.texts)
            e.nextSteps.forEach { assertTrue(it, it in Diagnosis.texts) }
            e.causes.flatMap { it.evidence }.forEach { assertTrue(it.text, it.text in Diagnosis.texts) }
        }
    }

    @Test
    fun assistantKeepsHistoryAndSendsNoVin() = runBlocking {
        val seen = mutableListOf<List<ChatTurn>>()
        var fail = false
        val assistant = DiagnosisAssistant({ _, turns -> seen += turns; if (fail) error("offline"); "answer ${turns.size}" }, "es")
        val context = AssistantContext.build(
            AssistantContext.Car("Opel Astra Sports Tourer 1.6 CDTI 2014", "Diesel", "ISO 15765-4 CAN (11 bit, 500 kbaud)"),
            AssistantContext.Code("P2463", "Diesel particulate filter - soot accumulation", "Stored"),
            explain("P2463", DiagnosisContext(averageTripKm = 5.0)), emptyList(), mapOf("Engine RPM" to "1800 rpm"), emptyMap(), emptyList(), "5/6 ready", 12.4,
        )
        assertTrue(context.contains("Opel Astra") && context.contains("P2463") && context.contains("Engine RPM: 1800 rpm"))
        assertFalse(context.contains("VIN"))
        assertTrue(assistant.system.contains("Spanish"))
        assertEquals("answer 1", assistant.start(context, "¿Qué hago?"))
        assertEquals("answer 3", assistant.ask("¿Puedo conducir?"))
        assertTrue(seen.last()[0].text.startsWith(context))
        fail = true
        runCatching { assistant.ask("otra") }
        // A failed question is not kept, so the next attempt sends a clean history.
        fail = false
        assistant.ask("otra")
        assertEquals(listOf(true, false, true, false, true), seen.last().map { it.fromUser })
    }
}
