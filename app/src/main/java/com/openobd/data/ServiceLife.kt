package com.openobd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the car's own computer says is left of the engine oil and the air filter (GM / Opel), per car. */
class ServiceLife(context: Context) {
    data class Reading(val vehicleId: String, val oilPct: Double?, val airFilterPct: Double?, val at: Long)

    private val prefs = context.getSharedPreferences("service_life", Context.MODE_PRIVATE)
    private val _latest = MutableStateFlow(load())
    val latest: StateFlow<Map<String, Reading>> = _latest

    private fun load(): Map<String, Reading> = prefs.all.keys.filter { it.endsWith(":at") }.map { it.removeSuffix(":at") }.associateWith { id ->
        Reading(id, prefs.getFloat("$id:oil", -1f).takeIf { it >= 0 }?.toDouble(), prefs.getFloat("$id:air", -1f).takeIf { it >= 0 }?.toDouble(), prefs.getLong("$id:at", 0))
    }

    fun save(r: Reading) {
        if (r.oilPct == null && r.airFilterPct == null) return
        val e = prefs.edit().putLong("${r.vehicleId}:at", r.at)
        r.oilPct?.let { e.putFloat("${r.vehicleId}:oil", it.toFloat()) }
        r.airFilterPct?.let { e.putFloat("${r.vehicleId}:air", it.toFloat()) }
        e.apply()
        _latest.value = load()
    }

    companion object {
        /** Below this much oil life left, Home suggests the oil change. */
        const val OIL_LOW_PCT = 15.0
    }
}
