package br.obd.app

import br.obd.app.link.Transport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Conversa com o ELM327 (ou compatível) por qualquer meio: Bluetooth, BLE, Wi-Fi, USB
 * ou o simulador. Cada comando é texto terminado em \r; o adaptador responde e termina com '>'.
 */
class Elm327(private val log: (String) -> Unit) {

    private var link: Transport? = null
    private val lock = Mutex() // um comando por vez: o ELM327 não aceita pedidos sobrepostos

    /** Quando false, os comandos não aparecem no log (usado nos benchmarks). */
    var verbose = true

    @Volatile private var alive = false
    val isConnected get() = alive && link?.isOpen == true

    /** Hora do último comando trocado com o adaptador (para o keep-alive). */
    @Volatile var lastIoAt = 0L
        private set

    /** Perfil do adaptador descoberto na inicialização. */
    var profile = AdapterProfile()
        private set

    suspend fun connect(t: Transport) = withContext(Dispatchers.IO) {
        close()
        t.open()
        link = t
        alive = true
    }

    /** Executa E/S no transporte; se a conexão caiu, fecha tudo e avisa com uma mensagem clara. */
    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        log("Conexão perdida: ${e.message}")
        close()
        throw IOException("Conexão com o adaptador perdida", e)
    }

    /** Configuração inicial recomendada para leitura rápida e fácil de interpretar. */
    /**
     * [forcedProtocol]: "0" = automático, ou o número do ATSP (1–9, A). [extra]: comandos AT
     * que o usuário quer mandar na conexão (separados por ; ou linha), para adaptadores e carros
     * que precisam de ajuste fino.
     */
    suspend fun init(forcedProtocol: String = "0", extra: String = ""): String {
        send("ATZ", timeoutMs = 3000)  // reset
        val version = send("ATI")      // versão relatada (clones dizem v1.5 ou v2.1)
        setupDefaults()
        profile = AdapterProfile.detect(this, link?.kind)
        log("Adaptador: ${profile.summary}")
        if (forcedProtocol != "0") send("ATSP$forcedProtocol")
        extra.split(';', '\n').map { it.trim().uppercase() }.filter { it.isNotEmpty() }.forEach { send(it, 3000) }
        // força a detecção agora (a primeira vez demora)
        var first = send("0100", timeoutMs = 8000)
        // a busca automática falha em alguns carros/clones: tenta protocolo por protocolo
        if (forcedProtocol == "0" && !first.replace(" ", "").contains("4100")) {
            log("Detecção automática não achou o protocolo; testando um a um…")
            for (n in listOf("6", "8", "7", "9", "5", "4", "3", "1", "2")) {
                send("ATSP$n")
                first = send("0100", timeoutMs = 6000)
                if (first.replace(" ", "").contains("4100")) { log("Protocolo $n respondeu"); break }
            }
            if (!first.replace(" ", "").contains("4100")) send("ATSP0")
        }
        val proto = send("ATDP")
        log("Protocolo: $proto")
        return version.lines().lastOrNull { it.isNotBlank() } ?: version
    }

    /** Volta às configurações que o app usa (depois de testes que mexem nelas). */
    suspend fun setupDefaults() {
        send("ATE0")  // sem eco
        send("ATL0")  // sem quebras de linha extras
        send("ATS0")  // sem espaços nos bytes
        send("ATH0")  // sem cabeçalhos
        send("ATAT1") // timeout adaptativo
        send("ATSP0") // detecta o protocolo sozinho
    }

    suspend fun send(cmd: String, timeoutMs: Long = 2000): String = lock.withLock {
        withContext(Dispatchers.IO) { io {
            val l = link ?: throw IOException("Não conectado")
            while (l.available() > 0) l.read() // descarta lixo pendente
            l.write("$cmd\r".toByteArray())

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (l.available() > 0) {
                    val c = l.read().toChar()
                    if (c == '>') break
                    sb.append(c)
                } else {
                    Thread.sleep(2)
                }
            }
            lastIoAt = System.currentTimeMillis()
            val resp = clean(sb.toString())
            if (verbose) log("> $cmd  ←  ${resp.replace("\n", " | ")}")
            resp
        } }
    }

    /**
     * Comando de monitoramento (ATMA, ATMR...): o adaptador fica despejando mensagens
     * até receber qualquer caractere. Escuta por [durationMs] e depois interrompe.
     */
    suspend fun monitor(cmd: String, durationMs: Long): List<String> = lock.withLock {
        withContext(Dispatchers.IO) { io {
            val l = link ?: throw IOException("Não conectado")
            while (l.available() > 0) l.read()
            l.write("$cmd\r".toByteArray())

            val sb = StringBuilder()
            var ended = false
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                if (l.available() > 0) {
                    val c = l.read().toChar()
                    if (c == '>') { ended = true; break } // parou sozinho (ex.: BUFFER FULL)
                    sb.append(c)
                } else Thread.sleep(1)
            }
            if (!ended) {
                l.write("\r".toByteArray())
                val stop = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < stop) {
                    if (l.available() > 0) {
                        val c = l.read().toChar()
                        if (c == '>') break
                        sb.append(c)
                    } else Thread.sleep(2)
                }
            }
            val lines = clean(sb.toString()).lines().filter { it.isNotBlank() }
            log("> $cmd  ←  ${lines.size} linhas em ${durationMs} ms")
            lines
        } }
    }

    private fun clean(raw: String) = raw
        .replace("SEARCHING...", "")
        .replace("\r", "\n")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")

    /** Lê um PID do modo 01 e devolve os bytes de dados (A, B, C...) ou null. */
    suspend fun readPid(pid: String, retries: Int = 1): IntArray? {
        repeat(retries + 1) {
            val resp = send("01$pid").replace(" ", "")
            val marker = "41$pid"
            val line = resp.lines().firstOrNull { it.startsWith(marker) }
            // na linha K um "NO DATA" de vez em quando é normal: tenta de novo
            if (line != null) return line.removePrefix(marker).chunked(2)
                .mapNotNull { it.toIntOrNull(16) }.toIntArray()
        }
        return null
    }

    suspend fun batteryVoltage(): String = send("ATRV")

    /** Códigos de falha. mode = "03" confirmados, "07" pendentes, "0A" permanentes. */
    suspend fun readDtcs(mode: String = "03"): List<String> {
        val respPrefix = (mode.toInt(16) + 0x40).toString(16).uppercase()
        val resp = send(mode, timeoutMs = 5000)
        if (resp.contains("NO DATA") || resp.contains("?")) return emptyList()

        val codes = mutableListOf<String>()
        for (raw in resp.lines()) {
            // respostas multi-frame do CAN vêm como "0:43..." "1:...", e uma linha só com o tamanho
            var line = raw.replace(" ", "").replace(Regex("^[0-9A-F]:"), "")
            if (line.length <= 3) continue
            if (line.startsWith(respPrefix)) line = line.removePrefix(respPrefix)
            // no CAN o primeiro byte é a quantidade de códigos; nos outros protocolos não existe
            if ((line.length / 2) % 2 == 1) line = line.drop(2)
            line.chunked(4).filter { it.length == 4 && it != "0000" }
                .forEach { codes += decodeDtc(it) }
        }
        return codes.distinct()
    }

    suspend fun clearDtcs(): String = send("04", timeoutMs = 5000)

    fun close() {
        alive = false
        runCatching { link?.close() }
        link = null
    }

    companion object {
        fun decodeDtc(hex: String): String {
            val v = hex.toInt(16)
            val letter = "PCBU"[(v shr 14) and 0x3]
            val d1 = (v shr 12) and 0x3
            return "$letter$d1${hex.substring(1).uppercase()}"
        }

        /** Junta uma resposta multi-frame (CAN com ATH0) em uma string hex só. */
        fun joinFrames(resp: String): String = resp.lines()
            .map { it.replace(" ", "") }
            .filter { it.length > 3 } // descarta a linha que só traz o tamanho total
            .joinToString("") { it.replace(Regex("^[0-9A-F]:"), "") }

        fun isError(resp: String) = resp.isBlank() || resp.contains("?") ||
            listOf("NO DATA", "ERROR", "UNABLE", "STOPPED", "BUS INIT").any { resp.contains(it) }
    }
}
