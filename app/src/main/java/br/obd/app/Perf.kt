package br.obd.app

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado da medição de aceleração. */
data class PerfState(
    val phase: Phase,
    val speed: Double?,
    val elapsed: Double,
    val splits: Map<Int, Double> = emptyMap(),
    val trace: List<Pair<Double, Double>> = emptyList(),
    val sampleHz: Double = 0.0,
) {
    enum class Phase { WAITING_STOP, READY, RUNNING, DONE }
}

/**
 * Cronômetro de 0–60 e 0–100 km/h. Lê só a velocidade, o mais rápido que o carro
 * deixar, e interpola entre leituras para estimar o instante exato de cada marca.
 */
object PerfRunner {
    private lateinit var ctx: Context
    private val _state = MutableStateFlow<PerfState?>(null)
    val state: StateFlow<PerfState?> = _state.asStateFlow()
    private var job: Job? = null

    private val _best = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val best: StateFlow<Map<Int, Double>> = _best.asStateFlow()

    val TARGETS = listOf(40, 60, 80, 100)

    fun init(c: Context) {
        ctx = c.applicationContext
        val sp = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE)
        _best.value = TARGETS.mapNotNull { t -> sp.getFloat("best$t", -1f).takeIf { it > 0 }?.let { t to it.toDouble() } }.toMap()
    }

    fun start() {
        if (job?.isActive == true) return
        _state.value = PerfState(PerfState.Phase.WAITING_STOP, null, 0.0)
        job = ObdManager.exclusive("Medindo aceleração") {
            val elm = ObdManager.elm
            elm.verbose = false
            try {
                var phase = PerfState.Phase.WAITING_STOP
                var t0 = 0L
                var prev: Pair<Long, Double>? = null
                val splits = LinkedHashMap<Int, Double>()
                val trace = ArrayList<Pair<Double, Double>>()
                var n = 0; val tStart = System.currentTimeMillis()
                val limit = tStart + 5 * 60_000
                while (System.currentTimeMillis() < limit) {
                    val sp = elm.readPid("0D", retries = 0)?.let(Pids.SPEED.decode) ?: continue
                    val now = System.currentTimeMillis()
                    n++
                    val hz = n / ((now - tStart) / 1000.0).coerceAtLeast(0.1)
                    when (phase) {
                        PerfState.Phase.WAITING_STOP -> if (sp == 0.0) { phase = PerfState.Phase.READY; Feedback.step() }
                        PerfState.Phase.READY -> if (sp > 0) {
                            // a largada aconteceu entre a leitura anterior (0) e esta
                            t0 = prev?.let { (it.first + now) / 2 } ?: now
                            phase = PerfState.Phase.RUNNING
                            Feedback.cue()
                        }
                        PerfState.Phase.RUNNING -> {
                            val el = (now - t0) / 1000.0
                            trace += el to sp
                            val p = prev
                            for (target in TARGETS) {
                                if (target !in splits && sp >= target && p != null && p.second < target) {
                                    val f = (target - p.second) / (sp - p.second)
                                    val tt = (p.first + (now - p.first) * f - t0) / 1000.0
                                    splits[target] = tt
                                    Feedback.step()
                                }
                            }
                            if (sp < 3 && el > 2) { phase = PerfState.Phase.READY; splits.clear(); trace.clear() }
                            if (100 in splits || el > 60) phase = PerfState.Phase.DONE
                        }
                        PerfState.Phase.DONE -> {}
                    }
                    prev = now to sp
                    _state.value = PerfState(phase, sp, if (phase == PerfState.Phase.RUNNING || phase == PerfState.Phase.DONE) (now - t0) / 1000.0 else 0.0,
                        splits.toMap(), trace.toList(), hz)
                    if (phase == PerfState.Phase.DONE) break
                }
                if (splits.isNotEmpty()) {
                    Feedback.success()
                    val sp = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE).edit()
                    val b = _best.value.toMutableMap()
                    splits.forEach { (t, v) -> if ((b[t] ?: 999.0) > v) { b[t] = v; sp.putFloat("best$t", v.toFloat()) } }
                    sp.apply()
                    _best.value = b
                    ObdManager.log("Aceleração: " + splits.entries.joinToString { "0-${it.key} em %.2f s".format(it.value) })
                }
            } finally {
                elm.verbose = true
            }
        }
        if (job == null) _state.value = null
    }

    fun stop() { job?.cancel(); job = null; _state.value = null }
    fun clear() { _state.value = null }
}
