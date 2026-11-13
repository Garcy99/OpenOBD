package com.openobd.session

import com.openobd.core.diagnosis.MilWatch
import com.openobd.core.voice.Announcement
import kotlinx.coroutines.launch

/**
 * The check engine light coming on while driving: the codes are read at once and the driver is told (notification and,
 * when spoken alerts are on, voice) the code, what it means and whether it is safe to keep driving.
 */
class MilAlertFeature internal constructor(private val s: ObdSession) {
    @Volatile private var watch: MilWatch? = null
    @Volatile private var checking = false

    internal fun onConnected(supported: Set<Int>) {
        if (0x01 !in supported) { watch = null; s.live.unsubscribe("mil"); return }
        watch = MilWatch()
        s.live.subscribe("mil", MilWatch.keys)
    }

    internal fun onCycle(values: Map<String, Double>, fresh: Set<String>) {
        val w = watch ?: return
        if (!w.onCycle(values, fresh) || checking) return
        checking = true
        // By code and kind: a pending code becoming confirmed is just what lights the lamp.
        val known = (s.dtcScan.value as? Op.Done)?.value?.codes?.map { it.code to it.type }?.toSet().orEmpty()
        s.appScope.launch {
            try {
                s.debug.event("Check engine light / new code while driving: reading the codes")
                val scan = runCatching { s.scanDtcsInternal(record = true) }.getOrNull()
                val codes = scan?.codes.orEmpty()
                if (scan == null) { val a = Announcement(MilWatch.LIT_NO_CODE, notify = true); lastAlert = a; s.voice.warn(a); return@launch }
                // The new one, confirmed before pending. None new: a module answering oddly, not a new fault.
                val fresh = codes.filter { (it.code to it.type) !in known }
                val code = fresh.sortedBy { if (it.type == com.openobd.core.obd.DtcType.STORED) 0 else 1 }.firstOrNull()
                if (code == null) { s.debug.event("No new code after all: no alert"); return@launch }
                val a = run {
                    val advice = runCatching { s.diagnosis.explain(code.code).advice }.getOrNull()
                    Announcement(MilWatch.LIT, listOf(code.code, s.describe(code.code).description, advice?.label ?: "Look at the codes when you can stop safely."), notify = true)
                }
                lastAlert = a
                s.voice.warn(a)
            } finally {
                checking = false
            }
        }
    }

    /** The last warning given (for tests and the debug report). */
    @Volatile var lastAlert: Announcement? = null
        internal set
}
