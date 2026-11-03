package com.openobd.core.diagnosis

/**
 * The car data given to the AI assistant as plain text. Deliberately leaves out the VIN, location and anything
 * personal: make/model/engine are enough to reason about a fault.
 */
object AssistantContext {
    data class Car(val description: String?, val fuel: String?, val protocol: String?)
    data class Code(val code: String, val description: String, val source: String)

    fun build(
        car: Car,
        focus: Code,
        explanation: Explanation?,
        otherCodes: List<Code>,
        freezeFrame: Map<String, String>,
        live: Map<String, String>,
        failedTests: List<String>,
        readiness: String?,
        batteryV: Double?,
    ): String = buildString {
        appendLine("Car data read by OpenOBD:")
        appendLine("- Car: " + (car.description ?: "unknown model"))
        car.fuel?.let { appendLine("- Fuel: $it") }
        car.protocol?.let { appendLine("- OBD protocol: $it") }
        batteryV?.let { appendLine("- Battery voltage now: %.1f V".format(java.util.Locale.US, it)) }
        appendLine("- Code to explain: ${focus.code} - ${focus.description} (${focus.source})")
        if (otherCodes.isNotEmpty()) {
            appendLine("- Other codes present:")
            otherCodes.forEach { appendLine("  - ${it.code} - ${it.description} (${it.source})") }
        }
        if (freezeFrame.isNotEmpty()) {
            appendLine("- Freeze frame (values when a code was stored):")
            freezeFrame.forEach { (k, v) -> appendLine("  - $k: $v") }
        }
        if (live.isNotEmpty()) {
            appendLine("- Live values now:")
            live.forEach { (k, v) -> appendLine("  - $k: $v") }
        }
        if (failedTests.isNotEmpty()) {
            appendLine("- On-board tests out of limits:")
            failedTests.forEach { appendLine("  - $it") }
        }
        readiness?.let { appendLine("- Emission monitors: $it") }
        explanation?.let { e ->
            appendLine("- The app's own rule-based assessment: drive advice ${e.advice.name}; ranked causes: " +
                e.causes.filter { it.cause.isNotEmpty() }.take(5).joinToString("; ") { it.cause })
        }
    }.trim()
}
