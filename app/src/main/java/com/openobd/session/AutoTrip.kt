package com.openobd.session

/**
 * Tells when the engine has been off long enough to end an automatic trip: no engine speed and no road speed for
 * [offMs] after the engine ran, or for [neverStartedMs] when it never started (the car's radio can join the phone
 * as soon as a door opens).
 */
class EngineOffDetector(private val offMs: Long = 3 * 60_000L, private val neverStartedMs: Long = 10 * 60_000L) : CycleListener {
    @Volatile private var since = 0L
    @Volatile private var lastRunning = 0L
    @Volatile private var ran = false

    /** Start watching (at connection). */
    fun reset(now: Long) { since = now; lastRunning = 0L; ran = false }

    override fun onCycle(values: Map<String, Double>, fresh: Set<String>, timeMs: Long) {
        val rpm = values["010C"].takeIf { "010C" in fresh }
        val speed = values["010D"].takeIf { "010D" in fresh }
        if ((rpm != null && rpm > 250) || (speed != null && speed > 2)) { lastRunning = timeMs; ran = true }
    }

    /** True when the trip should end now. */
    fun engineOff(now: Long): Boolean = if (ran) now - lastRunning > offMs else since > 0 && now - since > neverStartedMs
}

/** Phrases of automatic trips, for translation coverage. */
val autoTripTexts = listOf(
    "Automatic trip", "Waiting for the car…", "Connecting to the car…", "Could not connect to the adapter. The trip was not recorded.",
    "Trip saved", "Automatic trips",
    // The trip being recorded, in the notification.
    "Link lost: the trip is kept until it comes back (%1\$s)", "Trip %1\$s",
    "Engine off: the trip ends by itself in %1\$s", "End trip",
)
