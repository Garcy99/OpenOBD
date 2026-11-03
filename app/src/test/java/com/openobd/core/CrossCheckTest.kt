package com.openobd.core

import com.openobd.core.specs.CrossCheck
import com.openobd.core.specs.CrossFinding
import com.openobd.core.specs.CrossLevel
import com.openobd.core.specs.EeaVersion
import com.openobd.core.specs.ObservedCar
import com.openobd.core.specs.SpecFuel
import com.openobd.core.specs.VehicleSpecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossCheckTest {
    private val astraVin = "W0LPE8EH0E8012345" // Opel, model year E = 2014
    private val astra = VehicleSpecs(make = "OPEL", model = "ASTRA SPORTS TOURER", year = 2014, fuel = SpecFuel.DIESEL, engineCc = 1598, powerKw = 100.0,
        country = "ES", source = "Manual")

    private fun diesel(cc: Int, kw: Double, n: Long) = EeaVersion(SpecFuel.DIESEL, cc, kw, 1450, null, 112.0, 4.3, null, null, n)
    private fun petrol(cc: Int, kw: Double, n: Long) = EeaVersion(SpecFuel.PETROL, cc, kw, 1350, null, 140.0, 6.0, null, null, n)
    private fun List<CrossFinding>.levels() = map { it.level }
    private fun List<CrossFinding>.has(level: CrossLevel, fragment: String) = any { it.level == level && it.text.contains(fragment) }

    @Test
    fun everythingAgreesForTheAstra() {
        val f = CrossCheck.check(astra, astraVin, ObservedCar(astraVin, fuelType = 4, compressionIgnition = true),
            listOf(diesel(1598, 100.0, 900), diesel(1598, 81.0, 300), petrol(1364, 103.0, 500)), currentYear = 2026)
        assertTrue(f.toString(), f.none { it.level != CrossLevel.MATCH })
        assertTrue(f.has(CrossLevel.MATCH, "VIN maker"))
        assertTrue(f.has(CrossLevel.MATCH, "model year"))
        assertTrue(f.has(CrossLevel.MATCH, "fuel type"))
        assertEquals(listOf("900"), f.first { it.text.contains("EU registration catalog") }.args)
    }

    @Test
    fun wrongCarFromAPlateLookupIsFlagged() {
        val leon = VehicleSpecs(make = "SEAT", model = "LEON", year = 2014, fuel = SpecFuel.PETROL, country = "ES", source = "Manual")
        val f = CrossCheck.check(leon, null, ObservedCar(astraVin, fuelType = null, compressionIgnition = true), currentYear = 2026)
        assertTrue(f.has(CrossLevel.MISMATCH, "VIN belongs to"))
        val fuel = f.first { it.text.contains("engine computer reports") }
        assertEquals(CrossLevel.MISMATCH, fuel.level)
        assertEquals(listOf("Diesel", "Petrol"), fuel.args)
    }

    @Test
    fun aDifferentCarConnectedIsFlagged() {
        val f = CrossCheck.check(astra, astraVin, ObservedCar("WVWZZZ1KZEW123456"), currentYear = 2026)
        assertTrue(f.has(CrossLevel.MISMATCH, "not the one saved"))
        // The VIN of the connected car is the one compared with the saved make
        assertTrue(f.has(CrossLevel.MISMATCH, "VIN belongs to"))
    }

    @Test
    fun europeanYearCodesAreOnlyAHint() {
        val f = CrossCheck.check(astra.copy(year = 2010), astraVin, null, currentYear = 2026)
        assertTrue(f.has(CrossLevel.INFO, "registration certificate"))
        assertTrue(f.none { it.level == CrossLevel.MISMATCH })
    }

    @Test
    fun northAmericanVinIsCheckedStrictly() {
        val accord = VehicleSpecs(make = "HONDA", model = "ACCORD", year = 2003, fuel = SpecFuel.PETROL, source = "NHTSA")
        val good = CrossCheck.check(accord, "1HGCM82633A004352", null, currentYear = 2026)
        assertTrue(good.toString(), good.none { it.level == CrossLevel.MISMATCH })
        assertTrue(CrossCheck.check(accord.copy(year = 2009), "1HGCM82633A004352", null, currentYear = 2026).has(CrossLevel.MISMATCH, "saved year is"))
        assertTrue(CrossCheck.check(accord, "1HGCM82643A004352", null, currentYear = 2026).has(CrossLevel.MISMATCH, "check digit"))
    }

    @Test
    fun catalogComparison() {
        assertTrue(CrossCheck.check(astra, null, null, emptyList()).has(CrossLevel.INFO, "not found in the EU"))
        val noMatch = CrossCheck.check(astra, null, null, listOf(petrol(1598, 100.0, 50), diesel(1956, 121.0, 40)))
        assertTrue(noMatch.has(CrossLevel.INFO, "Check the engine data"))
        // Specs that came from the EU catalog are not compared with themselves
        assertTrue(CrossCheck.check(astra.copy(source = "EEA (ES, 2014)"), null, null, emptyList()).none { it.text.contains("EU") })
    }

    @Test
    fun hybridsAndMissingData() {
        val hybrid = VehicleSpecs(make = "TOYOTA", model = "C-HR", year = 2020, fuel = SpecFuel.HYBRID_PETROL, source = "Manual")
        assertEquals(listOf(CrossLevel.MATCH), CrossCheck.check(hybrid, null, ObservedCar(fuelType = 8)).levels())
        assertEquals(listOf(CrossLevel.MATCH), CrossCheck.check(hybrid, null, ObservedCar(fuelType = 17)).levels())
        assertEquals(listOf(CrossLevel.MISMATCH), CrossCheck.check(hybrid, null, ObservedCar(fuelType = 19)).levels())
        // Fuel type "not available" falls back to the ignition type
        assertEquals(listOf(CrossLevel.MATCH), CrossCheck.check(astra, null, ObservedCar(fuelType = 0, compressionIgnition = true)).levels())
        assertTrue(CrossCheck.check(astra.copy(source = "EEA"), null, null).single().text.startsWith("Connect to the car"))
    }

    @Test
    fun vinMakers() {
        assertEquals("opel", CrossCheck.vinBrandId(astraVin))
        assertEquals("volkswagen", CrossCheck.vinBrandId("WVWZZZ1KZEW123456"))
        assertEquals("seat", CrossCheck.vinBrandId("VSSZZZ5FZER123456"))
        assertEquals("ford", CrossCheck.vinBrandId("WF0XXXGCDXEA12345"))
        assertEquals(null, CrossCheck.vinBrandId("XXX00000000000000"))
    }
}
