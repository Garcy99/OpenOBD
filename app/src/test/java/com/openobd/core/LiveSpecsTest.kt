package com.openobd.core

import com.openobd.core.specs.EeaCatalog
import com.openobd.core.specs.EpaCatalog
import com.openobd.core.specs.NhtsaVin
import com.openobd.core.specs.RdwClient
import com.openobd.core.specs.UrlHttp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talks to the real services. Run with -PliveSpecs=true; skipped otherwise so CI never depends on them. */
class LiveSpecsTest {
    private val http = UrlHttp()

    @Test
    fun liveServices() = runBlocking {
        assumeTrue(System.getProperty("liveSpecs") == "true")
        val eea = EeaCatalog(http)
        val years = eea.years("ES")
        println("EEA ES years: $years")
        val makes = eea.makes("ES", 2019)
        println("EEA ES 2019 top makes: ${makes.take(5)}")
        val models = eea.models("ES", 2019, "SEAT")
        println("SEAT models: ${models.take(5)}")
        val versions = eea.versions("ES", 2019, "SEAT", "LEON")
        println("Leon versions: ${versions.take(3)}")
        assertTrue(years.contains(2019) && makes.any { it.name == "SEAT" } && models.any { it.name == "LEON" } && versions.isNotEmpty())
        println("EEA FR 2021 Clio: ${eea.versions("FR", 2021, "RENAULT", "CLIO").take(2)}")

        val rdw = RdwClient(http).lookup("HLZ-85-B", 0)
        println("RDW: $rdw")
        assertTrue(rdw.make == "VOLKSWAGEN")

        val epa = EpaCatalog(http)
        val opt = epa.options(2019, "Honda", "Civic 5Dr").first()
        println("EPA: ${epa.vehicle(opt.id, 0)}")
        println("NHTSA: ${NhtsaVin(http).decode("1HGCM82633A004352", 0)}")
    }
}
