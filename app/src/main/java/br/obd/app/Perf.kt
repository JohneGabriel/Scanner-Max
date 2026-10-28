package br.obd.app

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado da medição de desempenho. */
data class PerfState(
    val mode: Mode,
    val phase: Phase,
    val speed: Double?,
    val elapsed: Double,
    val splits: Map<Int, Double> = emptyMap(),
    val trace: List<Pair<Double, Double>> = emptyList(),
    val sampleHz: Double = 0.0,
    /** Distância percorrida na medição (m). */
    val distance: Double = 0.0,
    /** Tempo dos 400 m (s) e velocidade final. */
    val quarter: Pair<Double, Double>? = null,
    /** Frenagem: velocidade inicial, tempo e distância até parar. */
    val brake: Triple<Double, Double, Double>? = null,
    /** Potência máxima estimada nas rodas (cv), se o peso do carro é conhecido. */
    val powerCv: Double? = null,
) {
    enum class Phase { WAITING_STOP, READY, RUNNING, DONE }
    enum class Mode { ACCEL, BRAKE }
}

/**
 * Cronômetro de desempenho: 0–40/60/80/100 km/h, 400 m e potência estimada (aceleração),
 * ou frenagem de 80+ km/h até parar. Lê só a velocidade, o mais rápido que o carro deixar,
 * e interpola entre leituras para estimar o instante de cada marca.
 */
object PerfRunner {
    private lateinit var ctx: Context
    private val _state = MutableStateFlow<PerfState?>(null)
    val state: StateFlow<PerfState?> = _state.asStateFlow()
    private var job: Job? = null

    private val _best = MutableStateFlow<Map<Int, Double>>(emptyMap())
    /** Melhores tempos: 40/60/80/100 = 0–N km/h, 400 = 400 m, -1 = frenagem (m), -2 = potência (cv). */
    val best: StateFlow<Map<Int, Double>> = _best.asStateFlow()

    val TARGETS = listOf(40, 60, 80, 100)

    fun init(c: Context) {
        ctx = c.applicationContext
        val sp = ctx.getSharedPreferences("perf", Context.MODE_PRIVATE)
        _best.value = (TARGETS + listOf(400, -1, -2)).mapNotNull { t -> sp.getFloat("best$t", -1f).takeIf { it > 0 }?.let { t to it.toDouble() } }.toMap()
    }

    private fun record(key: Int, v: Double, lowerIsBetter: Boolean = true) {
        val b = _best.value.toMutableMap()
        val old = b[key]
        if (old == null || (lowerIsBetter && v < old) || (!lowerIsBetter && v > old)) {
            b[key] = v
            ctx.getSharedPreferences("perf", Context.MODE_PRIVATE).edit().putFloat("best$key", v.toFloat()).apply()
            _best.value = b
        }
    }

    fun start(mode: PerfState.Mode = PerfState.Mode.ACCEL) {
        if (job?.isActive == true) return
        _state.value = PerfState(mode, if (mode == PerfState.Mode.ACCEL) PerfState.Phase.WAITING_STOP else PerfState.Phase.READY, null, 0.0)
        job = ObdManager.exclusive(if (mode == PerfState.Mode.ACCEL) "Medindo aceleração" else "Medindo frenagem") {
            val elm = ObdManager.elm
            elm.verbose = false
            try { if (mode == PerfState.Mode.ACCEL) accel() else brake() } finally { elm.verbose = true }
        }
        if (job == null) _state.value = null
    }

    private suspend fun speed(): Double? = ObdManager.elm.readPid("0D", retries = 0)?.let(Pids.SPEED.decode)

    private suspend fun accel() {
        val mass = Garage.current.value?.massKg
        var phase = PerfState.Phase.WAITING_STOP
        var t0 = 0L
        var prev: Pair<Long, Double>? = null
        val splits = LinkedHashMap<Int, Double>()
        val trace = ArrayList<Pair<Double, Double>>()
        var dist = 0.0
        var quarter: Pair<Double, Double>? = null
        var maxPower: Double? = null
        var n = 0; val tStart = System.currentTimeMillis()
        while (System.currentTimeMillis() < tStart + 5 * 60_000) {
            val sp = speed() ?: continue
            val now = System.currentTimeMillis()
            n++
            val hz = n / ((now - tStart) / 1000.0).coerceAtLeast(0.1)
            val p = prev
            when (phase) {
                PerfState.Phase.WAITING_STOP -> if (sp == 0.0) { phase = PerfState.Phase.READY; Feedback.step() }
                PerfState.Phase.READY -> if (sp > 0) {
                    // a largada aconteceu entre a leitura anterior (0) e esta
                    t0 = p?.let { (it.first + now) / 2 } ?: now
                    phase = PerfState.Phase.RUNNING
                    dist = 0.0
                    Feedback.cue()
                }
                PerfState.Phase.RUNNING -> {
                    val el = (now - t0) / 1000.0
                    trace += el to sp
                    if (p != null) {
                        val dt = (now - p.first) / 1000.0
                        val vAvg = (sp + p.second) / 2 / 3.6
                        val before = dist
                        dist += vAvg * dt
                        if (quarter == null && before < 400 && dist >= 400) {
                            val f = (400 - before) / (dist - before)
                            quarter = ((p.first + (now - p.first) * f - t0) / 1000.0) to sp
                            Feedback.step()
                        }
                        // potência nas rodas ≈ massa × aceleração × velocidade (sem perdas aerodinâmicas)
                        if (mass != null && dt in 0.05..2.0 && sp > 20) {
                            val a = (sp - p.second) / 3.6 / dt
                            val pw = mass * a * vAvg / 735.5
                            if (pw > 0) maxPower = maxOf(maxPower ?: 0.0, pw)
                        }
                        for (target in TARGETS) {
                            if (target !in splits && sp >= target && p.second < target) {
                                val f = (target - p.second) / (sp - p.second)
                                splits[target] = (p.first + (now - p.first) * f - t0) / 1000.0
                                Feedback.step()
                            }
                        }
                    }
                    if (sp < 3 && el > 2) { phase = PerfState.Phase.READY; splits.clear(); trace.clear(); quarter = null; maxPower = null }
                    if ((100 in splits && (quarter != null || el > 25)) || el > 60) phase = PerfState.Phase.DONE
                }
                PerfState.Phase.DONE -> {}
            }
            prev = now to sp
            _state.value = PerfState(PerfState.Mode.ACCEL, phase, sp,
                if (phase == PerfState.Phase.RUNNING || phase == PerfState.Phase.DONE) (now - t0) / 1000.0 else 0.0,
                splits.toMap(), trace.toList(), hz, dist, quarter, null, maxPower)
            if (phase == PerfState.Phase.DONE) break
        }
        if (splits.isNotEmpty() || quarter != null) {
            Feedback.success()
            splits.forEach { (t, v) -> record(t, v) }
            quarter?.let { record(400, it.first) }
            maxPower?.let { record(-2, it, lowerIsBetter = false) }
            ObdManager.log("Aceleração: " + splits.entries.joinToString { "0-${it.key} em %.2f s".format(it.value) } +
                (quarter?.let { " · 400 m em %.2f s".format(it.first) } ?: ""))
        }
    }

    private suspend fun brake() {
        var phase = PerfState.Phase.READY
        var t0 = 0L
        var v0 = 0.0
        var prev: Pair<Long, Double>? = null
        var dist = 0.0
        val trace = ArrayList<Pair<Double, Double>>()
        var result: Triple<Double, Double, Double>? = null
        var n = 0; val tStart = System.currentTimeMillis()
        var maxSeen = 0.0
        while (System.currentTimeMillis() < tStart + 5 * 60_000) {
            val sp = speed() ?: continue
            val now = System.currentTimeMillis()
            n++
            val hz = n / ((now - tStart) / 1000.0).coerceAtLeast(0.1)
            val p = prev
            when (phase) {
                // pronto quando passar de 80 km/h; a medição começa quando a velocidade cai 3 km/h do pico
                PerfState.Phase.READY, PerfState.Phase.WAITING_STOP -> {
                    maxSeen = maxOf(maxSeen, sp)
                    if (maxSeen >= 80 && sp <= maxSeen - 3) {
                        phase = PerfState.Phase.RUNNING; t0 = p?.first ?: now; v0 = p?.second ?: sp; dist = 0.0; Feedback.cue()
                    }
                    if (sp < maxSeen - 15) maxSeen = sp
                }
                PerfState.Phase.RUNNING -> {
                    if (p != null) dist += (sp + p.second) / 2 / 3.6 * ((now - p.first) / 1000.0)
                    trace += (now - t0) / 1000.0 to sp
                    if (sp == 0.0) {
                        result = Triple(v0, (now - t0) / 1000.0, dist)
                        phase = PerfState.Phase.DONE
                    } else if (p != null && sp > p.second + 3) {
                        phase = PerfState.Phase.READY; maxSeen = sp; trace.clear() // voltou a acelerar
                    }
                }
                PerfState.Phase.DONE -> {}
            }
            prev = now to sp
            _state.value = PerfState(PerfState.Mode.BRAKE, phase, sp, if (phase == PerfState.Phase.RUNNING) (now - t0) / 1000.0 else result?.second ?: 0.0,
                trace = trace.toList(), sampleHz = hz, distance = dist, brake = result)
            if (phase == PerfState.Phase.DONE) break
        }
        result?.let { (v, t, d) ->
            Feedback.success()
            // normaliza para 100→0 (distância cresce com o quadrado da velocidade)
            record(-1, d * (100.0 / v) * (100.0 / v))
            ObdManager.log("Frenagem: de %.0f km/h em %.2f s e %.1f m".format(v, t, d))
        }
    }

    fun stop() { job?.cancel(); job = null; _state.value = null }
    fun clear() { _state.value = null }
}
