package com.openobd.session

import com.openobd.core.brand.CustomSensors
import com.openobd.core.brand.ModuleClient
import com.openobd.core.elm.ElmError
import com.openobd.core.elm.ElmException
import com.openobd.core.obd.DerivedSensors
import com.openobd.core.obd.ObdClient
import com.openobd.core.obd.Pids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class Reading(val value: Double, val min: Double, val max: Double, val sum: Double, val count: Long, val time: Long) {
    val avg: Double get() = if (count > 0) sum / count else value
}

/** Time-stamped ring buffer used for charts and sparklines. */
class History(capacity: Int = 1200) {
    private val times = LongArray(capacity)
    private val values = FloatArray(capacity)
    private var head = 0
    private var size = 0

    @Synchronized
    fun add(t: Long, v: Double) {
        times[head] = t; values[head] = v.toFloat()
        head = (head + 1) % times.size
        if (size < times.size) size++
    }

    /** Returns points newer than [sinceMs] in chronological order. */
    @Synchronized
    fun snapshot(sinceMs: Long = 0): List<Pair<Long, Float>> {
        val out = ArrayList<Pair<Long, Float>>(size)
        for (i in 0 until size) {
            val idx = (head - size + i + times.size) % times.size
            if (times[idx] >= sinceMs) out += times[idx] to values[idx]
        }
        return out
    }

    @Synchronized
    fun clear() { head = 0; size = 0 }
}

fun interface CycleListener {
    /** Called after each polling cycle with the latest value of every known sensor. */
    fun onCycle(values: Map<String, Double>, fresh: Set<String>, timeMs: Long)
}

/**
 * Polls the union of sensors requested by visible screens and background features, as fast as the
 * adapter allows. Fast-changing PIDs are read every cycle, slow ones (temperatures, levels) every 8th.
 */
class LiveDataManager(private val scope: CoroutineScope) {
    private val subscriptions = MutableStateFlow<Map<String, Set<String>>>(emptyMap())
    private val _readings = MutableStateFlow<Map<String, Reading>>(emptyMap())
    val readings: StateFlow<Map<String, Reading>> = _readings
    private val _rate = MutableStateFlow(0.0)
    /** Polling cycles per second. */
    val rate: StateFlow<Double> = _rate
    private val _tick = MutableStateFlow(0L)
    val tick: StateFlow<Long> = _tick

    private val histories = ConcurrentHashMap<String, History>()
    private val listeners = CopyOnWriteArrayList<CycleListener>()
    private val pauseCount = AtomicInteger(0)
    private val latest = ConcurrentHashMap<String, Double>()

    @Volatile var params: DerivedSensors.Params = DerivedSensors.Params()
    /** Invoked when the adapter stopped answering repeatedly. */
    var onAdapterLost: (() -> Unit)? = null

    private var job: Job? = null

    /** Sensors selected for the chart screen (kept across navigation). */
    val chartKeys = MutableStateFlow(listOf("010C", "010D", "0111"))

    fun subscribe(owner: String, keys: Collection<String>) {
        subscriptions.value = subscriptions.value + (owner to keys.toSet())
    }

    fun unsubscribe(owner: String) {
        subscriptions.value = subscriptions.value - owner
    }

    fun addListener(l: CycleListener) { listeners += l }
    fun removeListener(l: CycleListener) { listeners -= l }

    fun history(key: String): History = histories.getOrPut(key) { History() }

    fun latestValue(key: String): Double? = latest[key]

    fun resetStats() {
        _readings.value = _readings.value.mapValues { (_, r) -> Reading(r.value, r.value, r.value, r.value, 1, r.time) }
        histories.values.forEach { it.clear() }
    }

    fun pause() { pauseCount.incrementAndGet() }
    fun resume() { pauseCount.updateAndGet { maxOf(0, it - 1) } }

    fun start(client: ObdClient, supported: Set<Int>) {
        stop()
        pauseCount.set(0) // a pause left over from a previous connection must not freeze this one
        latest.clear()
        _readings.value = emptyMap()
        histories.clear()
        job = scope.launch { loop(client, supported) }
    }

    fun stop() {
        job?.cancel()
        job = null
        _rate.value = 0.0
    }

    private suspend fun CoroutineScope.loop(client: ObdClient, supported: Set<Int>) {
        val modules = ModuleClient(client.elm)
        var cycle = 0L
        var failures = 0
        var windowStart = System.currentTimeMillis()
        var windowCycles = 0
        while (isActive) {
            if (pauseCount.get() > 0) { delay(100); continue }
            val keys = subscriptions.value.values.flatten().toSet()
            val sensorKeys = HashSet<String>()
            for (k in keys) {
                if (k.startsWith("CALC_")) DerivedSensors.requirements(k, supported)?.let { sensorKeys += it }
                else sensorKeys += k
            }
            val pids = sensorKeys.mapNotNull { k -> Pids.sensors[k]?.pid }.filter { it in supported }.distinct()
            val customs = sensorKeys.filter { it.startsWith("X_") }.mapNotNull { CustomSensors.pid(it) }
            if (pids.isEmpty() && customs.isEmpty()) { delay(250); continue }
            val slowPids = pids.filter { p -> Pids.all[p]?.sensors?.all { it.slow } == true }
            val fastPids = pids - slowPids.toSet()
            val toRead = if (cycle % 8 == 0L || fastPids.isEmpty()) pids else fastPids
            cycle++

            val customData = if (customs.isNotEmpty() && (cycle % 2 == 1L || pids.isEmpty())) {
                try { modules.readCustom(customs) } catch (e: Exception) { emptyMap() }
            } else emptyMap()
            val data = if (toRead.isEmpty()) emptyMap() else try {
                client.readPids(toRead)
            } catch (e: ElmException) {
                if (e.error == ElmError.TIMEOUT && ++failures >= 4) { onAdapterLost?.invoke(); return }
                delay(200); continue
            } catch (e: Exception) {
                if (++failures >= 4) { onAdapterLost?.invoke(); return }
                delay(200); continue
            }
            if (data.isEmpty() && customData.isEmpty() && ++failures >= 25) { onAdapterLost?.invoke(); return }
            if (data.isNotEmpty() || customData.isNotEmpty()) failures = 0

            val now = System.currentTimeMillis()
            val fresh = HashSet<String>()
            for ((pid, bytes) in data) {
                for (s in Pids.all[pid]?.sensors.orEmpty()) {
                    val v = s.decodeSafe(bytes) ?: continue
                    latest[s.key] = v
                    fresh += s.key
                }
            }
            for ((key, bytes) in customData) {
                val v = CustomSensors.get(key)?.decodeSafe(bytes) ?: continue
                latest[key] = v
                fresh += key
            }
            val calc = DerivedSensors.compute(latest, params)
            for ((k, v) in calc) { latest[k] = v; fresh += k }
            // Speed-dependent values become invalid when stopped
            if ("CALC_CONS" !in calc) latest.remove("CALC_CONS")

            val updated = HashMap(_readings.value)
            for (k in fresh) {
                val v = latest[k] ?: continue
                val r = updated[k]
                updated[k] = if (r == null) Reading(v, v, v, v, 1, now)
                else Reading(v, minOf(r.min, v), maxOf(r.max, v), r.sum + v, r.count + 1, now)
                history(k).add(now, v)
            }
            _readings.value = updated
            _tick.value = cycle
            val snapshot = HashMap(latest)
            for (l in listeners) runCatching { l.onCycle(snapshot, fresh, now) }

            windowCycles++
            if (now - windowStart >= 2000) {
                _rate.value = windowCycles * 1000.0 / (now - windowStart)
                windowStart = now; windowCycles = 0
            }
        }
    }
}
