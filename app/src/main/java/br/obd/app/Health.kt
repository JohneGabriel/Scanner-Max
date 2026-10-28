package br.obd.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.sqrt

/** Um sistema avaliado na saúde do motor. */
data class HealthItem(
    val key: String,
    val label: String,
    val level: Level,
    val value: String,
    val detail: String,
    /** Teste guiado que aprofunda este item (id), se houver. */
    val testId: String? = null,
)

data class HealthSummary(
    val score: Int,
    val level: Level,
    val headline: String,
    val items: List<HealthItem>,
    val live: Boolean,
)

/**
 * Saúde do motor: junta o que o app vê ao vivo (temperatura, tensão, mistura, lenta),
 * a luz de injeção e os códigos, os testes recentes e a manutenção num placar de 0 a 100.
 * Também guarda os extremos da sessão (máxima de temperatura, mínima de recarga).
 */
object EngineHealth {
    private var maxCoolant: Double? = null
    private var minCharging: Double? = null
    private var runningSince = 0L
    private val idleRpm = ArrayDeque<Double>()
    private val trims = ArrayDeque<Double>()
    private val o2 = ArrayDeque<Double>()

    private val _summary = MutableStateFlow<HealthSummary?>(null)
    val summary: StateFlow<HealthSummary?> = _summary.asStateFlow()

    fun reset() {
        maxCoolant = null; minCharging = null; runningSince = 0L
        idleRpm.clear(); trims.clear(); o2.clear()
    }

    /** Chamado a cada ciclo da leitura ao vivo. */
    fun observe(v: Map<String, Double>, fresh: Boolean) {
        val now = System.currentTimeMillis()
        val rpm = if (fresh) v["rpm"] else null
        val running = (rpm ?: 0.0) >= 400
        if (running && runningSince == 0L) runningSince = now
        if (!running) runningSince = 0L
        v["coolant"]?.let { c -> maxCoolant = maxOf(maxCoolant ?: c, c) }
        val volts = v["battery"] ?: v["moduleV"]
        // recarga só conta depois de 20 s ligado (a partida derruba a tensão)
        if (running && volts != null && now - runningSince > 20_000) minCharging = minOf(minCharging ?: volts, volts)
        if (running && (v["speed"] ?: 0.0) < 1 && (v["throttle"] ?: 0.0) < 6 && rpm != null && rpm < 1300) push(idleRpm, rpm, 60)
        val s = v["stft"]; val l = v["ltft"]
        if (running && (s != null || l != null) && v["fuelStatus"]?.toInt() == 2) push(trims, (s ?: 0.0) + (l ?: 0.0), 60)
        v["o2s1"]?.let { if (running) push(o2, it, 80) }
        _summary.value = compute(v, fresh)
    }

    private fun push(q: ArrayDeque<Double>, x: Double, max: Int) { q.addLast(x); while (q.size > max) q.removeFirst() }

    private fun std(q: Collection<Double>): Double {
        if (q.isEmpty()) return 0.0
        val m = q.average(); return sqrt(q.sumOf { (it - m) * (it - m) } / q.size)
    }

    /** Calcula o placar (também desconectado, a partir do que ficou salvo). */
    fun compute(v: Map<String, Double> = ObdManager.values.value, fresh: Boolean = ObdManager.connected.value &&
        System.currentTimeMillis() - ObdManager.lastLiveAt < 8000): HealthSummary {
        val prefs = Settings.prefs.value
        val items = ArrayList<HealthItem>()
        val rpm = if (fresh) v["rpm"] else null
        val running = (rpm ?: 0.0) >= 400
        val vehId = Garage.current.value?.id

        // códigos e luz de injeção
        val diag = ObdManager.diag.value
        val stored = diag.stored.orEmpty(); val pending = diag.pending.orEmpty()
        val mil = v["milOn"] == 1.0 || diag.readiness?.milOn == true
        val count = maxOf(v["milCount"]?.toInt() ?: 0, stored.size)
        items += when {
            mil || stored.isNotEmpty() -> HealthItem("codes", "Códigos de falha", Level.BAD,
                if (count > 0) "$count ativo(s)" else "luz acesa",
                "A luz de injeção está acesa ou há código confirmado. Abra o Diagnóstico para ver qual é e o que fazer.")
            pending.isNotEmpty() || count > 0 -> HealthItem("codes", "Códigos de falha", Level.WARN, "${maxOf(pending.size, count)} pendente(s)",
                "A central registrou algo que ainda não acendeu a luz. Vale olhar antes de virar problema.")
            diag.readAt != null || v["milCount"] != null -> HealthItem("codes", "Códigos de falha", Level.GOOD, "nenhum",
                "Central sem códigos registrados.")
            else -> HealthItem("codes", "Códigos de falha", Level.INFO, "não lido", "Leia o diagnóstico para incluir os códigos no placar.")
        }

        // temperatura
        val coolant = v["coolant"]
        val mx = maxCoolant
        items += when {
            coolant == null && mx == null -> HealthItem("cooling", "Temperatura", Level.INFO, "—", "Sem leitura de temperatura ainda.", "cooling")
            (coolant ?: 0.0) >= prefs.coolantWarn + 7 || (mx ?: 0.0) >= prefs.coolantWarn + 7 ->
                HealthItem("cooling", "Temperatura", Level.BAD, "${(mx ?: coolant!!).toInt()} °C máx.",
                    "O motor passou de ${prefs.coolantWarn + 7} °C. Verifique líquido de arrefecimento, ventoinha e termostato.", "cooling")
            (coolant ?: 0.0) >= prefs.coolantWarn || (mx ?: 0.0) >= prefs.coolantWarn ->
                HealthItem("cooling", "Temperatura", Level.WARN, "${(mx ?: coolant!!).toInt()} °C máx.",
                    "Chegou perto do limite (${prefs.coolantWarn} °C). Acompanhe e rode o teste de Arrefecimento.", "cooling")
            running && coolant != null && coolant < 70 && System.currentTimeMillis() - runningSince > 15 * 60_000 ->
                HealthItem("cooling", "Temperatura", Level.WARN, "${coolant.toInt()} °C",
                    "Mais de 15 min ligado e o motor não aqueceu: termostato pode estar preso aberto.", "cooling")
            else -> HealthItem("cooling", "Temperatura", Level.GOOD, coolant?.let { "${it.toInt()} °C" } ?: "${mx!!.toInt()} °C máx.",
                "Dentro do normal." + (mx?.let { " Máxima nesta sessão: ${it.toInt()} °C." } ?: ""), "cooling")
        }

        // elétrica
        val volts = v["battery"] ?: v["moduleV"]
        val minC = minCharging
        items += when {
            volts == null -> HealthItem("electric", "Bateria e recarga", Level.INFO, "—", "Sem leitura de tensão.", "battery")
            running && (volts < 12.9 || (minC != null && minC < 12.9)) -> HealthItem("electric", "Bateria e recarga", Level.BAD,
                "%.1f V".format(minC ?: volts), "Motor ligado e a tensão abaixo de 12,9 V: o alternador não está recarregando.", "battery")
            running && volts > 15.0 -> HealthItem("electric", "Bateria e recarga", Level.BAD, "%.1f V".format(volts),
                "Tensão alta: regulador do alternador com defeito.", "battery")
            running && volts < 13.4 -> HealthItem("electric", "Bateria e recarga", Level.WARN, "%.1f V".format(volts),
                "Recarga fraca. Pode ser consumo alto ligado ou alternador no limite.", "battery")
            !running && volts < 12.2 -> HealthItem("electric", "Bateria e recarga", Level.WARN, "%.1f V".format(volts),
                "Bateria baixa em repouso. A partida pode falhar.", "battery")
            else -> HealthItem("electric", "Bateria e recarga", Level.GOOD, "%.1f V".format(volts),
                if (running) "Alternador recarregando normalmente." else "Bateria carregada.", "battery")
        }

        // mistura
        val trimAvg = if (trims.size >= 10) trims.average() else v["ltft"]
        if (trimAvg != null) items += when {
            abs(trimAvg) >= 20 -> HealthItem("mixture", "Mistura", Level.BAD, "%+.0f%%".format(trimAvg),
                "A central está corrigindo ${abs(trimAvg).toInt()}% do combustível (${if (trimAvg > 0) "pobre" else "rica"}): perto do limite.", "mixture")
            abs(trimAvg) >= 10 -> HealthItem("mixture", "Mistura", Level.WARN, "%+.0f%%".format(trimAvg),
                "Correção acima de ±10%: algo está desequilibrando a mistura.", "mixture")
            else -> HealthItem("mixture", "Mistura", Level.GOOD, "%+.0f%%".format(trimAvg), "Correções normais.", "mixture")
        }

        // lenta
        if (idleRpm.size >= 15) {
            val sd = std(idleRpm); val avg = idleRpm.average()
            items += when {
                sd > 70 -> HealthItem("idle", "Marcha lenta", Level.WARN, "±${sd.toInt()} rpm", "Lenta oscilando bastante.", "idle")
                avg < 600 -> HealthItem("idle", "Marcha lenta", Level.WARN, "${avg.toInt()} rpm", "Lenta baixa para motor quente.", "idle")
                else -> HealthItem("idle", "Marcha lenta", Level.GOOD, "${avg.toInt()} rpm", "Estável (±${sd.toInt()} rpm).", "idle")
            }
        }

        // sonda
        if (o2.size >= 30 && v["fuelStatus"]?.toInt() == 2) {
            val sw = (1 until o2.size).count { (o2.elementAt(it - 1) < 0.45) != (o2.elementAt(it) < 0.45) }
            val range = o2.max() - o2.min()
            items += if (range < 0.4 || sw < 2) HealthItem("o2", "Sonda lambda", Level.WARN, "%.2f V".format(range),
                "A sonda está variando pouco. Rode o teste da Sonda lambda.", "lambda")
            else HealthItem("o2", "Sonda lambda", Level.GOOD, "ativa", "Oscilando normalmente.", "lambda")
        }

        // testes recentes (30 dias)
        val monthAgo = System.currentTimeMillis() - 30L * 86400_000
        val recent = TestRunner.history.value.filter { it.vehicleId == vehId && it.at >= monthAgo }.groupBy { it.testId }.values.map { it.first() }
        if (recent.isNotEmpty()) {
            val worst = recent.maxByOrNull { lvRank(it.level) }!!
            items += HealthItem("tests", "Testes recentes", if (worst.level == Level.INFO) Level.GOOD else worst.level,
                "${recent.size} feito(s)",
                if (worst.level == Level.GOOD || worst.level == Level.INFO) "Nenhum problema nos testes dos últimos 30 dias."
                else "${worst.title}: ${worst.headline}")
        }

        // manutenção
        val due = Maintenance.items.value.filter { (Maintenance.remaining(it) ?: Double.MAX_VALUE) <= 1000 }
        if (due.isNotEmpty()) {
            val over = due.any { (Maintenance.remaining(it) ?: 0.0) < 0 }
            items += HealthItem("maint", "Manutenção", if (over) Level.WARN else Level.INFO,
                if (over) "vencida" else "${due.size} próxima(s)", due.joinToString(", ") { it.name })
        }

        val scored = items.filter { it.level != Level.INFO }
        var score = 100
        scored.forEach { score -= when (it.level) { Level.BAD -> 28; Level.WARN -> 10; else -> 0 } }
        score = score.coerceIn(0, 100)
        val level = when {
            items.any { it.level == Level.BAD } -> Level.BAD
            items.any { it.level == Level.WARN } -> Level.WARN
            scored.isEmpty() -> Level.INFO
            else -> Level.GOOD
        }
        val headline = when (level) {
            Level.GOOD -> "Motor saudável"
            Level.WARN -> "Atenção: ${items.first { it.level == Level.WARN }.label.lowercase()}"
            Level.BAD -> "Problema: ${items.first { it.level == Level.BAD }.label.lowercase()}"
            Level.INFO -> "Conecte para avaliar"
        }
        return HealthSummary(if (scored.isEmpty()) 0 else score, level, headline,
            items.sortedByDescending { lvRank(it.level) }, fresh)
    }

    private fun lvRank(l: Level) = when (l) { Level.BAD -> 3; Level.WARN -> 2; Level.GOOD -> 1; Level.INFO -> 0 }
}
