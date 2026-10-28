package br.obd.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Combustível usado para estimar o consumo. */
enum class FuelKind(val label: String, val afr: Double, val density: Double) {
    GASOLINA("Gasolina comum (com etanol)", 13.0, 0.750),
    ETANOL("Etanol hidratado", 8.5, 0.809),
    MISTURA("Mistura no tanque (flex)", 0.0, 0.0),
    GASOLINA_PURA("Gasolina sem etanol", 14.7, 0.740),
    DIESEL("Diesel", 14.5, 0.840),
}

/**
 * O que o usuário sabe do carro e o app não consegue ler pelo OBD: cilindrada,
 * combustível, preço, e o fator de calibração do consumo (aprendido nos abastecimentos).
 */
data class VehicleProfile(
    val id: String,
    val name: String,
    val maker: String? = null,
    val year: Int? = null,
    val vin: String? = null,
    val protocol: String? = null,
    val displacementL: Double = 1.6,
    val displacementSet: Boolean = false,
    val fuel: FuelKind = FuelKind.GASOLINA,
    /** % de etanol hidratado no tanque, quando [fuel] é MISTURA. */
    val ethanolPct: Int = 50,
    val pricePerL: Double = 6.29,
    /** Multiplica a estimativa de consumo (1,0 = sem calibração). */
    val calib: Double = 1.0,
    val calibCount: Int = 0,
    /** Litros estimados (sem calibração) desde a marcação de tanque cheio, ou null sem marcação. */
    val rawSinceFull: Double? = null,
    val lastSeen: Long = 0,
    val totalKm: Double = 0.0,
    val lastDiag: String? = null,
    val model: String? = null,
    val powerCv: Int? = null,
    val cylinders: Int? = null,
) {
    /** Relação ar/combustível estequiométrica e densidade (kg/L) do que está no tanque. */
    fun afrDensity(measuredEthanolPct: Double? = null): Pair<Double, Double> {
        if (fuel != FuelKind.MISTURA && measuredEthanolPct == null) return fuel.afr to fuel.density
        val x = ((measuredEthanolPct ?: ethanolPct.toDouble()) / 100).coerceIn(0.0, 1.0)
        val g = FuelKind.GASOLINA; val e = FuelKind.ETANOL
        return (x * e.afr + (1 - x) * g.afr) to (x * e.density + (1 - x) * g.density)
    }

    val fuelLabel get() = if (fuel == FuelKind.MISTURA) "Flex · $ethanolPct% etanol" else fuel.label

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("maker", maker); put("year", year); put("vin", vin)
        put("protocol", protocol); put("disp", displacementL); put("dispSet", displacementSet)
        put("fuel", fuel.name); put("eth", ethanolPct); put("price", pricePerL); put("calib", calib)
        put("calibN", calibCount); put("rawFull", rawSinceFull); put("seen", lastSeen); put("km", totalKm)
        put("diag", lastDiag); put("model", model); put("cv", powerCv); put("cyl", cylinders)
    }

    companion object {
        fun fromJson(o: JSONObject) = VehicleProfile(
            id = o.getString("id"), name = o.optString("name", "Meu carro"),
            maker = o.optStringOrNull("maker"), year = o.optIntOrNull("year"), vin = o.optStringOrNull("vin"),
            protocol = o.optStringOrNull("protocol"), displacementL = o.optDouble("disp", 1.6),
            displacementSet = o.optBoolean("dispSet", false),
            fuel = runCatching { FuelKind.valueOf(o.optString("fuel")) }.getOrDefault(FuelKind.GASOLINA),
            ethanolPct = o.optInt("eth", 50), pricePerL = o.optDouble("price", 6.29), calib = o.optDouble("calib", 1.0),
            calibCount = o.optInt("calibN", 0), rawSinceFull = o.optDoubleOrNull("rawFull"),
            lastSeen = o.optLong("seen", 0), totalKm = o.optDouble("km", 0.0), lastDiag = o.optStringOrNull("diag"),
            model = o.optStringOrNull("model"), powerCv = o.optIntOrNull("cv"), cylinders = o.optIntOrNull("cyl"),
        )
    }
}

fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k)
fun JSONObject.optIntOrNull(k: String): Int? = if (isNull(k) || !has(k)) null else optInt(k)
fun JSONObject.optDoubleOrNull(k: String): Double? = if (isNull(k) || !has(k)) null else optDouble(k).takeIf { !it.isNaN() }

/** Carros conhecidos, salvos no aparelho. O atual é o último conectado. */
object Garage {
    private lateinit var ctx: Context
    private val prefs get() = ctx.getSharedPreferences("garage", Context.MODE_PRIVATE)

    private val _vehicles = MutableStateFlow<List<VehicleProfile>>(emptyList())
    val vehicles: StateFlow<List<VehicleProfile>> = _vehicles.asStateFlow()

    private val _current = MutableStateFlow<VehicleProfile?>(null)
    /** Carro conectado agora ou, desconectado, o último usado. */
    val current: StateFlow<VehicleProfile?> = _current.asStateFlow()

    fun init(c: Context) {
        ctx = c.applicationContext
        val arr = runCatching { JSONArray(prefs.getString("list", "[]")) }.getOrDefault(JSONArray())
        _vehicles.value = (0 until arr.length()).mapNotNull { runCatching { VehicleProfile.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        val cur = prefs.getString("current", null)
        _current.value = _vehicles.value.firstOrNull { it.id == cur } ?: _vehicles.value.maxByOrNull { it.lastSeen }
    }

    private fun persist() {
        val arr = JSONArray()
        _vehicles.value.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("list", arr.toString()).putString("current", _current.value?.id).apply()
    }

    /** Chamado quando o carro é identificado: acha o perfil pelo chassi ou cria um. */
    fun onIdentified(info: VehicleInfo, adapterKey: String): VehicleProfile {
        val id = info.vin ?: "sem-chassi:$adapterKey"
        val existing = _vehicles.value.firstOrNull { it.id == id }
        val p = (existing ?: VehicleProfile(
            id = id,
            name = if (info.vin?.startsWith("SMXDEMO") == true) "Carro de demonstração"
                else info.maker?.let { m -> listOfNotNull(m.substringBefore(" ("), info.year?.toString()).joinToString(" ") } ?: "Meu carro",
            fuel = if (info.fuelType == 11 || info.fuelType == 3) FuelKind.ETANOL
                else if (Pids.isDiesel(info.fuelType)) FuelKind.DIESEL else FuelKind.GASOLINA,
        )).copy(
            maker = info.maker ?: existing?.maker, year = info.year ?: existing?.year, vin = info.vin,
            protocol = info.protocol, lastSeen = System.currentTimeMillis(),
        )
        save(p)
        _current.value = p
        persist()
        return p
    }

    fun save(p: VehicleProfile) {
        _vehicles.value = _vehicles.value.filter { it.id != p.id } + p
        if (_current.value?.id == p.id || _current.value == null) _current.value = p
        persist()
    }

    fun update(block: (VehicleProfile) -> VehicleProfile) {
        val c = _current.value ?: return
        save(block(c))
    }

    fun select(id: String) {
        _current.value = _vehicles.value.firstOrNull { it.id == id } ?: return
        persist()
    }

    fun delete(id: String) {
        _vehicles.value = _vehicles.value.filter { it.id != id }
        if (_current.value?.id == id) _current.value = _vehicles.value.maxByOrNull { it.lastSeen }
        persist()
    }

    /**
     * O OBD não informa cilindrada nem potência. Tenta a base pública de chassis da NHTSA
     * (EUA): ela decodifica bem carros de fábricas que exportam para lá e, para chassis
     * brasileiros, às vezes só a marca. Só preenche o que veio e o usuário não definiu.
     */
    suspend fun lookupVin(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val p = _current.value ?: return@withContext "Nenhum carro selecionado."
        val vin = p.vin ?: return@withContext "Este carro não informou o chassi (VIN) pela central."
        val txt = try {
            val c = java.net.URL("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVinValues/$vin?format=json").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return@withContext "Sem conexão com a internet (ou a base não respondeu)."
        }
        val r = runCatching { JSONObject(txt).getJSONArray("Results").getJSONObject(0) }.getOrNull()
            ?: return@withContext "A base não reconheceu este chassi."
        fun f(k: String) = r.optString(k).trim().takeIf { it.isNotEmpty() && it != "Not Applicable" && it != "0" }
        val disp = f("DisplacementL")?.toDoubleOrNull()
        val hp = f("EngineHP")?.toDoubleOrNull()
        val cyl = f("EngineCylinders")?.toIntOrNull()
        val model = f("Model")
        val make = f("Make")
        val found = listOfNotNull(make, model, disp?.let { "%.1f L".format(it) }, hp?.let { "${(it * 1.0139).toInt()} cv" }, cyl?.let { "$it cil." })
        if (disp == null && hp == null && model == null) return@withContext "A base só reconheceu ${make ?: "o fabricante"}. Preencha a cilindrada à mão."
        val updated = p.copy(
            displacementL = if (!p.displacementSet && disp != null) disp else p.displacementL,
            displacementSet = p.displacementSet || disp != null,
            powerCv = p.powerCv ?: hp?.let { (it * 1.0139).toInt() },
            cylinders = p.cylinders ?: cyl,
            model = p.model ?: model,
        )
        save(updated)
        "Encontrado: ${found.joinToString(" · ")}"
    }

    /** Soma o que uma viagem gastou (estimativa crua) e rodou. */
    fun addTrip(km: Double, rawLiters: Double) = update { p ->
        p.copy(totalKm = p.totalKm + km, rawSinceFull = p.rawSinceFull?.plus(rawLiters))
    }

    /** "Enchi o tanque": começa a contar a partir de agora. */
    fun markFull() = update { it.copy(rawSinceFull = 0.0) }

    /**
     * "Abasteci X litros e completei": compara com o que o app estimou desde a última marcação
     * e ajusta o fator. Devolve o novo fator ou null se rodou pouco para calibrar.
     */
    fun calibrate(liters: Double): Double? {
        val p = _current.value ?: return null
        val raw = p.rawSinceFull ?: return null
        if (raw < 2.0 || liters <= 0) return null
        val k = (liters / raw).coerceIn(0.4, 2.5)
        val newK = if (p.calibCount == 0) k else (p.calib * 0.4 + k * 0.6)
        save(p.copy(calib = newK, calibCount = p.calibCount + 1, rawSinceFull = 0.0))
        return newK
    }
}
