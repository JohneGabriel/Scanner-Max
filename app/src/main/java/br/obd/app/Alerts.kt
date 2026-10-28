package br.obd.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import br.obd.app.ui.MainActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Alert(val id: String, val level: Level, val title: String, val message: String, val at: Long = System.currentTimeMillis())

/**
 * Vigia os dados ao vivo e avisa o que é crítico: superaquecimento, alternador sem carregar,
 * bateria fraca, giro no limite, reserva, luz de injeção nova. Cada alerta só se repete
 * depois de alguns minutos para não virar ruído.
 */
object Alerts {
    private lateinit var ctx: Context
    private const val CHANNEL = "alerts"
    private val _active = MutableStateFlow<List<Alert>>(emptyList())
    /** Alertas valendo agora (some quando a condição passa). */
    val active: StateFlow<List<Alert>> = _active.asStateFlow()

    private val lastFired = HashMap<String, Long>()
    private val since = HashMap<String, Long>()
    private var milBaseline: Int? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Alertas do carro", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Superaquecimento, bateria, alternador e luz de injeção"
                    enableVibration(true)
                }
            )
        }
    }

    fun reset() { _active.value = emptyList(); since.clear(); milBaseline = null }

    fun dismiss(id: String) { _active.value = _active.value.filter { it.id != id } }

    /** Condição que precisa durar [holdMs] antes de virar alerta. */
    private fun cond(id: String, on: Boolean, holdMs: Long, now: Long): Boolean {
        if (!on) { since.remove(id); return false }
        val s = since.getOrPut(id) { now }
        return now - s >= holdMs
    }

    /** Chamado a cada ciclo da leitura ao vivo. */
    fun check(v: Map<String, Double>, fresh: Boolean) {
        val p = Settings.prefs.value
        if (!p.alerts) { if (_active.value.isNotEmpty()) _active.value = emptyList(); return }
        val now = System.currentTimeMillis()
        val list = ArrayList<Alert>()
        val rpm = if (fresh) v["rpm"] else null
        val running = rpm != null && rpm >= 400
        val coolant = v["coolant"]
        val volts = v["battery"] ?: v["moduleV"]
        val diesel = Pids.isDiesel(ObdManager.vehicle.value.fuelType)

        if (coolant != null && cond("hot2", coolant >= p.coolantWarn + 7, 3000, now)) {
            list += Alert("hot2", Level.BAD, "Superaquecimento: ${coolant.toInt()} °C",
                "Pare em local seguro e desligue o motor. Não abra o reservatório quente. Verifique o nível do líquido e a ventoinha.")
        } else if (coolant != null && cond("hot", coolant >= p.coolantWarn, 8000, now)) {
            list += Alert("hot", Level.WARN, "Motor quente: ${coolant.toInt()} °C",
                "Acima de ${p.coolantWarn} °C. Alivie o pé e observe; se continuar subindo, pare e verifique o arrefecimento.")
        }
        if (running && volts != null && cond("nocharge", volts < 12.9, 20_000, now)) {
            list += Alert("nocharge", Level.BAD, "Alternador não está carregando",
                "Motor ligado e a tensão em ${"%.1f".format(volts)} V (o normal é 13,5–14,8 V). O carro está rodando só com a bateria: desligue o que puder e vá à oficina.")
        }
        if (running && volts != null && cond("overcharge", volts > 15.2, 10_000, now)) {
            list += Alert("overcharge", Level.BAD, "Tensão alta: ${"%.1f".format(volts)} V",
                "O regulador do alternador está deixando passar carga demais. Isso danifica a bateria e componentes eletrônicos.")
        }
        if (!running && volts != null && cond("lowbatt", volts < 11.9, 15_000, now)) {
            list += Alert("lowbatt", Level.WARN, "Bateria baixa: ${"%.1f".format(volts)} V",
                "Com o motor desligado a bateria está abaixo de 12 V. A próxima partida pode falhar.")
        }
        val red = if (diesel) 4300 else p.redline
        if (rpm != null && cond("redline", rpm >= red, 1500, now)) {
            list += Alert("redline", Level.WARN, "Giro no limite: ${rpm.toInt()} rpm", "Troque de marcha: manter o giro no limite desgasta o motor.")
        }
        v["fuelLevel"]?.let { f ->
            if (cond("fuel", f <= p.lowFuelPct, 30_000, now))
                list += Alert("fuel", Level.INFO, "Reserva: ${f.toInt()}% no tanque", "Hora de abastecer.")
        }
        v["oilTemp"]?.let { o ->
            if (cond("oil", o >= 135, 5000, now)) list += Alert("oil", Level.BAD, "Óleo muito quente: ${o.toInt()} °C", "Alivie o ritmo e deixe o motor esfriar.")
        }
        val ltft = v["ltft"]
        if (ltft != null && cond("trim", kotlin.math.abs(ltft) >= 23, 60_000, now)) {
            list += Alert("trim", Level.INFO, "Correção de combustível no limite (%+.0f%%)".format(ltft),
                "A central não consegue mais acertar a mistura. Rode o teste de Mistura e correções.")
        }
        // luz de injeção e códigos (lidos de tempos em tempos pela leitura ao vivo)
        v["milCount"]?.toInt()?.let { n ->
            val base = milBaseline
            if (base == null) milBaseline = n
            else if (n > base) {
                list += Alert("mil", Level.WARN, "Novo código de falha registrado",
                    "A central registrou ${n - base} código(s) novo(s). Abra o Diagnóstico para ver o que é.")
            }
        }
        // mantém alertas já disparados de luz de injeção até o usuário dispensar
        val keep = _active.value.filter { it.id == "mil" && list.none { n -> n.id == "mil" } }
        val merged = list + keep
        val prevIds = _active.value.map { it.id }.toSet()
        _active.value = merged
        merged.filter { it.id !in prevIds }.forEach { fire(it) }
    }

    private fun fire(a: Alert) {
        val now = System.currentTimeMillis()
        if (now - (lastFired[a.id] ?: 0) < 5 * 60_000) return
        lastFired[a.id] = now
        ObdManager.log("ALERTA ${a.level}: ${a.title}")
        when (a.level) { Level.BAD -> Feedback.critical(); Level.WARN -> Feedback.warning(); else -> Feedback.step() }
        if (a.level == Level.INFO) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val open = PendingIntent.getActivity(ctx, 10, Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(a.id.hashCode(), NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_obd)
                .setColor(if (a.level == Level.BAD) 0xFFEF5350.toInt() else 0xFFFFB74D.toInt())
                .setContentTitle(a.title)
                .setContentText(a.message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build())
        }
    }
}

// ------------------------------------------------------------------ manutenção

data class MaintItem(val id: String, val name: String, val intervalKm: Int, val lastKm: Double?)

/**
 * Lembretes de manutenção por quilometragem. O hodômetro é o que o usuário informa
 * uma vez, somado ao que o app mede nas viagens (ou o hodômetro da central, se existir).
 */
object Maintenance {
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences("maint", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow<List<MaintItem>>(emptyList())
    val items: StateFlow<List<MaintItem>> = _items.asStateFlow()

    val DEFAULTS = listOf(
        MaintItem("oil", "Troca de óleo e filtro", 10_000, null),
        MaintItem("air", "Filtro de ar", 15_000, null),
        MaintItem("fuelf", "Filtro de combustível", 20_000, null),
        MaintItem("cabin", "Filtro de cabine (ar-condicionado)", 15_000, null),
        MaintItem("plugs", "Velas de ignição", 30_000, null),
        MaintItem("tbi", "Limpeza do corpo de borboleta", 20_000, null),
        MaintItem("coolant", "Líquido de arrefecimento", 40_000, null),
        MaintItem("belt", "Correia dentada", 50_000, null),
    )

    private fun key() = "items:" + (Garage.current.value?.id ?: "default")
    private fun odoKey() = "odo:" + (Garage.current.value?.id ?: "default")

    fun init(c: Context) { ctx = c.applicationContext; load() }

    fun load() {
        val raw = sp.getString(key(), null)
        _items.value = if (raw == null) DEFAULTS else runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                MaintItem(o.getString("id"), o.getString("name"), o.getInt("int"), o.optDoubleOrNull("last"))
            }
        }.getOrDefault(DEFAULTS)
    }

    private fun persist() {
        val a = JSONArray()
        _items.value.forEach { a.put(JSONObject().apply { put("id", it.id); put("name", it.name); put("int", it.intervalKm); put("last", it.lastKm) }) }
        sp.edit().putString(key(), a.toString()).apply()
    }

    /** Hodômetro atual estimado ou null se o usuário nunca informou. */
    fun odometer(): Double? {
        ObdManager.values.value["odometer"]?.let { return it }
        val base = sp.getFloat(odoKey() + ":base", -1f).takeIf { it >= 0 } ?: return null
        val appAt = sp.getFloat(odoKey() + ":app", 0f)
        val total = Garage.current.value?.totalKm ?: 0.0
        return base + (total - appAt)
    }

    fun setOdometer(km: Double) {
        sp.edit().putFloat(odoKey() + ":base", km.toFloat())
            .putFloat(odoKey() + ":app", (Garage.current.value?.totalKm ?: 0.0).toFloat()).apply()
        _items.value = _items.value.toList()
    }

    fun done(id: String) {
        val odo = odometer() ?: return
        _items.value = _items.value.map { if (it.id == id) it.copy(lastKm = odo) else it }
        persist()
    }

    fun setInterval(id: String, km: Int) {
        _items.value = _items.value.map { if (it.id == id) it.copy(intervalKm = km) else it }
        persist()
    }

    fun setLast(id: String, km: Double?) {
        _items.value = _items.value.map { if (it.id == id) it.copy(lastKm = km) else it }
        persist()
    }

    /** Quilômetros até vencer (negativo = vencido), ou null sem dados. */
    fun remaining(i: MaintItem): Double? {
        val odo = odometer() ?: return null
        val last = i.lastKm ?: return null
        return last + i.intervalKm - odo
    }

    fun dueSoon(): List<MaintItem> = _items.value.filter { (remaining(it) ?: Double.MAX_VALUE) <= 1000 }
}
