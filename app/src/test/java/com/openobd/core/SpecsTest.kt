package com.openobd.core

import com.openobd.core.specs.Consumption
import com.openobd.core.specs.DgtLabel
import com.openobd.core.specs.EeaCatalog
import com.openobd.core.specs.EpaCatalog
import com.openobd.core.specs.Http
import com.openobd.core.specs.NhtsaVin
import com.openobd.core.specs.RdwClient
import com.openobd.core.specs.SpecCountries
import com.openobd.core.specs.SpecFuel
import com.openobd.core.specs.SpecSource
import com.openobd.core.specs.SpecsException
import com.openobd.core.specs.Spain
import com.openobd.core.specs.brandForMake
import com.openobd.core.specs.prettyName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder
import java.util.Calendar
import java.util.TimeZone

/** Replays responses captured from the real services (app/src/test/resources/fixtures). */
class FixtureHttp(private val routes: List<Pair<String, String>>) : Http {
    val requests = mutableListOf<String>()
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()
    private fun route(key: String) = routes.firstOrNull { key.contains(it.first) }?.second?.let(::fixture) ?: error("No fixture for $key")
    override suspend fun get(url: String, headers: Map<String, String>) = route(url).also { requests += url }
    override suspend fun post(url: String, body: String, contentType: String, headers: Map<String, String>): String {
        val decoded = URLDecoder.decode(body, "UTF-8")
        requests += decoded
        return route(decoded)
    }
}

class SpecsTest {
    private fun date(y: Int, m: Int, d: Int) = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear(); set(y, m - 1, d) }.timeInMillis

    @Test
    fun eeaVersionsForSpain() = runBlocking {
        val http = FixtureHttp(listOf("GROUP BY UPPER(Ft)" to "eea_versions.json"))
        val eea = EeaCatalog(http)
        val versions = eea.versions("ES", 2019, "SEAT", "LEON")
        val top = versions.first()
        assertEquals(SpecFuel.PETROL, top.fuel)
        assertEquals(1498, top.engineCc)
        assertEquals(96.0, top.powerKw!!, 0.01)
        assertEquals(17216L, top.registrations)
        assertTrue(versions.any { it.fuel == SpecFuel.DIESEL && it.engineCc == 1598 })
        val sql = http.requests.single()
        assertTrue(sql, sql.contains("MS = 'ES'") && sql.contains("UPPER(Mk) = 'SEAT'") && sql.contains("Year = 2019"))
        val specs = eea.toSpecs("ES", 2019, "SEAT", "LEON", top, 0)
        assertEquals("WLTP", specs.co2Cycle)
        assertTrue(specs.consumptionEstimated)
        assertEquals(133 / 23.2, specs.consumptionL100!!, 0.01) // ~5.7 L/100 km
        assertEquals("1.5 L · 96 kW (131 CV)", specs.engineLabel())
    }

    @Test
    fun eeaEscapesQuotes() {
        assertEquals("'D''ARCY'", EeaCatalog.sqlString("D'ARCY"))
        assertEquals("EL", SpecCountries.eeaCode("GR"))
        assertEquals(SpecFuel.PHEV_PETROL, EeaCatalog.fuelOf("PETROL/ELECTRIC", "P"))
        assertEquals(SpecFuel.HYBRID_PETROL, EeaCatalog.fuelOf("petrol/electric", "H"))
        assertEquals(SpecFuel.CNG, EeaCatalog.fuelOf("NG-BIOMETHANE", "B"))
        assertEquals(SpecFuel.ELECTRIC, EeaCatalog.fuelOf("ELECTRIC", "E"))
    }

    @Test
    fun rdwPlate() = runBlocking {
        val http = FixtureHttp(listOf("m9d7-ebf2" to "rdw_car.json", "8ys7-d773" to "rdw_fuel.json"))
        val s = RdwClient(http).lookup("hlz-85-b", 0)
        assertEquals("VOLKSWAGEN", s.make)
        assertEquals("CADDY", s.model)
        assertEquals(2021, s.year)
        assertEquals(SpecFuel.PETROL, s.fuel)
        assertEquals(1498, s.engineCc)
        assertEquals(84.0, s.powerKw!!, 0.01)
        assertEquals(148.0, s.co2Gkm!!, 0.01)
        assertEquals("WLTP", s.co2Cycle)
        assertEquals(date(2027, 6, 17), s.inspectionDue)
        assertTrue(http.requests.first().endsWith("kenteken=HLZ85B"))
    }

    @Test
    fun epaVehicleAndSingleItemMenus() = runBlocking {
        val http = FixtureHttp(listOf("menu/options" to "epa_options.json", "menu/model" to "epa_models.json", "/41096" to "epa_vehicle.json"))
        val epa = EpaCatalog(http)
        assertTrue("Civic 5Dr" in epa.models(2019, "Honda"))
        assertEquals("41096", epa.options(2019, "Honda", "Civic 5Dr").first().id)
        val v = epa.vehicle("41096", 0)
        assertEquals("Honda", v.make)
        assertEquals(1500, v.engineCc)
        assertEquals(235.215 / 32, v.consumptionL100!!, 0.01)
        assertEquals(SpecFuel.PETROL, v.fuel)
    }

    @Test
    fun nhtsaVin() = runBlocking {
        val s = NhtsaVin(FixtureHttp(listOf("1HGCM82633A004352" to "nhtsa.json"))).decode("1HGCM82633A004352", 0)
        assertEquals("HONDA", s.make)
        assertEquals("Accord", s.model)
        assertEquals(2003, s.year)
        assertEquals(2999, s.engineCc)
        assertEquals(240 / 1.34102, s.powerKw!!, 0.1)
        val err = runCatching { NhtsaVin(FixtureHttp(listOf("VSS" to "nhtsa_eu.json"))).decode("VSSZZZ5FZKR000001", 0) }.exceptionOrNull()
        assertTrue(err is SpecsException)
    }

    @Test
    fun consumptionFromCo2() {
        assertEquals(5.0, Consumption.fromCo2(SpecFuel.PETROL, 116.0)!!, 0.01)
        assertEquals(5.0, Consumption.fromCo2(SpecFuel.DIESEL, 132.0)!!, 0.01)
        assertNull(Consumption.fromCo2(SpecFuel.ELECTRIC, 0.0))
        assertEquals(7.84, Consumption.mpgUsToL100(30.0)!!, 0.01)
    }

    @Test
    fun spanishItv() {
        val reg = date(2015, 3, 10)
        assertEquals(date(2019, 3, 10), Spain.nextItv(reg, date(2018, 1, 1)))   // first at 4 years
        assertEquals(date(2021, 3, 10), Spain.nextItv(reg, date(2019, 6, 1)))   // then every 2 years
        assertEquals(date(2025, 3, 10), Spain.nextItv(reg, date(2023, 4, 1)))   // 10th anniversary
        assertEquals(date(2026, 3, 10), Spain.nextItv(reg, date(2025, 4, 1)))   // then yearly
        assertEquals(date(2027, 3, 10), Spain.nextItv(reg, date(2026, 11, 3)))
    }

    @Test
    fun dgtLabels() {
        assertEquals(DgtLabel.C, Spain.dgtLabel(SpecFuel.PETROL, 2019))
        assertEquals(DgtLabel.B, Spain.dgtLabel(SpecFuel.PETROL, 2003))
        assertEquals(DgtLabel.NONE, Spain.dgtLabel(SpecFuel.PETROL, 1998))
        assertEquals(DgtLabel.C, Spain.dgtLabel(SpecFuel.DIESEL, 2016))
        assertEquals(DgtLabel.B, Spain.dgtLabel(SpecFuel.DIESEL, 2010))
        assertEquals(DgtLabel.NONE, Spain.dgtLabel(SpecFuel.DIESEL, 2004))
        assertEquals(DgtLabel.C, Spain.dgtLabel(SpecFuel.DIESEL, 2013, euroNorm = "EURO 6"))
        assertEquals(DgtLabel.ECO, Spain.dgtLabel(SpecFuel.HYBRID_PETROL, 2020))
        assertEquals(DgtLabel.ECO, Spain.dgtLabel(SpecFuel.LPG, 2018))
        assertEquals(DgtLabel.CERO, Spain.dgtLabel(SpecFuel.PHEV_PETROL, 2021, electricRangeKm = 55))
        assertEquals(DgtLabel.ECO, Spain.dgtLabel(SpecFuel.PHEV_PETROL, 2021, electricRangeKm = 30))
        assertEquals(DgtLabel.CERO, Spain.dgtLabel(SpecFuel.ELECTRIC, 2022))
    }

    @Test
    fun countriesBrandsAndNames() {
        assertEquals(listOf(SpecSource.EEA_CATALOG), SpecCountries.sources("ES"))
        assertEquals(SpecSource.RDW_PLATE, SpecCountries.sources("NL").first())
        assertTrue(SpecSource.EPA_CATALOG in SpecCountries.sources("US"))
        assertEquals("seat", brandForMake("SEAT")?.id)
        assertEquals("mercedes", brandForMake("MERCEDES-BENZ")?.id)
        assertEquals("citroen", brandForMake("CITROEN")?.id)
        assertEquals("skoda", brandForMake("SKODA")?.id)
        assertEquals("landrover", brandForMake("LAND ROVER")?.id)
        assertEquals("Seat Leon", prettyName("SEAT LEON"))
        assertEquals("BMW X3", prettyName("BMW X3"))
    }
}
