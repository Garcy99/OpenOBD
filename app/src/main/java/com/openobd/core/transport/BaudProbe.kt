package com.openobd.core.transport

/**
 * Finds the serial speed of an ELM327 USB cable. Genuine ELM327 chips start at 38400 baud, clones use
 * 38400, 115200 or 9600, and some were reprogrammed (ATPP 0C) to faster rates.
 */
object BaudProbe {
    /** Most common first, so a typical cable answers on the first try. */
    val RATES = listOf(38400, 115200, 9600, 57600, 230400, 500000, 19200)

    /** True when [reply] looks like an ELM327 answer: its prompt, an OK or its version banner. */
    fun looksLikeElm(reply: String): Boolean {
        val t = reply.uppercase()
        return t.contains('>') || t.contains("ELM") || t.contains("STN") || Regex("(^|\\s)OK(\\s|$)").containsMatchIn(t)
    }

    /**
     * Tries each rate with [preferred] first and returns the first one where [ask] gets an ELM-like reply,
     * or null when none does. [ask] sets the rate, sends a probe command and returns what came back.
     */
    suspend fun find(preferred: Int?, ask: suspend (Int) -> String): Int? {
        val order = (listOfNotNull(preferred) + RATES).distinct()
        for (rate in order) if (looksLikeElm(runCatching { ask(rate) }.getOrDefault(""))) return rate
        return null
    }
}
