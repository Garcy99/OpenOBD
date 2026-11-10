package com.openobd.core.diesel

/**
 * Watches a diesel's particulate filter during every drive and tells when the car starts and ends a regeneration,
 * and when one is cut short (engine off or link lost). Cutting regenerations is what clogs filters, so the driver is
 * asked to keep the engine running.
 *
 * Only reliable sources are used: the regeneration status the car reports (standard PID 8B), else the filter's
 * inlet temperature (PID 7C), which rises well above normal during a regeneration. Cars that report neither are not
 * guessed at.
 */
class RegenWatch {
    enum class Source { STATUS, TEMPERATURE }

    sealed interface Event {
        data class Started(val source: Source, val atMs: Long, val loadPct: Double?) : Event
        data class Ended(val seconds: Long) : Event
        data class Interrupted(val seconds: Long) : Event
    }

    /** What the car tells about its filter now: null while it tells nothing. */
    data class State(val source: Source?, val active: Boolean, val since: Long?, val loadPct: Double?, val tempC: Double?)

    var state = State(null, false, null, null, null)
        private set

    private var hotSince: Long? = null
    private var coolSince: Long? = null

    fun onCycle(v: Map<String, Double>, now: Long): Event? {
        val status = v[STATUS]
        val temp = v[INLET_TEMP]
        val load = v[LOAD]
        val rpm = v[RPM]
        val wasActive = state.active
        val source = when { status != null -> Source.STATUS; temp != null -> Source.TEMPERATURE; else -> null }
        val active = when (source) {
            Source.STATUS -> status!! >= 0.5
            Source.TEMPERATURE -> {
                // Hot for a minute starts it, cool for half a minute ends it (short peaks under hard load do not count).
                val t = temp!!
                if (t >= HOT_C) { coolSince = null; if (hotSince == null) hotSince = now } else hotSince = null
                if (t <= COOL_C) { if (coolSince == null) coolSince = now } else coolSince = null
                when {
                    !wasActive && hotSince != null && now - hotSince!! >= 60_000 -> true
                    wasActive && coolSince != null && now - coolSince!! >= 30_000 -> false
                    else -> wasActive
                }
            }
            null -> wasActive
        }
        // The engine stopping while it regenerates cuts it short.
        if (wasActive && rpm != null && rpm < 300) {
            val secs = (now - (state.since ?: now)) / 1000
            state = State(source, false, null, load, temp)
            return Event.Interrupted(secs)
        }
        val event = when {
            active && !wasActive -> Event.Started(source!!, now, load)
            !active && wasActive -> Event.Ended((now - (state.since ?: now)) / 1000)
            else -> null
        }
        state = State(source, active, if (active) state.since ?: now else null, load, temp)
        return event
    }

    /** The link to the car went away: a regeneration in progress counts as cut short. */
    fun onLinkLost(now: Long): Event? {
        if (!state.active) return null
        val secs = (now - (state.since ?: now)) / 1000
        state = state.copy(active = false, since = null)
        hotSince = null; coolSince = null
        return Event.Interrupted(secs)
    }

    companion object {
        const val STATUS = "018B_R"
        const val LOAD = "018B_T"
        const val INLET_TEMP = "017C_I"
        const val RPM = "010C"
        const val HOT_C = 500.0
        const val COOL_C = 400.0
        /** What the app says and shows about regenerations (notifications, voice), for translation. */
        val texts = listOf(
            "Particulate filter", "Filter regenerating", "Keep the engine running and, if you can, drive on at a steady pace until it ends.",
            "Filter regeneration finished", "It took %1\$s.", "Filter regeneration interrupted",
            "The engine stopped after %1\$s. Plan a steady 15-20 minute drive soon so the filter can finish cleaning itself.",
            "The particulate filter is regenerating. Keep the engine running until it ends.", "Filter regeneration finished.",
            "Filter regeneration interrupted.", "Monthly summary", "Your %1\$s", "Yes", "No",
        )

        /** Live values the watch needs (only the supported ones are asked for). */
        val keys = listOf(STATUS, LOAD, INLET_TEMP, RPM)
    }
}
