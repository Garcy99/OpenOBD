package com.openobd.core.report

/** What the app knows about the car right now; anything unknown is null. */
data class ScoreInput(
    val milOn: Boolean? = null,
    val storedCodes: Int? = null,
    val pendingCodes: Int? = null,
    val permanentCodes: Int? = null,
    val monitorsNotReady: Int? = null,
    val moduleCodes: Int? = null,
    /** Battery voltage and whether the engine was running when it was read. */
    val batteryV: Double? = null,
    val engineRunning: Boolean? = null,
    val healthAct: Int = 0,
    val healthWatch: Int = 0,
    val maintenanceOverdue: Int = 0,
)

data class ScoreReason(val text: String, val args: List<String> = emptyList(), val points: Int)

data class CarScoreResult(val score: Int?, val reasons: List<ScoreReason>) {
    val label: String get() = when {
        score == null -> "Not checked yet"
        score >= 90 -> "Excellent"
        score >= 75 -> "Good"
        score >= 55 -> "Needs attention"
        else -> "Have it checked"
    }
}

/**
 * One number for the state of the car, like the scores in premium diagnostic apps: everything starts at 100 and
 * each problem costs points, capped per kind so one category never hides the others. Without a scan or any
 * history there is no score.
 */
object CarScore {
    fun compute(i: ScoreInput): CarScoreResult {
        val known = listOfNotNull(i.milOn, i.storedCodes, i.moduleCodes, i.batteryV).isNotEmpty() || i.healthAct + i.healthWatch + i.maintenanceOverdue > 0
        if (!known) return CarScoreResult(null, emptyList())
        val r = mutableListOf<ScoreReason>()
        if (i.milOn == true) r += ScoreReason("Check engine light is on", points = 25)
        i.storedCodes?.takeIf { it > 0 }?.let { r += ScoreReason("%1\$s confirmed trouble code(s)", listOf("$it"), minOf(30, it * 12)) }
        i.pendingCodes?.takeIf { it > 0 }?.let { r += ScoreReason("%1\$s pending code(s)", listOf("$it"), minOf(15, it * 5)) }
        i.permanentCodes?.takeIf { it > 0 && (i.storedCodes ?: 0) == 0 }?.let { r += ScoreReason("%1\$s permanent code(s)", listOf("$it"), minOf(10, it * 5)) }
        i.moduleCodes?.takeIf { it > 0 }?.let { r += ScoreReason("%1\$s code(s) in other modules", listOf("$it"), minOf(15, it * 4)) }
        i.monitorsNotReady?.takeIf { it > 0 }?.let { r += ScoreReason("%1\$s emission monitor(s) not ready", listOf("$it"), minOf(8, it * 2)) }
        i.batteryV?.let { v ->
            val running = i.engineRunning ?: (v > 13.2)
            when {
                running && v < 12.6 -> r += ScoreReason("The battery is not charging", points = 20)
                !running && v < 12.0 -> r += ScoreReason("Battery charge is low", points = 15)
                !running && v < 12.3 -> r += ScoreReason("Battery partly discharged", points = 6)
                running && v > 15.0 -> r += ScoreReason("Charging voltage too high", points = 15)
                else -> {}
            }
        }
        if (i.healthAct > 0) r += ScoreReason("%1\$s health trend(s) need attention", listOf("${i.healthAct}"), minOf(20, i.healthAct * 10))
        if (i.healthWatch > 0) r += ScoreReason("%1\$s health trend(s) to watch", listOf("${i.healthWatch}"), minOf(8, i.healthWatch * 4))
        if (i.maintenanceOverdue > 0) r += ScoreReason("%1\$s service item(s) overdue", listOf("${i.maintenanceOverdue}"), minOf(16, i.maintenanceOverdue * 8))
        val score = (100 - r.sumOf { it.points }).coerceIn(0, 100)
        return CarScoreResult(score, r.sortedByDescending { it.points })
    }

    val texts = listOf(
        "Not checked yet", "Excellent", "Good", "Needs attention", "Have it checked",
        "Check engine light is on", "%1\$s confirmed trouble code(s)", "%1\$s pending code(s)", "%1\$s permanent code(s)",
        "%1\$s code(s) in other modules", "%1\$s emission monitor(s) not ready", "The battery is not charging", "Battery charge is low",
        "Battery partly discharged", "Charging voltage too high", "%1\$s health trend(s) need attention", "%1\$s health trend(s) to watch",
        "%1\$s service item(s) overdue",
    )
}
