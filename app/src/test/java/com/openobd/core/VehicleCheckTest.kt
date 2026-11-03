package com.openobd.core

import com.openobd.core.brand.DiagProtocol
import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ModuleDtc
import com.openobd.core.brand.ModuleScanResult
import com.openobd.core.obd.Readiness
import com.openobd.core.report.CheckInput
import com.openobd.core.report.CheckLevel
import com.openobd.core.report.CheckSection
import com.openobd.core.report.ItvVerdict
import com.openobd.core.report.Verdict
import com.openobd.core.report.VehicleCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleCheckTest {
    // PID 01: MIL off / on, compression (diesel) monitors, all complete or several incomplete
    private val readyDiesel = Readiness.decode(intArrayOf(0x00, 0x0F, 0xEB, 0x00))
    private val notReadyDiesel = Readiness.decode(intArrayOf(0x00, 0x0F, 0xEB, 0xEB))
    private val milOn = Readiness.decode(intArrayOf(0x82, 0x0F, 0xEB, 0x00))
    private val ecm = EcuModule("ECM", "ECM", 0x7E0, 0x7E8)
    private val ebcm = EcuModule("EBCM", "EBCM", 0x243, 0x643)

    private fun clean() = CheckInput(
        vin = "W0LPE8EE5E1000001", readiness = readyDiesel, stored = emptyList(), pending = emptyList(), permanent = emptyList(),
        modules = listOf(ModuleScanResult(ecm, true), ModuleScanResult(ebcm, true)),
        distanceSinceClearKm = 18_400.0, warmupsSinceClear = 255, distanceWithMilKm = 0.0,
        moduleVins = mapOf("EBCM" to "W0LPE8EE5E1000001"), batteryV = 14.1, engineRunning = true,
    )

    @Test
    fun cleanCarPasses() {
        val r = VehicleCheck.evaluate(clean())
        assertEquals(r.items.joinToString("\n"), Verdict.GOOD, r.verdict)
        assertEquals(ItvVerdict.PASS, r.itv)
        assertTrue(r.section(CheckSection.IDENTITY).single().level == CheckLevel.OK)
        assertEquals(CheckLevel.UNKNOWN, r.section(CheckSection.ODOMETER).single().level) // Astra J has no PID A6
    }

    @Test
    fun codesClearedToHideAFault() {
        val r = VehicleCheck.evaluate(clean().copy(
            readiness = notReadyDiesel, permanent = listOf("P2463"), distanceSinceClearKm = 12.0, warmupsSinceClear = 1,
        ))
        assertEquals(Verdict.BAD, r.verdict)
        assertEquals(ItvVerdict.FAIL, r.itv)
        val clearing = r.section(CheckSection.CLEARING)
        assertTrue(clearing.any { it.level == CheckLevel.FAIL && it.text.startsWith("Permanent codes remain") })
        assertTrue(clearing.any { it.level == CheckLevel.WARN && it.args == listOf("12", "1") })
        assertTrue(r.section(CheckSection.READINESS).any { it.level == CheckLevel.WARN })
    }

    @Test
    fun monitorsNotReadyIsNotYetFail() {
        val r = VehicleCheck.evaluate(clean().copy(readiness = notReadyDiesel, distanceSinceClearKm = 5000.0, warmupsSinceClear = 120))
        assertEquals(ItvVerdict.NOT_READY, r.itv)
        assertEquals(Verdict.CHECK, r.verdict)
    }

    @Test
    fun warningLightAndModuleFaults() {
        val r = VehicleCheck.evaluate(clean().copy(
            readiness = milOn, stored = listOf("P0401"), distanceWithMilKm = 840.0,
            modules = listOf(ModuleScanResult(ebcm, true, DiagProtocol.GMLAN, listOf(ModuleDtc("C0561", 0x71, 0x6F, DiagProtocol.GMLAN)))),
        ))
        assertEquals(Verdict.BAD, r.verdict)
        assertEquals(ItvVerdict.FAIL, r.itv)
        assertTrue(r.items.any { it.text.startsWith("Driven %1") && it.args == listOf("840") })
        assertTrue(r.section(CheckSection.MODULES).any { it.level == CheckLevel.WARN && it.args[1] == "EBCM C0561" })
    }

    @Test
    fun swappedModuleAndOdometerMismatch() {
        val r = VehicleCheck.evaluate(clean().copy(
            moduleVins = mapOf("EBCM" to "W0LPD8ED1C9999999"), obdOdometerKm = 128_451.0, recordedOdometerKm = 98_000.0,
        ))
        assertEquals(CheckLevel.WARN, r.section(CheckSection.IDENTITY).single().level)
        val odo = r.section(CheckSection.ODOMETER).single()
        assertEquals(CheckLevel.WARN, odo.level)
        assertEquals(listOf("128 451", "98 000"), odo.args)
    }

    @Test
    fun weakBatteryAndBadCharging() {
        assertEquals(CheckLevel.WARN, VehicleCheck.evaluate(clean().copy(batteryV = 11.9, engineRunning = false)).section(CheckSection.BATTERY).single().level)
        assertEquals(CheckLevel.WARN, VehicleCheck.evaluate(clean().copy(batteryV = 12.6, engineRunning = true)).section(CheckSection.BATTERY).single().level)
        assertEquals(CheckLevel.OK, VehicleCheck.evaluate(clean().copy(batteryV = 12.6, engineRunning = false)).section(CheckSection.BATTERY).single().level)
    }

    @Test
    fun everyTemplateIsListedForTranslation() {
        // Build a report that hits as many branches as possible and check each text is in the translation list.
        val inputs = listOf(clean(), clean().copy(readiness = milOn, stored = listOf("P0401"), pending = listOf("P0171"), permanent = listOf("P0401"),
            distanceSinceClearKm = 500.0, distanceWithMilKm = 3.0, obdOdometerKm = 1.0, batteryV = null, modules = listOf(ModuleScanResult(ebcm, false))),
            CheckInput(null, null, null, null, null))
        for (i in inputs) for (item in VehicleCheck.evaluate(i).items) assertTrue(item.text, item.text in VehicleCheck.texts)
    }
}
