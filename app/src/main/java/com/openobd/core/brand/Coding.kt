package com.openobd.core.brand

import kotlinx.serialization.Serializable

/** How much a write can hurt. Dangerous ones show an explanation and a second confirmation and need the car parked. */
@Serializable
enum class WriteRisk { SAFE, DANGEROUS }

/** One choice of a coding, e.g. "On" = bits 0x04 set. [value] is hex, already in position under the mask. */
@Serializable
data class CodingOption(val label: String, val value: String)

/**
 * A setting stored in a module's data identifier: [byte] of the DID's data (after the DID echo) holds the bits in
 * [mask]. Changing it reads the whole identifier, changes only those bits, writes it back (UDS 2E) and reads it
 * again to check, so the rest of the module's coding is never touched.
 */
@Serializable
data class Coding(
    val id: String,
    val name: String,
    val description: String = "",
    /** Module request id: "7E0", or "DA10F1" for 29-bit. */
    val header: String,
    /** Reply id; blank = header + 8. */
    val receive: String = "",
    /** Data identifier, 4 hex digits. */
    val did: String,
    val byte: Int,
    val mask: Int = 0xFF,
    val options: List<CodingOption>,
    val risk: WriteRisk = WriteRisk.DANGEROUS,
    /** What can go wrong, shown before a dangerous write. */
    val warning: String = "",
    /** Diagnostic session for the write (03 = extended). */
    val session: Int = 3,
    val tested: Boolean = false,
    val source: String = "",
    /** The module is on the second (medium-speed) bus. */
    val secondaryBus: Boolean = false,
) {
    val didId: Int get() = did.toInt(16)

    fun validate(): String? {
        if (name.isBlank()) return "Name is required"
        if (!Regex("^[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$").matches(header.trim())) return "Header must be a CAN ID like 7E0"
        if (receive.isNotBlank() && !Regex("^[0-9A-Fa-f]{3}$").matches(receive.trim())) return "Reply ID must be a CAN ID like 7E8"
        if (!Regex("^[0-9A-Fa-f]{4}$").matches(did)) return "Identifier must be 4 hex digits, e.g. 0101"
        // F1xx are identification records (VIN, part numbers ...): never written.
        if (didId in 0xF100..0xF1FF) return "Identification records cannot be written"
        if (byte !in 0..255) return "Byte must be 0 to 255"
        if (mask !in 1..0xFF) return "Mask must be 01 to FF"
        if (session !in 1..0x7F) return "Invalid session"
        if (options.size < 2) return "At least two options are needed"
        for (o in options) {
            val v = o.value.toIntOrNull(16) ?: return "Option values must be hex, e.g. 04"
            if (v and mask.inv() and 0xFF != 0 || v > 0xFF) return "An option value uses bits outside the mask"
            if (o.label.isBlank()) return "Every option needs a name"
        }
        if (options.map { it.value.toInt(16) }.distinct().size != options.size) return "Two options have the same value"
        return null
    }

    fun module(): EcuModule = moduleOf(name, header, receive).copy(secondaryBus = secondaryBus)

    companion object {
        /** Validation messages of codings and routines, for translation. */
        val texts = listOf(
            "Name is required", "Header must be a CAN ID like 7E0", "Reply ID must be a CAN ID like 7E8", "Identifier must be 4 hex digits, e.g. 0101",
            "Identification records cannot be written", "Byte must be 0 to 255", "Mask must be 01 to FF", "Invalid session",
            "At least two options are needed", "Option values must be hex, e.g. 04", "An option value uses bits outside the mask",
            "Every option needs a name", "Two options have the same value", "Start must be a routine request 3101…",
            "Stop must be a request 3102…", "Result must be a request 3103…",
        )
    }

    /** The option the current DID data matches, if any. */
    fun current(data: IntArray): CodingOption? {
        val b = data.getOrNull(byte) ?: return null
        return options.firstOrNull { (b and mask) == (it.value.toInt(16) and mask) }
    }

    /** New DID data with only the masked bits changed. */
    fun apply(data: IntArray, option: CodingOption): IntArray {
        require(byte < data.size) { "The identifier is shorter than expected" }
        val out = data.copyOf()
        out[byte] = (data[byte] and mask.inv() and 0xFF) or (option.value.toInt(16) and mask)
        return out
    }
}

/**
 * A routine the module runs (UDS 31 01), e.g. a service regeneration of the diesel particulate filter. Always
 * dangerous: it changes what the engine does, so it needs both confirmations and the conditions below.
 */
@Serializable
data class Routine(
    val id: String,
    val name: String,
    val description: String = "",
    val header: String,
    val receive: String = "",
    /** Full start request, e.g. "3101XXXX" + parameters. */
    val start: String,
    /** Stop request ("3102XXXX"); sent if the user cancels or something fails. */
    val stop: String = "",
    /** Result request ("3103XXXX"), polled while it runs. */
    val result: String = "",
    val warning: String = "",
    /** The engine must be running (regeneration) or off (most adaptations). */
    val engineRunning: Boolean = false,
    val session: Int = 3,
    val tested: Boolean = false,
    val source: String = "",
) {
    fun validate(): String? {
        if (name.isBlank()) return "Name is required"
        if (!Regex("^[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$").matches(header.trim())) return "Header must be a CAN ID like 7E0"
        if (receive.isNotBlank() && !Regex("^[0-9A-Fa-f]{3}$").matches(receive.trim())) return "Reply ID must be a CAN ID like 7E8"
        if (!Regex("^3101([0-9A-Fa-f]{2}){2,30}$").matches(start)) return "Start must be a routine request 3101…"
        if (stop.isNotBlank() && !Regex("^3102([0-9A-Fa-f]{2}){2,30}$").matches(stop)) return "Stop must be a request 3102…"
        if (result.isNotBlank() && !Regex("^3103([0-9A-Fa-f]{2}){2,30}$").matches(result)) return "Result must be a request 3103…"
        return null
    }

    fun module(): EcuModule = moduleOf(name, header, receive)
}

internal fun moduleOf(name: String, header: String, receive: String): EcuModule {
    val h = header.trim().toInt(16)
    return if (header.trim().length == 3) EcuModule(name, name, h, receive.trim().takeIf { it.isNotEmpty() }?.toInt(16) ?: (h + 8))
    else EcuModule(name, name, (h shr 8) and 0xFF, 0xF1, is29Bit = true)
}

/** What happened to one write, kept per car so every change can be undone. */
@Serializable
enum class WriteStatus { PENDING, APPLIED, UNCHANGED, RESTORED, RESTORE_FAILED, UNDONE, ROUTINE_RUN, ROUTINE_FAILED }

@Serializable
data class WriteRecord(
    val id: String,
    val vehicleId: String,
    val time: Long,
    val name: String,
    /** Option chosen, or the routine request. */
    val detail: String,
    val header: String,
    val receive: String = "",
    val did: String = "",
    val session: Int = 3,
    /** Whole DID data before the change: the backup. */
    val before: String = "",
    val after: String = "",
    val status: WriteStatus = WriteStatus.PENDING,
    /** English message key and optional reason key (translated when shown). */
    val message: String = "",
    val reason: String = "",
    /** This entry puts back the record with this id. */
    val undoOf: String = "",
) {
    val undoable: Boolean get() = did.isNotEmpty() && before.isNotEmpty() && status == WriteStatus.APPLIED
    val needsRestore: Boolean get() = did.isNotEmpty() && before.isNotEmpty() && (status == WriteStatus.RESTORE_FAILED || status == WriteStatus.PENDING)
}

/** The car-side operations the engine needs; [ModuleClient] in the app, fakes in tests. */
interface CodingBus {
    /** Whole data of a DID (after the echo). Throws [WriteRefused] or another exception. */
    suspend fun read(m: EcuModule, did: Int): IntArray
    /** Writes the whole data of a DID in [session]. Throws [WriteRefused] when the module says no. */
    suspend fun write(m: EcuModule, did: Int, data: IntArray, session: Int)
    /** Sends a routine request and returns the positive reply data. Throws [WriteRefused]. */
    suspend fun routine(m: EcuModule, request: String, session: Int): IntArray
}

class WriteRefused(val nrc: Int?, message: String) : Exception(message)

enum class OutcomeKind {
    DONE,
    /** Nothing was changed in the car. */
    NOT_CHANGED,
    /** The write failed and the original value is back. */
    RESTORED,
    /** The write failed and the original could not be confirmed: the backup stays in the history. */
    NEEDS_ATTENTION,
}

/** [message] and [reason] are English keys for translation. */
data class WriteOutcome(val kind: OutcomeKind, val message: String, val reason: String = "", val record: WriteRecord? = null) {
    val ok: Boolean get() = kind == OutcomeKind.DONE
}

/** Conditions checked before writing; null = the car did not report it (e.g. while on MS-CAN). */
data class CarState(val rpm: Double?, val speedKmh: Double?, val volts: Double?)

object WriteSafety {
    const val MIN_VOLTS = 12.0

    /** Known conditions that block the write, as English keys; empty = go ahead. */
    fun problems(risk: WriteRisk, state: CarState, engineRunning: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        if ((state.speedKmh ?: 0.0) > 0.5) out += "The car must be stopped."
        if (risk == WriteRisk.DANGEROUS) {
            if (engineRunning) {
                if (state.rpm != null && state.rpm < 400) out += "Start the engine first."
            } else if ((state.rpm ?: 0.0) > 100) out += "Switch the engine off and leave the ignition on."
            if (state.volts != null && state.volts < MIN_VOLTS && !engineRunning) out += "Battery too low. Charge it or connect a charger first."
        }
        return out
    }

    /** Conditions that could not be checked; shown in the explanation of a dangerous write so the user checks them. */
    fun unknowns(state: CarState): List<String> = listOfNotNull(
        "Speed could not be read: make sure the car is parked.".takeIf { state.speedKmh == null },
        "Engine speed could not be read.".takeIf { state.rpm == null },
        "Battery voltage could not be read: make sure it is charged.".takeIf { state.volts == null },
    )

    val texts = listOf(
        "The car must be stopped.", "Start the engine first.", "Switch the engine off and leave the ignition on.",
        "Battery too low. Charge it or connect a charger first.", "Speed could not be read: make sure the car is parked.",
        "Engine speed could not be read.", "Battery voltage could not be read: make sure it is charged.",
    )
}

/**
 * Applies codings with a backup first and a check afterwards:
 * 1. read the whole identifier (the backup) and store it before anything is sent;
 * 2. write the changed copy;
 * 3. read it back; if it is not what was written, write the backup again and check that too.
 * Every step is recorded so the change can be undone later from the history.
 */
class CodingEngine(
    private val bus: CodingBus,
    /** Stores or replaces a record by id (persisted before the write is sent). */
    private val save: suspend (WriteRecord) -> Unit,
    private val newId: () -> String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private fun hex(d: IntArray) = d.joinToString("") { "%02X".format(it) }
    private fun bytes(h: String) = h.chunked(2).map { it.toInt(16) }.toIntArray()

    suspend fun apply(vehicleId: String, coding: Coding, option: CodingOption): WriteOutcome {
        val m = coding.module()
        val before = try { bus.read(m, coding.didId) } catch (e: Exception) {
            return WriteOutcome(OutcomeKind.NOT_CHANGED, READ_FAILED, reason(e))
        }
        if (coding.byte >= before.size) return WriteOutcome(OutcomeKind.NOT_CHANGED, TOO_SHORT)
        if (coding.current(before) == option) return WriteOutcome(OutcomeKind.NOT_CHANGED, ALREADY)
        val after = coding.apply(before, option)
        val rec = WriteRecord(
            id = newId(), vehicleId = vehicleId, time = now(), name = coding.name, detail = option.label, header = coding.header,
            receive = coding.receive, did = coding.did.uppercase(), session = coding.session, before = hex(before), after = hex(after),
        )
        save(rec) // the backup exists before the first byte is written
        return writeChecked(rec, before, after, DONE)
    }

    /**
     * Puts back what [record] changed. Only the bytes that change touched are written back, so later changes to
     * other settings in the same identifier stay. The undo is a history entry of its own (and can be undone too).
     */
    suspend fun undo(record: WriteRecord): WriteOutcome {
        require(record.undoable)
        val m = moduleOf(record.name, record.header, record.receive)
        val did = record.did.toInt(16)
        val original = bytes(record.before)
        val changed = bytes(record.after)
        val current = try { bus.read(m, did) } catch (e: Exception) { return WriteOutcome(OutcomeKind.NOT_CHANGED, READ_FAILED, reason(e)) }
        if (current.size != original.size) return WriteOutcome(OutcomeKind.NOT_CHANGED, SIZE_CHANGED)
        val target = current.copyOf()
        for (i in original.indices) if (original[i] != changed[i]) target[i] = original[i]
        if (target.contentEquals(current)) {
            val r = record.copy(status = WriteStatus.UNDONE)
            save(r); return WriteOutcome(OutcomeKind.DONE, ALREADY_ORIGINAL, record = r)
        }
        val rec = WriteRecord(
            id = newId(), vehicleId = record.vehicleId, time = now(), name = record.name, detail = record.detail, header = record.header,
            receive = record.receive, did = record.did, session = record.session, before = hex(current), after = hex(target), undoOf = record.id,
        )
        save(rec)
        val out = writeChecked(rec, current, target, UNDONE)
        if (out.ok) save(record.copy(status = WriteStatus.UNDONE))
        return out
    }

    private suspend fun writeChecked(rec0: WriteRecord, before: IntArray, after: IntArray, okMessage: String): WriteOutcome {
        val m = moduleOf(rec0.name, rec0.header, rec0.receive)
        val did = rec0.did.toInt(16)
        suspend fun done(kind: OutcomeKind, status: WriteStatus, message: String, reason: String = ""): WriteOutcome {
            val r = rec0.copy(status = status, message = message, reason = reason)
            save(r)
            return WriteOutcome(kind, message, reason, r)
        }
        val writeError = try { bus.write(m, did, after, rec0.session); null } catch (e: Exception) { e }
        val readBack = runCatching { bus.read(m, did) }.getOrNull()
        if (writeError == null && readBack != null && readBack.contentEquals(after)) return done(OutcomeKind.DONE, WriteStatus.APPLIED, okMessage)
        val why = reason(writeError)
        if (readBack != null && readBack.contentEquals(before)) return done(OutcomeKind.NOT_CHANGED, WriteStatus.UNCHANGED, NOTHING_CHANGED, why)
        // Something unexpected is in the module now (or it does not answer): put the backup back.
        repeat(2) {
            runCatching { bus.write(m, did, before, rec0.session) }
            val check = runCatching { bus.read(m, did) }.getOrNull()
            if (check != null && check.contentEquals(before)) return done(OutcomeKind.RESTORED, WriteStatus.RESTORED, RESTORED, why)
        }
        return done(OutcomeKind.NEEDS_ATTENTION, WriteStatus.RESTORE_FAILED, RESTORE_FAILED, why)
    }

    /** Writes the backup of a record whose restore failed (or whose write was interrupted) again. */
    suspend fun retryRestore(record: WriteRecord): WriteOutcome {
        val m = moduleOf(record.name, record.header, record.receive)
        val did = record.did.toInt(16)
        val original = bytes(record.before)
        val current = runCatching { bus.read(m, did) }.getOrNull()
        if (current == null || !current.contentEquals(original)) runCatching { bus.write(m, did, original, record.session) }
        val check = runCatching { bus.read(m, did) }.getOrNull()
        return if (check != null && check.contentEquals(original)) {
            val r = record.copy(status = WriteStatus.RESTORED, message = BACK)
            save(r); WriteOutcome(OutcomeKind.RESTORED, BACK, record = r)
        } else WriteOutcome(OutcomeKind.NEEDS_ATTENTION, STILL_NOT, record = record)
    }

    /** Runs a routine; on any failure the stop request is sent. */
    suspend fun run(vehicleId: String, routine: Routine): WriteOutcome {
        val m = routine.module()
        val rec = WriteRecord(newId(), vehicleId, now(), routine.name, routine.start, routine.header, routine.receive, session = routine.session)
        save(rec)
        return try {
            bus.routine(m, routine.start, routine.session)
            val r = rec.copy(status = WriteStatus.ROUTINE_RUN, message = STARTED)
            save(r); WriteOutcome(OutcomeKind.DONE, STARTED, record = r)
        } catch (e: Exception) {
            if (routine.stop.isNotBlank()) runCatching { bus.routine(m, routine.stop, routine.session) }
            val r = rec.copy(status = WriteStatus.ROUTINE_FAILED, message = NOT_STARTED, reason = reason(e))
            save(r); WriteOutcome(OutcomeKind.NOT_CHANGED, NOT_STARTED, r.reason, r)
        }
    }

    /** Stops a running routine. */
    suspend fun stop(routine: Routine): Boolean =
        routine.stop.isNotBlank() && runCatching { bus.routine(routine.module(), routine.stop, routine.session) }.isSuccess

    companion object {
        const val DONE = "Done. A backup was saved: you can undo it from the history."
        const val READ_FAILED = "Could not read the current value. Nothing was changed."
        const val TOO_SHORT = "This module's identifier is shorter than the coding expects: it does not match your car. Nothing was changed."
        const val ALREADY = "Already set. Nothing was changed."
        const val ALREADY_ORIGINAL = "Already at the original value."
        const val UNDONE = "Undone: the original value is back."
        const val SIZE_CHANGED = "The identifier changed size since the backup: undo is not safe. Nothing was changed."
        const val NOTHING_CHANGED = "The module did not take the change. Nothing was changed."
        const val RESTORED = "The change could not be confirmed, so the original value was written back."
        const val RESTORE_FAILED = "The change could not be confirmed and the original value could not be written back. Keep the ignition on and use Restore in the history; the backup is saved."
        const val BACK = "The original value is back."
        const val STILL_NOT = "The module still does not take the original value. The backup is kept."
        const val STARTED = "Started. Follow the instructions and do not switch the ignition off."
        const val NOT_STARTED = "The routine did not start."

        fun reason(e: Exception?): String = when {
            e == null -> "The module did not keep the new value."
            e is WriteRefused && e.nrc == 0x33 -> "The module is locked (security access). OpenOBD does not unlock modules."
            e is WriteRefused && e.nrc == 0x22 -> "Conditions not correct: engine off, ignition on, car parked."
            e is WriteRefused && e.nrc == 0x31 -> "The module does not know this identifier or value: it does not match your car."
            e is WriteRefused && e.nrc in setOf(0x11, 0x12, 0x7E, 0x7F) -> "The module does not allow this in the diagnostic session."
            e is WriteRefused && e.nrc == 0x72 -> "The module could not store the value."
            e is WriteRefused && e.nrc == 0x13 -> "The module expects a different length: it does not match your car."
            e is WriteRefused -> "The module refused."
            else -> "No answer from the module."
        }

        val texts = listOf(
            DONE, READ_FAILED, TOO_SHORT, ALREADY, ALREADY_ORIGINAL, UNDONE, SIZE_CHANGED, NOTHING_CHANGED, RESTORED, RESTORE_FAILED, BACK,
            STILL_NOT, STARTED, NOT_STARTED,
            "The module did not keep the new value.", "The module is locked (security access). OpenOBD does not unlock modules.",
            "Conditions not correct: engine off, ignition on, car parked.",
            "The module does not know this identifier or value: it does not match your car.",
            "The module does not allow this in the diagnostic session.", "The module could not store the value.",
            "The module expects a different length: it does not match your car.", "The module refused.", "No answer from the module.",
        )
    }
}

/** Bits of one identifier byte that changed between two reads. */
data class BitChange(val did: Int, val byte: Int, val mask: Int, val before: Int, val after: Int)

/**
 * Finds settings by comparison: read the identifiers, change one setting with the car's own menu, read again.
 * The bytes that changed are where that setting lives; they can be saved as a coding (untested, dangerous).
 */
object CodingFinder {
    fun diff(before: Map<Int, IntArray>, after: Map<Int, IntArray>): List<BitChange> = before.keys.sorted().flatMap { id ->
        val a = before.getValue(id)
        val b = after[id]?.takeIf { it.size == a.size } ?: return@flatMap emptyList()
        a.indices.filter { a[it] != b[it] }.map { i -> BitChange(id, i, a[i] xor b[i], a[i], b[i]) }
    }

    /** Header / reply id strings for a module, as codings store them. */
    fun addressOf(m: EcuModule): Pair<String, String> =
        if (m.is29Bit) "DA%02XF1".format(m.tx) to "" else "%03X".format(m.tx) to (if (m.rx != m.tx + 8) "%03X".format(m.rx) else "")

    fun toCoding(id: String, name: String, m: EcuModule, c: BitChange, beforeLabel: String, afterLabel: String): Coding {
        val (header, receive) = addressOf(m)
        return Coding(
            id = id, name = name, header = header, receive = receive, did = "%04X".format(c.did), byte = c.byte, mask = c.mask,
            options = listOf(CodingOption(beforeLabel, "%02X".format(c.before and c.mask)), CodingOption(afterLabel, "%02X".format(c.after and c.mask))),
            risk = WriteRisk.DANGEROUS, tested = false, source = "Identifier explorer", secondaryBus = m.secondaryBus,
        )
    }
}
