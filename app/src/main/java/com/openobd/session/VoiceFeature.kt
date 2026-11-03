package com.openobd.session

import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.voice.Announcement
import com.openobd.core.voice.VoiceWatch
import com.openobd.data.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** Spoken alerts: produces [announcements]; the app speaks them with the phone's text-to-speech. */
class VoiceFeature internal constructor(private val s: ObdSession) {
    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 8)
    val announcements: SharedFlow<Announcement> = _announcements

    @Volatile private var watch: VoiceWatch? = null
    private var units = com.openobd.core.obd.UnitSystem.METRIC

    internal fun configure(settings: AppSettings) {
        if (settings.voiceAlerts) {
            if (watch == null || units != settings.units) { units = settings.units; watch = VoiceWatch(settings.units) }
            s.live.subscribe("voice", VoiceWatch.keys)
        } else {
            watch = null
            s.live.unsubscribe("voice")
        }
    }

    internal fun onCycle(values: Map<String, Double>, now: Long) {
        watch?.onCycle(values, now)?.forEach { _announcements.tryEmit(it) }
    }

    internal fun onAlert(e: AlertEvent) {
        val w = watch ?: return
        val def = SensorCatalog.get(e.rule.sensorKey)
        _announcements.tryEmit(w.alert(e.sensorName, e.value, def?.unit ?: ObdUnit.NONE, def?.decimals ?: 0))
    }

    internal fun onLinkLost() {
        if (watch != null) _announcements.tryEmit(Announcement("Connection to the car lost."))
    }

    /** Says a short sentence so the user can check the volume and the voice. */
    fun test() { _announcements.tryEmit(Announcement("Voice alerts are on.")) }
}
