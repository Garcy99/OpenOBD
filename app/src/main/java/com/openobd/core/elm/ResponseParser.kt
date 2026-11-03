package com.openobd.core.elm

/**
 * One complete OBD message from one ECU. [data] starts with the service byte (e.g. 0x41) and
 * has headers, PCI bytes and checksums removed. ISO-TP multi-frame CAN messages are reassembled.
 */
class EcuMessage(val ecu: String, val data: IntArray) {
    val service: Int get() = data.getOrElse(0) { -1 }
    val isNegative: Boolean get() = service == 0x7F
    override fun toString(): String = "$ecu: " + data.joinToString(" ") { "%02X".format(it) }
}

/** Turns cleaned ELM327 output lines into [EcuMessage]s for the active protocol. */
object ResponseParser {

    private val hexToken = Regex("^[0-9A-Fa-f]+$")

    fun parse(lines: List<String>, protocol: ObdProtocol, headersOn: Boolean): List<EcuMessage> {
        val tokenLines = lines.mapNotNull { tokenize(it, protocol, headersOn) }
        if (tokenLines.isEmpty()) return emptyList()
        return when {
            protocol.isCan && headersOn -> parseCanWithHeaders(tokenLines, protocol)
            protocol.isCan -> parseCanNoHeaders(lines)
            headersOn -> tokenLines.mapNotNull { t ->
                // [prio, target, source, data..., checksum]
                if (t.size < 5) null
                else EcuMessage(t[2], t.subList(3, t.size - 1).map { it.toInt(16) }.toIntArray())
            }
            else -> tokenLines.map { t -> EcuMessage("ECU", t.map { it.toInt(16) }.toIntArray()) }
        }
    }

    /** Splits a line into hex tokens, or returns null if it is not a data line. */
    internal fun tokenize(line: String, protocol: ObdProtocol, headersOn: Boolean): List<String>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        // Headers-off multi-frame CAN lines look like "0: 49 02 01 31 44 34"
        val body = if (Regex("^[0-9A-F]:").containsMatchIn(trimmed)) trimmed.substringAfter(':').trim() else trimmed
        val parts = body.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty() || !parts.all { hexToken.matches(it) }) return null
        if (parts.size > 1) {
            // Normal spaced output. Sanity check: every token except an 11-bit CAN header is a byte.
            return if (parts.drop(1).all { it.length == 2 } && (parts[0].length == 2 || parts[0].length == 3)) parts else null
        }
        // Spaces disabled (ATS0): split manually.
        val s = parts[0]
        return if (protocol.isCan && headersOn && !protocol.is29Bit) {
            if (s.length < 5 || (s.length - 3) % 2 != 0) null
            else listOf(s.substring(0, 3)) + s.substring(3).chunked(2)
        } else {
            if (s.length % 2 != 0) (if (s.length == 3) listOf(s) else null) else s.chunked(2)
        }
    }

    private fun parseCanWithHeaders(tokenLines: List<List<String>>, protocol: ObdProtocol): List<EcuMessage> {
        class Pending(val total: Int, val bytes: MutableList<Int>, var nextSeq: Int)

        val out = mutableListOf<EcuMessage>()
        val pending = LinkedHashMap<String, Pending>()
        for (t in tokenLines) {
            val headerLen = if (protocol.is29Bit) 4 else 1
            if (t.size <= headerLen) continue
            val header = t.take(headerLen).joinToString("")
            val ecu = if (protocol.is29Bit) header.takeLast(2) else header
            val bytes = t.drop(headerLen).map { it.toInt(16) }
            val pci = bytes[0]
            when (pci shr 4) {
                0x0 -> { // single frame
                    val len = pci and 0x0F
                    if (len == 0 || bytes.size < 1 + len) {
                        // Some adapters/ECUs omit padding; take what we have
                        out += EcuMessage(ecu, bytes.drop(1).toIntArray())
                    } else {
                        out += EcuMessage(ecu, bytes.subList(1, 1 + len).toIntArray())
                    }
                }
                0x1 -> { // first frame
                    if (bytes.size < 2) continue
                    val total = ((pci and 0x0F) shl 8) or bytes[1]
                    pending[ecu] = Pending(total, bytes.drop(2).toMutableList(), 1)
                }
                0x2 -> { // consecutive frame
                    val p = pending[ecu] ?: continue
                    val seq = pci and 0x0F
                    if (seq != (p.nextSeq and 0x0F)) {
                        // Out of order - ELM output is serial so this means corrupted data; drop it.
                        pending.remove(ecu)
                        continue
                    }
                    p.nextSeq++
                    p.bytes.addAll(bytes.drop(1))
                    if (p.bytes.size >= p.total) {
                        out += EcuMessage(ecu, p.bytes.take(p.total).toIntArray())
                        pending.remove(ecu)
                    }
                }
                else -> Unit // 0x3 flow control or garbage
            }
        }
        // Flush incomplete messages (better partial data than none, e.g. truncated VIN)
        for ((ecu, p) in pending) if (p.bytes.isNotEmpty()) out += EcuMessage(ecu, p.bytes.toIntArray())
        return out
    }

    private fun parseCanNoHeaders(lines: List<String>): List<EcuMessage> {
        val out = mutableListOf<EcuMessage>()
        var multi: MutableList<Int>? = null
        var total = 0
        for (raw in lines) {
            val line = raw.trim()
            when {
                Regex("^[0-9A-F]{3}$").matches(line) -> { // length line of a multi-frame message
                    multi = mutableListOf(); total = line.toInt(16)
                }
                Regex("^[0-9A-F]:").containsMatchIn(line) -> {
                    val tokens = line.substringAfter(':').trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                    // Corrupted line (noise on the serial link): skip it rather than fail the whole reply.
                    if (!tokens.all { it.length == 2 && hexToken.matches(it) }) continue
                    val m = multi ?: mutableListOf<Int>().also { multi = it }
                    tokens.forEach { m.add(it.toInt(16)) }
                    if (total > 0 && m.size >= total) {
                        out += EcuMessage("ECU", m.take(total).toIntArray()); multi = null; total = 0
                    }
                }
                else -> {
                    val t = tokenize(line, ObdProtocol.ISO_15765_4_CAN_11_500, false) ?: continue
                    out += EcuMessage("ECU", t.map { it.toInt(16) }.toIntArray())
                }
            }
        }
        multi?.let { if (it.isNotEmpty()) out += EcuMessage("ECU", (if (total > 0) it.take(total) else it).toIntArray()) }
        return out
    }
}
