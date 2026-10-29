package br.obd.app

import br.obd.app.usb.UsbKLinePort
import br.obd.app.vag.KLineException
import br.obd.app.vag.MeasureCell
import br.obd.app.vag.VagDtc
import br.obd.app.vag.VagInit
import br.obd.app.vag.VagSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Comunicação com as centrais VW pelo cabo KKL (USB). Tudo que toca a porta roda numa
 * única thread, para nenhum pedido se misturar com o keep-alive.
 */
object VagManager {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kline").apply { priority = Thread.MAX_PRIORITY } }
        .asCoroutineDispatcher()
    private val lock = Mutex()
    private val scope get() = ObdManager.scope

    private val _status = MutableStateFlow("Cabo não conectado")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _session = MutableStateFlow<VagInit?>(null)
    val session: StateFlow<VagInit?> = _session.asStateFlow()

    private val _ident = MutableStateFlow<List<String>>(emptyList())
    val ident: StateFlow<List<String>> = _ident.asStateFlow()

    private val _group = MutableStateFlow<Pair<Int, List<MeasureCell>>?>(null)
    val group: StateFlow<Pair<Int, List<MeasureCell>>?> = _group.asStateFlow()

    private val _dtcs = MutableStateFlow<List<VagDtc>?>(null)
    val dtcs: StateFlow<List<VagDtc>?> = _dtcs.asStateFlow()

    private val _liveGroup = MutableStateFlow<Int?>(null)
    val liveGroup: StateFlow<Int?> = _liveGroup.asStateFlow()

    private var port: UsbKLinePort? = null
    private var vag: VagSession? = null
    private var keepAliveJob: Job? = null
    private var liveJob: Job? = null

    private fun log(msg: String) = scope.launch { ObdManager.log("[VW] $msg") }

    fun cableDescription(): String? =
        UsbKLinePort.findDrivers(ObdManager.context).firstOrNull()?.let { UsbKLinePort.describe(it.device) }

    /** Abre o cabo e acorda a central no endereço indicado (01 = motor). */
    fun connect(address: Int) = scope.launch {
        if (_busy.value != null) return@launch
        _busy.value = "Conectando ao endereço %02X…".format(address)
        try {
            // o cabo só pode ser usado por um de cada vez: libera a conexão OBD2 pelo USB, se houver
            if (ObdManager.link.value?.kind == br.obd.app.link.LinkKind.USB && ObdManager.connected.value) {
                log("Liberando o cabo da conexão OBD2…")
                ObdManager.disconnect()
                kotlinx.coroutines.delay(800)
            }
            disconnectNow()
            val driver = UsbKLinePort.findDrivers(ObdManager.context).firstOrNull()
                ?: throw IllegalStateException("Nenhum cabo USB encontrado. Plugue o cabo KKL pelo OTG.")
            val p = UsbKLinePort.open(ObdManager.context, driver)
            port = p
            _status.value = "Cabo aberto: ${UsbKLinePort.describe(driver.device)}"
            log("Cabo aberto: ${UsbKLinePort.describe(driver.device)}")
            val s = VagSession(p) { log(it) }
            vag = s
            val init = withContext(io) { lock.withLock { s.connect(address) } }
            _session.value = init
            _status.value = "Conectado à central %02X · %s · %d baud".format(init.address, init.protocol, init.baud)
            _busy.value = "Lendo a identificação…"
            val id = withContext(io) { lock.withLock { s.readIdent() } }
            _ident.value = id
            id.forEach { log("Identificação: $it") }
            startKeepAlive()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Falhou: ${e.message}")
            _status.value = "Falhou: ${e.message}"
            disconnectNow()
        } finally {
            _busy.value = null
        }
    }

    fun readGroup(group: Int) = op("Lendo o grupo $group…") { s ->
        _group.value = group to s.readGroup(group)
    }

    fun readDtcs() = op("Lendo códigos de falha…") { s ->
        val d = s.readDtcs()
        _dtcs.value = d
        log("Códigos: ${if (d.isEmpty()) "nenhum" else d.joinToString { it.code.toString() }}")
    }

    /** Lê o grupo sem parar (até ~5 vezes por segundo), para acompanhar ao vivo. */
    fun setLiveGroup(group: Int?) {
        _liveGroup.value = group
        liveJob?.cancel()
        if (group == null) return
        liveJob = scope.launch {
            while (isActive && _liveGroup.value == group && _session.value != null) {
                try {
                    val cells = withContext(io) { lock.withLock { vag!!.also { it.trace = false }.readGroup(group) } }
                    _group.value = group to cells
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Leitura ao vivo parou: ${e.message}")
                    _liveGroup.value = null
                    lost(e)
                    break
                } finally {
                    vag?.trace = true
                }
                delay(50)
            }
        }
    }

    fun disconnect() = scope.launch { disconnectNow(); _status.value = "Desconectado" }

    /** Fecha tudo mesmo se quem chamou estiver sendo cancelado (ex.: a leitura ao vivo). */
    private suspend fun disconnectNow() = withContext(NonCancellable) {
        liveJob?.cancel(); _liveGroup.value = null
        keepAliveJob?.cancel()
        val s = vag; val p = port
        vag = null; port = null
        if (s != null) withContext(io) { lock.withLock { runCatching { s.end() } } }
        p?.close()
        _session.value = null
    }

    private fun op(label: String, block: (VagSession) -> Unit) = scope.launch {
        val s = vag ?: run { _status.value = "Conecte à central primeiro"; return@launch }
        if (_busy.value != null) return@launch
        _busy.value = label
        try {
            withContext(io) { lock.withLock { block(s) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Falhou: ${e.message}")
            lost(e)
        } finally {
            _busy.value = null
        }
    }

    /** Se a central parou de responder, a sessão acabou: é preciso inicializar de novo. */
    private suspend fun lost(e: Exception) {
        if (e is KLineException && e.message?.contains("recusou") == true) {
            _status.value = "A central recusou: ${e.message}"
            return
        }
        _status.value = "Sessão perdida (${e.message}). Conecte de novo."
        disconnectNow()
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            while (isActive && _session.value != null) {
                delay(300)
                val s = vag ?: break
                if (_liveGroup.value != null || s.idleMs < 500) continue
                try {
                    withContext(io) {
                        lock.withLock {
                            s.trace = false
                            try { s.keepAlive() } finally { s.trace = true }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Keep-alive falhou: ${e.message}")
                    lost(e)
                    break
                }
            }
        }
    }
}
