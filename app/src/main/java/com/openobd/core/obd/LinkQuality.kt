package com.openobd.core.obd

/**
 * How well the adapter link is doing: readings per second, requests the adapter did not answer and times it had to be
 * brought back in the last few minutes, and links lost (and got back) during this trip.
 */
data class LinkQuality(val readingsPerSecond: Double, val timeouts: Int, val recoveries: Int, val dropsThisTrip: Int) {
    enum class Level { GOOD, FAIR, POOR }

    val level: Level get() = when {
        dropsThisTrip >= 2 || readingsPerSecond < 0.5 || timeouts >= 10 -> Level.POOR
        dropsThisTrip == 1 || recoveries > 0 || timeouts >= 3 || readingsPerSecond < 2 -> Level.FAIR
        else -> Level.GOOD
    }

    companion object {
        val texts = listOf(
            "Connection: good", "Connection: fair", "Connection: poor", "%1\$s readings/s", "Adapter connection",
            "Readings per second", "Requests the adapter did not answer (5 min)", "Times it got stuck and came back (5 min)",
            "Connection lost and back, this trip",
            "Good: the adapter answers steadily.",
            "Fair: the adapter misses some requests. If it keeps happening, check that it is firmly plugged in and the phone is close.",
            "Poor: the link keeps failing. A better adapter (or Bluetooth with less interference) usually fixes it; export the debug report if it goes on.",
        )
    }
}
