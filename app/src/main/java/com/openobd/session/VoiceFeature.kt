package com.openobd.session

import com.openobd.core.obd.ObdUnit
import com.openobd.core.obd.SensorCatalog
import com.openobd.core.voice.Announcement
import com.openobd.core.voice.VoiceWatch
import com.openobd.data.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Alerts while driving: produces [announcements]. The app speaks them with the phone's text-to-speech when spoken
 * alerts are on, and shows the important ones ([Announcement.notify]) as notifications in any case.
 */
class VoiceFeature internal constructor(private val s: ObdSession) {
    private val _announcements = MutableSharedFlow<Announcement>(extraBufferCapacity = 8)
    val announcements: SharedFlow<Announcement> = _announcements

    /** Always watching (the important warnings are shown even without the voice). */
    @Volatile private var watch: VoiceWatch? = null
    @Volatile private var voiceOn = false
    private var units = com.openobd.core.obd.UnitSystem.METRIC
    private var diesel = false

    internal fun configure(settings: AppSettings) {
        voiceOn = settings.voiceAlerts
        val d = s.diesel.isDiesel
        if (watch == null || units != settings.units || diesel != d) { units = settings.units; diesel = d; watch = VoiceWatch(settings.units, d) }
        s.live.subscribe("voice", VoiceWatch.keys)
    }

    internal fun onCycle(values: Map<String, Double>, now: Long) {
        watch?.onCycle(values, now)?.forEach { if (voiceOn || it.notify) _announcements.tryEmit(it) }
    }

    internal fun onAlert(e: AlertEvent) {
        val w = watch ?: return
        if (!voiceOn) return
        val def = SensorCatalog.get(e.rule.sensorKey)
        _announcements.tryEmit(w.alert(e.sensorName, e.value, def?.unit ?: ObdUnit.NONE, def?.decimals ?: 0))
    }

    /** Says [a] when spoken alerts are on. */
    internal fun say(a: Announcement) { if (voiceOn) _announcements.tryEmit(a) }

    /** An important warning: shown as a notification, and said when spoken alerts are on. */
    internal fun warn(a: Announcement) { _announcements.tryEmit(a.copy(notify = true)) }

    internal fun onLinkLost() {
        if (voiceOn) _announcements.tryEmit(Announcement("Connection to the car lost."))
    }

    /** Says a short sentence so the user can check the volume and the voice. */
    fun test() { _announcements.tryEmit(Announcement("Voice alerts are on.")) }
}
