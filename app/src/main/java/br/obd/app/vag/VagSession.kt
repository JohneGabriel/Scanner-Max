package br.obd.app.vag

/*
 * Protocolos de diagnóstico da VW pela linha K, sem nada de Android:
 * este arquivo vai junto para a versão Windows.
 *
 *  - Inicialização a 5 baud: o endereço da central é "desenhado" na linha K bit a bit,
 *    200 ms por bit, ligando e desligando o sinal de break do conversor USB-serial.
 *  - KW1281: blocos [tamanho, contador, título, dados..., 03]. Cada byte (menos o 03 final)
 *    precisa ser confirmado pelo outro lado com o seu complemento (~byte).
 *  - KWP2000 (ISO 14230): mensagens [formato, destino, origem, dados..., checksum].
 *
 * O cabo KKL é meio-duplex: tudo que enviamos volta como eco e precisa ser descartado.
 */

/** Porta serial ligada à linha K (cabo KKL). */
interface KLinePort {
    fun setBaud(baud: Int)
    /** true = linha em nível baixo (break); false = repouso (alto). */
    fun setBreak(on: Boolean)
    fun write(b: Int)
    /** Lê um byte, ou -1 se o tempo acabar. */
    fun read(timeoutMs: Int): Int
    fun purge()
    fun close()
}

class KLineException(msg: String) : Exception(msg)

enum class VagProtocol { KW1281, KWP2000 }

data class VagInit(val address: Int, val baud: Int, val kw1: Int, val kw2: Int, val protocol: VagProtocol)

/** Uma célula de bloco de medição: fórmula + dois bytes. */
data class MeasureCell(val formula: Int, val a: Int, val b: Int)

data class VagDtc(val code: Int, val status: Int)

class VagSession(
    private val port: KLinePort,
    private val log: (String) -> Unit,
) {
    var init: VagInit? = null
        private set
    private var counter = 0
    private var identKw1281: List<String> = emptyList()
    private var lastMsg = 0L

    /** Registro detalhado dos bytes (útil para depurar o primeiro contato). */
    var trace = true

    // ================================================================== inicialização

    /** Acorda a central no [address] a 5 baud. Tenta 10400 e depois 9600 baud. */
    fun connect(address: Int): VagInit {
        var lastError: Exception? = null
        for (baud in listOf(10400, 9600)) {
            try {
                val r = initAt(address, baud)
                init = r
                return r
            } catch (e: KLineException) {
                lastError = e
                log("Tentativa a $baud baud falhou: ${e.message}")
                port.setBreak(false)
                Thread.sleep(2600) // a central só aceita nova inicialização depois de ~2,5 s em repouso
            }
        }
        throw KLineException("A central %02X não respondeu (${lastError?.message})".format(address))
    }

    private fun initAt(address: Int, baud: Int): VagInit {
        port.setBaud(baud)
        port.setBreak(false)
        Thread.sleep(400) // linha em repouso antes de começar
        port.purge()

        // start (baixo) + 7 bits de dados, do menos significativo para o mais + paridade ímpar + stop (alto)
        val levels = ArrayList<Boolean>() // true = alto
        levels += false
        var ones = 0
        for (i in 0 until 7) {
            val bit = (address shr i) and 1 == 1
            levels += bit
            if (bit) ones++
        }
        levels += ones % 2 == 0 // paridade ímpar
        levels += true
        log("Inicialização 5 baud no endereço %02X a %d baud…".format(address, baud))
        val t0 = System.nanoTime()
        levels.forEachIndexed { i, high ->
            port.setBreak(!high)
            sleepUntil(t0 + (i + 1) * 200_000_000L)
        }
        port.purge() // o break gera lixo na recepção

        val sync = port.read(500)
        if (sync != 0x55) throw KLineException("sincronismo esperado 55, veio ${hex(sync)}")
        val kw1 = port.read(100)
        val kw2 = port.read(100)
        if (kw1 < 0 || kw2 < 0) throw KLineException("key bytes não chegaram (${hex(kw1)} ${hex(kw2)})")
        log("Sincronismo 55 a $baud baud · key bytes %02X %02X".format(kw1, kw2))

        Thread.sleep(30) // W4: 25–50 ms antes de devolver o complemento do KW2
        writeEcho(kw2.inv() and 0xFF)

        val protocol = if ((kw2 and 0x7F) == 0x0F) VagProtocol.KWP2000 else VagProtocol.KW1281
        if (protocol == VagProtocol.KWP2000) {
            val ack = port.read(100)
            if (ack != (address.inv() and 0xFF)) {
                throw KLineException("KWP2000: esperado complemento do endereço, veio ${hex(ack)}")
            }
            Thread.sleep(60)
        }
        lastMsg = System.currentTimeMillis()
        val result = VagInit(address, baud, kw1, kw2, protocol)
        if (protocol == VagProtocol.KW1281) {
            // a central começa a mandar a identificação na hora e espera cada byte ser
            // confirmado em poucos ms: a leitura tem de acontecer aqui, sem pausa
            counter = 0
            init = result
            identKw1281 = receiveUntilAck().filter { it.title == T_ASCII }.map { ascii(it.data) }
        }
        return result
    }

    // ================================================================== funções de alto nível

    /** Identificação da central (número de peça, nome, codificação). */
    fun readIdent(): List<String> = when (proto()) {
        VagProtocol.KW1281 -> identKw1281 // lida durante a inicialização
        VagProtocol.KWP2000 -> {
            kwpRequest(intArrayOf(0x10, 0x89)) // sessão de diagnóstico VW
            val r = kwpRequest(intArrayOf(0x1A, 0x9B))
            listOf(ascii(r.drop(2).toIntArray()))
        }
    }

    /** Lê um bloco (grupo) de medição: até 4 células. */
    fun readGroup(group: Int): List<MeasureCell> = when (proto()) {
        VagProtocol.KW1281 -> {
            val blocks = request(T_GROUP_READ, intArrayOf(group and 0xFF))
            val b = blocks.firstOrNull { it.title == T_GROUP_RESPONSE }
                ?: throw KLineException("grupo $group não suportado (${blocks.joinToString { hex(it.title) }})")
            cells(b.data)
        }
        VagProtocol.KWP2000 -> {
            val r = kwpRequest(intArrayOf(0x21, group and 0xFF))
            cells(r.drop(2).toIntArray())
        }
    }

    /** Códigos de falha armazenados. */
    fun readDtcs(): List<VagDtc> = when (proto()) {
        VagProtocol.KW1281 -> {
            val out = ArrayList<VagDtc>()
            for (blk in request(T_DTC_READ, IntArray(0)).filter { it.title == T_DTC_RESPONSE }) {
                blk.data.toList().chunked(3).filter { it.size == 3 }.forEach { (hi, lo, st) ->
                    val code = hi * 256 + lo
                    if (code != 0xFFFF) out += VagDtc(code, st)
                }
            }
            out
        }
        VagProtocol.KWP2000 -> {
            val r = kwpRequest(intArrayOf(0x18, 0x02, 0xFF, 0x00))
            r.drop(2).chunked(3).filter { it.size == 3 }.map { (hi, lo, st) -> VagDtc(hi * 256 + lo, st) }
        }
    }

    /** Mantém a sessão viva quando não há pedidos (a central desiste após ~1 s em silêncio). */
    fun keepAlive() {
        when (proto()) {
            VagProtocol.KW1281 -> {
                sendBlock(T_ACK, IntArray(0))
                val b = recvBlock()
                if (b.title != T_ACK) log("Keep-alive: resposta inesperada ${hex(b.title)}")
            }
            VagProtocol.KWP2000 -> kwpRequest(intArrayOf(0x3E, 0x01))
        }
    }

    /** Encerra a sessão educadamente. */
    fun end() {
        runCatching {
            when (init?.protocol) {
                VagProtocol.KW1281 -> sendBlock(T_END, IntArray(0))
                VagProtocol.KWP2000 -> kwpRequest(intArrayOf(0x82))
                null -> Unit
            }
        }
        init = null
        port.setBreak(false)
    }

    val idleMs get() = System.currentTimeMillis() - lastMsg

    private fun proto() = init?.protocol ?: throw KLineException("não conectado à central")

    // ================================================================== KW1281

    data class Block(val counter: Int, val title: Int, val data: IntArray)

    private fun request(title: Int, data: IntArray): List<Block> {
        sendBlock(title, data)
        return receiveUntilAck()
    }

    /** Recebe blocos confirmando cada um com ACK, até a central responder com ACK (fim). */
    private fun receiveUntilAck(): List<Block> {
        val out = ArrayList<Block>()
        repeat(64) {
            val b = recvBlock()
            when (b.title) {
                T_ACK -> return out
                T_NAK -> throw KLineException("a central recusou o pedido (NAK ${b.data.joinToString(" ") { hex(it) }})")
                else -> {
                    out += b
                    sendBlock(T_ACK, IntArray(0))
                }
            }
        }
        throw KLineException("blocos demais sem fim")
    }

    private fun sendBlock(title: Int, data: IntArray) {
        counter = (counter + 1) and 0xFF
        val bytes = intArrayOf(data.size + 3, counter, title) + data
        if (trace) log("TX ${bytes.joinToString(" ") { hex(it) }} 03")
        Thread.sleep(2)
        for (b in bytes) sendAcked(b)
        writeEcho(0x03)
        lastMsg = System.currentTimeMillis()
    }

    private fun recvBlock(firstTimeoutMs: Int = 1200): Block {
        val len = port.read(firstTimeoutMs)
        if (len < 0) throw KLineException("a central não respondeu")
        if (len < 3) throw KLineException("tamanho de bloco inválido ${hex(len)}")
        ack(len)
        val body = IntArray(len - 1)
        for (i in body.indices) {
            val b = port.read(100)
            if (b < 0) throw KLineException("bloco incompleto (${i + 1} de ${len - 1})")
            ack(b)
            body[i] = b
        }
        val end = port.read(100)
        if (end != 0x03) throw KLineException("fim de bloco esperado 03, veio ${hex(end)}")
        counter = body[0]
        lastMsg = System.currentTimeMillis()
        val blk = Block(body[0], body[1], body.copyOfRange(2, body.size))
        if (trace) log("RX ${hex(len)} ${body.joinToString(" ") { hex(it) }} 03")
        return blk
    }

    /** Envia um byte e espera a central devolver o complemento. */
    private fun sendAcked(b: Int) {
        writeEcho(b)
        val c = port.read(100)
        if (c != (b.inv() and 0xFF)) throw KLineException("complemento de ${hex(b)} esperado ${hex(b.inv() and 0xFF)}, veio ${hex(c)}")
    }

    /** Confirma um byte recebido devolvendo o complemento. */
    private fun ack(b: Int) {
        Thread.sleep(1)
        writeEcho(b.inv() and 0xFF)
    }

    // ================================================================== KWP2000

    /** Envia um pedido e devolve os dados da resposta positiva (serviço + 0x40 em diante). */
    private fun kwpRequest(data: IntArray): IntArray {
        val wait = 60 - (System.currentTimeMillis() - lastMsg) // P3 mínimo entre mensagens
        if (wait > 0) Thread.sleep(wait)
        val target = init?.address ?: 0x01
        val msg = intArrayOf(0x80 or data.size, target, 0xF1) + data
        val frame = msg + (msg.sum() and 0xFF)
        if (trace) log("TX ${frame.joinToString(" ") { hex(it) }}")
        for (b in frame) { writeEcho(b); Thread.sleep(5) } // P4: 5–20 ms entre bytes
        repeat(10) {
            val r = kwpReceive()
            lastMsg = System.currentTimeMillis()
            if (r.isNotEmpty() && r[0] == 0x7F) {
                if (r.size >= 3 && r[2] == 0x78) return@repeat // "aguarde": a resposta vem depois
                throw KLineException("resposta negativa ${r.joinToString(" ") { hex(it) }}")
            }
            if (r.isEmpty() || r[0] != (data[0] + 0x40)) throw KLineException("resposta inesperada ${r.joinToString(" ") { hex(it) }}")
            return r
        }
        throw KLineException("a central pediu para aguardar demais")
    }

    private fun kwpReceive(): IntArray {
        val fmt = port.read(1000)
        if (fmt < 0) throw KLineException("a central não respondeu")
        var len = fmt and 0x3F
        val hdr = arrayListOf(fmt)
        if (fmt and 0xC0 != 0) { hdr += port.read(50); hdr += port.read(50) } // destino e origem
        if (len == 0) { len = port.read(50); hdr += len }
        val data = IntArray(len) { port.read(50) }
        val cs = port.read(50)
        if (data.any { it < 0 } || cs < 0) throw KLineException("resposta incompleta")
        val sum = (hdr.sum() + data.sum()) and 0xFF
        if (sum != cs) throw KLineException("checksum errado")
        if (trace) log("RX ${(hdr + data.toList() + cs).joinToString(" ") { hex(it) }}")
        return data
    }

    // ================================================================== utilidades

    private fun writeEcho(b: Int) {
        port.write(b)
        val e = port.read(50)
        if (e != b) throw KLineException("eco do cabo esperado ${hex(b)}, veio ${hex(e)}")
    }

    private fun cells(d: IntArray) =
        d.toList().chunked(3).filter { it.size == 3 }.map { (f, a, b) -> MeasureCell(f, a, b) }

    private fun ascii(d: IntArray) =
        d.filter { it in 0x20..0x7E }.joinToString("") { it.toChar().toString() }.trim()

    private fun sleepUntil(targetNanos: Long) {
        while (true) {
            val left = targetNanos - System.nanoTime()
            if (left <= 0) return
            if (left > 2_000_000) Thread.sleep((left - 1_000_000) / 1_000_000) else Thread.yield()
        }
    }

    companion object {
        const val T_ACK = 0x09
        const val T_NAK = 0x0A
        const val T_END = 0x06
        const val T_DTC_READ = 0x07
        const val T_DTC_CLEAR = 0x05
        const val T_DTC_RESPONSE = 0xFC
        const val T_ASCII = 0xF6
        const val T_GROUP_READ = 0x29
        const val T_GROUP_RESPONSE = 0xE7

        fun hex(b: Int) = if (b < 0) "--" else "%02X".format(b)
    }
}
