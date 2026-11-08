package com.openobd.core

import com.openobd.core.brand.Equation
import com.openobd.core.brand.Obdb
import com.openobd.core.brand.ObdbEntry
import com.openobd.core.brand.PidPacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObdbTest {
    private fun eval(formula: String, vararg bytes: Int) = Equation(formula).evaluate(bytes)!!

    @Test fun `one byte with offset`() {
        val f = Obdb.formula(0, 8, false, false, 1.0, 1.0, -40.0, -40.0, 215.0)
        assertEquals(50.0, eval(f, 90), 1e-9)
    }

    @Test fun `two bytes big endian with scaling`() {
        val f = Obdb.formula(0, 16, false, false, 1.0, 1000.0, 4.0, 4.0, 69.535)
        assertEquals(5.0, eval(f, 0x03, 0xE8), 1e-9)
    }

    @Test fun `value is clamped to the range`() {
        val f = Obdb.formula(0, 8, false, false, 1.0, 1.0, 0.0, 0.0, 100.0)
        assertEquals(100.0, eval(f, 250), 1e-9)
    }

    @Test fun `signed 32 bit`() {
        val f = Obdb.formula(0, 32, false, true, 1.0, 1000.0, 0.0, -1000.0, 1000.0)
        assertEquals(-1.0, eval(f, 0xFF, 0xFF, 0xFC, 0x18), 1e-9)
        assertEquals(2.0, eval(f, 0x00, 0x00, 0x07, 0xD0), 1e-9)
    }

    @Test fun `signed byte`() {
        val f = Obdb.formula(8, 8, false, true, 1.0, 1.0, 0.0, -128.0, 127.0)
        assertEquals(-2.0, eval(f, 0, 0xFE), 1e-9)
    }

    @Test fun `bits inside a byte are counted from the most significant bit`() {
        // bix 2, len 3 -> bits 5..3 of byte A
        val f = Obdb.formula(2, 3, false, false, 1.0, 1.0, 0.0, 0.0, 7.0)
        assertEquals(5.0, eval(f, 0b00101000), 1e-9)
        val bit = Obdb.formula(9, 1, false, false, 1.0, 1.0, 0.0, 0.0, 1.0)
        assertEquals(1.0, eval(bit, 0, 0b01000000), 1e-9)
        assertEquals(0.0, eval(bit, 0, 0b10111111), 1e-9)
    }

    @Test fun `field across a byte boundary`() {
        // bix 4, len 12 -> low nibble of A and all of B
        val f = Obdb.formula(4, 12, false, false, 1.0, 1.0, 0.0, 0.0, 4095.0)
        assertEquals(0xABC.toDouble(), eval(f, 0xFA, 0xBC), 1e-9)
    }

    @Test fun `little endian bytes`() {
        val f = Obdb.formula(0, 16, true, false, 1.0, 1.0, 0.0, 0.0, 65535.0)
        assertEquals(0x1234.toDouble(), eval(f, 0x34, 0x12), 1e-9)
    }

    @Test fun `bytes past Z use two-letter names`() {
        val f = Obdb.formula(26 * 8, 8, false, false, 2.0, 1.0, 0.0, 0.0, 510.0)
        assertTrue(f.contains("AA"))
        assertEquals(20.0, eval(f, *IntArray(27) { if (it == 26) 10 else 0 }), 1e-9)
    }

    private val sample = """
        { "commands": [
          { "hdr": "7E0", "cmd": {"22": "1A2b"}, "freq": 1,
            "signals": [
              {"id": "X_TEMP", "path": "Engine", "name": "Oil temperature", "fmt": {"len": 8, "add": -40, "min": -40, "max": 215, "unit": "celsius"}},
              {"id": "X_SOOT", "path": "DPF", "name": "Soot load", "fmt": {"bix": 8, "len": 16, "div": 100, "max": 655.35, "unit": "percent"}},
              {"id": "X_HIDDEN", "hidden": true, "name": "Hidden", "fmt": {"len": 8, "max": 255}},
              {"id": "X_GEAR", "name": "Gear", "fmt": {"bix": 24, "len": 4, "map": {"0": {"description": "Park", "value": "P"}, "1": "Reverse"}}},
              {"id": "X_LAMP", "name": "Lamp", "fmt": {"bix": 28, "len": 1, "max": 1, "unit": "offon"}}
            ]},
          { "hdr": "DA10", "cmd": {"22": "0101"}, "filter": {"from": 2018},
            "signals": [{"id": "NEW", "name": "New car only", "fmt": {"len": 8, "max": 255}}]},
          { "hdr": "7E0", "rax": "7E8", "cmd": {"21": "01"}, "filter": {"to": 2012},
            "signals": [{"id": "OLD", "name": "Old car only", "fmt": {"len": 8, "max": 255, "unit": "rpm"}}]},
          { "hdr": "6F1", "eax": "12", "cmd": {"22": "4402"},
            "signals": [{"id": "EAX", "name": "Extended address", "fmt": {"len": 8, "max": 255}}]},
          { "hdr": "7E0", "cmd": {"2E": "0303"},
            "signals": [{"id": "WRITE", "name": "Never", "fmt": {"len": 8, "max": 255}}]}
        ]}
    """.trimIndent()

    private val entry = ObdbEntry(repo = "VauxhallOpel", make = "VauxhallOpel", signals = 9)

    @Test fun `signal set becomes a read only pack`() {
        val pack = Obdb.toPack(entry, sample, year = 2010)
        val names = pack.pids.map { it.name }
        assertEquals(listOf("Oil temperature", "Soot load", "Gear", "Lamp", "Old car only", "Extended address"), names)
        val bmw = pack.pids.last()
        assertEquals("6F1" to "12", bmw.header to bmw.extAddr)
        assertEquals("", bmw.receive)
        pack.pids.forEach { assertNull("${it.name}: ${it.validate()}", it.validate()) }
        assertEquals("Vauxhall / Opel (OBDb)", pack.name)
        assertEquals(Obdb.ATTRIBUTION, pack.description)

        val temp = pack.pids[0]
        assertEquals("221A2B", temp.request)
        assertEquals("7E0", temp.header)
        assertEquals("°C", temp.unit)
        assertEquals("OBDb (CC BY-SA 4.0) · Engine", temp.source)
        assertEquals(50.0, temp.toSensor().decode(intArrayOf(90, 0, 0, 0))!!, 1e-9)

        val soot = pack.pids[1]
        assertEquals(12.34, soot.toSensor().decode(intArrayOf(0, 0x04, 0xD2, 0))!!, 1e-9)

        val gear = pack.pids[2].toSensor()
        assertEquals("Park", gear.labeler!!(gear.decode(intArrayOf(0, 0, 0, 0x08))!!))
        assertEquals("Reverse", gear.labeler!!(gear.decode(intArrayOf(0, 0, 0, 0x10))!!))

        val lamp = pack.pids[3].toSensor()
        assertEquals("On", lamp.labeler!!(lamp.decode(intArrayOf(0, 0, 0, 0x08))!!))

        assertEquals("7E8", pack.pids[4].receive)
        assertEquals("2101", pack.pids[4].request)
    }

    @Test fun `year filter and 29 bit headers`() {
        val pack = Obdb.toPack(entry, sample, year = 2019)
        val newer = pack.pids.single { it.name == "New car only" }
        assertEquals("DA10F1", newer.header)
        assertEquals(0x10, newer.headerId!! shr 8 and 0xFF)
        assertTrue(pack.pids.none { it.name == "Old car only" })
        // Unknown year: everything readable is offered
        assertEquals(7, Obdb.toPack(entry, sample, year = null).pids.size)
    }

    @Test fun `pack passes the normal pack checks and merges`() {
        val pack = Obdb.toPack(entry, sample, year = null)
        val again = PidPacks.decode(PidPacks.encode(pack))
        assertEquals(pack.pids.size, again.pids.size)
        var n = 0
        val merged = PidPacks.merge(emptyList(), emptyList(), again) { "p${n++}" }
        // Two values from the same identifier are both kept
        assertEquals(pack.pids.size, merged.added)
        assertEquals(0, PidPacks.merge(merged.pids, emptyList(), again) { "q${n++}" }.added)
    }

    @Test fun `catalog matching uses the app's make names and platform groups`() {
        val index = listOf(
            ObdbEntry("VauxhallOpel", "VauxhallOpel", "", 300),
            ObdbEntry("Opel-Corsa", "Opel", "Corsa", 20),
            ObdbEntry("Volkswagen-Golf", "Volkswagen", "Golf", 100),
            ObdbEntry("Volkswagen", "Volkswagen", "", 400),
            ObdbEntry("Land-Rover-Defender", "Land Rover", "Defender", 40),
            ObdbEntry("Skoda-Octavia", "Skoda", "Octavia", 40),
            ObdbEntry("Peugeot-208", "Peugeot", "208", 500),
            ObdbEntry("BMW-3-Series", "BMW", "3-Series", 600),
            ObdbEntry("Mercedes-Benz-C-Class", "Mercedes-Benz", "C-Class", 26),
            ObdbEntry("NissanInfiniti", "NissanInfiniti", "", 50),
        )
        // The app calls the brand "Opel / Vauxhall"; registries say "OPEL" or "Vauxhall".
        assertEquals(listOf("VauxhallOpel"), Obdb.forCar(index, "Opel / Vauxhall", "Astra").map { it.repo })
        assertEquals(listOf("VauxhallOpel"), Obdb.forCar(index, "VAUXHALL", null).map { it.repo })
        assertEquals("Opel-Corsa", Obdb.forCar(index, "Opel", "Corsa").first().repo)
        // No general set and an unlisted model: the make's largest sets.
        assertEquals("Peugeot-208", Obdb.forCar(index, "Peugeot", "5008").single().repo)
        assertEquals("Skoda-Octavia", Obdb.forCar(index, "Škoda", "Octavia Combi").single().repo)
        assertEquals("Volkswagen-Golf", Obdb.forCar(index, "VW", "Golf GTI").first().repo)
        assertEquals("Land-Rover-Defender", Obdb.forCar(index, "Land Rover", "Defender").single().repo)
        assertEquals("Mercedes-Benz-C-Class", Obdb.forCar(index, "MERCEDES-BENZ", "C 220").single().repo)
        assertEquals(listOf("NissanInfiniti"), Obdb.forCar(index, "Infiniti", null).map { it.repo })
        // A Seat has no data of its own here: the Volkswagen group is offered; a Citroën gets Peugeot (and Opel).
        assertTrue(Obdb.forCar(index, "SEAT", "Leon").isEmpty())
        assertEquals("Volkswagen", Obdb.related(index, "SEAT", "Leon").first().repo)
        assertTrue("Peugeot-208" in Obdb.related(index, "Citroën", "C3").map { it.repo })
        assertEquals(listOf("BMW-3-Series"), Obdb.related(index, "MINI", "Cooper").map { it.repo })
        assertEquals("Land Rover Defender", index[4].title)
        assertEquals(listOf("Opel-Corsa", "VauxhallOpel"), Obdb.search(index, "corsa").map { it.repo } + Obdb.search(index, "vauxhall").map { it.repo })
        assertTrue(Obdb.forCar(index, null, null).isEmpty())
    }

    @Test fun `every brand the app knows gets community data`() {
        val file = java.io.File("src/main/assets/obdb/index.json").takeIf { it.exists() } ?: java.io.File("app/src/main/assets/obdb/index.json")
        val index = Obdb.decodeIndex(file.readText())
        // Tesla is the only brand without any OBDb data.
        val missing = com.openobd.core.brand.Brands.all.filter { it.id != "other" && it.id != "tesla" }
            .filter { b -> Obdb.forCar(index, b.name, null).isEmpty() && Obdb.related(index, b.name, null).isEmpty() }
            .map { it.name }
        assertEquals(emptyList<String>(), missing)
    }
}
