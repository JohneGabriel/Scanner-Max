package br.obd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import br.obd.app.link.BleTransport
import br.obd.app.link.BtClassicTransport
import br.obd.app.link.LinkKind
import br.obd.app.link.LinkTarget
import br.obd.app.link.SimTransport
import br.obd.app.link.Transport
import br.obd.app.link.UsbElmTransport
import br.obd.app.link.WifiTransport
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
    val mode06Can: List<Mode06Can> = emptyList(),
    val readAt: Long? = null,
)

/** Estatística simples de uma série de leituras. */
data class Stats(val avg: Double, val min: Double, val max: Double, val std: Double) {
    companion object {
        fun of(v: List<Double>): Stats? {
            if (v.isEmpty()) return null
            val avg = v.average()
            val std = kotlin.math.sqrt(v.sumOf { (it - avg) * (it - avg) } / v.size)
            return Stats(avg, v.min(), v.max(), std)
        }
    }
}

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

    /** Quando a leitura ao vivo recebeu a última resposta (a notificação usa para saber se o dado é velho). */
    @Volatile var lastLiveAt = 0L
        private set

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

    private val _resetResult = MutableStateFlow<String?>(null)
    val resetResult: StateFlow<String?> = _resetResult.asStateFlow()

    private val _vehicle = MutableStateFlow(VehicleInfo())
    /** Protocolo, PIDs suportados e chassi do carro conectado. */
    val vehicle: StateFlow<VehicleInfo> = _vehicle.asStateFlow()

    /** O carro aceita vários PIDs no mesmo pedido (só CAN). */
    @Volatile private var multiPid = false

    private val _fuelType = MutableStateFlow<Int?>(null)
    val fuelType: StateFlow<Int?> = _fuelType.asStateFlow()

    private val _report = MutableStateFlow<String?>(null)
    val report: StateFlow<String?> = _report.asStateFlow()

    private val _terminal = MutableStateFlow<List<String>>(emptyList())
    val terminal: StateFlow<List<String>> = _terminal.asStateFlow()

    private val misses = HashMap<String, Int>()
    private val _unavailable = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Sensores que a central diz suportar mas não respondem na prática (6 falhas seguidas
     * enquanto os outros respondem). Somem do painel até a próxima conexão.
     */
    val unavailable: StateFlow<Set<String>> = _unavailable.asStateFlow()

    private fun note(def: PidDef, ok: Boolean) {
        if (ok) { misses[def.key] = 0; return }
        val n = (misses[def.key] ?: 0) + 1
        misses[def.key] = n
        if (n >= 6 && def != Pids.RPM && def.key !in _unavailable.value) {
            _unavailable.value = _unavailable.value + def.key
            log("Sensor ${def.label} (PID ${def.pid}) não responde: escondido até reconectar")
        }
    }

    private val _adapter = MutableStateFlow(AdapterProfile())
    /** O que o adaptador conectado é e aceita. */
    val adapter: StateFlow<AdapterProfile> = _adapter.asStateFlow()

    private val _link = MutableStateFlow<LinkTarget?>(null)
    /** Adaptador da conexão atual (ou da última). */
    val link: StateFlow<LinkTarget?> = _link.asStateFlow()

    /** Viagem automática: começa quando o motor liga, termina 45 s depois de desligar. */
    val trip = TripTracker(
        onCheckpoint = { s, pts -> if (s.vehicleId?.startsWith("SMXDEMO") != true) TripStore.save(s, pts) },
    ) { s, pts ->
        if (s.vehicleId?.startsWith("SMXDEMO") == true) return@TripTracker
        TripStore.save(s, pts)
        Garage.addTrip(s.km, s.rawLiters)
        log("Viagem salva: %.1f km em %.0f min, %.2f L".format(s.km, s.durationSec / 60, s.liters))
    }

    private val fullLog = ArrayList<String>()
    private val _logTail = MutableStateFlow<List<String>>(emptyList())
    val logTail: StateFlow<List<String>> = _logTail.asStateFlow()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    val elm = Elm327 { msg -> scope.launch { log(msg) } }

    private var liveJob: Job? = null

    fun init(ctx: Context) {
        if (::context.isInitialized) return
        context = ctx.applicationContext
        Settings.init(context)
        Feedback.init(context)
        Garage.init(context)
        TripStore.init(context)
        TestRunner.init(context)
        Alerts.init(context)
        Maintenance.init(context)
        PerfRunner.init(context)
        CustomPids.init(context)
        Speech.init(context)
        _link.value = savedTarget
        loadDiag()
    }

    fun log(msg: String) {
        if (fullLog.size > 60_000) fullLog.subList(0, 20_000).clear()
        val line = "${timeFmt.format(Date())}  $msg"
        fullLog += line
        _logTail.value = (_logTail.value + line).takeLast(200)
    }

    // ------------------------------------------------------------- conexão

    private val prefs get() = context.getSharedPreferences("obd", Context.MODE_PRIVATE)

    /** Último adaptador usado (qualquer tipo). Migra o endereço Bluetooth das versões antigas. */
    val savedTarget: LinkTarget?
        get() = LinkTarget.decode(prefs.getString("target", null))
            ?: prefs.getString("device", null)?.let { LinkTarget(LinkKind.BT_CLASSIC, it, "ELM327") }

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BluetoothDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()
        return adapter.bondedDevices.toList()
            .sortedByDescending { looksLikeObd(it.name) }
    }

    fun bluetoothOn(): Boolean = context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true

    fun looksLikeObd(name: String?): Boolean {
        val n = (name ?: "").lowercase()
        return listOf("obd", "elm", "vlink", "v-link", "vgate", "icar", "veepeak", "konnwei", "scan", "car", "kiwi", "torque", "link")
            .any { n.contains(it) }
    }

    /** Carro selecionado antes da demonstração (volta a ser o atual ao sair). */
    private var beforeDemo: String? = null

    fun connect(target: LinkTarget) = scope.launch {
        if (_status.value.startsWith("Conectando")) return@launch
        if (target.kind == LinkKind.DEMO) beforeDemo = Garage.current.value?.id?.takeIf { !it.startsWith("SMXDEMO") }
        userDisconnected = false
        misses.clear(); _unavailable.value = emptySet()
        _vehicle.value = VehicleInfo()
        _values.value = emptyMap()
        Alerts.reset()
        EngineHealth.reset()
        if (target.kind == LinkKind.USB && !UsbElmTransport.ensurePermission(context)) {
            _status.value = "Permissão USB negada ou adaptador não encontrado"
            return@launch
        }
        connectNow(target)
    }

    /** true depois de "Desconectar": nenhuma rotina deve reconectar sozinha. */
    @Volatile private var userDisconnected = false

    private fun transportFor(t: LinkTarget): Transport = when (t.kind) {
        LinkKind.BT_CLASSIC -> {
            val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("Este aparelho não tem Bluetooth")
            if (!bt.isEnabled) throw IOException("O Bluetooth está desligado")
            BtClassicTransport(bt.getRemoteDevice(t.address)) { log(it) }
        }
        LinkKind.BLE -> BleTransport(context, t.address) { log(it) }
        LinkKind.WIFI -> WifiTransport(context, t.address)
        LinkKind.USB -> UsbElmTransport(context) { log(it) }
        LinkKind.DEMO -> SimTransport()
    }

    private suspend fun connectNow(target: LinkTarget): Boolean = try {
        _status.value = "Conectando a ${target.display}…"
        _link.value = target
        elm.close()
        elm.connect(transportFor(target))
        _status.value = "Inicializando o adaptador…"
        val version = elm.init(Settings.prefs.value.forcedProtocol, Settings.prefs.value.extraInit)
        _adapter.value = elm.profile
        if (target.kind != LinkKind.DEMO) prefs.edit().putString("target", target.encode()).apply()
        _adapterName.value = "${target.display} · $version"
        _connected.value = true
        _status.value = "Conectado"
        Feedback.start()
        startService()
        startHeartbeat()
        // primeira conexão com este carro: descobre o que ele oferece
        if (_vehicle.value.supported.isEmpty()) exclusive("Identificando o veículo…") { detectVehicle() }
        setLive(LiveMode.DASHBOARD)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        elm.close()
        _connected.value = false
        _status.value = "Falha ao conectar: ${e.message}"
        log("Falha ao conectar (${target.kind.label}): ${e.message}")
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

    private var heartbeatJob: Job? = null

    /**
     * O ELM327 (ou o módulo Bluetooth dele) derruba o link depois de uns 20 s sem tráfego:
     * no log, todo "Broken pipe" veio no primeiro comando depois de um silêncio. Com a tela
     * parada, manda um ATRV (só lê a tensão, não mexe na linha do carro) a cada 3 s.
     */
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (!_connected.value || _busy.value != null) continue
                if (System.currentTimeMillis() - elm.lastIoAt < 3000) continue
                try {
                    val was = elm.verbose
                    elm.verbose = false
                    try {
                        if (_adapter.value.canReadVoltage || _adapter.value.features.isEmpty()) readBattery() else elm.send("ATI")
                    } finally { elm.verbose = was }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!elm.isConnected) reconnect()
                }
            }
        }
    }

    fun disconnect() {
        userDisconnected = true
        heartbeatJob?.cancel(); heartbeatJob = null
        setLive(LiveMode.OFF)
        trip.finish()
        Alerts.reset()
        EngineHealth.reset()
        elm.close()
        _connected.value = false
        _status.value = "Desconectado"
        stopService()
        if (_link.value?.kind == LinkKind.DEMO) {
            Garage.current.value?.id?.takeIf { it.startsWith("SMXDEMO") }?.let { Garage.delete(it) }
            beforeDemo?.let { Garage.select(it) }
            _vehicle.value = VehicleInfo()
            _values.value = emptyMap()
            _diag.value = DiagState()
            loadDiag()
            _link.value = savedTarget
            _status.value = "Saiu da demonstração"
        }
    }

    private suspend fun reconnect(): Boolean {
        if (userDisconnected) return false
        _connected.value = false
        Feedback.lost()
        val target = _link.value ?: return false
        for (attempt in 1..3) {
            _status.value = "Conexão caiu — reconectando ($attempt/3)…"
            if (userDisconnected) return false
            if (connectNow(target)) return true
            delay(2000)
        }
        _status.value = "Não foi possível reconectar"
        trip.finish()
        stopService()
        return false
    }

    // ------------------------------------------------------------- leitura ao vivo

    /**
     * Plano de leitura: PID e de quantos em quantos ciclos ele é lido (1 = sempre).
     * Só entram os PIDs que o carro suporta.
     */
    private fun plan(mode: LiveMode): List<Pair<PidDef, Int>> {
        val v = _vehicle.value
        fun ok(d: PidDef) = d.key !in _unavailable.value && (if (v.supported.isEmpty()) d in BASIC else v.has(d))
        val base = listOf(
            Pids.RPM to 1, Pids.SPEED to 2, Pids.MAP to 3, Pids.MAF to 3, Pids.FUEL_RATE to 3, Pids.THROTTLE to 3,
            Pids.LOAD to 6, Pids.FUEL_STATUS to 8, Pids.STFT to 8, Pids.ADVANCE to 10, Pids.COOLANT to 12,
            Pids.LTFT to 15, Pids.IAT to 15, Pids.O2S1 to 20, Pids.FUEL_LEVEL to 40, Pids.ETHANOL to 60, Pids.OIL_TEMP to 30,
        )
        // sensores escolhidos na tela de gráficos são lidos em todo ciclo
        val focused = focus.mapNotNull { Pids.byKey[it] }.filter { ok(it) && base.none { b -> b.first == it } }.map { it to 1 }
        return base.filter { ok(it.first) }.map { (d, e) -> if (d.key in focus) d to 1 else d to e } + focused
    }

    private val BASIC = setOf(Pids.RPM, Pids.SPEED, Pids.COOLANT, Pids.THROTTLE, Pids.LOAD, Pids.MAP, Pids.IAT)

    /** Sensores fora do plano principal, lidos em rodízio para a lista "Todos os sensores". */
    private fun extras(planDefs: Set<PidDef>): List<PidDef> {
        val v = _vehicle.value
        return Pids.ALL.filter { v.has(it) && it !in planDefs && it.key !in _unavailable.value }
    }

    private var uiMode = LiveMode.OFF
    private var appVisible = true

    /** Chamado pela tela: com o app em segundo plano, mantém pelo menos o painel lendo. */
    fun setVisible(visible: Boolean) {
        appVisible = visible
        applyLive()
    }

    fun setLive(mode: LiveMode) {
        uiMode = mode
        applyLive()
    }

    private fun applyLive() {
        // conectado, a leitura nunca para: ela alimenta a viagem, os alertas e a notificação
        val mode = if (_connected.value && uiMode == LiveMode.OFF && !userDisconnected) LiveMode.DASHBOARD else uiMode
        _liveMode.value = mode
        if (mode == LiveMode.OFF) {
            liveJob?.cancel(); liveJob = null; return
        }
        if (liveJob?.isActive == true) return
        liveJob = scope.launch { liveLoop() }
    }

    private suspend fun liveLoop() {
        var tick = 0
        var rot = 0
        var t0 = System.currentTimeMillis()
        var requests = 0
        var emptyCycles = 0
        while (currentCoroutineContext().isActive && _liveMode.value != LiveMode.OFF) {
            if (!_connected.value || _busy.value != null) { delay(250); continue }
            val plan = plan(_liveMode.value)
            try {
                val due = plan.filterIndexed { i, (_, every) -> tick % every == i % every }.map { it.first }.toMutableList()
                // rodízio dos demais sensores: 1 por ciclo (2 em CAN, onde sobra velocidade)
                if (_liveMode.value == LiveMode.DASHBOARD) {
                    val ex = extras(plan.map { it.first }.toSet())
                    if (ex.isNotEmpty()) repeat(if (multiPid) 2 else if (tick % 2 == 0) 1 else 0) {
                        due += ex[rot++ % ex.size]
                    }
                }
                var answered = 0
                if (multiPid) {
                    // CAN: até 6 PIDs de tamanho fixo num pedido só
                    val (fixed, variable) = due.partition { it.bytes > 0 }
                    for (batch in fixed.chunked(6)) {
                        answered += readBatch(batch); requests++
                    }
                    for (d in variable) { if (readInto(d) != null) answered++; requests++ }
                } else {
                    for (d in due) { if (readInto(d) != null) answered++; requests++ }
                }
                if (tick % 10 == 5 && (_adapter.value.canReadVoltage || _adapter.value.features.isEmpty())) { readBattery(); requests++ }
                // luz de injeção e quantidade de códigos, de tempos em tempos (vira alerta se aumentar)
                if (tick % 150 == 20) {
                    elm.readPid("01", retries = 0)?.let { d ->
                        if (d.isNotEmpty()) _values.update { it + ("milCount" to (d[0] and 0x7F).toDouble()) + ("milOn" to if (d[0] and 0x80 != 0) 1.0 else 0.0) }
                    }
                    requests++
                }
                // sensores personalizados em rodízio (1 por ciclo no CAN, a cada 3 na linha K)
                val customs = CustomPids.list.value
                if (customs.isNotEmpty() && (multiPid || tick % 3 == 1)) {
                    val p = customs[(tick / (if (multiPid) 1 else 3)) % customs.size]
                    val (value, _) = CustomPids.read(elm, p, _vehicle.value.protocol.contains("29"))
                    if (value != null) _values.update { it + (p.key to value) }
                    requests++
                }
                tick++
                addSample()
                val now = System.currentTimeMillis()
                if (now - t0 >= 2000) {
                    _rate.value = requests * 1000.0 / (now - t0)
                    t0 = now; requests = 0
                }
                // Ignição desligada e religada: o Bluetooth continua de pé (a tomada OBD tem
                // 12 V direto), mas a sessão com a central morreu. Reabre.
                if (answered > 0) lastLiveAt = now
                val fresh = now - lastLiveAt < 8000
                trip.onTick(_values.value, fresh, Garage.current.value)
                Alerts.check(_values.value, fresh)
                EngineHealth.observe(_values.value, fresh)
                emptyCycles = if (answered == 0 && due.isNotEmpty()) emptyCycles + 1 else 0
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

    /** Lê vários PIDs num único pedido (CAN). Devolve quantos vieram. */
    private suspend fun readBatch(batch: List<PidDef>): Int {
        if (batch.size == 1) return if (readInto(batch[0]) != null) 1 else 0
        val resp = elm.send("01" + batch.joinToString("") { it.pid })
        val got = parseMulti(resp)
        var n = 0
        val upd = HashMap<String, Double>()
        val anyAnswer = got.isNotEmpty()
        for (d in batch) {
            val v = got[d.pidInt]?.let(d.decode)
            if (anyAnswer) note(d, v != null)
            if (v == null) continue
            upd[d.key] = v; n++
            if (d == Pids.LTFT) trackLtft(v)
        }
        if (upd.isNotEmpty()) _values.update { it + upd }
        return n
    }

    /** Separa a resposta de um pedido agrupado em PID -> bytes. */
    private fun parseMulti(resp: String): Map<Int, IntArray> {
        val bytes = Elm327.joinFrames(resp).chunked(2).mapNotNull { it.toIntOrNull(16) }
        val out = HashMap<Int, IntArray>()
        var i = bytes.indexOf(0x41)
        if (i < 0) return out
        i++
        while (i < bytes.size) {
            val def = Pids.byPid[bytes[i]] ?: break
            if (def.bytes == 0 || i + 1 + def.bytes > bytes.size) break
            out[bytes[i]] = bytes.subList(i + 1, i + 1 + def.bytes).toIntArray()
            i += 1 + def.bytes
            if (i < bytes.size && bytes[i] == 0x41) i++ // resposta de outra central
        }
        return out
    }

    /** Descobre protocolo, PIDs suportados, chassi e tipo de combustível. */
    private suspend fun detectVehicle() {
        val dp = elm.send("ATDP").removePrefix("AUTO,").removePrefix("AUTO").trim()
        val dpn = elm.send("ATDPN").removePrefix("A")
        val can = dpn.firstOrNull() in listOf('6', '7', '8', '9', 'A', 'B', 'C')
        val supported = HashSet<Int>()
        var base = 0
        while (base <= 0xE0) {
            val pidHex = "%02X".format(base)
            val resp = elm.send("01$pidHex", 4000)
            if (Elm327.isError(resp)) break
            var mask = 0L
            var any = false
            resp.lines().map { it.replace(" ", "").replace(Regex("^[0-9A-F]:"), "") }
                .filter { it.startsWith("41$pidHex") }
                .forEach { l ->
                    val data = l.removePrefix("41$pidHex").take(8)
                    if (data.length == 8) { mask = mask or data.toLong(16); any = true }
                }
            if (!any) break
            for (i in 0 until 32) if (mask and (1L shl (31 - i)) != 0L) supported += base + i + 1
            if (mask and 1L == 0L) break
            base += 0x20
        }
        val vin = readVin()
        val ecuName = readAscii("090A", "490A")
        val calId = readAscii("0904", "4904")
        val fuel = if (0x51 in supported) elm.readPid("51")?.getOrNull(0) else null
        multiPid = can && runCatching {
            parseMulti(elm.send("010C0D")).keys.containsAll(listOf(0x0C, 0x0D))
        }.getOrDefault(false)
        val info = VehicleInfo(protocolName(dp, dpn), can, supported, vin, fuel, ecuName, calId)
        _vehicle.value = info
        _fuelType.value = fuel
        // CAN aguenta temporização agressiva: mais leituras por segundo
        var aggressive = false
        if (can && elm.profile.has("ATAT2")) { elm.send("ATAT2"); aggressive = true }
        _adapter.value = elm.profile.copy(carProtocol = info.protocol, multiPid = multiPid, aggressiveTiming = aggressive)
        Garage.onIdentified(info, _link.value?.address ?: "?")
        Maintenance.load()
        loadDiag()
        log("Veículo: ${info.maker ?: "fabricante ?"} ${info.year ?: ""} · ${info.protocol} · " +
            "${supported.size} PIDs · VIN ${vin ?: "—"} · pedidos agrupados: ${if (multiPid) "sim" else "não"}")
    }

    /** Texto ASCII do modo 09 (nome da central, calibração). */
    private suspend fun readAscii(cmd: String, prefix: String): String? {
        val resp = elm.send(cmd, 4000)
        if (Elm327.isError(resp)) return null
        val hex = Elm327.joinFrames(resp).replace(Regex(prefix + "[0-9A-F]{2}"), "")
        return hex.chunked(2).mapNotNull { it.toIntOrNull(16) }.filter { it in 0x20..0x7E }
            .joinToString("") { it.toChar().toString() }.trim().takeIf { it.length >= 3 }
    }

    private suspend fun readVin(): String? {
        val resp = elm.send("0902", 5000)
        if (Elm327.isError(resp)) return null
        val hex = Elm327.joinFrames(resp).replace(Regex("4902[0-9A-F]{2}"), "")
        val txt = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .filter { it in 0x30..0x5A }.joinToString("") { it.toChar().toString() }
        return txt.takeLast(17).takeIf { it.length == 17 }
    }

    private fun protocolName(dp: String, dpn: String) = when (dpn.firstOrNull()) {
        '1' -> "J1850 PWM (Ford)"
        '2' -> "J1850 VPW (GM)"
        '3' -> "ISO 9141-2 (linha K)"
        '4' -> "KWP2000 lento (linha K)"
        '5' -> "KWP2000 rápido (linha K)"
        '6' -> "CAN 11 bits 500 kbps"
        '7' -> "CAN 29 bits 500 kbps"
        '8' -> "CAN 11 bits 250 kbps"
        '9' -> "CAN 29 bits 250 kbps"
        else -> dp.ifBlank { "—" }
    }

    /** Fecha a sessão antiga com a central e refaz a inicialização até ela responder. */
    private suspend fun recoverBus() {
        log("Central parou de responder — reabrindo a comunicação")
        _status.value = "Carro sem resposta — reabrindo a comunicação…"
        _values.value = emptyMap()
        while (currentCoroutineContext().isActive && _liveMode.value != LiveMode.OFF && elm.isConnected) {
            if (_busy.value != null) { delay(500); continue }
            trip.onTick(emptyMap(), false, Garage.current.value)
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
        val v = elm.readPid(def.pid)?.let(def.decode)
        // só conta como falha do sensor se a central está respondendo a outros
        if (System.currentTimeMillis() - lastLiveAt < 5000) note(def, v != null)
        if (v == null) return null
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
        v.forEach { (k, x) ->
            val q = series.getOrPut(k) { ArrayDeque() }
            q.addLast(x); while (q.size > 400) q.removeFirst()
        }
        _seriesTick.value = _seriesTick.value + 1
    }

    /** Histórico recente de todos os valores lidos (para os gráficos). */
    val series = HashMap<String, ArrayDeque<Double>>()
    private val _seriesTick = MutableStateFlow(0)
    val seriesTick: StateFlow<Int> = _seriesTick.asStateFlow()

    /** Chaves lidas com prioridade (tela de gráficos). */
    @Volatile var focus: Set<String> = emptySet()

    /** Lê um PID personalizado uma vez, para testar a fórmula. */
    fun testCustom(p: CustomPid, onResult: (Double?, String) -> Unit) = exclusive("Testando ${p.name}…") {
        val (v, raw) = CustomPids.read(elm, p, _vehicle.value.protocol.contains("29"))
        onResult(v, raw)
    }

    // ------------------------------------------------------------- operações exclusivas

    /** Roda [block] com a leitura ao vivo pausada e um aviso de "ocupado" na tela. */
    fun exclusive(label: String, block: suspend () -> Unit): Job? {
        if (!_connected.value) { _status.value = "Conecte primeiro"; return null }
        if (_busy.value != null) return null
        return scope.launch {
            _busy.value = label
            try {
                if (!elm.isConnected && !reconnect()) return@launch
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                log("Falhou: ${e.message}")
                _busy.value = "A conexão caiu — reconectando…"
                if (reconnect()) _status.value = "Reconectado. A operação foi interrompida: rode de novo."
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
            val v = _vehicle.value
            for (def in listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT, Pids.LOAD, Pids.STFT, Pids.LTFT,
                Pids.MAP, Pids.MAF, Pids.ADVANCE, Pids.IAT, Pids.THROTTLE, Pids.FUEL_STATUS)
                .filter { v.supported.isEmpty() || v.has(it) }) {
                val d = freezePid(def.pid) ?: continue
                val v = def.decode(d) ?: continue
                val txt = if (def == Pids.FUEL_STATUS) Pids.fuelStatusText(v.toInt())
                else "%.1f %s".format(v, def.unit)
                freeze += def.label to txt
            }
        }

        // modo 06: testes que a própria central faz (formato muda entre linha K e CAN)
        val m06 = mutableListOf<Mode06Result>()
        val m06c = mutableListOf<Mode06Can>()
        if (_vehicle.value.isCan) m06c += mode06Can()
        else for (tid in 1..8) m06 += mode06(tid)

        _diag.value = DiagState(stored, pending, readiness, freezeDtc, freeze, m06, m06c, System.currentTimeMillis())
        saveDiag(_diag.value)
        _values.update { it + ("milCount" to (readiness?.dtcCount ?: stored.size).toDouble()) }
        if (stored.isNotEmpty() || readiness?.milOn == true) Feedback.warning() else Feedback.success()
    }

    /** Guarda o último diagnóstico no perfil do carro (aparece desconectado e no relatório). */
    private fun saveDiag(d: DiagState) {
        val o = org.json.JSONObject().apply {
            put("stored", org.json.JSONArray(d.stored.orEmpty())); put("pending", org.json.JSONArray(d.pending.orEmpty()))
            put("at", d.readAt ?: 0L); put("freezeDtc", d.freezeDtc)
            d.readiness?.let { r ->
                put("mil", r.milOn); put("count", r.dtcCount); put("diesel", r.diesel)
                put("mons", org.json.JSONArray().apply { r.monitors.forEach { (n, ok) -> put(org.json.JSONArray().put(n).put(ok)) } })
            }
        }
        Garage.update { it.copy(lastDiag = o.toString()) }
    }

    private fun loadDiag() {
        if (_diag.value.readAt != null && _connected.value) return
        val raw = Garage.current.value?.lastDiag ?: return
        runCatching {
            val o = org.json.JSONObject(raw)
            fun arr(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            val mons = o.optJSONArray("mons")
            val readiness = if (o.has("mil")) Readiness(o.optBoolean("mil"), o.optInt("count"), o.optBoolean("diesel"),
                (0 until (mons?.length() ?: 0)).map { i -> mons!!.getJSONArray(i).let { it.getString(0) to it.getBoolean(1) } }) else null
            _diag.value = DiagState(arr("stored"), arr("pending"), readiness, o.optStringOrNull("freezeDtc"), emptyList(),
                emptyList(), emptyList(), o.optLong("at").takeIf { it > 0 })
        }
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

    /** Modo 06 no CAN: descobre os monitores (MIDs) suportados e lê os testes de cada um. */
    private suspend fun mode06Can(): List<Mode06Can> {
        val mids = ArrayList<Int>()
        var base = 0
        while (base <= 0xE0) {
            val h = "%02X".format(base)
            val bytes = Elm327.joinFrames(elm.send("06$h", 3000)).chunked(2).mapNotNull { it.toIntOrNull(16) }
            val i = bytes.windowed(2).indexOfFirst { it[0] == 0x46 && it[1] == base }
            if (i < 0 || i + 6 > bytes.size) break
            val mask = (bytes[i + 2].toLong() shl 24) or (bytes[i + 3].toLong() shl 16) or
                (bytes[i + 4].toLong() shl 8) or bytes[i + 5].toLong()
            for (b in 0 until 32) if (mask and (1L shl (31 - b)) != 0L) mids += base + b + 1
            if (mask and 1L == 0L) break
            base += 0x20
        }
        val out = ArrayList<Mode06Can>()
        for (mid in mids.filter { it % 0x20 != 0 }.take(40)) {
            val bytes = Elm327.joinFrames(elm.send("06%02X".format(mid), 3000)).chunked(2).mapNotNull { it.toIntOrNull(16) }
            var i = bytes.indexOf(0x46)
            if (i < 0) continue
            i++
            // cada teste: MID TID UAS valor(2) mínimo(2) máximo(2)
            while (i + 9 <= bytes.size && bytes[i] == mid) {
                out += Mode06Can(
                    mid, bytes[i + 1], bytes[i + 2],
                    bytes[i + 3] * 256 + bytes[i + 4], bytes[i + 5] * 256 + bytes[i + 6], bytes[i + 7] * 256 + bytes[i + 8],
                )
                i += 9
            }
        }
        return out
    }

    fun clearDtcs() = exclusive("Apagando códigos…") {
        val r = elm.clearDtcs()
        log("Apagar códigos: $r")
        elm.send("0100", 8000)
        _diag.value = DiagState()
        Garage.update { it.copy(lastDiag = null) }
        Alerts.dismiss("mil")
        _values.update { it - "milCount" }
        _status.value = if (Elm327.isError(r)) "A central recusou: $r" else "Códigos apagados"
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

    // ------------------------------------------------------------- exportação

    fun exportLogFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "obd-log-$stamp.txt")
        file.writeText(buildString {
            appendLine("Scanner Max — log exportado em $stamp")
            appendLine("Adaptador: ${_adapterName.value ?: "—"}")
            appendLine("Status: ${_status.value}")
            _report.value?.let { appendLine(); appendLine(it) }
            appendLine("Adaptador: ${_adapter.value.summary}")
            _adapter.value.features.forEach { appendLine("  ${it.cmd} (${it.label}, v${it.since}): ${if (it.ok) "sim" else "não"}") }
            _resetResult.value?.let { appendLine(); appendLine("Reset de adaptação: $it") }
            appendLine()
            appendLine("=== Log completo ===")
            fullLog.forEach { appendLine(it) }
        })
        return file
    }
}
