package br.obd.app.link

import br.obd.app.vag.KLinePort

/**
 * Um "ELM327 de mentira" para o cabo KKL: o cabo só liga o celular à linha K, então quem fala
 * o protocolo OBD2 (ISO 9141-2 ou ISO 14230-4 KWP2000) é o próprio app. Esta classe entende os
 * comandos de texto do ELM327 (AT…, 0100, 03…) e responde no mesmo formato, para o resto do app
 * funcionar igual com o cabo, o Bluetooth ou o Wi-Fi.
 *
 * Sem nada de Android: testável na JVM com uma linha K simulada.
 */
class KLineElm(
    private val port: KLinePort,
    private val log: (String) -> Unit = {},
    /** Duração de cada bit da inicialização a 5 baud (200 ms; menor só nos testes). */
    private val bitMs: Long = 200,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Proto(val num: String, val label: String) {
        ISO9141("3", "ISO 9141-2"),
        KWP_SLOW("4", "ISO 14230-4 (KWP 5BAUD)"),
        KWP_FAST("5", "ISO 14230-4 (KWP FAST)"),
    }

    // configurações do "ELM"
    private var echo = true
    private var spaces = true
    private var headers = false
    private var linefeeds = false
    /** "0" automático, "3", "4", "5"; os outros números são protocolos que o cabo não tem. */
    private var wanted = "0"

    private var session: Proto? = null
    private var lastIo = 0L
    /** Depois de uma falha, a central só aceita nova inicialização após alguns segundos. */
    private var failedAt = 0L

    val connected get() = session != null

    // ================================================================== comandos de texto

    /** Processa um comando (sem o \r) e devolve a resposta completa, terminada em "\r\r>". */
    @Synchronized
    fun handle(raw: String): String {
        val cmd = raw.trim().uppercase().replace(" ", "")
        val body = if (cmd.startsWith("AT")) at(cmd.substring(2)) else if (cmd.isEmpty()) "" else obd(cmd)
        val nl = if (linefeeds) "\r\n" else "\r"
        val out = StringBuilder()
        if (echo) out.append(raw.trim()).append(nl)
        if (body.isNotEmpty()) out.append(body.replace("\r", nl)).append(nl)
        out.append(nl).append('>')
        return out.toString()
    }

    private fun at(c: String): String = when {
        c == "Z" || c == "WS" || c == "D" -> {
            close(); echo = true; spaces = true; headers = false; linefeeds = false
            if (c == "D") "OK" else "\rELM327 v1.0 (cabo KKL)"
        }
        c == "I" -> "ELM327 v1.0 (cabo KKL)"
        c == "@1" -> "Cabo KKL (linha K direta)"
        c == "DP" -> (if (wanted == "0") "AUTO, " else "") + (session?.label ?: protoLabel(wanted))
        c == "DPN" -> (if (wanted == "0") "A" else "") + (session?.num ?: if (wanted == "0") "0" else wanted)
        c == "PC" -> { close(); "OK" }
        c.startsWith("SP") || c.startsWith("TP") -> {
            val n = c.drop(2).removePrefix("A").ifEmpty { "0" }
            if (n != wanted) close()
            wanted = n
            "OK"
        }
        c.startsWith("E") && c.length == 2 -> { echo = c[1] == '1'; "OK" }
        c.startsWith("S") && c.length == 2 && c[1] in "01" -> { spaces = c[1] == '1'; "OK" }
        c.startsWith("H") && c.length == 2 -> { headers = c[1] == '1'; "OK" }
        c.startsWith("L") && c.length == 2 -> { linefeeds = c[1] == '1'; "OK" }
        // ajustes que não fazem sentido aqui, mas o app pode mandar: aceita sem fazer nada
        c.startsWith("AT") || c.startsWith("ST") || c.startsWith("SW") || c == "M0" || c == "M1" -> "OK"
        else -> "?" // RV (sem medidor de tensão), monitor, CAN etc.
    }

    private fun protoLabel(n: String) = Proto.entries.firstOrNull { it.num == n }?.label ?: if (n == "0") "AUTO" else "?"

    private fun obd(cmd: String): String {
        var hex = cmd
        if (!hex.all { it in "0123456789ABCDEF" }) return "?"
        if (hex.length % 2 == 1) hex = hex.dropLast(1) // "0100 1": dígito de quantidade de respostas
        if (hex.isEmpty() || hex.length > 14) return "?"
        val data = hex.chunked(2).map { it.toInt(16) }.toIntArray()
        if (wanted !in listOf("0", "3", "4", "5")) return "UNABLE TO CONNECT"
        if (!ensureSession()) return "BUS INIT: ...ERROR"
        return try {
            val frames = request(data)
            if (frames.isEmpty()) "NO DATA" else frames.joinToString("\r") { format(it) }
        } catch (e: KLineIoException) {
            log("Linha K: ${e.message}")
            close()
            "NO DATA"
        }
    }

    private fun format(f: IntArray): String {
        val bytes = if (headers) f.toList() else f.drop(3).dropLast(1)
        return bytes.joinToString(if (spaces) " " else "") { "%02X".format(it) }
    }

    // ================================================================== sessão

    /** Mantém a central acordada (sem pedidos por 5 s ela encerra a sessão). */
    @Synchronized
    fun keepAlive() {
        if (session == null || clock() - lastIo < KEEPALIVE_MS) return
        try { request(intArrayOf(0x01, 0x00)) } catch (e: KLineIoException) { log("Linha K: sessão encerrada (${e.message})"); close() }
    }

    @Synchronized
    fun close() {
        session = null
        runCatching { port.setBreak(false) }
    }

    private fun ensureSession(): Boolean {
        if (session != null && clock() - lastIo < P3_MAX) return true
        session = null
        // depois de uma falha, a central precisa de ~2,5 s de linha em repouso
        val rest = 2600 - (clock() - failedAt)
        if (failedAt > 0 && rest > 0) Thread.sleep(rest)
        val order = when (wanted) {
            "3", "4" -> listOf(false)   // só 5 baud
            "5" -> listOf(true)          // só rápida
            else -> listOf(false, true)  // automático: 5 baud (ISO 9141 e KWP) e depois rápida
        }
        for (fast in order) {
            val p = try { if (fast) fastInit() else slowInit() } catch (e: KLineIoException) {
                log("Linha K: inicialização ${if (fast) "rápida" else "5 baud"} falhou (${e.message})")
                failedAt = clock()
                port.setBreak(false)
                null
            }
            if (p != null) {
                session = p
                failedAt = 0
                log("Linha K: conectado em ${p.label}")
                return true
            }
            if (fast == false && order.size > 1) Thread.sleep(2600)
        }
        return false
    }

    /** Inicialização a 5 baud no endereço 33 (OBD2): serve para ISO 9141-2 e KWP2000 lento. */
    private fun slowInit(): Proto {
        port.setBaud(BAUD)
        port.setBreak(false)
        Thread.sleep(bitMs * 2) // W5: linha em repouso
        port.purge()
        val addr = 0x33
        val levels = ArrayList<Boolean>()
        levels += false // start
        var ones = 0
        for (i in 0 until 7) { val b = (addr shr i) and 1 == 1; levels += b; if (b) ones++ }
        levels += ones % 2 == 0 // paridade ímpar
        levels += true // stop
        log("Linha K: inicialização 5 baud (endereço 33)…")
        val t0 = System.nanoTime()
        levels.forEachIndexed { i, high ->
            port.setBreak(!high)
            sleepUntil(t0 + (i + 1) * bitMs * 1_000_000L)
        }
        port.purge()
        val sync = port.read(400)
        if (sync != 0x55) throw KLineIoException("sincronismo esperado 55, veio ${hex(sync)}")
        val kb1 = port.read(30)
        val kb2 = port.read(30)
        if (kb1 < 0 || kb2 < 0) throw KLineIoException("key bytes não chegaram")
        Thread.sleep(30) // W4
        writeEcho(kb2.inv() and 0xFF)
        val inv = port.read(60)
        if (inv != (addr.inv() and 0xFF)) throw KLineIoException("confirmação esperada CC, veio ${hex(inv)}")
        log("Linha K: key bytes %02X %02X".format(kb1, kb2))
        lastIo = clock()
        Thread.sleep(P3_MIN)
        return if (kb2 == 0x8F) Proto.KWP_SLOW else Proto.ISO9141
    }

    /** Inicialização rápida do KWP2000: 25 ms em baixo, 25 ms em alto e StartCommunication. */
    private fun fastInit(): Proto {
        port.setBaud(BAUD)
        port.setBreak(false)
        Thread.sleep(300)
        port.purge()
        log("Linha K: inicialização rápida (KWP2000)…")
        val t0 = System.nanoTime()
        port.setBreak(true); sleepUntil(t0 + 25_000_000L)
        port.setBreak(false); sleepUntil(t0 + 50_000_000L)
        val req = intArrayOf(0xC1, 0x33, 0xF1, 0x81)
        sendFrame(req + checksum(req), iso = false)
        val resp = readFrames(Proto.KWP_FAST, firstMs = 300)
        if (resp.none { it.size >= 5 && it[3] == 0xC1 }) throw KLineIoException("sem resposta ao StartCommunication")
        lastIo = clock()
        Thread.sleep(P3_MIN)
        return Proto.KWP_FAST
    }

    // ================================================================== mensagens

    private fun request(data: IntArray): List<IntArray> {
        val p = session ?: throw KLineIoException("sem sessão")
        val wait = P3_MIN - (clock() - lastIo)
        if (wait > 0) Thread.sleep(wait)
        val hdr = if (p == Proto.ISO9141) intArrayOf(0x68, 0x6A, 0xF1) else intArrayOf(0xC0 or data.size, 0x33, 0xF1)
        val msg = hdr + data
        sendFrame(msg + checksum(msg), iso = p == Proto.ISO9141)
        var frames = readFrames(p, firstMs = 300)
        // KWP: "aguarde" (7F xx 78) e a resposta de verdade vem depois
        var tries = 0
        while (p != Proto.ISO9141 && frames.isNotEmpty() && frames.all { it.size > 5 && it[3] == 0x7F && it[5] == 0x78 } && tries++ < 10) {
            frames = readFrames(p, firstMs = 5000)
        }
        lastIo = clock()
        return frames
    }

    /**
     * Envia uma mensagem sem esperar o eco de cada byte (em cabos CH340 essa espera passa dos 20 ms
     * permitidos entre bytes e a central descarta a mensagem). ISO 9141-2 exige 5 ms entre bytes;
     * o KWP2000 aceita tudo de uma vez. O eco é conferido depois.
     */
    private fun sendFrame(frame: IntArray, iso: Boolean) {
        port.purge()
        if (iso) for (b in frame) { port.write(b); Thread.sleep(P4) } else port.writeAll(frame)
        for (b in frame) {
            val e = port.read(100)
            if (e != b) throw KLineIoException("eco do cabo esperado ${hex(b)}, veio ${hex(e)}")
        }
    }

    /** Lê tudo o que as centrais mandarem até a linha ficar em silêncio, e separa em mensagens. */
    private fun readFrames(p: Proto, firstMs: Int): List<IntArray> {
        val buf = ArrayList<Int>()
        var b = port.read(firstMs)
        while (b >= 0) {
            buf += b
            b = port.read(SILENCE_MS)
        }
        return split(buf, p)
    }

    private fun split(buf: List<Int>, p: Proto): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = 0
        while (i + 4 < buf.size) {
            val end = if (p == Proto.ISO9141) {
                // ISO 9141 não diz o tamanho: a mensagem acaba onde a soma bate e começa outra (ou acaba tudo)
                (i + 4 until minOf(buf.size, i + 12)).firstOrNull { e ->
                    (buf.subList(i, e).sum() and 0xFF) == buf[e] && (e == buf.lastIndex || buf[e + 1] == 0x48)
                }
            } else {
                val len = buf[i] and 0x3F
                (i + 3 + len).takeIf { len > 0 && it < buf.size && (buf.subList(i, it).sum() and 0xFF) == buf[it] }
            }
            if (end == null) { i++; continue } // byte perdido: procura o próximo começo
            out += buf.subList(i, end + 1).toIntArray()
            i = end + 1
        }
        return out
    }

    private fun writeEcho(b: Int) {
        port.write(b)
        val e = port.read(60)
        if (e != b) throw KLineIoException("eco do cabo esperado ${hex(b)}, veio ${hex(e)}")
    }

    private fun checksum(m: IntArray) = m.sum() and 0xFF

    private fun sleepUntil(targetNanos: Long) {
        while (true) {
            val left = targetNanos - System.nanoTime()
            if (left <= 0) return
            if (left > 2_000_000) Thread.sleep((left - 1_000_000) / 1_000_000) else Thread.yield()
        }
    }

    private fun hex(b: Int) = if (b < 0) "--" else "%02X".format(b)

    companion object {
        const val BAUD = 10400
        const val P3_MIN = 60L        // pausa mínima entre a resposta e o próximo pedido
        const val P3_MAX = 4500L      // a central encerra a sessão depois de 5 s sem pedidos
        const val KEEPALIVE_MS = 3000L
        const val P4 = 5L             // pausa entre bytes enviados
        const val SILENCE_MS = 60     // sem bytes por esse tempo = as centrais terminaram de responder
    }
}

class KLineIoException(msg: String) : Exception(msg)
