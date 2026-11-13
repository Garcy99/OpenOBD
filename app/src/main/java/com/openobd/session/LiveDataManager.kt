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
    private companion object {
        /** Unanswered requests before a maker value is only retried now and then (every [RETRY_CYCLES] cycles). */
        const val MAX_UNANSWERED = 6
        const val RETRY_CYCLES = 60L
        /** The brands' built-in values (a guess per car); the ones the user added are always asked for. */
        val BUILTIN_IDS: Set<String> by lazy { com.openobd.core.brand.Families.all.flatMap { it.builtinPids }.map { it.id }.toSet() }
        /** The built-in definitions themselves: one the user found on the car under the same id is theirs, always asked for. */
        val BUILTIN_PIDS: Set<com.openobd.core.brand.CustomPid> by lazy { com.openobd.core.brand.Families.all.flatMap { it.builtinPids }.toSet() }
        /** ... and only after this long without an answer. */
        const val GIVE_UP_MS = 20_000L
        /** At most one soft recovery (see the loop) in this long; more silence than that is a lost link. */
        const val SOFT_RECOVERY_MS = 30_000L
    }

    /**
     * Unanswered requests in a row before the link counts as lost: a few for an adapter in the car, more over a phone
     * bridge or Wi-Fi, where a second's hiccup is normal and the link comes back by itself.
     */
    @Volatile var timeoutsBeforeLost = 4

    /**
     * Since when the adapter answers but the car does not (NO DATA, CAN ERROR ...): the engine was switched off.
     * Null while the car answers.
     */
    @Volatile var carSilentSince: Long? = null
        private set

    /** The fuel tank estimate of this connection (set by the session with the car's tank and consumption). */
    @Volatile var tank: com.openobd.core.obd.FuelTankEstimator? = null
    /** Called when the tank estimate recognises a refuel. */
    @Volatile var onRefuel: ((com.openobd.core.obd.FuelTankEstimator.Refuel) -> Unit)? = null

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

    /**
     * Built-in maker values this car did not answer on earlier connections: not asked for at all (each try costs a
     * header switch and a wait, which upsets some adapters). Set by the session at each connection.
     */
    @Volatile var skipMakerKeys: Set<String> = emptySet()
    /** A built-in maker value given up on during this connection (to remember for the next ones). */
    @Volatile var onMakerGaveUp: ((String) -> Unit)? = null
    /** The adapter stopped answering for a moment and came back without reconnecting (for the debug log). */
    @Volatile var onSoftRecovery: (() -> Unit)? = null
    /** How many times the adapter was brought back without reconnecting (this app run). */
    val softRecoveries = java.util.concurrent.atomic.AtomicInteger()

    private var job: Job? = null

    /** Sensors selected for the chart screen (kept across navigation). */
    val chartKeys = MutableStateFlow(listOf("010C", "010D", "CALC_PEDAL"))

    fun subscribe(owner: String, keys: Collection<String>) {
        subscriptions.value = subscriptions.value + (owner to keys.toSet())
    }

    fun unsubscribe(owner: String) {
        subscriptions.value = subscriptions.value - owner
    }

    fun addListener(l: CycleListener) { listeners += l }

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
        carSilentSince = null
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
        var customTurn = 0L
        var failures = 0
        var windowStart = System.currentTimeMillis()
        var windowCycles = 0
        // Built-in maker values this car never answers (the brand's oil pressure on an engine without the sensor ...)
        // are asked again only now and then, so they do not slow every other gauge down.
        val unanswered = HashMap<String, Int>()
        val firstMiss = HashMap<String, Long>()
        val answered = HashSet<String>()
        val gaveUp = HashSet<String>()
        var lastCustomKeys = emptySet<String>()
        var lastSoftRecovery = 0L
        /**
         * Several requests in a row without an answer. Before taking the link for lost, ask the adapter itself (its
         * voltmeter): when it answers, it was only stuck (after switching to a module, or a slow reply), so it is put
         * back on the standard OBD addressing and polling goes on, without dropping and reconnecting. Once in a while.
         */
        suspend fun recovered(): Boolean {
            val now = System.currentTimeMillis()
            if (now - lastSoftRecovery < SOFT_RECOVERY_MS) return false
            lastSoftRecovery = now
            val alive = runCatching { kotlinx.coroutines.withTimeoutOrNull(3000) { client.elm.readVoltage() } }.getOrNull() != null
            if (!alive) return false
            runCatching { modules.resetToObd() }
            softRecoveries.incrementAndGet()
            onSoftRecovery?.invoke()
            return true
        }
        while (isActive) {
            if (pauseCount.get() > 0) { delay(100); continue }
            val keys = subscriptions.value.values.flatten().toSet()
            val sensorKeys = HashSet<String>()
            for (k in keys) {
                if (k.startsWith("CALC_")) DerivedSensors.requirements(k, supported, params.diesel)?.let { sensorKeys += it }
                else sensorKeys += k
            }
            val pids = sensorKeys.mapNotNull { k -> Pids.sensors[k]?.pid }.filter { it in supported }.distinct()
            val customKeys = sensorKeys.filter { it.startsWith("X_") }.toSet()
            // Values asked for anew get fresh chances.
            if (customKeys != lastCustomKeys) { (customKeys - lastCustomKeys).forEach { unanswered.remove(it); firstMiss.remove(it) }; lastCustomKeys = customKeys }
            val nowMs = System.currentTimeMillis()
            val customs = customKeys.mapNotNull { CustomSensors.pid(it) }.filter { p -> !(p in BUILTIN_PIDS && p.key in skipMakerKeys) }.filter { p ->
                p !in BUILTIN_PIDS || p.key in answered || (unanswered[p.key] ?: 0) < MAX_UNANSWERED || nowMs - (firstMiss[p.key] ?: nowMs) < GIVE_UP_MS ||
                    cycle % RETRY_CYCLES == 0L
            }
            if (pids.isEmpty() && customs.isEmpty()) { delay(250); continue }
            val slowPids = pids.filter { p -> Pids.all[p]?.sensors?.all { it.slow } == true }
            val fastPids = pids - slowPids.toSet()
            val toRead = if (cycle % 8 == 0L || fastPids.isEmpty()) pids else fastPids
            cycle++

            val askedCustom = customs.isNotEmpty() && (cycle % 2 == 1L || pids.isEmpty())
            // The brand's built-in values (often for modules a car may not have) get their own turns, so a module that
            // makes the adapter wait never holds up the values the user added.
            val (builtin, own) = customs.partition { it in BUILTIN_PIDS }
            customTurn++
            val asked = when {
                own.isEmpty() -> builtin
                builtin.isEmpty() -> own
                customTurn % 3 == 0L -> builtin
                else -> own
            }
            val customData = if (askedCustom) {
                try { modules.readCustom(asked) } catch (e: Exception) { emptyMap() }
            } else emptyMap()
            val data = if (toRead.isEmpty()) emptyMap() else try {
                client.readPids(toRead)
            } catch (e: ElmException) {
                // The adapter answering with a bus error: it is there, the car is not.
                if (e.error != ElmError.TIMEOUT && e.error != ElmError.LINK_LOST && carSilentSince == null) carSilentSince = System.currentTimeMillis()
                if (e.error == ElmError.TIMEOUT && ++failures >= timeoutsBeforeLost) {
                    if (recovered()) { failures = 0; delay(200); continue }
                    onAdapterLost?.invoke(); return
                }
                delay(200); continue
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (++failures >= timeoutsBeforeLost) {
                    if (recovered()) { failures = 0; delay(200); continue }
                    onAdapterLost?.invoke(); return
                }
                delay(200); continue
            }
            // A maker value counts as unanswered only when the car answered the rest of the cycle (not while the
            // link hiccups or the bus changes).
            if (askedCustom && (data.isNotEmpty() || customData.isNotEmpty())) for (p in asked) {
                if (customData[p.key] != null) { answered += p.key; unanswered.remove(p.key); firstMiss.remove(p.key) }
                else if (p.key !in answered) {
                    val n = (unanswered[p.key] ?: 0) + 1
                    unanswered[p.key] = n
                    val since = firstMiss.getOrPut(p.key) { System.currentTimeMillis() }
                    // A built-in value this car does not have: remembered, so later connections do not ask at all.
                    if (p in BUILTIN_PIDS && n >= MAX_UNANSWERED && System.currentTimeMillis() - since >= GIVE_UP_MS && gaveUp.add(p.key)) onMakerGaveUp?.invoke(p.key)
                }
            }
            if (data.isEmpty() && customData.isEmpty()) {
                if (toRead.isNotEmpty() && client.lastMiss == ElmError.TIMEOUT) {
                    // The adapter itself did not answer (a link hiccup, or stuck after a module read): not the car.
                    if (++failures >= timeoutsBeforeLost) {
                        if (recovered()) { failures = 0; delay(200); continue }
                        onAdapterLost?.invoke(); return
                    }
                    continue
                }
                // Asked for standard values, and the adapter said the car does not answer: the car has gone quiet.
                if (toRead.isNotEmpty() && carSilentSince == null) carSilentSince = System.currentTimeMillis()
                if (++failures >= timeoutsBeforeLost * 6) { onAdapterLost?.invoke(); return }
            }
            if (data.isNotEmpty() || customData.isNotEmpty()) { failures = 0; carSilentSince = null }

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
            // The tank: estimated across cycles (level sensor steadied by the fuel burnt).
            tank?.update(latest, now)?.let { e ->
                e.pct?.let { latest[DerivedSensors.TANK.key] = it; fresh += DerivedSensors.TANK.key }
                e.litres?.let { latest[DerivedSensors.TANK_L.key] = it; fresh += DerivedSensors.TANK_L.key }
                e.rangeKm?.let { latest[DerivedSensors.RANGE.key] = it; fresh += DerivedSensors.RANGE.key }
                e.refuel?.let { r -> runCatching { onRefuel?.invoke(r) } }
            }
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

/**
 * The brand's built-in values (GM oil life, tyre pressures, filter values ...) a car did not answer, per car, with
 * when: not asked for again for a week, so a car without them is not slowed (or its adapter upset) by them.
 */
class SilentMakerValues(context: android.content.Context, private val keepMs: Long = 7 * 86_400_000L) {
    private val prefs = context.getSharedPreferences("silent_maker_values", android.content.Context.MODE_PRIVATE)

    fun of(carId: String, now: Long = System.currentTimeMillis()): Set<String> =
        prefs.getStringSet(carId, emptySet()).orEmpty().mapNotNull { e ->
            val key = e.substringBefore('|'); val at = e.substringAfter('|', "0").toLongOrNull() ?: 0L
            key.takeIf { now - at < keepMs }
        }.toSet()

    fun add(carId: String, key: String, now: Long = System.currentTimeMillis()) {
        val kept = prefs.getStringSet(carId, emptySet()).orEmpty().filter { it.substringBefore('|') != key && now - (it.substringAfter('|', "0").toLongOrNull() ?: 0L) < keepMs }
        prefs.edit().putStringSet(carId, (kept + "$key|$now").toSet()).apply()
    }

    fun clear(carId: String) { prefs.edit().remove(carId).apply() }
}
