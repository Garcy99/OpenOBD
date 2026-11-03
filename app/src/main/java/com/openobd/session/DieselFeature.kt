package com.openobd.session

import com.openobd.core.diesel.RegenMonitor
import com.openobd.core.diesel.RegenStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Particulate filter regeneration helper. Runs only while its screen is open. */
class DieselFeature internal constructor(private val s: ObdSession) {
    @Volatile private var monitor: RegenMonitor? = null
    private val _status = MutableStateFlow<RegenStatus?>(null)
    val status: StateFlow<RegenStatus?> = _status

    /** True when the active car runs on diesel (from its specs or the readiness monitors it reports). */
    val isDiesel: Boolean
        get() = s.garage.data.value.active?.specs?.fuel?.name?.contains("DIESEL") == true ||
            (s.dtcScan.value as? Op.Done)?.value?.readiness?.isDiesel == true

    fun start() {
        if (monitor == null) monitor = RegenMonitor()
        s.live.subscribe("regen", RegenMonitor.keys)
    }

    fun stop() {
        s.live.unsubscribe("regen")
        monitor = null
        _status.value = null
    }

    internal fun onCycle(values: Map<String, Double>, now: Long) {
        monitor?.let { _status.value = it.onCycle(values, now) }
    }
}
