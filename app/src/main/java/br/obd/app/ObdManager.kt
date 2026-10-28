package br.obd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LiveMode { OFF, DASHBOARD, FUEL }

data class Sample(val t: Long, val rpm: Double?, val speed: Double?, val o2: Double?)

data class DiagState(
    val stored: List<String>? = null,
    val pending: List<String>? = null,
    val readiness: Readiness? = null,
    val freezeDtc: String? = null,
    val freeze: List<Pair<String, String>> = emptyList(),
    val mode06: List<Mode06Result> = emptyList(),
    val readAt: Long? = null,
)

data class LambdaResult(
    val seconds: Int,
    val samples: Int,
    val min: Double,
    val max: Double,
    val avg: Double,
    val switchesPerSec: Double,
    val richPct: Int,
    val closedLoop: Boolean,
    val rpm: Double?,
    val verdict: String,
)

/**
 * Estado e operações do app, independentes da tela. Vive enquanto o processo vive;
 * o ObdService mantém o processo em primeiro plano para a conexão não cair.
 */
@SuppressLint("StaticFieldLeak")
object ObdManager {
    lateinit var context: Context
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _status = MutableStateFlow("Desconectado")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _adapterName = MutableStateFlow<String?>(null)
    val adapterName: StateFlow<String?> = _adapterName.asStateFlow()

    /** Último valor de cada PID, pela chave do PidDef ("rpm", "ltft"...) e "battery". */
    private val _values = MutableStateFlow<Map<String, Double>>(emptyMap())
    val values: StateFlow<Map<String, Double>> = _values.asStateFlow()

    private val _history = MutableStateFlow<List<Sample>>(emptyList())
    val history: StateFlow<List<Sample>> = _history.asStateFlow()

    private val _liveMode = MutableStateFlow(LiveMode.OFF)
    val liveMode: StateFlow<LiveMode> = _liveMode.asStateFlow()

    private val _rate = MutableStateFlow(0.0)
    /** Pedidos por segundo que o carro está atendendo agora. */
    val rate: StateFlow<Double> = _rate.asStateFlow()

    /** Texto da operação em andamento (teste, leitura de códigos...) ou null. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _diag = MutableStateFlow(DiagState())
    val diag: StateFlow<DiagState> = _diag.asStateFlow()

    private val _lambda = MutableStateFlow<LambdaResult?>(null)
    val lambda: StateFlow<LambdaResult?> = _lambda.asStateFlow()

    private val _resetResult = MutableStateFlow<String?>(null)
    val resetResult: StateFlow<String?> = _resetResult.asStateFlow()

    private val _fuelType = MutableStateFlow<Int?>(null)
    val fuelType: StateFlow<Int?> = _fuelType.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    private val _recording = MutableStateFlow<File?>(null)
    val recording: StateFlow<File?> = _recording.asStateFlow()

    /** Quando a gravação atual começou (para o cronômetro da notificação). */
    var recordingStartedAt = 0L
        private set

    private val _trips = MutableStateFlow<List<File>>(emptyList())
    val trips: StateFlow<List<File>> = _trips.asStateFlow()

    private val fullLog = ArrayList<String>()
    private val _logTail = MutableStateFlow<List<String>>(emptyList())
    val logTail: StateFlow<List<String>> = _logTail.asStateFlow()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val elm = Elm327 { msg -> scope.launch { log(msg) } }

    private var liveJob: Job? = null
    private var recordJob: Job? = null

    fun init(ctx: Context) {
        if (::context.isInitialized) return
        context = ctx.applicationContext
        refreshTrips()
    }

    fun log(msg: String) {
        if (fullLog.size > 60_000) fullLog.subList(0, 20_000).clear()
        val line = "${timeFmt.format(Date())}  $msg"
        fullLog += line
        _logTail.value = (_logTail.value + line).takeLast(200)
    }

    // ------------------------------------------------------------- conexão

    private val prefs get() = context.getSharedPreferences("obd", Context.MODE_PRIVATE)
    val savedAddress: String? get() = prefs.getString("device", null)

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.toList()
            .sortedByDescending { (it.name ?: "").contains("OBD", ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) = scope.launch {
        userDisconnected = false
        connectNow(device)
    }

    /** true depois de "Desconectar": nenhuma rotina deve reconectar sozinha. */
    @Volatile private var userDisconnected = false

    @SuppressLint("MissingPermission")
    private suspend fun connectNow(device: BluetoothDevice): Boolean = try {
        _status.value = "Conectando a ${device.name ?: device.address}…"
        elm.close()
        elm.connect(device)
        _status.value = "Inicializando…"
        val version = elm.init().lines().last()
        prefs.edit().putString("device", device.address).apply()
        _adapterName.value = "${device.name ?: "?"} · $version"
        _connected.value = true
        _status.value = "Conectado"
        startService()
        scope.launch { _fuelType.value = runCatching { elm.readPid("51")?.getOrNull(0) }.getOrNull() }
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        _connected.value = false
        _status.value = "Falha ao conectar: ${e.message}"
        false
    }

    /** O serviço já em execução continua valendo após uma reconexão; só inicia se não estiver. */
    private var serviceStarted = false

    private fun startService() {
        if (serviceStarted) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
            serviceStarted = true
        } catch (e: Exception) {
            // o Android pode recusar iniciar o serviço com o app em segundo plano: a conexão segue mesmo assim
            log("Não foi possível iniciar o serviço: ${e.message}")
        }
    }

    private fun stopService() {
        serviceStarted = false
        context.stopService(Intent(context, ObdService::class.java))
    }

    fun disconnect() {
        userDisconnected = true
        setLive(LiveMode.OFF)
        stopRecording()
        elm.close()
        _connected.value = false
        _status.value = "Desconectado"
        stopService()
    }

    private suspend fun reconnect(): Boolean {
        if (userDisconnected) return false
        _connected.value = false
        val device = elm.lastDevice ?: return false
        for (attempt in 1..3) {
            _status.value = "Conexão caiu — reconectando ($attempt/3)…"
            if (userDisconnected) return false
            if (connectNow(device)) return true
            delay(2000)
        }
        _status.value = "Não foi possível reconectar"
        stopService()
        return false
    }

    // ------------------------------------------------------------- leitura ao vivo

    /** Cada item: PID e de quantos em quantos ciclos ele é lido (1 = sempre). */
    private val dashboardPlan = listOf(
        Pids.RPM to 1, Pids.SPEED to 2, Pids.THROTTLE to 2, Pids.LOAD to 4,
        Pids.MAP to 6, Pids.ADVANCE to 8, Pids.COOLANT to 15, Pids.IAT to 15,
    )
    private val fuelPlan = listOf(
        Pids.O2S1 to 1, Pids.STFT to 2, Pids.RPM to 3, Pids.O2S2 to 4,
        Pids.LTFT to 6, Pids.FUEL_STATUS to 8, Pids.COOLANT to 15,
    )

    fun setLive(mode: LiveMode) {
        _liveMode.value = mode
        if (mode == LiveMode.OFF) {
            liveJob?.cancel(); liveJob = null; return
        }
        if (liveJob?.isActive == true) return
        liveJob = scope.launch { liveLoop() }
    }

    private suspend fun liveLoop() {
        var tick = 0
        var t0 = System.currentTimeMillis()
        var requests = 0
        var emptyCycles = 0
        while (currentCoroutineContext().isActive && _liveMode.value != LiveMode.OFF) {
            if (!_connected.value || _busy.value != null) { delay(250); continue }
            val plan = if (_liveMode.value == LiveMode.FUEL) fuelPlan else dashboardPlan
            try {
                var answered = 0
                for ((def, every) in plan) {
                    if (tick % every != (plan.indexOfFirst { it.first == def } % every)) continue
                    if (readInto(def) != null) answered++
                    requests++
                }
                if (tick % 20 == 10) { readBattery(); requests++ }
                tick++
                addSample()
                val now = System.currentTimeMillis()
                if (now - t0 >= 2000) {
                    _rate.value = requests * 1000.0 / (now - t0)
                    t0 = now; requests = 0
                }
                // Ignição desligada e religada: o Bluetooth continua de pé (a tomada OBD tem
                // 12 V direto), mas a sessão da linha K com a central morreu. Reabre.
                emptyCycles = if (answered == 0) emptyCycles + 1 else 0
                if (emptyCycles >= 3) {
                    recoverBus()
                    emptyCycles = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!elm.isConnected) {
                    if (!reconnect()) { setLive(LiveMode.OFF); return }
                } else {
                    log("Erro na leitura: ${e.message}")
                    delay(500)
                }
            }
        }
    }

    /** Fecha a sessão antiga com a central e refaz a inicialização até ela responder. */
    private suspend fun recoverBus() {
        log("Central parou de responder — reabrindo a comunicação")
        _status.value = "Carro sem resposta — reabrindo a comunicação…"
        _values.value = emptyMap()
        while (currentCoroutineContext().isActive && _liveMode.value != LiveMode.OFF && elm.isConnected) {
            elm.send("ATPC")
            val r = elm.send("0100", timeoutMs = 10_000).replace(" ", "")
            if (r.contains("4100")) {
                log("Comunicação com o carro restabelecida")
                _status.value = "Conectado"
                return
            }
            _status.value = "Sem resposta do carro — a ignição está ligada?"
            delay(3000)
        }
    }

    private suspend fun readInto(def: PidDef): Double? {
        val v = elm.readPid(def.pid)?.let(def.decode) ?: return null
        _values.update { it + (def.key to v) }
        if (def == Pids.LTFT) trackLtft(v)
        return v
    }

    private val ltftRecent = ArrayDeque<Double>()
    private val _ltftStuck = MutableStateFlow(false)
    /** true quando as últimas leituras da correção longa são todas idênticas (aprendizado parado). */
    val ltftStuck: StateFlow<Boolean> = _ltftStuck.asStateFlow()

    private fun trackLtft(v: Double) {
        ltftRecent.addLast(v)
        while (ltftRecent.size > 20) ltftRecent.removeFirst()
        _ltftStuck.value = ltftRecent.size >= 15 && ltftRecent.all { it == ltftRecent.first() } && kotlin.math.abs(v) >= 5
    }

    private suspend fun readBattery() {
        val r = elm.batteryVoltage().replace("V", "").trim().toDoubleOrNull() ?: return
        _values.update { it + ("battery" to r) }
    }

    private fun addSample() {
        val v = _values.value
        val s = Sample(System.currentTimeMillis(), v["rpm"], v["speed"], v["o2s1"])
        _history.update { (it + s).takeLast(300) }
    }

    // ------------------------------------------------------------- operações exclusivas

    /** Roda [block] com a leitura ao vivo pausada e um aviso de "ocupado" na tela. */
    private fun exclusive(label: String, block: suspend () -> Unit) {
        if (!_connected.value) { _status.value = "Conecte primeiro"; return }
        if (_busy.value != null) return
        scope.launch {
            _busy.value = label
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log("Falhou: ${e.message}")
                reconnect()
            } catch (e: Exception) {
                log("Falhou: ${e.message}")
                _status.value = "Erro: ${e.message}"
            } finally {
                _busy.value = null
            }
        }
    }

    fun readDiagnostics() = exclusive("Lendo diagnóstico…") {
        val stored = elm.readDtcs("03")
        val pending = elm.readDtcs("07")
        val readiness = elm.readPid("01")?.let { ReadinessDecoder.decode(it) }

        // freeze frame (modo 02, quadro 0): primeiro o código que o gerou
        var freezeDtc: String? = null
        val freeze = mutableListOf<Pair<String, String>>()
        val ff = freezePid("02")
        if (ff != null && ff.size >= 2 && (ff[0] != 0 || ff[1] != 0)) {
            freezeDtc = Elm327.decodeDtc("%02X%02X".format(ff[0], ff[1]))
            for (def in listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT, Pids.LOAD, Pids.STFT, Pids.LTFT,
                Pids.MAP, Pids.ADVANCE, Pids.IAT, Pids.THROTTLE, Pids.FUEL_STATUS)) {
                val d = freezePid(def.pid) ?: continue
                val v = def.decode(d) ?: continue
                val txt = if (def == Pids.FUEL_STATUS) Pids.fuelStatusText(v.toInt())
                else "%.1f %s".format(v, def.unit)
                freeze += def.label to txt
            }
        }

        // modo 06: testes que a central faz nas sondas lambda
        val m06 = mutableListOf<Mode06Result>()
        for (tid in 1..8) m06 += mode06(tid)

        _diag.value = DiagState(stored, pending, readiness, freezeDtc, freeze, m06, System.currentTimeMillis())
    }

    private suspend fun freezePid(pid: String): IntArray? {
        val resp = elm.send("02${pid}00").replace(" ", "")
        val marker = "42${pid}00"
        val line = resp.lines().firstOrNull { it.startsWith(marker) } ?: return null
        return line.removePrefix(marker).chunked(2).mapNotNull { it.toIntOrNull(16) }.toIntArray()
    }

    private suspend fun mode06(tid: Int): List<Mode06Result> {
        val tidHex = "%02X".format(tid)
        val resp = elm.send("06$tidHex").replace(" ", "")
        return resp.lines().filter { it.startsWith("46$tidHex") }.mapNotNull { l ->
            val b = l.removePrefix("46$tidHex").chunked(2).mapNotNull { it.toIntOrNull(16) }
            if (b.size < 5) return@mapNotNull null
            val cid = b[0]
            val (value, unit) = Mode06Result.scale(tid, b[1] * 256 + b[2])
            val (limit, _) = Mode06Result.scale(tid, b[3] * 256 + b[4])
            Mode06Result(tid, cid and 0x7F, value, limit, cid and 0x80 != 0, unit)
        }
    }

    fun clearDtcs() = exclusive("Apagando códigos…") {
        val r = elm.clearDtcs()
        log("Apagar códigos: $r")
        elm.send("0100", 8000)
        _diag.value = DiagState()
        _status.value = if (Elm327.isError(r)) "A central recusou: $r" else "Códigos apagados"
    }

    /**
     * Teste da sonda lambda B1S1: lê só ela, o mais rápido possível, e conta quantas
     * vezes cruza 0,45 V. Uma sonda boa em malha fechada oscila sem parar entre ~0,1 e ~0,9 V.
     */
    fun lambdaTest(seconds: Int = 30) = exclusive("Testando a sonda lambda ($seconds s)…") {
        val status = elm.readPid("03")?.getOrNull(0)
        val rpm = elm.readPid("0C")?.let(Pids.RPM.decode)
        val volts = ArrayList<Double>()
        val end = System.currentTimeMillis() + seconds * 1000L
        elm.verbose = false
        try {
            while (System.currentTimeMillis() < end) {
                elm.readPid("14", retries = 0)?.getOrNull(0)?.let { volts += it * 0.005 }
                _busy.value = "Testando a sonda lambda… ${((end - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} s"
            }
        } finally {
            elm.verbose = true
        }
        if (volts.size < 5) {
            _lambda.value = LambdaResult(seconds, volts.size, 0.0, 0.0, 0.0, 0.0, 0, status == 2, rpm,
                "Poucas leituras — a central não respondeu à sonda.")
            return@exclusive
        }
        var switches = 0
        for (i in 1 until volts.size) if ((volts[i - 1] < 0.45) != (volts[i] < 0.45)) switches++
        val min = volts.min(); val max = volts.max(); val avg = volts.average()
        val rich = volts.count { it >= 0.45 } * 100 / volts.size
        val perSec = switches.toDouble() / seconds
        val closed = status == 2 || status == 16
        val verdict = when {
            rpm == null || rpm < 300 -> "O motor está desligado: a sonda só funciona com o motor quente e ligado."
            !closed -> "A central está em malha aberta (${Pids.fuelStatusText(status)}). Aqueça o motor e repita."
            max < 0.6 && min < 0.3 -> "A sonda ficou sempre baixa (pobre). Pode ser mistura pobre real ou sonda ruim."
            min > 0.45 -> "A sonda ficou sempre alta (rica). Coerente com excesso de combustível — ou sonda travada."
            max - min < 0.4 -> "A sonda quase não variou (%.2f–%.2f V): sinal de sonda lenta ou envelhecida.".format(min, max)
            perSec < 0.15 -> "A sonda oscila pouco (%.2f trocas/s). Repita a ~2.000 rpm; se continuar assim, ela está lenta.".format(perSec)
            else -> "A sonda está oscilando normalmente (%.2f trocas/s entre %.2f e %.2f V).".format(perSec, min, max)
        }
        _lambda.value = LambdaResult(seconds, volts.size, min, max, avg, perSec, rich, closed, rpm, verdict)
        log("Teste da sonda: ${volts.size} leituras, $switches trocas, %.2f–%.2f V".format(min, max))
    }

    /**
     * Única escrita que o OBD genérico permite: modo 04. Algumas centrais zeram as
     * adaptações junto com os códigos; medimos a correção longa antes e depois para saber.
     */
    fun resetAdaptation() = exclusive("Tentando zerar a adaptação…") {
        suspend fun ltftAvg(): Double? {
            val v = (1..3).mapNotNull { elm.readPid("07")?.let(Pids.LTFT.decode) }
            return if (v.isEmpty()) null else v.average()
        }
        val before = ltftAvg()
        val r = elm.clearDtcs()
        log("Modo 04: $r")
        delay(1500)
        elm.send("0100", 8000)
        val after = ltftAvg()
        fun f(x: Double?) = x?.let { "%+.1f%%".format(it) } ?: "—"
        _resetResult.value = buildString {
            appendLine("Correção longa antes: ${f(before)}  →  depois: ${f(after)}")
            when {
                Elm327.isError(r) -> append("A central recusou o comando ($r).")
                before == null || after == null -> append("Não deu para comparar a correção longa.")
                kotlin.math.abs(after) < kotlin.math.abs(before) - 3 ->
                    append("A correção voltou para perto de zero: a adaptação foi zerada. Agora rode uns 15 minutos para a central reaprender o combustível.")
                else -> append("A correção não mudou: esta central guarda a adaptação mesmo após o modo 04. O comando só apagou os códigos e a prontidão.")
            }
        }
        _diag.value = DiagState()
    }

    fun runProbe() = exclusive("Testando o adaptador…") {
        val r = AdapterProbe(elm) { msg -> _busy.value = msg }.run()
        r.lines().forEach { log(it) }
        _report.value = r
    }

    fun sendRaw(cmd: String) {
        val c = cmd.trim().uppercase().replace(" ", "")
        if (c.isEmpty()) return
        exclusive("Enviando $c…") {
            val r = elm.send(c, timeoutMs = 5000)
            _terminal.update { (it + "> $c" + r.ifBlank { "(sem resposta)" }).takeLast(300) }
        }
    }

    // ------------------------------------------------------------- viagens

    private val tripDir get() = File(context.filesDir, "trips").apply { mkdirs() }

    fun refreshTrips() {
        _trips.value = tripDir.listFiles()?.sortedByDescending { it.name } ?: emptyList()
    }

    fun startRecording() {
        if (_recording.value != null) return
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(tripDir, "viagem-$stamp.csv")
        val cols = listOf("rpm", "speed", "coolant", "load", "throttle", "iat", "map", "advance",
            "stft", "ltft", "o2s1", "o2s2", "battery")
        file.writeText("hora," + cols.joinToString(",") + "\n")
        recordingStartedAt = System.currentTimeMillis()
        _recording.value = file
        if (_liveMode.value == LiveMode.OFF) setLive(LiveMode.DASHBOARD)
        recordJob = scope.launch {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
            while (isActive) {
                val v = _values.value
                val row = fmt.format(Date()) + "," + cols.joinToString(",") { k ->
                    v[k]?.let { "%.2f".format(Locale.US, it) } ?: ""
                }
                withContext(Dispatchers.IO) { file.appendText(row + "\n") }
                delay(1000)
            }
        }
        refreshTrips()
    }

    fun stopRecording() {
        recordJob?.cancel(); recordJob = null
        _recording.value = null
        refreshTrips()
    }

    fun deleteTrip(f: File) { f.delete(); refreshTrips() }

    // ------------------------------------------------------------- exportação

    fun exportLogFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "obd-log-$stamp.txt")
        file.writeText(buildString {
            appendLine("Scanner-Max — log exportado em $stamp")
            appendLine("Adaptador: ${_adapterName.value ?: "—"}")
            appendLine("Status: ${_status.value}")
            _report.value?.let { appendLine(); appendLine(it) }
            _lambda.value?.let { appendLine(); appendLine("Teste da sonda: $it") }
            _resetResult.value?.let { appendLine(); appendLine("Reset de adaptação: $it") }
            appendLine()
            appendLine("=== Log completo ===")
            fullLog.forEach { appendLine(it) }
        })
        return file
    }
}
