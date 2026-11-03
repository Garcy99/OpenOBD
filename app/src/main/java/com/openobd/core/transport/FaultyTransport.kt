package com.openobd.core.transport

import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * Misbehaviour that real adapters and links show: cheap clones, BLE notification fragmentation,
 * noisy serial lines, buses that are too busy, adapters that stop answering mid-session.
 * Probabilities are per reply.
 */
data class FaultPlan(
    /** Deliver replies in random small pieces (BLE notifications are often 1-20 bytes). */
    val fragment: Boolean = false,
    val maxChunk: Int = 6,
    /** Extra random delay before each piece, in ms. */
    val jitterMs: Int = 0,
    /** Insert NUL / CR noise characters. */
    val noise: Double = 0.0,
    /** Add a line of non-hex garbage to the reply. */
    val garbageLine: Double = 0.0,
    /** Replace the reply to an OBD request by an adapter error (BUFFER FULL, CAN ERROR, STOPPED...). */
    val errorReply: Double = 0.0,
    /** Change one hex digit of the reply. */
    val corruptDigit: Double = 0.0,
    /** Drop the final '>' prompt: the reply is complete but the adapter seems to hang. */
    val dropPrompt: Double = 0.0,
    /** Hold the reply back and only deliver it when the next bytes are sent (slow bus, late answer). */
    val lateReply: Double = 0.0,
    /** Cut the reply short and never answer again (link lost mid-reply), after this many commands. */
    val disconnectAfter: Int? = null,
    /** Only inject faults into OBD requests, leaving AT commands alone (keeps initialisation deterministic). */
    val obdOnly: Boolean = true,
) {
    companion object {
        val NONE = FaultPlan()
        /** Typical BLE clone: tiny notifications, a bit of jitter, nothing lost. */
        val FRAGMENTED = FaultPlan(fragment = true, maxChunk = 4, jitterMs = 2)
        /** A bad day: everything that can go wrong, sometimes. */
        val NASTY = FaultPlan(
            fragment = true, maxChunk = 5, noise = 0.1, garbageLine = 0.05, errorReply = 0.05,
            corruptDigit = 0.03, dropPrompt = 0.03, lateReply = 0.03,
        )
    }
}

/** Wraps another (normally simulated) transport and injects the faults of a [FaultPlan]. */
class FaultyTransport(
    private val inner: ObdTransport,
    var plan: FaultPlan,
    private val random: Random = Random(1),
) : ObdTransport() {
    override val description: String get() = inner.description + " [faults]"

    /** Number of faults injected so far, by kind (for test reports). */
    val injected = linkedMapOf<String, Int>()
    private var commands = 0
    private var held: ByteArray? = null
    private var dead = false

    private fun count(kind: String) { injected[kind] = (injected[kind] ?: 0) + 1 }

    override suspend fun open() { inner.open(); isOpen = true; dead = false }

    override suspend fun close() { inner.close(); isOpen = false }

    override suspend fun send(data: ByteArray) {
        if (!isOpen || dead) throw TransportException("Link closed")
        held?.let { held = null; deliver(it) }
        val text = String(data, Charsets.US_ASCII)
        val isAt = text.trim().uppercase().let { it.startsWith("AT") || it.startsWith("ST") || it.isEmpty() }
        val faulty = !(plan.obdOnly && isAt)
        inner.send(data)
        val replies = mutableListOf<ByteArray>()
        while (true) replies += inner.incoming.tryReceive().getOrNull() ?: break
        commands++
        var reply = String(replies.fold(ByteArray(0)) { a, b -> a + b }, Charsets.ISO_8859_1)
        plan.disconnectAfter?.let { n ->
            if (commands > n) {
                count("disconnect")
                deliver(reply.take(reply.length / 2).toByteArray(Charsets.ISO_8859_1))
                dead = true
                notifyDisconnected(TransportException("Simulated link loss"))
                return
            }
        }
        if (faulty) reply = mangle(reply)
        val bytes = reply.toByteArray(Charsets.ISO_8859_1)
        if (faulty && chance(plan.lateReply)) { count("late"); held = bytes; return }
        deliver(bytes)
    }

    private fun chance(p: Double) = p > 0 && random.nextDouble() < p

    private fun mangle(input: String): String {
        var r = input
        val promptAt = r.lastIndexOf('>')
        if (promptAt < 0) return r
        var body = r.substring(0, promptAt)
        if (chance(plan.errorReply)) {
            count("error")
            val err = listOf("BUFFER FULL", "CAN ERROR", "STOPPED", "BUS BUSY", "NO DATA", "DATA ERROR", "<RX ERROR").random(random)
            // Keep the echo (if any) and replace the data lines.
            body = err + "\r\r"
        }
        if (chance(plan.garbageLine)) {
            count("garbage")
            val g = listOf("?", "ÿþ", "LV RESET", "ELM327 v1.5", "+CONNECTING<<", "SEARCHING...").random(random)
            val lines = body.split('\r').toMutableList()
            lines.add(random.nextInt(lines.size + 1), g)
            body = lines.joinToString("\r")
        }
        if (chance(plan.corruptDigit)) {
            val idx = body.indices.filter { body[it].isLetterOrDigit() }
            if (idx.isNotEmpty()) {
                count("corrupt")
                val i = idx.random(random)
                body = body.substring(0, i) + "0123456789ABCDEFGX".random(random) + body.substring(i + 1)
            }
        }
        if (chance(plan.noise)) {
            count("noise")
            val i = random.nextInt(body.length + 1)
            body = body.substring(0, i) + listOf("\u0000", "\r", "\u0000\u0000").random(random) + body.substring(i)
        }
        r = body + if (chance(plan.dropPrompt)) { count("noPrompt"); "" } else r.substring(promptAt)
        return r
    }

    private suspend fun deliver(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (!plan.fragment) { incoming.trySend(bytes); return }
        var i = 0
        while (i < bytes.size) {
            val n = random.nextInt(1, plan.maxChunk.coerceAtLeast(1) + 1).coerceAtMost(bytes.size - i)
            if (plan.jitterMs > 0) delay(random.nextLong(0, plan.jitterMs.toLong() + 1))
            incoming.trySend(bytes.copyOfRange(i, i + n))
            i += n
        }
    }
}
