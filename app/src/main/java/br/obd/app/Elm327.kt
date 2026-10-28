package br.obd.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Conversa com o ELM327 por Bluetooth Clássico (SPP).
 * Cada comando é texto terminado em \r; o adaptador responde e termina com '>'.
 */
class Elm327(private val log: (String) -> Unit) {

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val lock = Mutex() // um comando por vez: o ELM327 não aceita pedidos sobrepostos

    /** Quando false, os comandos não aparecem no log (usado nos benchmarks). */
    var verbose = true

    val isConnected get() = socket?.isConnected == true

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        val sock = try {
            device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() }
        } catch (e: Exception) {
            // muitos clones só aceitam o canal RFCOMM 1 direto
            log("SPP padrão falhou (${e.message}), tentando canal 1…")
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            (m.invoke(device, 1) as BluetoothSocket).also { it.connect() }
        }
        socket = sock
        input = sock.inputStream
        output = sock.outputStream
    }

    /** Configuração inicial recomendada para leitura rápida e fácil de interpretar. */
    suspend fun init(): String {
        send("ATZ", timeoutMs = 3000)  // reset
        val version = send("ATI")      // versão relatada (clones dizem v1.5 ou v2.1)
        setupDefaults()
        send("0100", timeoutMs = 8000) // força a detecção agora (a primeira vez demora)
        val proto = send("ATDP")
        log("Protocolo: $proto")
        return version
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
        withContext(Dispatchers.IO) {
            val inp = input ?: error("Não conectado")
            val out = output ?: error("Não conectado")
            while (inp.available() > 0) inp.read() // descarta lixo pendente
            out.write("$cmd\r".toByteArray())
            out.flush()

            val sb = StringBuilder()
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) {
                    val c = inp.read().toChar()
                    if (c == '>') break
                    sb.append(c)
                } else {
                    Thread.sleep(2)
                }
            }
            val resp = clean(sb.toString())
            if (verbose) log("> $cmd  ←  ${resp.replace("\n", " | ")}")
            resp
        }
    }

    /**
     * Comando de monitoramento (ATMA, ATMR...): o adaptador fica despejando mensagens
     * até receber qualquer caractere. Escuta por [durationMs] e depois interrompe.
     */
    suspend fun monitor(cmd: String, durationMs: Long): List<String> = lock.withLock {
        withContext(Dispatchers.IO) {
            val inp = input ?: error("Não conectado")
            val out = output ?: error("Não conectado")
            while (inp.available() > 0) inp.read()
            out.write("$cmd\r".toByteArray())
            out.flush()

            val sb = StringBuilder()
            var ended = false
            val end = System.currentTimeMillis() + durationMs
            while (System.currentTimeMillis() < end) {
                if (inp.available() > 0) {
                    val c = inp.read().toChar()
                    if (c == '>') { ended = true; break } // parou sozinho (ex.: BUFFER FULL)
                    sb.append(c)
                } else Thread.sleep(1)
            }
            if (!ended) {
                out.write("\r".toByteArray()); out.flush()
                val stop = System.currentTimeMillis() + 2000
                while (System.currentTimeMillis() < stop) {
                    if (inp.available() > 0) {
                        val c = inp.read().toChar()
                        if (c == '>') break
                        sb.append(c)
                    } else Thread.sleep(2)
                }
            }
            val lines = clean(sb.toString()).lines().filter { it.isNotBlank() }
            log("> $cmd  ←  ${lines.size} linhas em ${durationMs} ms")
            lines
        }
    }

    private fun clean(raw: String) = raw
        .replace("SEARCHING...", "")
        .replace("\r", "\n")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString("\n")

    /** Lê um PID do modo 01 e devolve os bytes de dados (A, B, C...) ou null. */
    suspend fun readPid(pid: String): IntArray? {
        val resp = send("01$pid").replace(" ", "")
        val marker = "41$pid"
        val line = resp.lines().firstOrNull { it.startsWith(marker) } ?: return null
        return line.removePrefix(marker).chunked(2)
            .mapNotNull { it.toIntOrNull(16) }.toIntArray()
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
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

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
