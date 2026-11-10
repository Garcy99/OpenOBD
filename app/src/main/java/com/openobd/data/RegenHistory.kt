package com.openobd.data

import android.content.Context
import com.openobd.core.diesel.RegenWatch

/** The particulate filter's regenerations seen on drives: how many, the last one and whether it was cut short. */
class RegenHistory(context: Context) {
    private val prefs = context.getSharedPreferences("regen", Context.MODE_PRIVATE)

    data class Summary(val finished: Int, val interrupted: Int, val lastAt: Long?, val lastInterrupted: Boolean, val lastSeconds: Long,
                       /** The filter's load towards the next regeneration as last reported (%), and when. */
                       val loadPct: Double? = null, val loadAt: Long? = null)

    /** Remembers the filter's load the car reported, for when it is not connected. */
    fun saveLoad(pct: Double) { prefs.edit().putFloat("load", pct.toFloat()).putLong("loadAt", System.currentTimeMillis()).apply() }

    fun record(e: RegenWatch.Event) {
        val now = System.currentTimeMillis()
        when (e) {
            is RegenWatch.Event.Started -> Unit
            is RegenWatch.Event.Ended -> prefs.edit().putInt("finished", prefs.getInt("finished", 0) + 1).putLong("lastAt", now)
                .putBoolean("lastInterrupted", false).putLong("lastSeconds", e.seconds).apply()
            is RegenWatch.Event.Interrupted -> prefs.edit().putInt("interrupted", prefs.getInt("interrupted", 0) + 1).putLong("lastAt", now)
                .putBoolean("lastInterrupted", true).putLong("lastSeconds", e.seconds).apply()
        }
    }

    fun summary() = Summary(prefs.getInt("finished", 0), prefs.getInt("interrupted", 0), prefs.getLong("lastAt", 0).takeIf { it > 0 },
        prefs.getBoolean("lastInterrupted", false), prefs.getLong("lastSeconds", 0),
        prefs.getFloat("load", -1f).takeIf { it >= 0 }?.toDouble(), prefs.getLong("loadAt", 0).takeIf { it > 0 })
}
