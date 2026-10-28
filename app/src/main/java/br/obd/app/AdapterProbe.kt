package br.obd.app

/**
 * Bateria de testes para descobrir o que o adaptador e o carro realmente suportam.
 * Só usa comandos de leitura: nada aqui grava configurações no adaptador nem
 * altera módulos do carro.
 */
class AdapterProbe(
    private val elm: Elm327,
    private val progress: (String) -> Unit,
) {
    private val r = StringBuilder()
    private var isCan = false
    private var is29bit = false

    private fun title(t: String) { r.append("\n=== $t ===\n") }
    private fun line(t: String) { r.append(t).append('\n') }

    suspend fun run(): String {
        r.clear()
        line("Relatório de capacidade do adaptador")
        line("Data: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(java.util.Date())}")

        step("Identificando o adaptador…") { identify() }
        step("Testando comandos AT…") { atSupport() }
        step("Contando módulos (ECUs)…") { ecus() }
        step("Lendo PIDs suportados…") { supportedPids() }
        step("Lendo VIN e identificação da ECU…") { vehicleInfo() }
        step("Medindo velocidade…") { benchmark() }
        if (isCan) {
            step("Escutando o barramento CAN…") { canMonitor() }
            if (!is29bit) step("Procurando módulos por UDS…") { udsScan() }
        } else {
            step("Testando serviços extras…") { extraServices() }
        }
        step("Restaurando configurações…") { restore() }
        progress("Teste concluído")
        return r.toString()
    }

    private suspend fun step(msg: String, block: suspend () -> Unit) {
        if (!elm.isConnected) {
            line("!! Pulado: adaptador desconectado ($msg)")
            return
        }
        progress(msg)
        try {
            block()
        } catch (e: Exception) {
            line("!! Falha nesta etapa: ${e.message}")
            runCatching { restore() }
        }
    }

    // ---------------------------------------------------------------------

    private suspend fun identify() {
        title("Identificação")
        line("ATI (versão relatada): ${elm.send("ATI")}")
        line("AT@1 (descrição): ${elm.send("AT@1")}")
        val sti = elm.send("STI")
        line("STI (chip STN/OBDLink): ${if (Elm327.isError(sti)) "não — não é um chip STN" else sti}")
        line("Protocolo: ${elm.send("ATDP")}")
        val dpn = elm.send("ATDPN").removePrefix("A")
        line("Número do protocolo: $dpn")
        isCan = dpn.firstOrNull() in listOf('6', '7', '8', '9', 'A', 'B', 'C')
        is29bit = dpn.firstOrNull() in listOf('7', '9')
        line("Barramento CAN: ${if (isCan) "sim (${if (is29bit) "29" else "11"} bits)" else "não"}")
        line("Tensão (ATRV): ${elm.send("ATRV")}")
    }

    private suspend fun atSupport() {
        title("Comandos AT (só leitura)")
        val cmds = linkedMapOf(
            "ATPPS" to "listar parâmetros programáveis",
            "ATIGN" to "ler entrada de ignição",
            "ATCS" to "estatísticas de erro do CAN",
            "ATBD" to "despejar o buffer interno",
            "ATFE" to "limpar eventos internos",
            "ATAMC" to "contador de atividade",
            "ATCRA" to "resetar filtro de recepção CAN",
            "ATCSM1" to "monitoramento silencioso CAN",
            "ATCFC1" to "controle de fluxo CAN automático",
        )
        var ok = 0
        for ((cmd, desc) in cmds) {
            val resp = elm.send(cmd)
            val supported = !resp.contains("?")
            if (supported) ok++
            line("${if (supported) "OK " else "-- "} $cmd ($desc): ${resp.lines().firstOrNull() ?: ""}")
        }
        line("Suportados: $ok de ${cmds.size}. Clones com chip falsificado costumam falhar em vários destes.")
    }

    private suspend fun ecus() {
        title("Módulos que respondem ao OBD")
        elm.send("ATH1")
        val resp = elm.send("0100", 4000)
        elm.send("ATH0")
        val headers = resp.lines().map { it.replace(" ", "") }
            .filter { it.length > 6 && !Elm327.isError(it) }
            .map { if (is29bit) it.take(8) else it.take(3) }
            .distinct()
        line("Respostas ao 0100: ${headers.size}")
        headers.forEach { line("  $it ${ecuName(it)}") }
    }

    private fun ecuName(h: String) = when (h) {
        "7E8", "18DAF110" -> "(motor)"
        "7E9", "18DAF118" -> "(câmbio)"
        "7EA" -> "(híbrido / outro)"
        else -> ""
    }

    private val supportedMode01 = mutableListOf<Int>()

    private suspend fun supportedPids() {
        title("PIDs suportados — modo 01 (dados ao vivo)")
        supportedMode01 += supported("01", "41")
        line("Total: ${supportedMode01.size}")
        progress("Lendo todos os ${supportedMode01.size} PIDs suportados…")
        for (pid in supportedMode01.filter { it % 0x20 != 0 }) {
            val hex = "%02X".format(pid)
            val data = elm.readPid(hex)
            val raw = data?.joinToString("") { "%02X".format(it) } ?: "sem resposta"
            line("  01$hex ${PID_NAMES[pid] ?: "(desconhecido)"}: $raw")
        }

        title("Modo 06 (testes de monitores)")
        val m06 = supported("06", "46")
        line(if (m06.isEmpty()) "Não suportado" else "MIDs: ${m06.joinToString { "%02X".format(it) }}")

        title("Modo 09 (informações do veículo)")
        val m09 = supported("09", "49")
        line(if (m09.isEmpty()) "Não suportado" else "InfoTypes: ${m09.joinToString { "%02X".format(it) }}")

        title("Códigos de falha")
        for ((mode, name) in listOf("03" to "confirmados", "07" to "pendentes", "0A" to "permanentes")) {
            val codes = elm.readDtcs(mode)
            line("Modo $mode ($name): ${codes.ifEmpty { listOf("nenhum") }.joinToString()}")
        }
    }

    /** Lê as máscaras de suporte (PID 00, 20, 40...) e devolve a lista de PIDs. */
    private suspend fun supported(mode: String, respMode: String): List<Int> {
        val result = mutableListOf<Int>()
        var base = 0
        while (base <= 0xE0) {
            val pidHex = "%02X".format(base)
            val resp = elm.send("$mode$pidHex", 4000)
            if (Elm327.isError(resp)) break
            val marker = "$respMode$pidHex"
            var mask = 0L
            var any = false
            resp.lines().map { it.replace(" ", "").replace(Regex("^[0-9A-F]:"), "") }
                .filter { it.startsWith(marker) }
                .forEach { l ->
                    val data = l.removePrefix(marker).take(8)
                    if (data.length == 8) { mask = mask or data.toLong(16); any = true }
                }
            if (!any) break
            for (i in 0 until 32) if (mask and (1L shl (31 - i)) != 0L) result += base + i + 1
            if (mask and 1L == 0L) break
            base += 0x20
        }
        return result
    }

    private suspend fun vehicleInfo() {
        title("Identificação do veículo")
        line("VIN (0902): ${ascii(elm.send("0902", 5000), "4902")}")
        line("Calibração (0904): ${ascii(elm.send("0904", 5000), "4904")}")
        line("Nome da ECU (090A): ${ascii(elm.send("090A", 5000), "490A")}")
        if (isCan && !is29bit) {
            elm.send("ATSH7E0")
            line("VIN por UDS (22F190 no motor): ${ascii(elm.send("22F190", 3000), "62F190", count = false)}")
            elm.send("ATSH7DF")
        }
    }

    private fun ascii(resp: String, prefix: String, count: Boolean = true): String {
        if (Elm327.isError(resp)) return "não disponível (${resp.lines().firstOrNull()})"
        val joined = Elm327.joinFrames(resp)
        // remove o prefixo da resposta e o byte de contagem/sequência que vem depois
        val data = joined.replace(Regex(prefix + if (count) "[0-9A-F]{2}" else ""), "")
        return data.chunked(2).mapNotNull { it.toIntOrNull(16) }
            .filter { it in 0x20..0x7E }
            .joinToString("") { it.toChar().toString() }
            .trim().ifEmpty { "vazio" }
    }

    private suspend fun benchmark() {
        title("Velocidade")
        val n = 20
        fun fmt(x: Double) = "%.1f".format(x)

        val (single, e1) = bench("010C", n)
        line("1 PID por pedido, ATAT1: ${fmt(single)} pedidos/s ($e1 erros em $n)")

        val (hinted, e2) = bench("010C1", n)
        line("Com dica de 1 resposta (\"010C1\"): ${if (e2 == n) "não suportado" else "${fmt(hinted)} pedidos/s ($e2 erros)"}")

        elm.send("ATAT2")
        val (at2, e3) = bench("010C", n)
        elm.send("ATAT1")
        line("1 PID por pedido, ATAT2 (mais agressivo): ${fmt(at2)} pedidos/s ($e3 erros)")

        if (isCan) {
            val test = elm.send("010C0D05", 3000)
            if (Elm327.isError(test) || !Elm327.joinFrames(test).contains("0D")) {
                line("Vários PIDs num pedido: não suportado (${test.lines().firstOrNull()})")
            } else {
                val (multi, e4) = bench("010C0D05", n)
                line("3 PIDs por pedido: ${fmt(multi)} pedidos/s = ${fmt(multi * 3)} PIDs/s ($e4 erros)")
                val (six, e5) = bench("010C0D05040B11", n)
                line("6 PIDs por pedido: ${if (e5 == n) "não suportado" else "${fmt(six * 6)} PIDs/s ($e5 erros)"}")
            }
        }
    }

    private suspend fun bench(cmd: String, n: Int): Pair<Double, Int> {
        elm.verbose = false
        var errors = 0
        val t0 = System.nanoTime()
        try {
            repeat(n) { if (Elm327.isError(elm.send(cmd))) errors++ }
        } finally {
            elm.verbose = true
        }
        val secs = (System.nanoTime() - t0) / 1e9
        return (n / secs) to errors
    }

    private suspend fun canMonitor() {
        title("Escuta passiva do barramento CAN (ATMA, 3 s)")
        elm.send("ATH1")
        val lines = elm.monitor("ATMA", 3000)
        elm.send("ATH0")
        val overflow = lines.any { it.contains("BUFFER FULL") }
        val frames = lines.filter { it.length > 4 && !it.contains(" ") && it.all { c -> c.isLetterOrDigit() } }
        val idLen = if (is29bit) 8 else 3
        val ids = frames.map { it.take(idLen) }.groupingBy { it }.eachCount()
        line("Quadros recebidos: ${frames.size} (${"%.0f".format(frames.size / 3.0)}/s)")
        line("Estourou o buffer: ${if (overflow) "sim — o adaptador não acompanha o barramento" else "não"}")
        line("IDs diferentes: ${ids.size}")
        ids.entries.sortedBy { it.key }.forEach { (id, c) -> line("  $id: $c quadros") }
        if (frames.isEmpty()) line("Nada recebido: o carro pode isolar o barramento na tomada OBD (gateway).")
        frames.take(10).forEach { line("  exemplo: $it") }
    }

    private suspend fun udsScan() {
        title("Módulos UDS (leitura 22F190 em 7E0–7E7)")
        for (addr in 0x7E0..0x7E7) {
            val h = "%03X".format(addr)
            elm.send("ATSH$h")
            val resp = elm.send("22F190", 1500).replace(" ", "")
            val state = when {
                resp.contains("62F190") -> "responde (VIN: ${ascii(resp, "62F190", count = false)})"
                resp.contains("7F22") -> "responde, mas recusa esse pedido (${resp.take(12)})"
                else -> "sem resposta"
            }
            line("  $h: $state")
        }
        elm.send("ATSH7DF")
    }

    /** Serviços do OBD que poucas centrais implementam: modo 08 (comandar testes) e modo 09. */
    private suspend fun extraServices() {
        title("Serviços extras")
        val m08 = elm.send("0800", 3000)
        line("Modo 08 (comandar testes): ${if (Elm327.isError(m08)) "não suportado (${m08.lines().firstOrNull()})" else m08.replace("\n", " | ")}")
        val m05 = elm.send("050100", 3000)
        line("Modo 05 (testes da sonda, legado): ${if (Elm327.isError(m05)) "não suportado (${m05.lines().firstOrNull()})" else m05.replace("\n", " | ")}")
        for (info in listOf("08", "0B", "0C")) {
            val r = elm.send("09$info", 4000)
            line("Modo 09 InfoType $info: ${r.replace("\n", " | ")}")
        }
    }

    /**
     * Módulos VW na linha K. (Fora do teste padrão: no Gol G5 com este clone, o ATIIA é
     * ignorado e quem responde é sempre a ECU do motor.) Cada módulo é "acordado" pelo próprio endereço:
     *  - init lento (5 baud, protocolo 4): os key bytes dizem qual protocolo o módulo fala
     *    (01 8A = KWP1281, que o ELM327 não suporta; xx 8F = KWP2000, que ele suporta);
     *  - init rápido (protocolo 5), só KWP2000.
     * Depois de acordar, pede só a identificação (1A 9B). Nada é gravado.
     */
    private suspend fun kwpScan() {
        title("Módulos VW pela linha K (KWP2000, só leitura)")
        line("Cada endereço leva alguns segundos: a linha K exige pausas entre sessões.")
        val modules = linkedMapOf(
            "01" to "motor",
            "03" to "ABS",
            "09" to "central elétrica",
            "15" to "airbag",
            "17" to "painel",
            "46" to "central de conforto",
        )
        for ((addr, name) in modules) {
            progress("Linha K: módulo $addr ($name)…")
            line("\n[$addr] $name")
            for ((proto, label) in listOf("4" to "init lento", "5" to "init rápido")) {
                closeSession()
                elm.send("ATSP$proto")
                if (proto == "4") elm.send("ATIIA$addr")
                elm.send("ATSH81${addr}F1")
                val resp = elm.send("1A9B", timeoutMs = 8000)
                val kw = elm.send("ATKW")
                val result = when {
                    resp.replace(" ", "").contains("5A9B") ->
                        "RESPONDE em KWP2000 — identificação: ${ascii(resp, "5A9B", count = false)}"
                    resp.replace(" ", "").startsWith("7F1A") ->
                        "responde em KWP2000, mas recusou a identificação (${resp.take(10)})"
                    Elm327.isError(resp) -> "sem comunicação (${resp.lines().firstOrNull()})"
                    else -> "resposta inesperada: ${resp.lines().firstOrNull()}"
                }
                line("  $label: $result")
                if (!Elm327.isError(kw)) line("  key bytes: $kw ${keyBytesInfo(kw)}")
                if (result.startsWith("RESPONDE")) break // não precisa testar o outro init
            }
        }
        closeSession()
        // volta ao protocolo do OBD genérico
        elm.send("ATD")
        elm.send("ATSP0")
    }

    private suspend fun closeSession() {
        elm.send("ATPC")
        // a ECU só aceita um novo init depois que a sessão anterior expira (P3 máx. = 5 s)
        kotlinx.coroutines.delay(5500)
    }

    private fun keyBytesInfo(kw: String): String {
        val k = kw.replace(" ", "").uppercase()
        return when {
            k.contains("018A") -> "(KWP1281 — protocolo VW que o ELM327 não fala)"
            k.endsWith("8F") -> "(KWP2000)"
            k.contains("0808") || k.contains("9494") -> "(ISO 9141 / OBD genérico)"
            else -> ""
        }
    }

    private suspend fun restore() {
        elm.verbose = true
        if (!elm.isConnected) return
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) elm.send(c)
        if (isCan && !is29bit) elm.send("ATSH7DF")
        if (!isCan) elm.send("0100", timeoutMs = 8000) // reabre a sessão do OBD genérico
    }

    companion object {
        val PID_NAMES = mapOf(
            0x01 to "status dos monitores / MIL",
            0x02 to "DTC do freeze frame",
            0x03 to "status do sistema de combustível",
            0x04 to "carga calculada do motor",
            0x05 to "temperatura do líquido de arrefecimento",
            0x06 to "correção de combustível curto prazo B1",
            0x07 to "correção de combustível longo prazo B1",
            0x08 to "correção de combustível curto prazo B2",
            0x09 to "correção de combustível longo prazo B2",
            0x0A to "pressão de combustível",
            0x0B to "pressão do coletor (MAP)",
            0x0C to "rotação (RPM)",
            0x0D to "velocidade",
            0x0E to "avanço de ignição",
            0x0F to "temperatura do ar de admissão",
            0x10 to "fluxo de ar (MAF)",
            0x11 to "posição da borboleta",
            0x12 to "ar secundário",
            0x13 to "sondas lambda presentes",
            0x14 to "sonda lambda B1S1",
            0x15 to "sonda lambda B1S2",
            0x16 to "sonda lambda B1S3",
            0x17 to "sonda lambda B1S4",
            0x1C to "padrão OBD do veículo",
            0x1F to "tempo desde a partida",
            0x21 to "distância com a luz de injeção acesa",
            0x22 to "pressão do trilho (relativa)",
            0x23 to "pressão do trilho (direta)",
            0x24 to "sonda lambda larga B1S1",
            0x2C to "EGR comandada",
            0x2D to "erro da EGR",
            0x2E to "purga do cânister comandada",
            0x2F to "nível de combustível",
            0x30 to "aquecimentos desde que apagou DTCs",
            0x31 to "distância desde que apagou DTCs",
            0x32 to "pressão de vapor do EVAP",
            0x33 to "pressão barométrica",
            0x34 to "sonda lambda larga B1S1 (corrente)",
            0x3C to "temperatura do catalisador B1S1",
            0x3D to "temperatura do catalisador B2S1",
            0x41 to "status dos monitores neste ciclo",
            0x42 to "tensão do módulo",
            0x43 to "carga absoluta",
            0x44 to "lambda comandado",
            0x45 to "borboleta relativa",
            0x46 to "temperatura ambiente",
            0x47 to "borboleta absoluta B",
            0x49 to "pedal do acelerador D",
            0x4A to "pedal do acelerador E",
            0x4C to "atuador da borboleta comandado",
            0x4D to "tempo com a luz de injeção acesa",
            0x4E to "tempo desde que apagou DTCs",
            0x51 to "tipo de combustível",
            0x52 to "percentual de etanol",
            0x5A to "pedal do acelerador relativo",
            0x5B to "bateria híbrida",
            0x5C to "temperatura do óleo",
            0x5D to "ponto de injeção",
            0x5E to "consumo de combustível",
            0x61 to "torque pedido pelo motorista",
            0x62 to "torque real",
            0x63 to "torque de referência",
            0x65 to "entradas/saídas auxiliares",
            0x66 to "MAF (sensor estendido)",
            0x67 to "temperatura do líquido (estendido)",
            0x68 to "temperatura de admissão (estendido)",
            0x6B to "temperatura da EGR",
            0x70 to "pressão do turbo comandada",
            0x73 to "pressão do escapamento",
            0x7C to "temperatura do filtro de partículas",
            0x8E to "torque de atrito do motor",
            0xA6 to "hodômetro",
        )
    }
}
