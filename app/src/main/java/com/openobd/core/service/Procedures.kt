package com.openobd.core.service

/**
 * A guided service procedure done with the car's own controls (no writes to control units). Text is English
 * source, translated by the UI. [families] limits it to brand family ids; empty = every car.
 */
data class Procedure(
    val id: String,
    val title: String,
    val summary: String,
    val steps: List<String>,
    val caution: String? = null,
    val families: Set<String> = emptySet(),
    val dieselOnly: Boolean = false,
    /** Opens the live regeneration helper. */
    val liveHelper: Boolean = false,
)

object Procedures {
    val all = listOf(
        Procedure(
            "dpf-drive", "Particulate filter regeneration drive",
            "Lets the engine burn off the soot in the DPF when the warning light comes on or after many short trips.",
            listOf(
                "Make sure there is at least a quarter tank of fuel and no other warning lights.",
                "Warm the engine up fully (coolant above 70 °C).",
                "Drive on a motorway or open road at a steady 60-110 km/h, in a gear that keeps 2000-3000 rpm.",
                "Keep it up for 15-20 minutes without stopping the engine. The live helper shows when the conditions are met.",
                "During a regeneration the idle speed may rise, the cooling fan may run and you may notice a hot smell: this is normal.",
                "If the DPF light is still on after two drives like this, have the filter checked; a forced regeneration needs a workshop tool.",
            ),
            caution = "Do not switch the engine off in the middle of a regeneration if you can avoid it.",
            dieselOnly = true, liveHelper = true,
        ),
        Procedure(
            "opel-oil-life", "Reset the service / oil life reminder",
            "Opel / Vauxhall with the Driver Information Center (Astra J, Insignia A, Zafira C, Meriva B, Mokka...).",
            listOf(
                "Switch the ignition on with the engine off.",
                "With the MENU button on the turn signal lever, choose the Vehicle Information menu.",
                "Turn the adjuster wheel until \"Remaining oil life\" is shown.",
                "Press and hold the SET/CLR button for a few seconds until the value shows 100 %.",
                "Switch the ignition off and on again and check the new value.",
            ),
            caution = "Only after the oil and filter have actually been changed. Menus differ between display versions: see \"Engine oil life monitor\" in the owner's manual.",
            families = setOf("gm-europe"),
        ),
        Procedure(
            "after-battery", "After replacing or disconnecting the battery",
            "Things many cars need to re-learn after losing power.",
            listOf(
                "Power windows: close each window fully and keep the switch held up for 3 seconds, so the one-touch function and pinch protection learn the end position again.",
                "Set the clock, and the radio presets if they were lost.",
                "Start the engine and let it idle for 10 minutes without switching anything on: the idle speed and throttle adaptations re-learn.",
                "The emission monitors start again from \"not ready\": drive normally for a few days before an ITV.",
                "Some cars (BMW, VAG, Mercedes with start-stop) also need the new battery registered with a diagnostic tool.",
            ),
        ),
        Procedure(
            "drive-cycle", "Drive cycle to make the monitors ready",
            "After clearing codes or a repair, before an ITV / emissions test.",
            listOf(
                "Start with a cold engine (left at least 8 hours) and let it idle for 2-3 minutes with the lights and rear demister on.",
                "Drive in town for 5 minutes with gentle stops and starts.",
                "Accelerate smoothly to 90 km/h and hold a steady speed for 5 minutes.",
                "Let the car slow down to 30 km/h without braking or using the clutch, then accelerate again to 90 km/h. Repeat twice.",
                "Finish with 5 minutes of town driving and 2 minutes of idle, then check Emission readiness in OpenOBD.",
            ),
            caution = "Drive safely and legally; the order is less important than covering all the conditions over a few days.",
        ),
    )

    fun forCar(familyId: String?, diesel: Boolean): List<Procedure> =
        all.filter { (it.families.isEmpty() || familyId in it.families) && (!it.dieselOnly || diesel) }

    /** Every text, for translation coverage. */
    val texts: List<String> get() = all.flatMap { listOfNotNull(it.title, it.summary, it.caution) + it.steps }
}
