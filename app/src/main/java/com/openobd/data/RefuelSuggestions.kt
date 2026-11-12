package com.openobd.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A refuel the fuel tank gauge noticed (the level jumping up while the car stood), waiting for the driver to confirm
 * it as a fill-up in the fuel log, with the price of the station where it happened when known.
 */
class RefuelSuggestions(context: Context) {
    data class Suggestion(
        val vehicleId: String,
        val at: Long,
        val litres: Double,
        val full: Boolean,
        val odometerKm: Double?,
        /** Price per litre where the car was (the station, else the usual one around), and the station's brand. */
        val pricePerL: Double? = null,
        val station: String = "",
    )

    private val prefs = context.getSharedPreferences("refuel_suggestion", Context.MODE_PRIVATE)
    private val _pending = MutableStateFlow(read())
    val pending: StateFlow<Suggestion?> = _pending

    private fun read(): Suggestion? {
        if (!prefs.contains("car")) return null
        return Suggestion(
            prefs.getString("car", "").orEmpty(), prefs.getLong("at", 0), Double.fromBits(prefs.getLong("l", 0)), prefs.getBoolean("full", false),
            prefs.getLong("odo", -1).takeIf { it >= 0 }?.let { Double.fromBits(it) },
            prefs.getLong("price", -1).takeIf { it >= 0 }?.let { Double.fromBits(it) }, prefs.getString("station", "").orEmpty(),
        )
    }

    fun set(s: Suggestion) {
        prefs.edit().putString("car", s.vehicleId).putLong("at", s.at).putLong("l", s.litres.toRawBits()).putBoolean("full", s.full)
            .putLong("odo", s.odometerKm?.toRawBits() ?: -1).putLong("price", s.pricePerL?.toRawBits() ?: -1).putString("station", s.station).apply()
        _pending.value = s
    }

    fun clear() { prefs.edit().clear().apply(); _pending.value = null }

    companion object {
        /** Too little to be a refuel worth logging (or the level sensor settling after a slope). */
        const val MIN_LITRES = 5.0
        const val NOTICE = "About %1\$s L added. Tap to log the fill-up."

        /** Phrases about detected refuels, for translation. */
        val texts = listOf("Refuel detected", NOTICE)

        /** Whether [at] already has a fill-up logged by hand (within a few hours), so nothing needs proposing. */
        fun alreadyLogged(fuel: List<com.openobd.core.garage.FuelEntry>, vehicleId: String, at: Long) =
            fuel.any { it.vehicleId == vehicleId && kotlin.math.abs(it.date - at) < 3 * 3_600_000L }
    }
}
