package com.openobd.core

import com.openobd.core.brand.CustomPid
import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.ModuleClient
import com.openobd.core.brand.PackEntry
import com.openobd.core.brand.PackException
import com.openobd.core.brand.PidPack
import com.openobd.core.brand.PidPacks
import com.openobd.core.brand.TorqueCsv
import com.openobd.core.elm.Elm327
import com.openobd.core.elm.ElmConfig
import com.openobd.core.obd.ObdClient
import com.openobd.core.transport.DemoTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class PidPacksTest {
    private val soot = CustomPid("a", "DPF soot", "Soot", "22336A", "(A*256)+B", 0.0, 100.0, "g", header = "7E0")
    private fun pack(vararg pids: CustomPid) = PidPack(name = "Astra J 1.6 CDTI", makes = listOf("Opel"), models = listOf("Astra"), pids = pids.toList())

    private fun rejected(text: String, msg: String) {
        try { PidPacks.decode(text); fail("accepted: $text") } catch (e: PackException) { assertTrue(e.message, e.message!!.contains(msg)) }
    }

    @Test
    fun roundTrip() {
        val p = pack(soot).copy(modules = listOf(EcuModule("ECM", "ECM", 0x7E0, 0x7E8)), verified = true)
        val back = PidPacks.decode(PidPacks.encode(p))
        assertEquals(p, back)
    }

    @Test
    fun writeRequestsAndJunkAreRefused() {
        rejected("hello", "not an OpenOBD PID pack")
        rejected(PidPacks.encode(pack()), "no PIDs")
        rejected(PidPacks.encode(pack(soot.copy(request = "14FFFFFF"))), "Only read requests")
        rejected(PidPacks.encode(pack(soot.copy(request = "2E336A01"))), "Only read requests")
        rejected(PidPacks.encode(pack(soot.copy(request = "3101FF00"))), "Only read requests")
        rejected(PidPacks.encode(pack(soot.copy(request = "04"))), "Only read requests")
        rejected(PidPacks.encode(pack(soot.copy(equation = "(A*"))), "Equation")
        assertNotNull(soot.copy(request = "2E0000").validate())
        assertNull(soot.validate())
        // Torque CSV imports go through the same check
        val r = TorqueCsv.parse("Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header\nClear,clr,04,A,0,1,,7E0\nTemp,t,221E1C,A,0,100,C,7E0\n", "t")
        assertEquals(listOf("Temp"), r.pids.map { it.name })
    }

    @Test
    fun aStoredWriteRequestIsNeverSent() = runTest {
        val t = DemoTransport(latencyMs = 0)
        t.open()
        val elm = Elm327(t); elm.initialize(ElmConfig()); ObdClient(elm).discover()
        val clear = CustomPid("x", "Not a PID", "x", "14FFFFFF", "A", header = "7E0")
        ModuleClient(elm).readCustom(listOf(clear))
        assertEquals(listOf("P0301", "P0420"), t.stored) // codes untouched
    }

    @Test
    fun mergeSkipsDuplicatesAndRenumbers() {
        var n = 0
        val existing = listOf(soot.copy(id = "mine"))
        val other = soot.copy(id = "a2", name = "Different name, same request")
        val oil = CustomPid("b", "Oil life", "Oil", "221234", "A", header = "7E0")
        val r = PidPacks.merge(existing, emptyList(), pack(other, oil).copy(modules = listOf(EcuModule("ECM", "ECM", 0x7E0, 0x7E8))), { "new${n++}" })
        assertEquals(1, r.added); assertEquals(1, r.skipped)
        assertEquals(listOf("mine", "new0"), r.pids.map { it.id })
        assertEquals(1, r.modules.size)
    }

    @Test
    fun catalogSortingAndUrls() {
        val e = listOf(
            PackEntry("a", "Ford Focus", makes = listOf("Ford"), file = "a.json"),
            PackEntry("b", "Opel Insignia", makes = listOf("Opel"), models = listOf("Insignia"), file = "b.json"),
            PackEntry("c", "Opel Astra J", makes = listOf("Opel"), models = listOf("Astra"), verified = true, file = "https://example.org/c.json"),
        )
        assertEquals(listOf("c", "b", "a"), PidPacks.sortFor(e, "Opel", "Astra Sports Tourer").map { it.id })
        assertEquals("https://raw.githubusercontent.com/Garcy99/OpenOBD/main/packs/a.json", PidPacks.urlOf(e[0]))
        assertEquals("https://example.org/c.json", PidPacks.urlOf(e[2]))
    }

    @Test
    fun repositoryCatalogIsValid() {
        val dir = listOf(File("../packs"), File("packs")).first { it.isDirectory }
        val catalog = PidPacks.decodeCatalog(File(dir, "index.json").readText())
        assertTrue(catalog.packs.isNotEmpty())
        for (entry in catalog.packs) {
            val pack = PidPacks.decode(File(dir, entry.file).readText())
            assertEquals(entry.name, pack.name)
            assertEquals(entry.pids, pack.pids.size)
        }
    }
}
