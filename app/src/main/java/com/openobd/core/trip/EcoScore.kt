package com.openobd.core.trip

import kotlin.math.min

/** Driving-style counters of one trip. */
data class EcoStats(
    val harshAccel: Int = 0,
    val harshBrake: Int = 0,
    /** Seconds above the economical engine speed (3000 rpm diesel, 3500 rpm petrol). */
    val highRpmS: Double = 0.0,
    /** Idling beyond the first minute of each stop. */
    val longIdleS: Double = 0.0,
    val drivingS: Double = 0.0,
    val distanceKm: Double = 0.0,
)

data class EcoTip(val text: String, val args: List<String> = emptyList())

/**
 * Smooth-driving score from 0 to 100, like the ones in premium driving apps: hard accelerations and braking,
 * revving high and long idling cost points, relative to the distance driven. Needs only speed and engine speed.
 */
class EcoScorer(private val diesel: Boolean = false) {
    companion object {
        /** m/s²: about 0-100 km/h in under 9 s. */
        const val HARSH_ACCEL = 3.0
        /** m/s²: firmer than a normal stop at a junction. */
        const val HARSH_BRAKE = -3.5
        private const val WINDOW_MS = 1000L

        /** Score for a trip; null for trips too short to judge. */
        fun score(s: EcoStats): Int? {
            if (s.distanceKm < 1.0 || s.drivingS < 120) return null
            val per10km = 10.0 / s.distanceKm
            val accel = min(30.0, s.harshAccel * per10km * 6)
            val brake = min(30.0, s.harshBrake * per10km * 6)
            val rpm = min(20.0, s.highRpmS / s.drivingS * 100)
            val idle = min(20.0, s.longIdleS / s.drivingS * 50)
            return (100 - accel - brake - rpm - idle).toInt().coerceIn(0, 100)
        }

        /** The one or two things that would raise the score most. */
        fun tips(s: EcoStats, diesel: Boolean = false): List<EcoTip> {
            if (score(s) == null) return emptyList()
            val per10km = 10.0 / s.distanceKm
            val costs = listOf(
                // Ranked by the points each habit actually cost (the same caps as the score).
                EcoTip("Accelerate more gently: %1\$s hard accelerations.", listOf(s.harshAccel.toString())) to min(30.0, s.harshAccel * per10km * 6),
                EcoTip("Look further ahead to brake less: %1\$s hard braking events.", listOf(s.harshBrake.toString())) to min(30.0, s.harshBrake * per10km * 6),
                EcoTip("Change up earlier: %1\$s%% of the time above %2\$s rpm.", listOf("%.0f".format(s.highRpmS / s.drivingS * 100), if (diesel) "3000" else "3500")) to
                    min(20.0, s.highRpmS / s.drivingS * 100),
                EcoTip("Switch the engine off during long stops: %1\$s min idling.", listOf("%.0f".format(s.longIdleS / 60))) to min(20.0, s.longIdleS / s.drivingS * 50),
            )
            return costs.filter { it.second >= 3 }.sortedByDescending { it.second }.take(2).map { it.first }
                .ifEmpty { listOf(EcoTip("Smooth driving. Keep it up!")) }
        }

        val texts = listOf(
            "Accelerate more gently: %1\$s hard accelerations.", "Look further ahead to brake less: %1\$s hard braking events.",
            "Change up earlier: %1\$s%% of the time above %2\$s rpm.", "Switch the engine off during long stops: %1\$s min idling.", "Smooth driving. Keep it up!",
        )
    }

    private var stats = EcoStats()
    val current: EcoStats get() = stats
    private var lastT = 0L
    private var refT = 0L
    private var refSpeed = 0.0
    private var accelEvent = false
    private var brakeEvent = false
    private var idleSince: Long? = null

    fun onSample(speedKmh: Double, rpm: Double, t: Long) {
        val dt = if (lastT == 0L) 0.0 else (t - lastT) / 1000.0
        lastT = t
        if (dt <= 0 || dt > 5) { refT = t; refSpeed = speedKmh; return }
        var s = stats
        val moving = speedKmh > 1
        if (moving || rpm > 300) s = s.copy(drivingS = s.drivingS + dt)
        s = s.copy(distanceKm = s.distanceKm + speedKmh * dt / 3600)
        if (rpm > (if (diesel) 3000 else 3500)) s = s.copy(highRpmS = s.highRpmS + dt)
        if (!moving && rpm > 300) {
            val since = idleSince ?: t.also { idleSince = it }
            if (t - since > 60_000) s = s.copy(longIdleS = s.longIdleS + dt)
        } else idleSince = null
        // Acceleration over at least one second, so a single noisy reading is not an event.
        if (t - refT >= WINDOW_MS) {
            val a = (speedKmh - refSpeed) / 3.6 / ((t - refT) / 1000.0)
            if (a > HARSH_ACCEL && !accelEvent) { accelEvent = true; s = s.copy(harshAccel = s.harshAccel + 1) } else if (a < 1.5) accelEvent = false
            if (a < HARSH_BRAKE && !brakeEvent) { brakeEvent = true; s = s.copy(harshBrake = s.harshBrake + 1) } else if (a > -1.5) brakeEvent = false
            refT = t; refSpeed = speedKmh
        }
        stats = s
    }
}
