package br.obd.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

enum class Level { GOOD, WARN, BAD, INFO }

data class Metric(val label: String, val value: String, val level: Level? = null)

/** O que um teste precisa para fazer sentido. */
enum class Need(val label: String, val hard: Boolean) {
    ENGINE_RUNNING("Motor ligado", false),
    ENGINE_OFF("Motor desligado, ignição ligada", false),
    WARM("Motor quente (acima de 75 °C)", false),
    STOPPED("Carro parado", false),
    NARROW_O2("Carro com sonda lambda comum", true),
    VOLTAGE("Adaptador lê a tensão (ATRV)", true),
}

/** Descrição de um teste guiado (a tela de introdução mostra tudo isto). */
data class TestDef(
    val id: String,
    val title: String,
    val subtitle: String,
    val duration: String,
    val what: String,
    val howTo: List<String>,
    val needs: List<Need>,
    val options: List<String> = emptyList(),
)

/** Resultado de um teste: veredito, números, explicação e o que fazer. */
data class TestReport(
    val testId: String,
    val title: String,
    val at: Long,
    val vehicleId: String?,
    val vehicleName: String?,
    val level: Level,
    val headline: String,
    val explanation: String,
    val metrics: List<Metric>,
    val nextSteps: List<String>,
    val chart: List<Double>,
    val chartLabel: String,
    val chartMin: Double? = null,
    val chartMax: Double? = null,
    val chartRef: Double? = null,
    val note: String? = null,
    /** Valores crus para comparar com execuções anteriores. */
    val raw: Map<String, Double> = emptyMap(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("test", testId); put("title", title); put("at", at); put("veh", vehicleId); put("vehName", vehicleName)
        put("level", level.name); put("head", headline); put("expl", explanation)
        put("metrics", JSONArray().apply {
            metrics.forEach { m -> put(JSONObject().apply { put("l", m.label); put("v", m.value); put("lv", m.level?.name) }) }
        })
        put("steps", JSONArray(nextSteps)); put("chart", JSONArray(chart.map { if (it.isNaN()) 0.0 else it }))
        put("chartLabel", chartLabel); put("cmin", chartMin); put("cmax", chartMax); put("cref", chartRef); put("note", note)
        put("raw", JSONObject().apply { raw.forEach { (k, v) -> if (!v.isNaN()) put(k, v) } })
    }

    companion object {
        fun fromJson(o: JSONObject): TestReport {
            val ms = o.optJSONArray("metrics") ?: JSONArray()
            val st = o.optJSONArray("steps") ?: JSONArray()
            val ch = o.optJSONArray("chart") ?: JSONArray()
            val rw = o.optJSONObject("raw") ?: JSONObject()
            return TestReport(
                o.getString("test"), o.optString("title"), o.getLong("at"), o.optStringOrNull("veh"),
                o.optStringOrNull("vehName"), runCatching { Level.valueOf(o.optString("level")) }.getOrDefault(Level.INFO),
                o.optString("head"), o.optString("expl"),
                (0 until ms.length()).map { i ->
                    val m = ms.getJSONObject(i)
                    Metric(m.optString("l"), m.optString("v"), m.optStringOrNull("lv")?.let { runCatching { Level.valueOf(it) }.getOrNull() })
                },
                (0 until st.length()).map { st.optString(it) }, (0 until ch.length()).map { ch.optDouble(it, 0.0) },
                o.optString("chartLabel"), o.optDoubleOrNull("cmin"), o.optDoubleOrNull("cmax"), o.optDoubleOrNull("cref"),
                o.optStringOrNull("note"), rw.keys().asSequence().associateWith { rw.optDouble(it) },
            )
        }
    }
}

/** Estado do teste em andamento, para a tela animada. */
data class TestLive(
    val testId: String,
    val step: Int,
    val steps: Int,
    val stepTitle: String,
    val instruction: String,
    val big: String,
    val bigUnit: String,
    val progress: Float,
    val secondsLeft: Int?,
    val urgent: Boolean = false,
    val chart: List<Double> = emptyList(),
    val chartMin: Double? = null,
    val chartMax: Double? = null,
    val chartRef: Double? = null,
)

object TestCatalog {
    val LAMBDA = TestDef(
        "lambda", "Sonda lambda", "Resposta da sonda e eficiência do catalisador", "≈ 35 s",
        "A sonda antes do catalisador diz à central se a mistura está rica ou pobre. Boa, ela oscila " +
            "sem parar entre ~0,1 e ~0,9 V. O teste mede a oscilação em lenta e a 2.000 rpm e olha a " +
            "sonda depois do catalisador: ela deve ficar estável.",
        listOf("Motor quente e em lenta", "Quando o app pedir, mantenha ~2.000 rpm por 15 s", "Solte e aguarde o resultado"),
        listOf(Need.NARROW_O2, Need.ENGINE_RUNNING, Need.WARM, Need.STOPPED),
    )
    val IDLE = TestDef(
        "idle", "Marcha lenta", "Estabilidade, entrada de ar falsa e borboleta", "45 s",
        "Mede a rotação, a pressão do coletor, a abertura da borboleta, o avanço e a correção de " +
            "combustível em lenta. Rode em situações diferentes (ar ligado, faróis, depois de uma limpeza) " +
            "e o app compara com a execução anterior.",
        listOf("Motor quente, carro parado", "Não toque no acelerador", "Se o motor morrer, o teste para e registra"),
        listOf(Need.ENGINE_RUNNING, Need.WARM, Need.STOPPED),
        listOf("Normal", "Ar-condicionado ligado", "Faróis e ventilação ligados", "Depois de um reparo"),
    )
    val DECEL = TestDef(
        "decel", "Retorno à lenta", "O motor morre ou afunda ao soltar o acelerador?", "≈ 20 s",
        "Ao soltar o pedal, a borboleta volta para a posição de repouso que a central aprendeu. Se ela " +
            "for pequena demais, a rotação afunda abaixo da lenta e o motor pode morrer. O teste mede " +
            "quanto afunda e quanto demora para voltar.",
        listOf("Motor quente, carro parado", "Aguarde 3 s em lenta", "Quando aparecer ACELERE, leve a ~2.500 rpm e solte de uma vez"),
        listOf(Need.ENGINE_RUNNING, Need.WARM, Need.STOPPED),
    )
    val MIXTURE = TestDef(
        "mixture", "Mistura e correções", "Entrada de ar, alimentação ou excesso de combustível", "≈ 55 s",
        "Compara as correções de combustível (curta + longa) em lenta e a 2.500 rpm. O jeito como elas " +
            "mudam com a rotação separa entrada de ar falsa, falta de pressão de combustível e mistura rica.",
        listOf("Motor quente, carro parado", "25 s em lenta", "Quando o app pedir, mantenha ~2.500 rpm por 25 s"),
        listOf(Need.ENGINE_RUNNING, Need.WARM, Need.STOPPED),
    )
    val BATTERY = TestDef(
        "battery", "Bateria e alternador", "Carga, partida e recarga", "≈ 1 min",
        "Mede a bateria em repouso, quanto a tensão cai na partida (bateria fraca cai demais) e se o " +
            "alternador recarrega, com e sem consumo elétrico. Funciona em qualquer carro: usa a tensão " +
            "na tomada OBD, lida pelo próprio adaptador.",
        listOf("Comece com o motor DESLIGADO e a ignição ligada", "Dê a partida quando o app pedir",
            "Depois, ligue faróis, ventilação no máximo e desembaçador quando pedir"),
        listOf(Need.VOLTAGE, Need.ENGINE_OFF),
    )
    val ALL = listOf(BATTERY, MIXTURE, LAMBDA, IDLE, DECEL)
    fun byId(id: String) = ALL.firstOrNull { it.id == id }
}

/** Roda os testes guiados com o adaptador reservado só para eles. */
object TestRunner {
    private lateinit var dir: File
    private val _live = MutableStateFlow<TestLive?>(null)
    val live: StateFlow<TestLive?> = _live.asStateFlow()

    private val _result = MutableStateFlow<TestReport?>(null)
    /** Resultado do último teste rodado nesta sessão. */
    val result: StateFlow<TestReport?> = _result.asStateFlow()

    private val _history = MutableStateFlow<List<TestReport>>(emptyList())
    val history: StateFlow<List<TestReport>> = _history.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "tests").apply { mkdirs() }
        _history.value = (dir.listFiles() ?: emptyArray())
            .mapNotNull { runCatching { TestReport.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.at }
    }

    fun clearResult() { _result.value = null; _error.value = null }

    fun delete(r: TestReport) {
        File(dir, "${r.at}.json").delete()
        _history.value = _history.value.filter { it.at != r.at }
    }

    fun previous(testId: String, vehicleId: String?, before: Long = Long.MAX_VALUE) =
        _history.value.firstOrNull { it.testId == testId && it.vehicleId == vehicleId && it.at < before }

    fun start(def: TestDef, option: String? = null) {
        if (_live.value != null) return
        _result.value = null; _error.value = null
        _live.value = TestLive(def.id, 1, 1, "Preparando…", "Reservando o adaptador para o teste", "—", "", 0f, null)
        val j = ObdManager.exclusive("Teste: ${def.title}") {
            try {
                val r = when (def.id) {
                    "lambda" -> lambda()
                    "idle" -> idle(option ?: "Normal")
                    "decel" -> decel()
                    "mixture" -> mixture()
                    "battery" -> battery()
                    else -> null
                }
                if (r != null) save(r)
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                _error.value = "A conexão com o adaptador caiu durante o teste. Reconecte e rode de novo."
                throw e
            } catch (e: Exception) {
                _error.value = "O teste falhou: ${e.message}"
            } finally {
                ObdManager.elm.verbose = true
                _live.value = null
            }
        }
        if (j == null) {
            _live.value = null
            _error.value = if (!ObdManager.connected.value) "Conecte ao adaptador primeiro." else "O adaptador está ocupado. Tente em alguns segundos."
        }
        job = j
    }

    fun cancel() {
        job?.cancel(); job = null
        _live.value = null
    }

    private fun save(r: TestReport) {
        runCatching { File(dir, "${r.at}.json").writeText(r.toJson().toString()) }
        _history.value = listOf(r) + _history.value
        _result.value = r
        ObdManager.log("Teste ${r.title}: ${r.level} — ${r.headline}")
    }

    // ------------------------------------------------------------------ apoio

    private val elm get() = ObdManager.elm
    private val veh get() = Garage.current.value

    private suspend fun pid(def: PidDef): Double? = elm.readPid(def.pid, retries = 0)?.let(def.decode)
    private suspend fun volts(): Double? =
        elm.send("ATRV").replace("V", "").trim().replace(",", ".").toDoubleOrNull()

    private fun emit(
        id: String, step: Int, steps: Int, title: String, instruction: String, big: String, unit: String,
        progress: Float, left: Int?, urgent: Boolean = false, chart: List<Double> = emptyList(),
        min: Double? = null, max: Double? = null, ref: Double? = null,
    ) {
        _live.value = TestLive(id, step, steps, title, instruction, big, unit, progress.coerceIn(0f, 1f), left,
            urgent, chart.takeLast(120), min, max, ref)
    }

    private fun report(
        def: TestDef, level: Level, headline: String, explanation: String, metrics: List<Metric>,
        steps: List<String>, chart: List<Double>, label: String, min: Double? = null, max: Double? = null,
        ref: Double? = null, note: String? = null, raw: Map<String, Double> = emptyMap(),
    ) = TestReport(def.id, def.title, System.currentTimeMillis(), veh?.id, veh?.name, level, headline, explanation,
        metrics, steps, downsample(chart, 240), label, min, max, ref, note, raw)

    private fun downsample(v: List<Double>, n: Int): List<Double> =
        if (v.size <= n) v else List(n) { i -> v[(i.toLong() * v.size / n).toInt()] }

    private fun std(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val m = v.average(); return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    private fun f0(v: Double?) = v?.let { "%.0f".format(it) } ?: "—"
    private fun f1(v: Double?) = v?.let { "%.1f".format(it) } ?: "—"
    private fun f2(v: Double?) = v?.let { "%.2f".format(it) } ?: "—"

    // ------------------------------------------------------------------ sonda lambda

    private suspend fun lambda(): TestReport {
        val def = TestCatalog.LAMBDA
        val status = pid(Pids.FUEL_STATUS)?.toInt()
        val rpm0 = pid(Pids.RPM)
        if (rpm0 == null || rpm0 < 300) {
            return report(def, Level.INFO, "Motor desligado",
                "A sonda só trabalha com o motor ligado e quente. Ligue o motor, espere a ventoinha ou uns 10 minutos e rode de novo.",
                emptyList(), listOf("Ligue o motor e deixe aquecer", "Rode o teste de novo"), emptyList(), "Sonda (V)")
        }
        val hasPost = Garage.current.value != null && ObdManager.vehicle.value.let { it.supported.isEmpty() || it.has(Pids.O2S2) }
        elm.verbose = false
        val all = ArrayList<Double>()
        val phase = arrayOf(ArrayList<Double>(), ArrayList<Double>())
        val post = ArrayList<Double>()
        var rpmNow: Double? = rpm0
        val t0 = System.currentTimeMillis()
        var phaseStart = t0
        var phaseIdx = 0
        var n = 0
        while (true) {
            val now = System.currentTimeMillis()
            val inPhase = (now - phaseStart) / 1000.0
            if (phaseIdx == 0 && inPhase >= 15) { phaseIdx = 1; phaseStart = now; continue }
            if (phaseIdx == 1 && inPhase >= 15 && phase[1].size >= 10) break
            if (phaseIdx == 1 && inPhase >= 35) break
            val v = elm.readPid("14", retries = 0)?.getOrNull(0)?.times(0.005)
            n++
            if (n % 5 == 0 && hasPost) elm.readPid("15", retries = 0)?.getOrNull(0)?.times(0.005)?.let { post += it }
            if (n % 4 == 0) rpmNow = pid(Pids.RPM) ?: rpmNow
            val rpmOk = phaseIdx == 0 || (rpmNow ?: 0.0) in 1600.0..2600.0
            if (v != null) { all += v; if (rpmOk) phase[phaseIdx] += v }
            val left = (15 - inPhase).toInt().coerceAtLeast(0)
            if (phaseIdx == 0) {
                emit(def.id, 1, 2, "Em lenta", "Não acelere. Medindo a oscilação da sonda.", f2(v), "V",
                    (inPhase / 30).toFloat(), left, false, all, 0.0, 1.0, 0.45)
            } else {
                val ok = (rpmNow ?: 0.0) in 1600.0..2600.0
                emit(def.id, 2, 2, "A 2.000 rpm",
                    if (ok) "Isso! Mantenha assim." else "ACELERE e mantenha ~2.000 rpm (agora ${f0(rpmNow)} rpm)",
                    f2(v), "V", (0.5 + inPhase / 30).toFloat(), left, !ok, all, 0.0, 1.0, 0.45)
            }
        }
        elm.verbose = true
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val rate = all.size / secs

        fun switches(v: List<Double>) = (1 until v.size).count { (v[it - 1] < 0.45) != (v[it] < 0.45) }
        val idle = phase[0]; val high = phase[1]
        val swIdle = switches(idle) / 15.0
        val swHigh = if (high.size > 3) switches(high) / (high.size / rate) else null
        val mn = all.minOrNull() ?: 0.0; val mx = all.maxOrNull() ?: 0.0
        val rich = all.count { it >= 0.45 } * 100.0 / all.size.coerceAtLeast(1)
        val postRange = if (post.size > 3) post.max() - post.min() else null
        val postAvg = post.takeIf { it.isNotEmpty() }?.average()
        val closed = status == 2 || status == 16

        val metrics = listOfNotNull(
            Metric("Faixa da sonda", "${f2(mn)} – ${f2(mx)} V", if (mx - mn >= 0.6) Level.GOOD else Level.WARN),
            Metric("Trocas por segundo (lenta)", f2(swIdle), if (swIdle >= 0.3) Level.GOOD else Level.WARN),
            swHigh?.let { Metric("Trocas por segundo (2.000 rpm)", f2(it), if (it >= 0.5) Level.GOOD else Level.WARN) },
            Metric("Tempo em rico", "%.0f%%".format(rich), if (rich in 30.0..70.0) Level.GOOD else Level.WARN),
            postAvg?.let { Metric("Sonda pós-catalisador (média)", "${f2(it)} V") },
            postRange?.let { Metric("Variação pós-catalisador", "${f2(it)} V", if (it < 0.3) Level.GOOD else Level.WARN) },
            Metric("Sistema de combustível", Pids.fuelStatusText(status), if (closed) Level.GOOD else Level.WARN),
            Metric("Leituras por segundo", f1(rate)),
        )
        val catWeak = postRange != null && mx - mn > 0.3 && postRange > (mx - mn) * 0.6
        val (level, head, expl, steps) = when {
            !closed -> Quad(Level.WARN, "Central em malha aberta",
                "A central não estava usando a sonda (${Pids.fuelStatusText(status)}). Com o motor frio isso é normal.",
                listOf("Deixe o motor aquecer até a ventoinha ligar", "Rode o teste de novo"))
            mx - mn < 0.4 -> Quad(Level.WARN, "Sonda quase parada",
                "A tensão variou só de ${f2(mn)} a ${f2(mx)} V. Uma sonda boa vai de ~0,1 a ~0,9 V. Sinal de sonda envelhecida, contaminada ou com o aquecimento falhando.",
                listOf("Verifique o conector e o aquecimento da sonda (fusível e resistência)", "Se o carro tem mais de 80–100 mil km com a sonda original, considere trocar", "Repita depois de rodar 15 min em estrada"))
            mx < 0.6 -> Quad(Level.WARN, "Sonda sempre em pobre",
                "A sonda não passou de ${f2(mx)} V: a mistura está sempre pobre ou a sonda não sobe. Entrada de ar falsa e pressão de combustível baixa são as causas mais comuns.",
                listOf("Rode o teste de Mistura e correções", "Procure mangueiras de vácuo soltas ou rachadas", "Se as correções estiverem normais, a sonda é a suspeita"))
            mn > 0.45 -> Quad(Level.WARN, "Sonda sempre em rico",
                "A sonda não desceu de ${f2(mn)} V: excesso de combustível ou sonda travada em rico.",
                listOf("Rode o teste de Mistura e correções", "Verifique bico pingando e pressão de combustível", "Num flex, confira se a central não está achando que tem mais etanol"))
            (swHigh ?: swIdle) < 0.3 -> Quad(Level.WARN, "Sonda lenta",
                "A sonda oscila pouco (${f2(swHigh ?: swIdle)} trocas/s). Sondas lentas fazem a central corrigir atrasado: consumo maior e lenta irregular.",
                listOf("Repita a 2.000 rpm estáveis", "Se continuar lenta, a sonda está envelhecida"))
            catWeak -> Quad(Level.WARN, "Sonda boa, catalisador suspeito",
                "A sonda da frente oscila bem, mas a de trás está copiando as oscilações (variou ${f2(postRange)} V). Com o catalisador bom, a de trás fica estável.",
                listOf("Confirme rodando o teste com o motor bem quente, depois de 15 min de estrada", "Se repetir, o catalisador está perdendo eficiência (código P0420 costuma aparecer)"))
            else -> Quad(Level.GOOD, "Sonda funcionando bem",
                "A sonda oscilou entre ${f2(mn)} e ${f2(mx)} V, ${f2(swHigh ?: swIdle)} vezes por segundo. A central está controlando a mistura normalmente.",
                listOf("Nada a fazer", "Repita o teste de vez em quando para acompanhar"))
        }
        val note = if (rate < 5) "O carro responde ~${f1(rate)} leituras/s: oscilações mais rápidas que isso não aparecem inteiras no gráfico." else null
        return report(def, level, head, expl, metrics, steps, all, "Sonda antes do catalisador (V)", 0.0, 1.0, 0.45, note,
            mapOf("min" to mn, "max" to mx, "swIdle" to swIdle, "swHigh" to (swHigh ?: Double.NaN)))
    }

    private data class Quad(val level: Level, val head: String, val expl: String, val steps: List<String>)

    // ------------------------------------------------------------------ marcha lenta

    private suspend fun idle(label: String): TestReport {
        val def = TestCatalog.IDLE
        val coolant = pid(Pids.COOLANT)
        val rpm = ArrayList<Double>(); val map = ArrayList<Double>(); val thr = ArrayList<Double>()
        val adv = ArrayList<Double>(); val trim = ArrayList<Double>()
        val v = ObdManager.vehicle.value
        fun has(d: PidDef) = v.supported.isEmpty() || v.has(d)
        var stalled = false
        val seconds = 45
        val t0 = System.currentTimeMillis()
        var tick = 0
        elm.verbose = false
        while (true) {
            val el = (System.currentTimeMillis() - t0) / 1000.0
            if (el >= seconds) break
            val r = pid(Pids.RPM)
            if (r != null) {
                rpm += r
                if (r < 250 && rpm.size > 3) { stalled = true; break }
            }
            when (tick % 4) {
                0 -> if (has(Pids.MAP)) pid(Pids.MAP)?.let { map += it }
                1 -> if (has(Pids.THROTTLE)) pid(Pids.THROTTLE)?.let { thr += it }
                2 -> if (has(Pids.ADVANCE)) pid(Pids.ADVANCE)?.let { adv += it }
                3 -> if (has(Pids.STFT)) pid(Pids.STFT)?.let { s -> trim += s }
            }
            tick++
            val dev = if (rpm.size > 5) std(rpm.takeLast(20)) else 0.0
            emit(def.id, 1, 1, label, if (dev > 60) "Rotação oscilando ±${f0(dev)} rpm" else "Não toque no acelerador",
                f0(r), "rpm", (el / seconds).toFloat(), (seconds - el).toInt(), dev > 60, rpm,
                0.0, maxOf(1200.0, (rpm.maxOrNull() ?: 0.0) * 1.1))
        }
        elm.verbose = true
        val ltft = pid(Pids.LTFT)
        val rAvg = rpm.takeIf { it.isNotEmpty() }?.average(); val rStd = std(rpm)
        val mAvg = map.takeIf { it.isNotEmpty() }?.average(); val tAvg = thr.takeIf { it.isNotEmpty() }?.average()
        val aAvg = adv.takeIf { it.isNotEmpty() }?.average(); val aStd = std(adv)
        val sAvg = trim.takeIf { it.isNotEmpty() }?.average()
        val secs = ((System.currentTimeMillis() - t0) / 1000).toInt()
        val metrics = listOfNotNull(
            Metric("Rotação média", "${f0(rAvg)} rpm", rAvg?.let { if (it in 650.0..1000.0) Level.GOOD else Level.WARN }),
            Metric("Oscilação", "±${f0(rStd)} rpm", if (rStd <= 40) Level.GOOD else if (rStd <= 70) Level.WARN else Level.BAD),
            mAvg?.let { Metric("Pressão do coletor", "${f0(it)} kPa", if (it <= 40) Level.GOOD else Level.WARN) },
            tAvg?.let { Metric("Borboleta", "${f1(it)} %") },
            aAvg?.let { Metric("Avanço", "${f1(it)}° (±${f1(aStd)})", if (aStd > 8) Level.WARN else null) },
            sAvg?.let { Metric("Correção curta", "%+.1f %%".format(it), if (abs(it) <= 10) Level.GOOD else Level.WARN) },
            ltft?.let { Metric("Correção longa", "%+.1f %%".format(it), if (abs(it) <= 10) Level.GOOD else Level.WARN) },
            coolant?.let { Metric("Temperatura do motor", "${f0(it)} °C", if (it < 75) Level.WARN else null) },
        )
        val prev = previous(def.id, veh?.id)
        val compare = prev?.let { p ->
            val dR = (rAvg ?: 0.0) - (p.raw["rpm"] ?: 0.0)
            val dM = (mAvg ?: 0.0) - (p.raw["map"] ?: 0.0)
            val dT = (tAvg ?: 0.0) - (p.raw["thr"] ?: 0.0)
            "Comparado com \"${p.note ?: "anterior"}\": rotação %+.0f rpm, coletor %+.0f kPa, borboleta %+.1f pontos.".format(dR, dM, dT)
        }
        val leak = mAvg != null && mAvg > 45 && (tAvg ?: 99.0) < 4
        val (level, head, expl, steps) = when {
            stalled -> Quad(Level.BAD, "O motor morreu em ${secs} s",
                "A lenta não se sustentou. As causas mais comuns são corpo de borboleta sujo ou com a adaptação errada, entrada de ar falsa e problema de ignição.",
                listOf("Rode o teste de Retorno à lenta", "Limpe o corpo de borboleta e faça a adaptação com um scanner do fabricante", "Confira mangueiras de vácuo e o tubo do cânister"))
            leak -> Quad(Level.WARN, "Entrada de ar fora da borboleta",
                "O coletor está com ${f0(mAvg)} kPa com a borboleta quase fechada (${f1(tAvg)}%). Um motor em lenta saudável fica entre 25 e 40 kPa: tem ar entrando por outro caminho.",
                listOf("Procure mangueiras de vácuo soltas, rachadas ou desconectadas (inclusive a do cânister)", "Verifique a junta do coletor de admissão e o servo-freio", "Depois do reparo, rode o teste de novo e compare"))
            rStd > 70 -> Quad(Level.WARN, "Lenta instável",
                "A rotação oscilou ±${f0(rStd)} rpm. Acima de ±50 já se sente o motor \"respirando\".",
                listOf("Verifique velas e cabos (falha de combustão)", "Limpe o corpo de borboleta", "Rode o teste de Mistura e correções"))
            rAvg != null && rAvg < 650 -> Quad(Level.WARN, "Lenta baixa",
                "A lenta ficou em ${f0(rAvg)} rpm, baixa para um motor quente.", listOf("Limpe o corpo de borboleta e refaça a adaptação", "Confira se há código de falha no Diagnóstico"))
            else -> Quad(Level.GOOD, "Lenta estável",
                "Rotação ${f0(rAvg)} rpm com oscilação de ±${f0(rStd)} rpm" + (mAvg?.let { " e coletor em ${f0(it)} kPa" } ?: "") + ".",
                listOf("Nada a fazer", "Rode com o ar-condicionado ligado para ver se a central compensa a carga"))
        }
        return report(def, level, head, expl + (compare?.let { "\n\n$it" } ?: ""), metrics, steps, rpm,
            "Rotação (rpm)", 0.0, maxOf(1200.0, (rpm.maxOrNull() ?: 0.0) * 1.1), rAvg, label,
            mapOf("rpm" to (rAvg ?: Double.NaN), "std" to rStd, "map" to (mAvg ?: Double.NaN), "thr" to (tAvg ?: Double.NaN)))
    }

    // ------------------------------------------------------------------ retorno à lenta

    private suspend fun decel(): TestReport {
        val def = TestCatalog.DECEL
        val pts = ArrayList<Pair<Long, Double>>()
        val t0 = System.currentTimeMillis()
        var stalled = false
        var peak = 0.0
        var releasedAt: Long? = null
        elm.verbose = false
        while (true) {
            val now = System.currentTimeMillis()
            val t = now - t0
            if (t > 30_000) break
            val r = pid(Pids.RPM) ?: continue
            pts += t to r
            peak = maxOf(peak, r)
            val base = pts.filter { it.first < 3000 }.map { it.second }.sorted().let { if (it.isEmpty()) r else it[it.size / 2] }
            if (peak > 1800 && releasedAt == null && r < peak - 500) releasedAt = now
            if (peak > 1800 && r < 250) { stalled = true; break }
            if (releasedAt != null && now - releasedAt > 6000) break
            val chart = pts.map { it.second }
            when {
                t < 3000 -> emit(def.id, 1, 3, "Lenta de referência", "Aguarde em lenta…", f0(r), "rpm",
                    t / 3000f * 0.2f, (3 - t / 1000).toInt(), false, chart, 0.0, 4000.0, base)
                peak < 1800 -> emit(def.id, 2, 3, "Acelere", "ACELERE ATÉ ~2.500 rpm E SOLTE DE UMA VEZ", f0(r), "rpm",
                    0.2f + (r / 2500).toFloat().coerceAtMost(1f) * 0.3f, null, true, chart, 0.0, 4000.0, base)
                else -> emit(def.id, 3, 3, "Medindo", if (releasedAt == null) "Agora solte o pedal!" else "Medindo a volta à lenta…",
                    f0(r), "rpm", 0.5f + (releasedAt?.let { (now - it) / 6000f } ?: 0f) * 0.5f, null,
                    releasedAt == null, chart, 0.0, maxOf(4000.0, peak * 1.1), base)
            }
        }
        elm.verbose = true
        val pre = pts.filter { it.first < 3000 }.map { it.second }
        val baseline = pre.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val peakIdx = pts.indices.maxByOrNull { pts[it].second }
        var dip: Double? = null
        var recovery: Double? = null
        if (peakIdx != null && peak > 1500 && baseline != null) {
            val after = pts.drop(peakIdx)
            val dropIdx = after.indexOfFirst { it.second <= baseline }
            if (dropIdx >= 0) {
                val tail = after.drop(dropIdx)
                dip = tail.minOf { it.second }
                val minIdx = tail.indexOfFirst { it.second == dip }
                val back = tail.drop(minIdx).firstOrNull { it.second >= baseline - 50 }
                recovery = back?.let { (it.first - tail[0].first) / 1000.0 }
            }
        }
        val under = if (baseline != null && dip != null) baseline - dip else null
        val metrics = listOfNotNull(
            Metric("Lenta antes", "${f0(baseline)} rpm"),
            Metric("Pico", "${f0(peak)} rpm"),
            Metric("Mínimo ao soltar", if (stalled) "morreu" else "${f0(dip)} rpm", if (stalled) Level.BAD else null),
            under?.let { Metric("Afundou abaixo da lenta", "${f0(it)} rpm", if (it <= 80) Level.GOOD else if (it <= 150) Level.WARN else Level.BAD) },
            recovery?.let { Metric("Tempo para estabilizar", "${f1(it)} s", if (it <= 2) Level.GOOD else Level.WARN) },
        )
        val prev = previous(def.id, veh?.id)
        val cmp = prev?.raw?.get("under")?.takeIf { under != null && !it.isNaN() }?.let {
            "\n\nNa execução anterior afundou ${f0(it)} rpm; agora ${f0(under)} rpm."
        } ?: ""
        val (level, head, expl, steps) = when {
            stalled -> Quad(Level.BAD, "O motor morreu ao soltar o acelerador",
                "Ao soltar o pedal, a borboleta volta para a posição de repouso que a central aprendeu — pequena demais para o ar que o motor precisa. É o sintoma clássico de adaptação errada da borboleta (ou aprendida com uma entrada de ar falsa aberta).",
                listOf("Corrija entradas de ar falsas antes de tudo", "Limpe o corpo de borboleta", "Faça a adaptação da borboleta com um scanner do fabricante (na VW: ajuste básico do grupo 060)"))
            peak < 1500 -> Quad(Level.INFO, "Não detectei a acelerada",
                "O pico foi ${f0(peak)} rpm. Leve o motor a ~2.500 rpm e solte de uma vez quando aparecer ACELERE.", listOf("Rode o teste de novo"))
            (under ?: 0.0) > 150 -> Quad(Level.WARN, "No limite de morrer",
                "A rotação afundou ${f0(under)} rpm abaixo da lenta antes de voltar. Com o ar-condicionado ligado ou numa frenagem, o motor pode apagar.",
                listOf("Limpe o corpo de borboleta", "Faça a adaptação da borboleta", "Confira entradas de ar falsas (teste de Marcha lenta)"))
            (recovery ?: 0.0) > 3 -> Quad(Level.WARN, "Demora para voltar à lenta",
                "A rotação levou ${f1(recovery)} s para estabilizar. Normal é até ~2 s.",
                listOf("Limpe o corpo de borboleta", "Verifique o sensor de temperatura do motor"))
            else -> Quad(Level.GOOD, "Retorno à lenta normal",
                "A rotação voltou para a lenta sem afundar (${f0(under ?: 0.0)} rpm abaixo) em ${f1(recovery ?: 0.0)} s.",
                listOf("Nada a fazer"))
        }
        return report(def, level, head, expl + cmp, metrics, steps, pts.map { it.second }, "Rotação (rpm)",
            0.0, maxOf(3000.0, peak * 1.1), baseline, null,
            mapOf("under" to (under ?: Double.NaN), "rec" to (recovery ?: Double.NaN), "stalled" to if (stalled) 1.0 else 0.0))
    }

    // ------------------------------------------------------------------ mistura

    private suspend fun mixture(): TestReport {
        val def = TestCatalog.MIXTURE
        val status = pid(Pids.FUEL_STATUS)?.toInt()
        val idleS = ArrayList<Double>(); val idleL = ArrayList<Double>(); val idleM = ArrayList<Double>()
        val hiS = ArrayList<Double>(); val hiL = ArrayList<Double>()
        val chart = ArrayList<Double>()
        var ltft = pid(Pids.LTFT)
        var rpm: Double? = pid(Pids.RPM)
        elm.verbose = false
        val t0 = System.currentTimeMillis()
        var phase = 0
        var phaseStart = t0
        var tick = 0
        var goodHigh = 0
        while (true) {
            val now = System.currentTimeMillis()
            val el = (now - phaseStart) / 1000.0
            if (phase == 0 && el >= 25) { phase = 1; phaseStart = now; continue }
            if (phase == 1 && ((el >= 25 && goodHigh >= 8) || el >= 50)) break
            val s = pid(Pids.STFT)
            when (tick % 3) {
                0 -> rpm = pid(Pids.RPM) ?: rpm
                1 -> ltft = pid(Pids.LTFT) ?: ltft
                2 -> if (phase == 0) pid(Pids.MAP)?.let { idleM += it }
            }
            tick++
            val total = (s ?: 0.0) + (ltft ?: 0.0)
            if (s != null) chart += total
            if (phase == 0) {
                if (s != null) { idleS += s; ltft?.let { idleL += it } }
                emit(def.id, 1, 2, "Em lenta", "Não acelere.", "%+.0f".format(total), "% correção",
                    (el / 50).toFloat(), (25 - el).toInt(), false, chart, -30.0, 30.0, 0.0)
            } else {
                val ok = (rpm ?: 0.0) in 2100.0..2900.0
                if (ok && s != null) { hiS += s; ltft?.let { hiL += it }; goodHigh++ }
                emit(def.id, 2, 2, "A 2.500 rpm", if (ok) "Isso! Mantenha." else "ACELERE e mantenha ~2.500 rpm (agora ${f0(rpm)})",
                    "%+.0f".format(total), "% correção", (0.5 + el / 50).toFloat(), (25 - el).toInt().coerceAtLeast(0), !ok,
                    chart, -30.0, 30.0, 0.0)
            }
        }
        elm.verbose = true
        val t1 = (idleS.averageOrNull() ?: 0.0) + (idleL.averageOrNull() ?: 0.0)
        val t2 = if (hiS.isNotEmpty()) hiS.average() + (hiL.averageOrNull() ?: 0.0) else null
        val l1 = idleL.averageOrNull(); val l2 = hiL.averageOrNull()
        val flex = Pids.isFlex(ObdManager.vehicle.value.fuelType) || Garage.current.value?.fuel == FuelKind.MISTURA
        val closed = status == 2 || status == 16
        val metrics = listOfNotNull(
            Metric("Correção total em lenta", "%+.1f %%".format(t1), lvl(t1)),
            t2?.let { Metric("Correção total a 2.500 rpm", "%+.1f %%".format(it), lvl(it)) },
            l1?.let { Metric("Correção longa (lenta)", "%+.1f %%".format(it), lvl(it)) },
            l2?.let { Metric("Correção longa (2.500)", "%+.1f %%".format(it), lvl(it)) },
            idleM.averageOrNull()?.let { Metric("Coletor em lenta", "${f0(it)} kPa", if (it <= 40) Level.GOOD else Level.WARN) },
            Metric("Sistema de combustível", Pids.fuelStatusText(status), if (closed) Level.GOOD else Level.WARN),
        )
        val atLimit = listOfNotNull(l1, l2).any { abs(it) >= 23 }
        val (level, head, expl, steps) = when {
            !closed && status != null -> Quad(Level.WARN, "Central em malha aberta",
                "As correções só valem com a central usando a sonda (${Pids.fuelStatusText(status)}).",
                listOf("Deixe o motor aquecer bem e repita"))
            t2 == null -> Quad(Level.INFO, "Faltou a etapa a 2.500 rpm",
                "Só deu para medir em lenta: correção total %+.1f%%.".format(t1), listOf("Rode de novo mantendo ~2.500 rpm na segunda etapa"))
            abs(t1) <= 10 && abs(t2) <= 10 -> Quad(Level.GOOD, "Mistura equilibrada",
                "Correções de %+.0f%% em lenta e %+.0f%% a 2.500 rpm: a central acerta a mistura sem esforço.".format(t1, t2),
                listOf("Nada a fazer"))
            t1 > 10 && t2 < t1 - 6 -> Quad(Level.WARN, "Entrada de ar falsa",
                "Pobre em lenta (%+.0f%%) e melhora com a rotação (%+.0f%%). Ar que entra por fora da borboleta pesa muito em lenta e pouco em giro alto.".format(t1, t2),
                listOf("Procure mangueiras de vácuo soltas ou rachadas", "Verifique o tubo do cânister, a válvula PCV e a junta do coletor", "Com o motor em lenta, borrife água com sabão nas junções: a rotação muda onde houver vazamento"))
            t1 > 10 && t2 > 10 -> Quad(Level.WARN, "Pobre em todas as rotações",
                "A central está adicionando combustível sempre (%+.0f%% / %+.0f%%). Falta combustível chegando ou o ar está sendo medido a menos.".format(t1, t2),
                listOf("Meça a pressão de combustível (bomba, filtro, regulador)", "Limpe ou teste os bicos", "Verifique o sensor de pressão do coletor (ou MAF)"))
            t1 < -10 && t2 < -10 -> Quad(Level.WARN, "Rica em todas as rotações",
                ("A central está tirando combustível sempre (%+.0f%% / %+.0f%%). ".format(t1, t2)) +
                    (if (flex) "Num flex, o mais comum é a central achar que tem mais etanol do que tem: ela injeta para etanol e corrige para baixo. " else "") +
                    "Outras causas: bico pingando, pressão de combustível alta, sensor de temperatura lendo frio.",
                listOfNotNull(
                    if (flex) "Abasteça bem e rode 15 min em velocidade constante para a central reaprender o combustível" else null,
                    "Verifique bicos e a pressão de combustível",
                    "Confira o sensor de temperatura do motor",
                    if (atLimit) "Se a correção longa não sair do limite, zere a adaptação com um scanner do fabricante" else null,
                ))
            t1 < -10 && t2 > t1 + 6 -> Quad(Level.WARN, "Rica só em lenta",
                "Rica em lenta (%+.0f%%) e normal em giro (%+.0f%%): excesso entrando em baixa vazão.".format(t1, t2),
                listOf("Verifique a válvula de purga do cânister (presa aberta)", "Bico pingando aparece mais em lenta"))
            else -> Quad(Level.WARN, "Correções fora do normal",
                "Correção de %+.0f%% em lenta e %+.0f%% a 2.500 rpm.".format(t1, t2), listOf("Repita o teste com o motor bem quente", "Confira os códigos de falha"))
        }
        val lvl2 = if (atLimit && level != Level.GOOD) Level.BAD else level
        return report(def, lvl2, head, expl + if (atLimit) "\n\nA correção longa está no limite: a central não consegue corrigir mais." else "",
            metrics, steps, chart, "Correção total (%)", -30.0, 30.0, 0.0, null,
            mapOf("idle" to t1, "high" to (t2 ?: Double.NaN)))
    }

    private fun lvl(v: Double) = if (abs(v) <= 10) Level.GOOD else if (abs(v) <= 20) Level.WARN else Level.BAD
    private fun List<Double>.averageOrNull() = if (isEmpty()) null else average()

    // ------------------------------------------------------------------ bateria e alternador

    private suspend fun battery(): TestReport {
        val def = TestCatalog.BATTERY
        val trace = ArrayList<Double>()
        elm.verbose = false
        val rpmStart = pid(Pids.RPM)
        val running = rpmStart != null && rpmStart >= 400
        var rest: Double? = null
        var crankMin: Double? = null
        var started = running

        if (!running) {
            // 1. repouso
            val r = ArrayList<Double>()
            val t0 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t0 < 6000) {
                volts()?.let { r += it; trace += it }
                val el = (System.currentTimeMillis() - t0) / 1000.0
                emit(def.id, 1, 4, "Bateria em repouso", "Motor desligado, ignição ligada. Não dê a partida ainda.",
                    f2(r.lastOrNull()), "V", (el / 6 * 0.2).toFloat(), (6 - el).toInt(), false, trace, 9.0, 15.0, 12.4)
                delay(150)
            }
            rest = r.averageOrNull()
            // 2. partida
            val t1 = System.currentTimeMillis()
            var lowest = rest ?: 12.6
            while (System.currentTimeMillis() - t1 < 30_000) {
                val v = volts() ?: continue
                trace += v
                lowest = minOf(lowest, v)
                val dropped = (rest ?: 12.6) - lowest > 0.8
                if (dropped && v > 13.0) { started = true; break }
                val el = (System.currentTimeMillis() - t1) / 1000.0
                emit(def.id, 2, 4, "Partida", if (dropped) "Partida detectada…" else "DÊ A PARTIDA AGORA", f2(v), "V",
                    0.2f + (el / 30 * 0.2).toFloat(), (30 - el).toInt(), !dropped, trace, 9.0, 15.0, 12.4)
            }
            crankMin = lowest.takeIf { (rest ?: 12.6) - it > 0.8 }
            if (!started) {
                // pode ter ligado mas o alternador não carregar: confere a rotação
                started = (pid(Pids.RPM) ?: 0.0) >= 400
            }
        }
        // 3. recarga em lenta
        val chg = ArrayList<Double>()
        if (started) {
            val t2 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t2 < 10_000) {
                volts()?.let { chg += it; trace += it }
                val el = (System.currentTimeMillis() - t2) / 1000.0
                emit(def.id, 3, 4, "Recarga em lenta", "Deixe o motor em lenta, sem consumo elétrico ligado.", f2(chg.lastOrNull()), "V",
                    0.4f + (el / 10 * 0.3).toFloat(), (10 - el).toInt(), false, trace, 9.0, 15.0, 13.6)
                delay(200)
            }
        }
        val charging = chg.drop(chg.size / 3).averageOrNull()
        // 4. com carga
        val load = ArrayList<Double>()
        if (started) {
            val t3 = System.currentTimeMillis()
            while (System.currentTimeMillis() - t3 < 14_000) {
                val v = volts()
                val el = (System.currentTimeMillis() - t3) / 1000.0
                if (v != null) { trace += v; if (el > 5) load += v }
                emit(def.id, 4, 4, "Com consumo", "LIGUE faróis, ventilação no máximo e desembaçador", f2(v), "V",
                    0.7f + (el / 14 * 0.3).toFloat(), (14 - el).toInt(), el < 4, trace, 9.0, 15.0, 13.6)
                delay(200)
            }
        }
        elm.verbose = true
        val loaded = load.averageOrNull()

        val restLv = rest?.let { if (it >= 12.4) Level.GOOD else if (it >= 12.2) Level.WARN else Level.BAD }
        val crankLv = crankMin?.let { if (it >= 10.0) Level.GOOD else if (it >= 9.6) Level.WARN else Level.BAD }
        val chgLv = charging?.let { if (it in 13.5..14.8) Level.GOOD else if (it in 13.2..15.0) Level.WARN else Level.BAD }
        val loadLv = loaded?.let { if (it >= 13.2) Level.GOOD else if (it >= 12.8) Level.WARN else Level.BAD }
        val metrics = listOfNotNull(
            rest?.let { Metric("Bateria em repouso", "${f2(it)} V · ${soc(it)}", restLv) },
            crankMin?.let { Metric("Mínimo na partida", "${f2(it)} V", crankLv) },
            charging?.let { Metric("Recarga em lenta", "${f2(it)} V", chgLv) },
            loaded?.let { Metric("Com consumo ligado", "${f2(it)} V", loadLv) },
            if (charging != null && loaded != null) Metric("Queda com consumo", "${f2(charging - loaded)} V",
                if (charging - loaded <= 0.5) Level.GOOD else Level.WARN) else null,
        )
        val lvls = listOfNotNull(restLv, crankLv, chgLv, loadLv)
        val level = when { Level.BAD in lvls -> Level.BAD; Level.WARN in lvls -> Level.WARN; lvls.isEmpty() -> Level.INFO; else -> Level.GOOD }
        val problems = ArrayList<String>()
        val steps = ArrayList<String>()
        if (running) problems += "O motor já estava ligado: medi só a recarga. Para avaliar a bateria e a partida, comece com o motor desligado."
        if (!started) problems += "Não detectei a partida em 30 s."
        if (restLv == Level.BAD || restLv == Level.WARN) {
            problems += "Em repouso a bateria está com ${f2(rest)} V (${soc(rest!!)}). Uma bateria carregada fica acima de 12,4 V."
            steps += "Recarregue a bateria (rodar 30 min em estrada ou carregador) e repita"
        }
        if (crankLv == Level.BAD || crankLv == Level.WARN) {
            problems += "Na partida a tensão caiu até ${f2(crankMin)} V. Abaixo de ~9,6 V a bateria está fraca para o motor de partida."
            steps += "Teste a bateria com carga numa autopeças; se tiver mais de 3–4 anos, prepare a troca"
            steps += "Limpe e aperte os terminais e o cabo terra"
        }
        if (chgLv == Level.BAD && charging != null && charging < 13.2) {
            problems += "Com o motor ligado a tensão ficou em ${f2(charging)} V: o alternador não está recarregando."
            steps += "Verifique a correia do alternador e o fusível/cabo de carga"
            steps += "Teste o regulador de tensão e as escovas do alternador"
        }
        if (charging != null && charging > 15.0) {
            problems += "Tensão de ${f2(charging)} V: o regulador do alternador está deixando passar carga demais (ferve a bateria)."
            steps += "Teste o regulador de tensão do alternador"
        }
        if (loadLv == Level.BAD || loadLv == Level.WARN) {
            problems += "Com consumo ligado a tensão caiu para ${f2(loaded)} V: o alternador está no limite."
            steps += "Verifique a correia (patinando) e o alternador"
        }
        val head = when (level) {
            Level.GOOD -> "Bateria e alternador em ordem"
            Level.BAD -> if (chgLv == Level.BAD) "Problema na recarga" else if (crankLv == Level.BAD) "Bateria fraca para a partida" else "Bateria descarregada"
            Level.WARN -> "Atenção à parte elétrica"
            Level.INFO -> "Teste incompleto"
        }
        val expl = problems.joinToString("\n\n").ifBlank {
            "Repouso ${f2(rest)} V, partida mínima ${f2(crankMin)} V, recarga ${f2(charging)} V e ${f2(loaded)} V com consumo: tudo dentro do esperado."
        }
        if (steps.isEmpty()) steps += "Nada a fazer"
        return report(def, level, head, expl, metrics, steps, trace, "Tensão (V)", 9.0, 15.0, 12.4, null,
            mapOf("rest" to (rest ?: Double.NaN), "crank" to (crankMin ?: Double.NaN), "chg" to (charging ?: Double.NaN), "load" to (loaded ?: Double.NaN)))
    }

    /** Estado de carga aproximado de uma bateria de chumbo em repouso. */
    private fun soc(v: Double) = when {
        v >= 12.6 -> "≈100%"
        v >= 12.4 -> "≈75%"
        v >= 12.2 -> "≈50%"
        v >= 12.0 -> "≈25%"
        else -> "descarregada"
    }
}
