package com.openobd.core.brand

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A shareable set of manufacturer PIDs / identifiers and the modules they live in, for one make / model / engine.
 * Packs travel as JSON files (share sheet, e-mail, forums) or come from the community catalog in the OpenOBD repo.
 */
@Serializable
data class PidPack(
    val format: Int = 1,
    val name: String,
    val description: String = "",
    val author: String = "",
    val makes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val years: String = "",
    val engines: List<String> = emptyList(),
    /** The author tested the values on a real car (not just copied them). */
    val verified: Boolean = false,
    val pids: List<CustomPid> = emptyList(),
    val modules: List<EcuModule> = emptyList(),
    /** Settings the pack can change; only written when the user picks an option, after a backup. */
    val codings: List<Coding> = emptyList(),
    val routines: List<Routine> = emptyList(),
)

/** One entry of the online catalog: what the pack is for and where to download it. */
@Serializable
data class PackEntry(
    val id: String,
    val name: String,
    val description: String = "",
    val makes: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val years: String = "",
    val engines: List<String> = emptyList(),
    val verified: Boolean = false,
    val pids: Int = 0,
    val codings: Int = 0,
    val author: String = "",
    /** Relative to the catalog URL, or absolute https. */
    val file: String,
)

@Serializable
data class PackCatalog(val format: Int = 1, val packs: List<PackEntry> = emptyList())

class PackException(message: String) : Exception(message)

object PidPacks {
    const val CATALOG_URL = "https://raw.githubusercontent.com/Garcy99/OpenOBD/main/packs/index.json"
    const val MAX_PIDS = 500

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = false }

    fun encode(pack: PidPack): String = json.encodeToString(PidPack.serializer(), pack)

    /** Parses and checks a pack. Invalid PIDs are rejected with their name so the author can fix them. */
    fun decode(text: String): PidPack {
        val p = try {
            json.decodeFromString(PidPack.serializer(), text.trim().removePrefix("\uFEFF"))
        } catch (e: Exception) {
            throw PackException("This file is not an OpenOBD PID pack.")
        }
        if (p.format != 1) throw PackException("This pack needs a newer version of OpenOBD.")
        if (p.name.isBlank()) throw PackException("The pack has no name.")
        if (p.pids.isEmpty() && p.codings.isEmpty() && p.routines.isEmpty()) throw PackException("The pack contains no PIDs.")
        if (p.pids.size > MAX_PIDS || p.codings.size + p.routines.size > MAX_PIDS) throw PackException("The pack has too many PIDs.")
        p.pids.forEach { pid -> pid.validate()?.let { throw PackException("${pid.name}: $it") } }
        p.codings.forEach { c -> c.validate()?.let { throw PackException("${c.name}: $it") } }
        p.routines.forEach { r -> r.validate()?.let { throw PackException("${r.name}: $it") } }
        p.modules.forEach { m -> if (m.tx !in 0..0x7FF && !m.is29Bit) throw PackException("${m.name}: invalid address") }
        return p
    }

    fun decodeCatalog(text: String): PackCatalog = try {
        json.decodeFromString(PackCatalog.serializer(), text)
    } catch (e: Exception) {
        throw PackException("The online catalog could not be read.")
    }

    /** Download URL of a catalog entry. */
    fun urlOf(entry: PackEntry, catalogUrl: String = CATALOG_URL): String =
        if (entry.file.startsWith("https://")) entry.file else catalogUrl.substringBeforeLast('/') + "/" + entry.file.removePrefix("/")

    /** Catalog entries for the car first (matching make, then model), then the rest. */
    fun sortFor(entries: List<PackEntry>, make: String?, model: String?): List<PackEntry> {
        fun score(e: PackEntry): Int {
            var s = 0
            if (make != null && e.makes.any { it.equals(make, true) }) s += 2
            if (model != null && e.models.any { model.contains(it, true) || it.contains(model, true) }) s += 1
            if (e.verified) s += 1
            return s
        }
        return entries.sortedWith(compareByDescending<PackEntry> { score(it) }.thenBy { it.name })
    }

    /** Builds a pack from a car's own custom PIDs and modules. */
    fun fromCar(
        name: String, make: String?, model: String?, pids: List<CustomPid>, modules: List<EcuModule>,
        codings: List<Coding> = emptyList(), routines: List<Routine> = emptyList(),
    ) = PidPack(
        name = name, makes = listOfNotNull(make?.takeIf { it.isNotBlank() }), models = listOfNotNull(model?.takeIf { it.isNotBlank() }),
        pids = pids, modules = modules, codings = codings, routines = routines,
    )

    data class MergeResult(val pids: List<CustomPid>, val modules: List<EcuModule>, val added: Int, val skipped: Int)

    /** Adds a pack's codings and routines; the same setting (module, identifier, bits) or routine is not added twice. */
    fun mergeCodings(codings: List<Coding>, routines: List<Routine>, pack: PidPack, newId: () -> String): Triple<List<Coding>, List<Routine>, Int> {
        fun key(c: Coding) = "${c.header.uppercase()}/${c.extAddr.uppercase()}/${c.kind}/${c.did.uppercase()}/${c.byte}/${c.mask}"
        fun rkey(r: Routine) = "${r.header.uppercase()}/${r.start.uppercase()}"
        val have = codings.map(::key).toMutableSet()
        val haveR = routines.map(::rkey).toMutableSet()
        val newC = pack.codings.filter { have.add(key(it)) }.map { it.copy(id = newId()) }
        val newR = pack.routines.filter { haveR.add(rkey(it)) }.map { it.copy(id = newId()) }
        return Triple(codings + newC, routines + newR, newC.size + newR.size)
    }

    /**
     * Adds a pack to a car. PIDs already present (same request and header) are skipped; new ones get fresh ids so
     * two packs can never clash.
     */
    fun merge(existing: List<CustomPid>, existingModules: List<EcuModule>, pack: PidPack, newId: () -> String): MergeResult {
        // Several values often come from one request (different bytes or bits), so the formula is part of the key.
        fun key(p: CustomPid) = p.request.uppercase() + "@" + p.header.trim().uppercase() + "/" + p.extAddr.trim().uppercase() + "#" + p.equation.replace(" ", "")
        val have = existing.map(::key).toMutableSet()
        val added = mutableListOf<CustomPid>()
        var skipped = 0
        for (p in pack.pids) {
            if (!have.add(key(p))) { skipped++; continue }
            added += p.copy(id = newId())
        }
        val modules = (existingModules + pack.modules).distinctBy { it.id }
        return MergeResult(existing + added, modules, added.size, skipped)
    }

    /** Every user-visible message, for translation coverage. */
    val texts = listOf(
        "This file is not an OpenOBD PID pack.", "This pack needs a newer version of OpenOBD.", "The pack has no name.",
        "The pack contains no PIDs.", "The pack has too many PIDs.", "The online catalog could not be read.",
    )
}
