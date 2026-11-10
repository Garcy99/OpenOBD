package com.openobd.session

import com.openobd.core.diesel.RegenMonitor
import com.openobd.core.diesel.RegenStatus
import com.openobd.core.diesel.RegenWatch
import com.openobd.core.voice.Announcement
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // ------------------------------------------------------------------ every drive: regenerations as they happen

    @Volatile private var watch: RegenWatch? = null
    private val _regen = MutableStateFlow<RegenWatch.State?>(null)
    /** The filter's state on this drive (null when the car does not tell, or is not a diesel). */
    val regen: StateFlow<RegenWatch.State?> = _regen
    private val _regenEvents = MutableSharedFlow<RegenWatch.Event>(extraBufferCapacity = 8)
    /** Regenerations starting, ending and being cut short (the app notifies them). */
    val regenEvents: SharedFlow<RegenWatch.Event> = _regenEvents

    /** At each connection: watches the filter when the car tells about it (standard PIDs 8B or 7C). */
    internal fun onConnected(supported: Set<Int>) {
        if (0x8B !in supported && 0x7C !in supported) { watch = null; _regen.value = null; s.live.unsubscribe("regenwatch"); return }
        if (watch == null) watch = RegenWatch()
        s.live.subscribe("regenwatch", RegenWatch.keys.filter { k -> com.openobd.core.obd.Pids.sensors[k]?.pid?.let { it in supported } == true })
    }

    internal fun onLinkLost(now: Long) {
        watch?.onLinkLost(now)?.let { emit(it) }
        _regen.value = watch?.state
    }

    private fun emit(e: RegenWatch.Event) {
        _regenEvents.tryEmit(e)
        s.voice.say(when (e) {
            is RegenWatch.Event.Started -> Announcement("The particulate filter is regenerating. Keep the engine running until it ends.")
            is RegenWatch.Event.Ended -> Announcement("Filter regeneration finished.")
            is RegenWatch.Event.Interrupted -> Announcement("Filter regeneration interrupted.")
        })
    }

    internal fun onCycle(values: Map<String, Double>, now: Long) {
        monitor?.let { _status.value = it.onCycle(values, now) }
        watch?.let { w ->
            w.onCycle(values, now)?.let { emit(it) }
            if (_regen.value != w.state) _regen.value = w.state
        }
    }
}
