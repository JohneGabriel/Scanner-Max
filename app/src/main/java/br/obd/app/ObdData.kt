package br.obd.app

import java.util.Calendar

/**
 * Definição de um PID do modo 01: como pedir, quantos bytes ele devolve e como
 * transformar os bytes em número.
 */
data class PidDef(
    val pid: String,
    val key: String,
    val label: String,
    val unit: String,
    /** quantidade fixa de bytes de dados; 0 = variável (não entra em pedidos agrupados) */
    val bytes: Int = 1,
    val digits: Int = 0,
    val decode: (IntArray) -> Double?,
) {
    val pidInt get() = pid.toInt(16)
    fun format(v: Double) = "%.${digits}f".format(v) + if (unit.isEmpty()) "" else " $unit"
}

object Pids {
    private fun a(d: IntArray) = d.getOrNull(0)?.toDouble()
    private fun ab(d: IntArray) = if (d.size >= 2) d[0] * 256.0 + d[1] else null
    private fun pct(d: IntArray) = a(d)?.times(100)?.div(255)
    private fun trim(x: Int) = (x - 128) * 100.0 / 128

    val FUEL_STATUS = PidDef("03", "fuelStatus", "Sistema de combustível", "", 0) { a(it) }
    val LOAD = PidDef("04", "load", "Carga", "%") { pct(it) }
    val COOLANT = PidDef("05", "coolant", "Temp. motor", "°C") { a(it)?.minus(40) }
    val STFT = PidDef("06", "stft", "Correção curta B1", "%", 0, 1) { d -> d.getOrNull(0)?.let { trim(it) } }
    val LTFT = PidDef("07", "ltft", "Correção longa B1", "%", 0, 1) { d -> d.getOrNull(0)?.let { trim(it) } }
    val STFT2 = PidDef("08", "stft2", "Correção curta B2", "%", 0, 1) { d -> d.getOrNull(0)?.let { trim(it) } }
    val LTFT2 = PidDef("09", "ltft2", "Correção longa B2", "%", 0, 1) { d -> d.getOrNull(0)?.let { trim(it) } }
    val FUEL_PRESS = PidDef("0A", "fuelPress", "Pressão de combustível", "kPa") { a(it)?.times(3) }
    val MAP = PidDef("0B", "map", "Pressão do coletor", "kPa") { a(it) }
    val RPM = PidDef("0C", "rpm", "Rotação", "rpm", 2) { d -> ab(d)?.div(4) }
    val SPEED = PidDef("0D", "speed", "Velocidade", "km/h") { a(it) }
    val ADVANCE = PidDef("0E", "advance", "Avanço de ignição", "°", 1, 1) { a(it)?.div(2)?.minus(64) }
    val IAT = PidDef("0F", "iat", "Temp. admissão", "°C") { a(it)?.minus(40) }
    val MAF = PidDef("10", "maf", "Fluxo de ar (MAF)", "g/s", 2, 1) { d -> ab(d)?.div(100) }
    val THROTTLE = PidDef("11", "throttle", "Borboleta", "%", 1, 1) { pct(it) }
    val O2S1 = PidDef("14", "o2s1", "Sonda B1S1", "V", 2, 2) { d -> d.getOrNull(0)?.times(0.005) }
    val O2S2 = PidDef("15", "o2s2", "Sonda B1S2", "V", 2, 2) { d -> d.getOrNull(0)?.times(0.005) }
    val RUNTIME = PidDef("1F", "runtime", "Tempo desde a partida", "s", 2) { ab(it) }
    val DIST_MIL = PidDef("21", "distMil", "Distância com luz de injeção acesa", "km", 2) { ab(it) }
    val RAIL_PRESS = PidDef("23", "railPress", "Pressão do trilho", "kPa", 2) { d -> ab(d)?.times(10) }
    val WB_LAMBDA = PidDef("24", "wbLambda", "Lambda (sonda banda larga)", "λ", 4, 3) { d -> ab(d)?.times(2)?.div(65536) }
    val EGR_CMD = PidDef("2C", "egr", "EGR comandada", "%") { pct(it) }
    val PURGE = PidDef("2E", "purge", "Purga do cânister", "%") { pct(it) }
    val FUEL_LEVEL = PidDef("2F", "fuelLevel", "Nível de combustível", "%") { pct(it) }
    val WARMUPS = PidDef("30", "warmups", "Aquecimentos desde que apagou os códigos", "") { a(it) }
    val DIST_CLEAR = PidDef("31", "distClear", "Distância desde que apagou os códigos", "km", 2) { ab(it) }
    val BARO = PidDef("33", "baro", "Pressão barométrica", "kPa") { a(it) }
    val WB_LAMBDA_I = PidDef("34", "wbLambdaI", "Lambda (banda larga, corrente)", "λ", 4, 3) { d -> ab(d)?.times(2)?.div(65536) }
    val CAT_TEMP = PidDef("3C", "catTemp", "Temp. catalisador B1S1", "°C", 2) { d -> ab(d)?.div(10)?.minus(40) }
    val MODULE_V = PidDef("42", "moduleV", "Tensão da central", "V", 2, 2) { d -> ab(d)?.div(1000) }
    val ABS_LOAD = PidDef("43", "absLoad", "Carga absoluta", "%", 2) { d -> ab(d)?.times(100)?.div(255) }
    val LAMBDA_CMD = PidDef("44", "lambdaCmd", "Lambda comandado", "λ", 2, 3) { d -> ab(d)?.times(2)?.div(65536) }
    val THROTTLE_REL = PidDef("45", "throttleRel", "Borboleta relativa", "%", 1, 1) { pct(it) }
    val AMBIENT = PidDef("46", "ambient", "Temp. ambiente", "°C") { a(it)?.minus(40) }
    val PEDAL = PidDef("49", "pedal", "Pedal do acelerador", "%", 1, 1) { pct(it) }
    val TIME_MIL = PidDef("4D", "timeMil", "Tempo com luz de injeção acesa", "min", 2) { ab(it) }
    val TIME_CLEAR = PidDef("4E", "timeClear", "Tempo desde que apagou os códigos", "min", 2) { ab(it) }
    val ETHANOL = PidDef("52", "ethanol", "Etanol no combustível", "%") { pct(it) }
    val OIL_TEMP = PidDef("5C", "oilTemp", "Temp. do óleo", "°C") { a(it)?.minus(40) }
    val FUEL_RATE = PidDef("5E", "fuelRate", "Consumo instantâneo", "l/h", 2, 2) { d -> ab(d)?.div(20) }
    val ODOMETER = PidDef("A6", "odometer", "Hodômetro", "km", 4, 1) { d ->
        if (d.size < 4) null else ((d[0].toLong() shl 24) or (d[1].toLong() shl 16) or (d[2].toLong() shl 8) or d[3].toLong()) / 10.0
    }

    val ALL = listOf(
        FUEL_STATUS, LOAD, COOLANT, STFT, LTFT, STFT2, LTFT2, FUEL_PRESS, MAP, RPM, SPEED, ADVANCE, IAT, MAF,
        THROTTLE, O2S1, O2S2, RUNTIME, DIST_MIL, RAIL_PRESS, WB_LAMBDA, EGR_CMD, PURGE, FUEL_LEVEL, WARMUPS,
        DIST_CLEAR, BARO, WB_LAMBDA_I, CAT_TEMP, MODULE_V, ABS_LOAD, LAMBDA_CMD, THROTTLE_REL, AMBIENT, PEDAL,
        TIME_MIL, TIME_CLEAR, ETHANOL, OIL_TEMP, FUEL_RATE, ODOMETER,
    )
    val byKey = ALL.associateBy { it.key }
    val byPid = ALL.associateBy { it.pidInt }

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
        1 -> "Gasolina"; 2 -> "Metanol"; 3 -> "Etanol"; 4 -> "Diesel"; 5 -> "GLP"; 6 -> "GNV"
        8 -> "Elétrico"
        9 -> "Flex rodando com gasolina"
        10 -> "Flex rodando com metanol"
        11 -> "Flex rodando com etanol"
        12 -> "Bicombustível rodando com GLP"
        13 -> "Bicombustível rodando com GNV"
        17 -> "Híbrido a gasolina"; 18 -> "Híbrido a etanol"; 19 -> "Híbrido a diesel"
        null -> "—"
        else -> "Código $v"
    }

    /** O carro é flex (ou bicombustível)? */
    fun isFlex(fuelType: Int?) = fuelType in 9..13
    fun isDiesel(fuelType: Int?) = fuelType == 4 || fuelType == 19
}

/** Informações do veículo: protocolo, PIDs suportados e o que dá para tirar do chassi (VIN). */
data class VehicleInfo(
    val protocol: String = "—",
    val isCan: Boolean = false,
    val supported: Set<Int> = emptySet(),
    val vin: String? = null,
    val fuelType: Int? = null,
) {
    val maker get() = vin?.let { Vin.maker(it) }
    val year get() = vin?.let { Vin.year(it) }
    val isVw get() = maker?.startsWith("Volkswagen") == true
    fun has(def: PidDef) = def.pidInt in supported
}

object Vin {
    private val wmi = mapOf(
        "9BW" to "Volkswagen (Brasil)", "WVW" to "Volkswagen", "WV1" to "Volkswagen Comerciais", "WV2" to "Volkswagen",
        "3VW" to "Volkswagen (México)", "1VW" to "Volkswagen (EUA)", "8AW" to "Volkswagen (Argentina)",
        "9BD" to "Fiat (Brasil)", "ZFA" to "Fiat", "8AP" to "Fiat (Argentina)",
        "9BG" to "Chevrolet (Brasil)", "8AG" to "Chevrolet (Argentina)", "KL1" to "Chevrolet (Coreia)",
        "9BF" to "Ford (Brasil)", "8AF" to "Ford (Argentina)", "3FA" to "Ford (México)",
        "93H" to "Honda (Brasil)", "9BR" to "Toyota (Brasil)", "8AJ" to "Toyota (Argentina)",
        "93Y" to "Renault (Brasil)", "8A1" to "Renault (Argentina)", "VF1" to "Renault",
        "936" to "Peugeot (Brasil)", "VF3" to "Peugeot", "935" to "Citroën (Brasil)", "VF7" to "Citroën",
        "9BH" to "Hyundai (Brasil)", "KMH" to "Hyundai", "9BM" to "Mercedes-Benz (Brasil)", "WDD" to "Mercedes-Benz",
        "94D" to "Nissan (Brasil)", "3N1" to "Nissan (México)", "93X" to "Mitsubishi (Brasil)",
        "9BS" to "Scania (Brasil)", "KNA" to "Kia", "WBA" to "BMW", "WAU" to "Audi", "93U" to "Audi (Brasil)",
        "988" to "Jeep (Brasil)", "1C4" to "Jeep/Chrysler (EUA)", "9C2" to "Honda Motos (Brasil)",
        "LSG" to "Chevrolet (China)", "LVV" to "Chery", "99A" to "CAOA Chery (Brasil)",
    )

    fun maker(vin: String) = wmi[vin.take(3).uppercase()]

    /** Ano-modelo pelo 10º caractere (o ciclo de 30 anos é resolvido pelo mais recente plausível). */
    fun year(vin: String): Int? {
        if (vin.length < 10) return null
        val codes = "ABCDEFGHJKLMNPRSTVWXY123456789"
        val i = codes.indexOf(vin[9].uppercaseChar())
        if (i < 0) return null
        val now = Calendar.getInstance().get(Calendar.YEAR) + 1
        var y = 1980 + i
        while (y + 30 <= now) y += 30
        return y
    }
}

/** Prontidão (PID 01): monitores de motor a gasolina/flex ou diesel. */
data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val diesel: Boolean,
    /** nome do monitor -> true se completo */
    val monitors: List<Pair<String, Boolean>>,
)

object ReadinessDecoder {
    fun decode(d: IntArray): Readiness? {
        if (d.size < 4) return null
        val (a, b, c, dd) = d
        val diesel = b and 0x08 != 0
        val mons = mutableListOf<Pair<String, Boolean>>()
        // contínuos: bits 0-2 disponíveis, bits 4-6 incompletos
        val cont = listOf("Falhas de combustão", "Sistema de combustível", "Componentes")
        cont.forEachIndexed { i, n -> if (b and (1 shl i) != 0) mons += n to (b and (1 shl (i + 4)) == 0) }
        val noncont = if (diesel) listOf(
            "Catalisador NMHC", "Redução de NOx (SCR)", "", "Pressão do turbo",
            "", "Sensor de gases de escape", "Filtro de partículas (DPF)", "EGR / comando variável",
        ) else listOf(
            "Catalisador", "Catalisador aquecido", "Sistema EVAP (cânister)", "Ar secundário",
            "Ar-condicionado", "Sonda lambda", "Aquecimento da sonda", "EGR / comando variável",
        )
        noncont.forEachIndexed { i, n ->
            if (n.isNotEmpty() && c and (1 shl i) != 0) mons += n to (dd and (1 shl i) == 0)
        }
        return Readiness(a and 0x80 != 0, a and 0x7F, diesel, mons)
    }
}

/** Teste do modo 06 em carros CAN: MID (o que foi testado) + TID + valor e limites. */
data class Mode06Can(val mid: Int, val tid: Int, val uas: Int, val value: Int, val min: Int, val max: Int) {
    val passed get() = value in min..max
    val name get() = MID_NAMES[mid] ?: "Monitor %02X".format(mid)

    companion object {
        val MID_NAMES = buildMap {
            put(0x01, "Sonda B1S1"); put(0x02, "Sonda B1S2"); put(0x03, "Sonda B1S3")
            put(0x05, "Sonda B2S1"); put(0x06, "Sonda B2S2")
            put(0x21, "Catalisador B1"); put(0x22, "Catalisador B2")
            put(0x31, "EGR"); put(0x35, "Comando variável B1")
            put(0x39, "EVAP vazamento"); put(0x3A, "EVAP 0,090\""); put(0x3B, "EVAP 0,040\""); put(0x3C, "EVAP 0,020\""); put(0x3D, "Válvula de purga")
            put(0x41, "Aquecedor sonda B1S1"); put(0x42, "Aquecedor sonda B1S2")
            put(0x71, "Ar secundário")
            put(0x81, "Sistema de combustível B1"); put(0x82, "Sistema de combustível B2")
            put(0xA1, "Falhas de combustão (geral)")
            for (i in 1..8) put(0xA1 + i, "Falhas de combustão cil. $i")
            put(0xB0, "Filtro de partículas")
        }
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
