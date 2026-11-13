package com.openobd.core.diagnosis

/**
 * Watches the check engine light and the number of confirmed codes (PID 01) through a drive: true when the light comes
 * on or a new code appears, so the codes are read at once and the driver is told what it is and whether to stop.
 */
class MilWatch {
    private var milOn: Boolean? = null
    private var count: Int? = null
    /** A different reading seen once: it counts only when the next one says the same (one module answering 0 is not a change). */
    private var candidate: Pair<Boolean, Int>? = null

    /** Feed each cycle; true when something new has lit up since the start (or since the last time it said so). */
    fun onCycle(v: Map<String, Double>, fresh: Set<String>): Boolean {
        if (MIL !in fresh) return false
        val reading = ((v[MIL] ?: return false) >= 0.5) to (v[COUNT]?.toInt() ?: 0)
        val was = milOn
        val before = count
        // The first reading is the baseline: what was already on when the drive started was seen on connecting.
        if (was == null || before == null) { milOn = reading.first; count = reading.second; return false }
        if (reading == (was to before)) { candidate = null; return false }
        if (candidate != reading) { candidate = reading; return false }
        candidate = null
        milOn = reading.first; count = reading.second
        return (reading.first && !was) || reading.second > before
    }

    companion object {
        const val MIL = "0101_MIL"
        const val COUNT = "0101_DTC"
        val keys = listOf(MIL, COUNT)

        const val LIT = "Check engine light: %1\$s (%2\$s). %3\$s"
        const val LIT_NO_CODE = "The check engine light has come on. Look at the codes when you can stop safely."
        val texts = listOf(LIT, LIT_NO_CODE, "Look at the codes when you can stop safely.")
    }
}
