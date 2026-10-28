package br.obd.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Estimativa de consumo a partir do que o carro informa:
 *  1. PID 5E (taxa de combustível, L/h) quando existe — raro;
 *  2. fluxo de ar (MAF, g/s) dividido pela relação ar/combustível;
 *  3. sem MAF, "speed-density": massa de ar = pressão do coletor × cilindrada × eficiência
 *     volumétrica ÷ (R × temperatura do ar), a cada duas voltas do motor.
 * No corte de combustível da desaceleração a central não injeta nada.
 * O resultado é multiplicado pelo fator aprendido nos abastecimentos.
 */
object FuelModel {
    const val VE = 0.80

    /** Litros por hora sem calibração, ou null se faltam dados. */
    fun rawLph(v: Map<String, Double>, p: VehicleProfile): Double? {
        val rpm = v["rpm"] ?: return null
        if (rpm < 300) return 0.0
        v["fuelRate"]?.let { return it }
        val status = v["fuelStatus"]?.toInt()
        val throttle = v["throttle"] ?: 0.0
        // status 4 = malha aberta por condição de condução: com pedal solto e giro alto, é o corte
        if (status == 4 && throttle < 6 && rpm > 1100) return 0.0
        val airGs = v["maf"] ?: run {
            val map = v["map"] ?: return null
            val iatK = (v["iat"] ?: 25.0) + 273.15
            map * p.displacementL * VE * rpm / (120 * 0.287 * iatK)
        }
        val (afr, density) = p.afrDensity(v["ethanol"]?.takeIf { p.fuel == FuelKind.MISTURA })
        // malha aberta com carga alta: enriquecimento (~λ 0,88), salvo lambda comandado conhecido
        val lambda = v["lambdaCmd"] ?: if (status == 4 && throttle > 60) 0.88 else 1.0
        val fuelGs = airGs / (afr * lambda)
        return fuelGs * 3600 / (density * 1000)
    }
}

data class TripPoint(
    val t: Long, val speed: Double?, val rpm: Double?, val coolant: Double?, val lph: Double?,
)

/** Resumo de uma viagem: tudo que as listas e o relatório mostram. */
data class TripSummary(
    val id: Long,
    val vehicleId: String?,
    val vehicleName: String?,
    val start: Long,
    val end: Long,
    val km: Double,
    val liters: Double,
    val rawLiters: Double,
    val calibrated: Boolean,
    val price: Double,
    val runSec: Double,
    val idleSec: Double,
    val movingSec: Double,
    val maxSpeed: Double,
    val maxRpm: Double,
    val avgRpm: Double?,
    val avgCoolant: Double?,
    val maxCoolant: Double?,
    val startCoolant: Double?,
    val warmupSec: Double?,
    val minBattery: Double?,
    val avgBattery: Double?,
    val hardAccel: Int,
    val hardBrake: Int,
    /** Segundos em cada faixa: <1500, 1500–2500, 2500–3500, >3500 rpm. */
    val rpmBands: List<Double>,
    val fuelCutSec: Double,
    val estimated: Boolean,
) {
    val durationSec get() = (end - start) / 1000.0
    val kmPerL get() = if (liters > 0.05 && km > 0.3) km / liters else null
    val lPer100 get() = if (km > 0.3) liters / km * 100 else null
    val cost get() = liters * price
    val avgSpeed get() = if (movingSec > 0) km / (runSec / 3600) else 0.0
    val idlePct get() = if (runSec > 0) idleSec / runSec * 100 else 0.0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("veh", vehicleId); put("vehName", vehicleName); put("start", start); put("end", end)
        put("km", km); put("l", liters); put("raw", rawLiters); put("cal", calibrated); put("price", price)
        put("run", runSec); put("idle", idleSec); put("moving", movingSec); put("vmax", maxSpeed); put("rmax", maxRpm)
        put("ravg", avgRpm); put("cavg", avgCoolant); put("cmax", maxCoolant); put("c0", startCoolant)
        put("warm", warmupSec); put("bmin", minBattery); put("bavg", avgBattery); put("acc", hardAccel)
        put("brk", hardBrake); put("bands", JSONArray(rpmBands)); put("cut", fuelCutSec); put("est", estimated)
    }

    companion object {
        fun fromJson(o: JSONObject): TripSummary {
            val b = o.optJSONArray("bands")
            return TripSummary(
                o.getLong("id"), o.optStringOrNull("veh"), o.optStringOrNull("vehName"), o.getLong("start"),
                o.getLong("end"), o.optDouble("km", 0.0), o.optDouble("l", 0.0), o.optDouble("raw", 0.0),
                o.optBoolean("cal"), o.optDouble("price", 0.0), o.optDouble("run", 0.0), o.optDouble("idle", 0.0),
                o.optDouble("moving", 0.0), o.optDouble("vmax", 0.0), o.optDouble("rmax", 0.0),
                o.optDoubleOrNull("ravg"), o.optDoubleOrNull("cavg"), o.optDoubleOrNull("cmax"),
                o.optDoubleOrNull("c0"), o.optDoubleOrNull("warm"), o.optDoubleOrNull("bmin"),
                o.optDoubleOrNull("bavg"), o.optInt("acc"), o.optInt("brk"),
                if (b == null) listOf(0.0, 0.0, 0.0, 0.0) else (0 until b.length()).map { b.optDouble(it, 0.0) },
                o.optDouble("cut", 0.0), o.optBoolean("est", true),
            )
        }
    }
}

/**
 * Acompanha a viagem em andamento a cada leitura do painel. Começa quando o motor liga e
 * termina quando ele fica 45 s desligado (ou a conexão acaba).
 */
class TripTracker(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Salva a viagem em andamento de tempos em tempos (não perde nada se o app for fechado). */
    private val onCheckpoint: (TripSummary, List<TripPoint>) -> Unit = { _, _ -> },
    private val onFinish: (TripSummary, List<TripPoint>) -> Unit,
) {
    private var lastCheckpoint = 0L
    private var active = false
    private var start = 0L
    private var last = 0L
    private var lastEngineOn = 0L
    private var km = 0.0
    private var raw = 0.0
    private var runSec = 0.0
    private var idleSec = 0.0
    private var movingSec = 0.0
    private var maxSpeed = 0.0
    private var maxRpm = 0.0
    private var rpmSum = 0.0
    private var coolSum = 0.0; private var coolSec = 0.0; private var maxCool: Double? = null
    private var startCool: Double? = null
    private var warmupSec: Double? = null
    private var battMin: Double? = null; private var battSum = 0.0; private var battN = 0
    private var acc = 0; private var brk = 0
    private val bands = DoubleArray(4)
    private var cutSec = 0.0
    private var estimated = true
    private var prevSpeed: Double? = null; private var prevSpeedAt = 0L
    private var lastPoint = 0L
    private val points = ArrayList<TripPoint>()
    private var profile: VehicleProfile? = null

    private val _live = MutableStateFlow<TripSummary?>(null)
    /** Viagem em andamento (resumo parcial) ou null. */
    val live: StateFlow<TripSummary?> = _live.asStateFlow()

    fun onTick(v: Map<String, Double>, fresh: Boolean, p: VehicleProfile?) {
        val now = clock()
        val rpm = if (fresh) v["rpm"] else null
        val on = rpm != null && rpm >= 300
        if (on) lastEngineOn = now
        if (!active) {
            if (on) begin(now, v, p)
            return
        }
        if (!on && now - lastEngineOn > 45_000) { finish(); return }
        if (now - lastCheckpoint >= 60_000 && runSec >= 60) {
            lastCheckpoint = now
            runCatching { onCheckpoint(summary(now), points.toList()) }
        }
        val dt = ((now - last) / 1000.0).coerceIn(0.0, 5.0)
        last = now
        if (!on || dt <= 0) { publish(now); return }
        profile = p ?: profile
        val speed = v["speed"] ?: 0.0
        runSec += dt
        if (speed < 2) idleSec += dt else movingSec += dt
        km += speed * dt / 3600
        maxSpeed = maxOf(maxSpeed, speed)
        maxRpm = maxOf(maxRpm, rpm!!)
        rpmSum += rpm * dt
        bands[when { rpm < 1500 -> 0; rpm < 2500 -> 1; rpm < 3500 -> 2; else -> 3 }] += dt
        val lph = profile?.let { FuelModel.rawLph(v, it) }
        if (lph != null) raw += lph * dt / 3600
        if (lph == 0.0 && rpm > 1100) cutSec += dt
        if (v["fuelRate"] != null) estimated = false
        v["coolant"]?.let { c ->
            coolSum += c * dt; coolSec += dt; maxCool = maxOf(maxCool ?: c, c)
            if (startCool == null) startCool = c
            if (warmupSec == null && (startCool ?: 99.0) < 50 && c >= 80) warmupSec = (now - start) / 1000.0
        }
        (v["battery"] ?: v["moduleV"])?.let { b -> battMin = minOf(battMin ?: b, b); battSum += b; battN++ }
        // acelerações/frenagens bruscas pela variação da velocidade
        v["speed"]?.let { s ->
            val ps = prevSpeed
            if (ps != null && now - prevSpeedAt in 300..3000) {
                val a = (s - ps) / ((now - prevSpeedAt) / 1000.0) // km/h por segundo
                if (a > 11) acc++
                if (a < -14) brk++
            }
            prevSpeed = s; prevSpeedAt = now
        }
        if (now - lastPoint >= 2000) {
            lastPoint = now
            points += TripPoint(now, v["speed"], rpm, v["coolant"], lph?.let { it * (profile?.calib ?: 1.0) })
            if (points.size > 5000) {
                val kept = points.filterIndexed { i, _ -> i % 2 == 0 }
                points.clear(); points.addAll(kept)
            }
        }
        publish(now)
    }

    private fun begin(now: Long, v: Map<String, Double>, p: VehicleProfile?) {
        active = true; start = now; last = now; lastEngineOn = now; lastCheckpoint = now
        km = 0.0; raw = 0.0; runSec = 0.0; idleSec = 0.0; movingSec = 0.0; maxSpeed = 0.0; maxRpm = 0.0
        rpmSum = 0.0; coolSum = 0.0; coolSec = 0.0; maxCool = null; startCool = v["coolant"]; warmupSec = null
        battMin = null; battSum = 0.0; battN = 0; acc = 0; brk = 0; bands.fill(0.0); cutSec = 0.0
        estimated = true; prevSpeed = null; lastPoint = 0L; points.clear(); profile = p
        publish(now)
    }

    private fun summary(end: Long): TripSummary {
        val p = profile
        val k = p?.calib ?: 1.0
        return TripSummary(
            id = start, vehicleId = p?.id, vehicleName = p?.name, start = start, end = end, km = km,
            liters = raw * k, rawLiters = raw, calibrated = (p?.calibCount ?: 0) > 0, price = p?.pricePerL ?: 0.0,
            runSec = runSec, idleSec = idleSec, movingSec = movingSec, maxSpeed = maxSpeed, maxRpm = maxRpm,
            avgRpm = if (runSec > 0) rpmSum / runSec else null, avgCoolant = if (coolSec > 0) coolSum / coolSec else null,
            maxCoolant = maxCool, startCoolant = startCool, warmupSec = warmupSec, minBattery = battMin,
            avgBattery = if (battN > 0) battSum / battN else null, hardAccel = acc, hardBrake = brk,
            rpmBands = bands.toList(), fuelCutSec = cutSec, estimated = estimated,
        )
    }

    private fun publish(now: Long) { _live.value = summary(now) }

    /** Fecha a viagem atual (motor desligado ou desconexão). */
    fun finish() {
        if (!active) return
        active = false
        val s = summary(lastEngineOn.coerceAtLeast(start))
        _live.value = null
        if (s.runSec >= 60 || s.km >= 0.2) onFinish(s, points.toList())
    }

    val isActive get() = active
}

/** Viagens salvas em arquivos: um resumo pequeno (lista rápida) e os pontos (gráficos). */
object TripStore {
    private lateinit var dir: File
    private val _trips = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips: StateFlow<List<TripSummary>> = _trips.asStateFlow()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "trips2").apply { mkdirs() }
        refresh()
    }

    fun refresh() {
        _trips.value = (dir.listFiles { f -> f.name.endsWith(".sum.json") } ?: emptyArray())
            .mapNotNull { runCatching { TripSummary.fromJson(JSONObject(it.readText())) }.getOrNull() }
            .sortedByDescending { it.start }
    }

    fun save(s: TripSummary, pts: List<TripPoint>) {
        File(dir, "${s.id}.sum.json").writeText(s.toJson().toString())
        val arr = JSONArray()
        pts.forEach { p ->
            arr.put(JSONArray().apply {
                put(p.t); put(p.speed ?: JSONObject.NULL); put(p.rpm ?: JSONObject.NULL)
                put(p.coolant ?: JSONObject.NULL); put(p.lph ?: JSONObject.NULL)
            })
        }
        File(dir, "${s.id}.pts.json").writeText(arr.toString())
        refresh()
    }

    fun points(id: Long): List<TripPoint> {
        val f = File(dir, "$id.pts.json")
        if (!f.exists()) return emptyList()
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: return emptyList()
        fun JSONArray.d(i: Int) = if (isNull(i)) null else optDouble(i)
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONArray(i)
            TripPoint(a.getLong(0), a.d(1), a.d(2), a.d(3), a.d(4))
        }
    }

    fun delete(id: Long) {
        File(dir, "$id.sum.json").delete(); File(dir, "$id.pts.json").delete()
        refresh()
    }

    fun get(id: Long) = _trips.value.firstOrNull { it.id == id }

    /** CSV de uma viagem (abre no Excel/Planilhas). */
    fun csv(id: Long, outDir: File): File {
        val s = get(id)
        val f = File(outDir.apply { mkdirs() }, "viagem-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date(id))}.csv")
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
        f.writeText(buildString {
            appendLine("hora,velocidade_kmh,rotacao_rpm,temp_motor_c,consumo_lh")
            points(id).forEach { p ->
                appendLine(listOf(fmt.format(java.util.Date(p.t)), p.speed, p.rpm, p.coolant, p.lph)
                    .joinToString(",") { v -> when (v) { null -> ""; is Double -> "%.2f".format(java.util.Locale.US, v); else -> v.toString() } })
            }
            if (s != null) {
                appendLine()
                appendLine("distancia_km,%.2f".format(java.util.Locale.US, s.km))
                appendLine("combustivel_l,%.2f".format(java.util.Locale.US, s.liters))
            }
        })
        return f
    }
}

/** Soma de várias viagens (período). */
data class TripTotals(
    val count: Int, val km: Double, val liters: Double, val cost: Double, val runSec: Double,
    val idleSec: Double, val avgCoolant: Double?, val maxSpeed: Double,
) {
    val kmPerL get() = if (liters > 0.1 && km > 0.5) km / liters else null
    companion object {
        fun of(list: List<TripSummary>): TripTotals {
            val cs = list.filter { it.avgCoolant != null }
            val cw = cs.sumOf { it.runSec }
            return TripTotals(
                list.size, list.sumOf { it.km }, list.sumOf { it.liters }, list.sumOf { it.cost },
                list.sumOf { it.runSec }, list.sumOf { it.idleSec },
                if (cw > 0) cs.sumOf { it.avgCoolant!! * it.runSec } / cw else null,
                list.maxOfOrNull { it.maxSpeed } ?: 0.0,
            )
        }
    }
}


/** CO₂ do escapamento por litro queimado (kg), pelo combustível do perfil. */
fun co2PerLiter(p: VehicleProfile?): Double {
    val g = 2.10; val e = 1.51
    return when (p?.fuel) {
        FuelKind.ETANOL -> e
        FuelKind.GASOLINA_PURA -> 2.31
        FuelKind.DIESEL -> 2.68
        FuelKind.MISTURA -> { val x = (p.ethanolPct / 100.0); x * e + (1 - x) * g }
        else -> g
    }
}

/** Autonomia estimada (km) pelo nível do tanque, capacidade e consumo médio recente. */
fun rangeKm(levelPct: Double?, p: VehicleProfile?, recent: List<TripSummary>): Double? {
    val tank = p?.tankL ?: return null
    val lvl = levelPct ?: return null
    val kmpl = TripTotals.of(recent.filter { it.vehicleId == p.id }.take(10)).kmPerL ?: return null
    return tank * lvl / 100 * kmpl
}
