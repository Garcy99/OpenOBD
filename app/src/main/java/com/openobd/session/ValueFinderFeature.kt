package com.openobd.session

import com.openobd.core.brand.EcuModule
import com.openobd.core.brand.IdKind
import com.openobd.core.brand.ModuleCategory
import com.openobd.core.brand.ValueFinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The value finder: reads what the engine computer answers in the identifier groups where live values are kept, then
 * finds which of them reads as a value the driver typed (seen in another app), and saves it as the car's own value.
 */
class ValueFinderFeature internal constructor(private val s: ObdSession) {
    data class State(
        val scanning: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        /** Identifiers that answered so far. */
        val answered: Int = 0,
        val candidates: List<ValueFinder.Candidate> = emptyList(),
        val searched: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    /** Everything read so far (identifier → bytes), kept for further searches on this connection. */
    private val records = LinkedHashMap<Pair<IdKind, Int>, IntArray>()
    /** Groups already read on this connection. */
    private val scanned = HashSet<Pair<IdKind, IntRange>>()
    private var job: Job? = null
    /** For tests: where to look instead of the whole engine computer (a full read takes minutes). */
    @Volatile internal var rangesForTest: List<Pair<IdKind, IntRange>>? = null
    @Volatile private var target: Double? = null
    @Volatile private var quantity = ValueFinder.Quantity.DPF_SOOT

    private fun engine(): EcuModule = s.modules.modulesForBrand().firstOrNull { it.tx == 0x7E0 && !it.secondaryBus }
        ?: EcuModule("Engine control module", "ECM", 0x7E0, 0x7E8, ModuleCategory.POWERTRAIN)

    /**
     * Looks for [value] (a [q]). The first time it reads the engine computer group by group (a few minutes; results
     * appear as it goes); after that it searches what was read.
     */
    fun search(value: Double, q: ValueFinder.Quantity) {
        target = value; quantity = q
        if (job?.isActive == true) { publish(); return }
        if (scanned.size == (rangesForTest ?: ValueFinder.engineRanges).size) { publish(); return }
        if (s.client == null) { _state.value = _state.value.copy(error = "Not connected to a vehicle"); return }
        val ranges = rangesForTest ?: ValueFinder.engineRanges
        val total = ranges.sumOf { it.second.count() }
        job = s.appScope.launch {
            _state.value = _state.value.copy(scanning = true, total = total, error = null)
            var before = 0
            try {
                val m = engine()
                for ((kind, range) in ranges) {
                    if ((kind to range) in scanned) { before += range.count(); continue }
                    val found = s.modules.scanIds(m, range, kind) { done, _ -> _state.value = _state.value.copy(done = before + done) }
                    found.forEach { (id, bytes) -> records[kind to id] = bytes }
                    scanned += kind to range
                    before += range.count()
                    _state.value = _state.value.copy(done = before, answered = records.size)
                    publish()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed")
            } finally {
                _state.value = _state.value.copy(scanning = false, answered = records.size)
                publish()
            }
        }
    }

    private fun publish() {
        val t = target ?: return
        _state.value = _state.value.copy(candidates = ValueFinder.search(records, t, quantity), searched = true, answered = records.size)
    }

    /** Stops reading (what was read is kept and searched). */
    fun stop() { job?.cancel() }

    /** The value has changed since: keeps the candidates that read as the new [value] (read again now). */
    fun refine(value: Double) {
        val cands = _state.value.candidates
        if (cands.isEmpty() || job?.isActive == true) return
        job = s.appScope.launch {
            _state.value = _state.value.copy(scanning = true, error = null)
            try {
                val m = engine()
                val fresh = HashMap<Pair<IdKind, Int>, IntArray>()
                for ((kind, ids) in cands.groupBy { it.kind }.mapValues { (_, l) -> l.map { it.id }.distinct() }) {
                    s.modules.readIds(m, ids, kind).forEach { (id, b) -> fresh[kind to id] = b; records[kind to id] = b }
                }
                target = value
                _state.value = _state.value.copy(candidates = ValueFinder.refine(cands, fresh, value, quantity))
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: "Failed")
            } finally {
                _state.value = _state.value.copy(scanning = false)
            }
        }
    }

    /** What [c] reads right now (to check it before saving). */
    suspend fun readNow(c: ValueFinder.Candidate): Double? =
        runCatching { s.modules.readIds(engine(), listOf(c.id), c.kind)[c.id]?.let { c.decode(it) } }.getOrNull()

    /** Saves [c] as the car's own value (replacing an earlier one of the same kind), and watches it from now on. */
    suspend fun save(c: ValueFinder.Candidate, q: ValueFinder.Quantity, name: String) {
        val m = engine()
        val header = "%03X".format(m.tx)
        val pid = ValueFinder.toCustomPid(c, q, name, header)
        s.garage.updateActive { v -> v.copy(customPids = v.customPids.filter { it.id != pid.id } + pid) }
        s.refreshDpfWatch()
    }

    /** A new connection may be another car: what was read no longer applies. */
    internal fun reset() {
        job?.cancel()
        records.clear()
        scanned.clear()
        target = null
        _state.value = State()
    }
}
