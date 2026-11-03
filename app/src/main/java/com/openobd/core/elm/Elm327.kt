package com.openobd.core.elm

import com.openobd.core.transport.ObdTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

enum class ElmError(val text: String, val userMessage: String) {
    NO_DATA("NO DATA", "The vehicle did not answer this request (not supported)."),
    UNABLE_TO_CONNECT("UNABLE TO CONNECT", "The adapter could not talk to the car. Is the ignition on?"),
    CAN_ERROR("CAN ERROR", "CAN bus error. Check the adapter is firmly plugged in."),
    BUS_ERROR("BUS ERROR", "Bus error. Check the adapter and wiring."),
    BUS_BUSY("BUS BUSY", "The vehicle bus is too busy."),
    BUS_INIT_ERROR("BUS INIT: ...ERROR", "Bus initialisation failed. Is the ignition on?"),
    FB_ERROR("FB ERROR", "Feedback error on the bus."),
    DATA_ERROR("DATA ERROR", "Corrupted data received."),
    BUFFER_FULL("BUFFER FULL", "Adapter buffer full. Try a lower polling speed."),
    STOPPED("STOPPED", "The request was interrupted."),
    UNKNOWN_COMMAND("?", "The adapter does not understand this command."),
    LOW_VOLTAGE("LV RESET", "Low voltage reset."),
    ERROR("ERROR", "Adapter reported an error."),
    TIMEOUT("TIMEOUT", "No reply from the adapter.");
}

sealed class ObdResult {
    abstract val raw: String

    class Data(val messages: List<EcuMessage>, override val raw: String) : ObdResult() {
        /** Positive replies to [service] (request service id; the reply is service + 0x40). */
        fun positive(service: Int): List<EcuMessage> = messages.filter { it.service == service + 0x40 }
        fun negative(): List<EcuMessage> = messages.filter { it.isNegative }
    }

    class Failure(val error: ElmError, override val raw: String) : ObdResult()
}

class ElmInfo(
    val version: String,
    val deviceDescription: String?,
    val protocol: ObdProtocol,
    val isStn: Boolean,
    val stnVersion: String?,
)

class ElmException(message: String, val error: ElmError? = null) : Exception(message)

data class ElmConfig(
    val protocol: ObdProtocol = ObdProtocol.AUTO,
    /** 0 = off, 1 = normal, 2 = aggressive (ATAT). */
    val adaptiveTiming: Int = 1,
    /** ATST value in ms (rounded to 4ms steps); null = adapter default. */
    val timeoutMs: Int? = null,
    val headersOn: Boolean = true,
)

/**
 * Driver for ELM327 (and compatible: STN11xx/OBDLink, Vgate, clones) adapters.
 * All commands are serialised with a mutex so UI features can share one connection.
 */
class Elm327(
    private val transport: ObdTransport,
    private val log: (direction: Char, text: String) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()

    var protocol: ObdProtocol = ObdProtocol.AUTO
        private set
    var headersOn: Boolean = true
        private set

    /** Sends a command and returns the cleaned response lines. */
    suspend fun command(cmd: String, timeoutMs: Long = 3000): List<String> = mutex.withLock { commandLocked(cmd, timeoutMs) }

    private suspend fun commandLocked(cmd: String, timeoutMs: Long): List<String> {
        // Drop anything stale that arrived between commands.
        while (transport.incoming.tryReceive().isSuccess) { /* drain */ }
        log('>', cmd)
        transport.send((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                while (true) {
                    val chunk = transport.incoming.receive()
                    sb.append(String(chunk, Charsets.ISO_8859_1))
                    if (sb.indexOf(">") >= 0) break
                }
            }
        } catch (e: TimeoutCancellationException) {
            log('!', "timeout after ${timeoutMs}ms: ${sb.toString().trim()}")
            // Interrupt a possibly still running request so the next command starts clean.
            runCatching { transport.send("\r".toByteArray()) }
            throw ElmException("No reply from the adapter to '$cmd'", ElmError.TIMEOUT)
        }
        val text = sb.substring(0, sb.indexOf(">"))
        val lines = clean(text, cmd)
        log('<', lines.joinToString(" | "))
        return lines
    }

    /**
     * Runs several commands without any other caller interleaving (used when temporarily changing the
     * CAN header / receive filter to talk to a specific module). Do not call [command] or [query] on
     * this instance from inside [block] - use the provided [Locked] channel.
     */
    suspend fun <T> exclusive(block: suspend Locked.() -> T): T = mutex.withLock { Locked().block() }

    inner class Locked {
        suspend fun command(cmd: String, timeoutMs: Long = 3000): List<String> = commandLocked(cmd, timeoutMs)

        suspend fun query(request: String, timeoutMs: Long = 5000): ObdResult = try {
            interpret(commandLocked(request, timeoutMs))
        } catch (e: ElmException) {
            ObdResult.Failure(e.error ?: ElmError.ERROR, e.message ?: "")
        }

        /** Sends an AT command and reports whether the adapter accepted it. */
        suspend fun at(cmd: String): Boolean = runCatching { commandLocked(cmd, 2000).any { it.contains("OK", true) } }.getOrDefault(false)
    }

    /** The ATST value (in 4 ms units) configured at initialisation, used to restore after module work. */
    var configuredTimeout: Int? = null
        private set

    /** Sends an OBD request (hex string such as "010C") and parses the reply. */
    suspend fun query(request: String, timeoutMs: Long = 5000): ObdResult {
        val lines = try {
            command(request, timeoutMs)
        } catch (e: ElmException) {
            return ObdResult.Failure(e.error ?: ElmError.ERROR, e.message ?: "")
        }
        return interpret(lines)
    }

    fun interpret(lines: List<String>): ObdResult {
        val raw = lines.joinToString("\n")
        val messages = ResponseParser.parse(lines, protocol, headersOn)
        if (messages.isNotEmpty()) return ObdResult.Data(messages, raw)
        val upper = raw.uppercase()
        val err = ElmError.entries.firstOrNull { it != ElmError.TIMEOUT && it != ElmError.UNKNOWN_COMMAND && upper.contains(it.text) }
            ?: if (upper.trim() == "?") ElmError.UNKNOWN_COMMAND else if (upper.isBlank()) ElmError.NO_DATA else ElmError.ERROR
        return ObdResult.Failure(err, raw)
    }

    /**
     * Resets and configures the adapter and establishes communication with the vehicle.
     * [progress] receives human readable steps.
     */
    suspend fun initialize(config: ElmConfig, progress: (String) -> Unit = {}): ElmInfo {
        progress("Resetting adapter")
        // Some clones need a bare CR to wake up / flush.
        runCatching { command("", 1000) }
        val reset = try {
            command("ATZ", 5000)
        } catch (e: ElmException) {
            delay(500)
            command("ATWS", 5000)
        }
        delay(300)
        val version = reset.firstOrNull { it.contains("ELM", true) || it.contains("STN", true) || it.contains("OBD", true) }
            ?: runCatching { command("ATI").firstOrNull() }.getOrNull() ?: "Unknown"

        progress("Configuring adapter")
        expectOk("ATE0")   // echo off
        expectOk("ATL0")   // linefeeds off
        runCatching { expectOk("ATS1") } // spaces on (easier parsing; some clones reject)
        headersOn = config.headersOn && runCatching { expectOk(if (config.headersOn) "ATH1" else "ATH0") }.isSuccess
        if (!config.headersOn) runCatching { expectOk("ATH0") }
        runCatching { expectOk("ATAT${config.adaptiveTiming.coerceIn(0, 2)}") }
        configuredTimeout = config.timeoutMs?.let { ms -> (ms / 4).coerceIn(1, 255) }
        configuredTimeout?.let { t -> runCatching { expectOk("ATST%02X".format(t)) } }
        expectOk("ATSP${config.protocol.code}")

        val description = runCatching { command("AT@1").firstOrNull() }.getOrNull()?.takeIf { it != "?" }
        val stn = runCatching { command("STI", 1500).firstOrNull() }.getOrNull()?.takeIf { it.contains("STN", true) }

        progress("Connecting to vehicle (protocol search can take ~15s)")
        protocol = ObdProtocol.AUTO
        var lines = command("0100", 20000)
        var joined = lines.joinToString(" ").uppercase()
        if (joined.contains("UNABLE TO CONNECT") || joined.contains("ERROR") || joined.contains("NO DATA")) {
            // Second attempt - first contact on KWP/ISO often fails.
            delay(1000)
            lines = command("0100", 20000)
            joined = lines.joinToString(" ").uppercase()
            if (joined.contains("UNABLE TO CONNECT")) throw ElmException(ElmError.UNABLE_TO_CONNECT.userMessage, ElmError.UNABLE_TO_CONNECT)
        }
        val dpn = runCatching { command("ATDPN").firstOrNull() }.getOrNull()
        protocol = dpn?.let { ObdProtocol.fromDpn(it) }?.takeIf { it != ObdProtocol.AUTO }
            ?: if (config.protocol != ObdProtocol.AUTO) config.protocol else ObdProtocol.ISO_15765_4_CAN_11_500
        if (interpret(lines) !is ObdResult.Data) {
            throw ElmException("The vehicle did not answer the first request: ${joined.ifBlank { "no reply" }}", ElmError.UNABLE_TO_CONNECT)
        }
        progress("Connected using ${protocol.label}")
        return ElmInfo(version.trim(), description, protocol, stn != null, stn)
    }

    suspend fun readVoltage(): Double? = runCatching {
        command("ATRV").firstOrNull()?.trim()?.removeSuffix("V")?.trim()?.toDouble()
    }.getOrNull()

    private suspend fun expectOk(cmd: String) {
        val r = command(cmd)
        if (r.none { it.contains("OK", true) }) throw ElmException("Adapter rejected $cmd: ${r.joinToString(" ")}")
    }

    companion object {
        fun clean(text: String, cmd: String): List<String> = text
            .replace("\u0000", "")
            .replace("\r", "\n")
            .split('\n')
            .map { it.trim() }
            .map { it.replace(Regex("^BUS INIT:\\s*\\.*\\s*OK", RegexOption.IGNORE_CASE), "").trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals(cmd, true) || it.equals(cmd.replace(" ", ""), true) }
            .filterNot { it.startsWith("SEARCHING", true) }
    }
}
