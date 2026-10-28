package br.obd.app

/** Definição de um PID do modo 01: como pedir e como transformar os bytes em número. */
data class PidDef(
    val pid: String,
    val key: String,
    val label: String,
    val unit: String,
    val decode: (IntArray) -> Double?,
)

object Pids {
    private fun a(d: IntArray) = d.getOrNull(0)?.toDouble()
    private fun ab(d: IntArray) = if (d.size >= 2) d[0] * 256.0 + d[1] else null
    private fun trim(x: Int) = (x - 128) * 100.0 / 128

    val RPM = PidDef("0C", "rpm", "Rotação", "rpm") { d -> ab(d)?.div(4) }
    val SPEED = PidDef("0D", "speed", "Velocidade", "km/h") { a(it) }
    val COOLANT = PidDef("05", "coolant", "Temp. motor", "°C") { a(it)?.minus(40) }
    val LOAD = PidDef("04", "load", "Carga", "%") { a(it)?.times(100)?.div(255) }
    val THROTTLE = PidDef("11", "throttle", "Borboleta", "%") { a(it)?.times(100)?.div(255) }
    val IAT = PidDef("0F", "iat", "Temp. admissão", "°C") { a(it)?.minus(40) }
    val MAP = PidDef("0B", "map", "Pressão coletor", "kPa") { a(it) }
    val ADVANCE = PidDef("0E", "advance", "Avanço ignição", "°") { a(it)?.div(2)?.minus(64) }
    val STFT = PidDef("06", "stft", "Correção curta", "%") { d -> d.getOrNull(0)?.let { trim(it) } }
    val LTFT = PidDef("07", "ltft", "Correção longa", "%") { d -> d.getOrNull(0)?.let { trim(it) } }
    val O2S1 = PidDef("14", "o2s1", "Sonda B1S1", "V") { d -> d.getOrNull(0)?.times(0.005) }
    val O2S2 = PidDef("15", "o2s2", "Sonda B1S2", "V") { d -> d.getOrNull(0)?.times(0.005) }
    val FUEL_STATUS = PidDef("03", "fuelStatus", "Sistema de combustível", "") { a(it) }

    val ALL = listOf(RPM, SPEED, COOLANT, LOAD, THROTTLE, IAT, MAP, ADVANCE, STFT, LTFT, O2S1, O2S2, FUEL_STATUS)
    val byKey = ALL.associateBy { it.key }

    fun fuelStatusText(v: Int?): String = when (v) {
        null -> "—"
        1 -> "Malha aberta: motor frio"
        2 -> "Malha fechada: usando a sonda"
        4 -> "Malha aberta: carga alta ou desaceleração"
        8 -> "Malha aberta: falha no sistema"
        16 -> "Malha fechada com falha na sonda"
        0 -> "Motor parado"
        else -> "Código $v"
    }

    fun fuelTypeText(v: Int?): String = when (v) {
        1 -> "Gasolina"; 3 -> "Etanol"; 4 -> "Diesel"
        9 -> "Flex rodando com gasolina"
        11 -> "Flex rodando com etanol"
        null -> "—"
        else -> "Código $v"
    }
}

/** Prontidão (PID 01) para motor a gasolina/flex. */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    /** nome do monitor -> true se completo */
    val monitors: List<Pair<String, Boolean>>,
)

object ReadinessDecoder {
    fun decode(d: IntArray): Readiness? {
        if (d.size < 4) return null
        val (a, b, c, dd) = d
        val mons = mutableListOf<Pair<String, Boolean>>()
        // contínuos: bits 0-2 disponíveis, bits 4-6 incompletos
        val cont = listOf("Falhas de ignição", "Sistema de combustível", "Componentes")
        cont.forEachIndexed { i, n -> if (b and (1 shl i) != 0) mons += n to (b and (1 shl (i + 4)) == 0) }
        val noncont = listOf(
            "Catalisador", "Catalisador aquecido", "Sistema EVAP (cânister)", "Ar secundário",
            "Ar-condicionado", "Sonda lambda", "Aquecimento da sonda", "EGR",
        )
        noncont.forEachIndexed { i, n -> if (c and (1 shl i) != 0) mons += n to (dd and (1 shl i) == 0) }
        return Readiness(a and 0x80 != 0, a and 0x7F, mons)
    }
}

/** Descrição em português dos códigos de falha mais comuns. */
object DtcInfo {
    private val known = mapOf(
        "P0010" to "Atuador do comando de válvulas (banco 1): circuito",
        "P0016" to "Sincronismo virabrequim/comando fora do esperado",
        "P0030" to "Aquecedor da sonda lambda B1S1: circuito",
        "P0031" to "Aquecedor da sonda lambda B1S1: tensão baixa",
        "P0032" to "Aquecedor da sonda lambda B1S1: tensão alta",
        "P0036" to "Aquecedor da sonda lambda B1S2: circuito",
        "P0037" to "Aquecedor da sonda lambda B1S2: tensão baixa",
        "P0038" to "Aquecedor da sonda lambda B1S2: tensão alta",
        "P0100" to "Sensor de fluxo de ar (MAF): circuito",
        "P0105" to "Sensor de pressão do coletor (MAP): circuito",
        "P0106" to "Sensor MAP: leitura fora da faixa esperada",
        "P0107" to "Sensor MAP: tensão baixa",
        "P0108" to "Sensor MAP: tensão alta",
        "P0110" to "Sensor de temperatura do ar: circuito",
        "P0112" to "Sensor de temperatura do ar: tensão baixa",
        "P0113" to "Sensor de temperatura do ar: tensão alta",
        "P0115" to "Sensor de temperatura do motor: circuito",
        "P0116" to "Sensor de temperatura do motor: leitura incoerente",
        "P0117" to "Sensor de temperatura do motor: tensão baixa",
        "P0118" to "Sensor de temperatura do motor: tensão alta",
        "P0120" to "Sensor de posição da borboleta: circuito",
        "P0121" to "Sensor de posição da borboleta: leitura incoerente",
        "P0122" to "Sensor de posição da borboleta: tensão baixa",
        "P0123" to "Sensor de posição da borboleta: tensão alta",
        "P0125" to "Motor demora a atingir temperatura para malha fechada",
        "P0128" to "Válvula termostática: motor abaixo da temperatura",
        "P0130" to "Sonda lambda B1S1: circuito",
        "P0131" to "Sonda lambda B1S1: tensão baixa",
        "P0132" to "Sonda lambda B1S1: tensão alta",
        "P0133" to "Sonda lambda B1S1: resposta lenta",
        "P0134" to "Sonda lambda B1S1: sem atividade",
        "P0135" to "Aquecedor da sonda B1S1: circuito",
        "P0136" to "Sonda lambda B1S2: circuito",
        "P0137" to "Sonda lambda B1S2: tensão baixa",
        "P0138" to "Sonda lambda B1S2: tensão alta",
        "P0140" to "Sonda lambda B1S2: sem atividade",
        "P0141" to "Aquecedor da sonda B1S2: circuito",
        "P0170" to "Correção de combustível (banco 1) fora do limite",
        "P0171" to "Mistura muito pobre (banco 1)",
        "P0172" to "Mistura muito rica (banco 1)",
        "P0201" to "Bico injetor 1: circuito",
        "P0202" to "Bico injetor 2: circuito",
        "P0203" to "Bico injetor 3: circuito",
        "P0204" to "Bico injetor 4: circuito",
        "P0221" to "Sensor de posição da borboleta B: leitura incoerente",
        "P0230" to "Bomba de combustível: circuito",
        "P0300" to "Falha de ignição aleatória/múltipla",
        "P0301" to "Falha de ignição no cilindro 1",
        "P0302" to "Falha de ignição no cilindro 2",
        "P0303" to "Falha de ignição no cilindro 3",
        "P0304" to "Falha de ignição no cilindro 4",
        "P0320" to "Sensor de rotação: circuito",
        "P0321" to "Sensor de rotação: leitura incoerente",
        "P0322" to "Sensor de rotação: sem sinal",
        "P0325" to "Sensor de detonação: circuito",
        "P0327" to "Sensor de detonação: tensão baixa",
        "P0328" to "Sensor de detonação: tensão alta",
        "P0335" to "Sensor de posição do virabrequim: circuito",
        "P0340" to "Sensor de fase (comando): circuito",
        "P0351" to "Bobina de ignição A: circuito",
        "P0352" to "Bobina de ignição B: circuito",
        "P0420" to "Eficiência do catalisador abaixo do limite",
        "P0440" to "Sistema EVAP (cânister): falha",
        "P0443" to "Válvula de purga do cânister: circuito",
        "P0444" to "Válvula de purga do cânister: circuito aberto",
        "P0445" to "Válvula de purga do cânister: curto",
        "P0500" to "Sensor de velocidade do veículo: circuito",
        "P0501" to "Sensor de velocidade: leitura incoerente",
        "P0505" to "Controle de marcha lenta: falha",
        "P0506" to "Marcha lenta abaixo do esperado",
        "P0507" to "Marcha lenta acima do esperado",
        "P0560" to "Tensão do sistema (bateria) incorreta",
        "P0562" to "Tensão do sistema baixa",
        "P0563" to "Tensão do sistema alta",
        "P0601" to "Central do motor: erro de memória",
        "P0606" to "Central do motor: falha no processador",
        "P0627" to "Bomba de combustível: circuito de controle",
        "P0638" to "Atuador da borboleta: faixa de controle",
        "P0641" to "Alimentação de referência A dos sensores",
        "P0651" to "Alimentação de referência B dos sensores",
        "P1545" to "Borboleta eletrônica: falha de controle (VW)",
        "P1546" to "Borboleta eletrônica: desvio de posição (VW)",
        "P2101" to "Motor da borboleta eletrônica: faixa de controle",
        "P2135" to "Sensores da borboleta A/B: incoerentes entre si",
        "P2138" to "Sensores do pedal D/E: incoerentes entre si",
        "P2177" to "Mistura pobre fora da marcha lenta (banco 1)",
        "P2178" to "Mistura rica fora da marcha lenta (banco 1)",
        "P2187" to "Mistura pobre em marcha lenta (banco 1)",
        "P2188" to "Mistura rica em marcha lenta (banco 1)",
        "P2195" to "Sonda B1S1 travada indicando mistura pobre",
        "P2196" to "Sonda B1S1 travada indicando mistura rica",
    )

    fun describe(code: String): String = known[code] ?: generic(code)

    private fun generic(code: String): String {
        if (code.length < 5) return "Código desconhecido"
        val maker = code[1] != '0' && code[1] != '2'
        val area = when (code[0]) {
            'P' -> when (code[2]) {
                '0', '1', '2' -> "mistura ar/combustível ou medição de ar"
                '3' -> "ignição ou falha de combustão"
                '4' -> "controle de emissões"
                '5' -> "velocidade, marcha lenta ou entradas"
                '6' -> "central do motor ou saídas"
                '7', '8', '9' -> "câmbio"
                else -> "motor/câmbio"
            }
            'C' -> "chassi"
            'B' -> "carroceria"
            else -> "rede de comunicação"
        }
        return "Sem descrição específica — área: $area${if (maker) " (código do fabricante)" else ""}"
    }
}

/** Resultado de um teste do modo 06 (ISO 9141: TID + componente + valor + limite). */
data class Mode06Result(
    val tid: Int,
    val component: Int,
    val value: Double,
    val limit: Double,
    val limitIsMin: Boolean,
    val unit: String,
) {
    val passed get() = if (limitIsMin) value >= limit else value <= limit
    val name get() = TID_NAMES[tid] ?: "Teste %02X".format(tid)
    val componentName get() = when (component) {
        1 -> "B1S1"; 2 -> "B1S2"; 3 -> "B1S3"; 4 -> "B1S4"
        5 -> "B2S1"; 6 -> "B2S2"
        else -> "componente $component"
    }

    companion object {
        val TID_NAMES = mapOf(
            1 to "Limiar rico→pobre",
            2 to "Limiar pobre→rico",
            3 to "Tensão baixa para cálculo de troca",
            4 to "Tensão alta para cálculo de troca",
            5 to "Tempo de troca rico→pobre",
            6 to "Tempo de troca pobre→rico",
            7 to "Tensão mínima no teste",
            8 to "Tensão máxima no teste",
        )

        fun scale(tid: Int, raw: Int): Pair<Double, String> = when (tid) {
            1, 2, 3, 4, 7, 8 -> raw * 0.005 to "V"
            5, 6 -> raw * 0.004 to "s"
            else -> raw.toDouble() to ""
        }
    }
}
