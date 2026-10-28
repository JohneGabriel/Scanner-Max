package br.obd.app

import java.net.URLEncoder

/** Explicação completa de um código de falha para mostrar no app. */
data class DtcDetail(
    val code: String,
    val title: String,
    val level: Level,
    val system: String,
    val meaning: String,
    val causes: List<String>,
    val actions: List<String>,
    val drive: String,
    val makerSpecific: Boolean,
)

/**
 * Guia dos códigos: descrição específica quando conhecida (DtcInfo) e explicação por
 * família (faixa do código), que cobre milhares de códigos genéricos.
 */
object DtcGuide {
    private data class Family(
        val range: IntRange, val system: String, val level: Level, val meaning: String,
        val causes: List<String>, val actions: List<String>, val drive: String,
    )

    private val SAFE = "Pode rodar normalmente, mas resolva logo para não aumentar o consumo ou danificar outras peças."
    private val CAREFUL = "Dá para rodar com cuidado até a oficina. Evite viagens longas e acelerações fortes."
    private val STOP = "Evite rodar: risco de dano sério ao motor. Se possível, leve guinchado ou só até a oficina mais próxima."

    private val families = listOf(
        Family(0x0010..0x0029, "Comando de válvulas variável", Level.WARN,
            "A central não está conseguindo controlar ou ler o sincronismo do comando de válvulas variável.",
            listOf("Óleo baixo, velho ou de viscosidade errada", "Válvula solenoide do variador travada ou com mau contato", "Corrente/correia de distribuição esticada ou fora do ponto"),
            listOf("Confira o nível e a data da última troca de óleo", "Teste a válvula solenoide e o chicote", "Verifique o ponto da distribuição"), CAREFUL),
        Family(0x0030..0x0064, "Aquecimento da sonda lambda", Level.WARN,
            "O aquecedor interno da sonda lambda não está funcionando. Sem ele a sonda demora a funcionar e o motor fica mais tempo com mistura imprecisa.",
            listOf("Resistência de aquecimento da sonda queimada", "Fusível do aquecimento queimado", "Conector oxidado ou fio rompido perto do escapamento"),
            listOf("Verifique o fusível da sonda", "Meça a resistência do aquecedor (normalmente 2 a 15 Ω)", "Troque a sonda se o aquecedor estiver aberto"), SAFE),
        Family(0x0100..0x0104, "Sensor de fluxo de ar (MAF)", Level.WARN,
            "O sensor que mede o ar admitido está com leitura fora do esperado. A central passa a estimar a mistura.",
            listOf("Sensor sujo (óleo do filtro esportivo, poeira)", "Entrada de ar entre o sensor e a borboleta", "Conector ou chicote com problema"),
            listOf("Limpe o sensor com produto próprio para MAF", "Confira a mangueira entre o filtro de ar e a borboleta", "Verifique o conector"), CAREFUL),
        Family(0x0105..0x0109, "Sensor de pressão do coletor (MAP)", Level.WARN,
            "O sensor de pressão do coletor está com leitura incoerente. Ele é a principal referência para calcular o combustível em carros sem MAF.",
            listOf("Mangueira do sensor solta ou rachada", "Sensor com defeito", "Chicote ou alimentação de 5 V com problema"),
            listOf("Confira a mangueira/vedação do sensor", "Compare a leitura com o motor desligado: deve ser a pressão do ar (~92–101 kPa)", "Teste a alimentação e o sinal"), CAREFUL),
        Family(0x0110..0x0114, "Sensor de temperatura do ar", Level.INFO,
            "A leitura da temperatura do ar admitido está fora da faixa. Afeta pouco, mas a central usa um valor padrão.",
            listOf("Sensor com defeito", "Conector solto ou oxidado"), listOf("Verifique o conector", "Compare com a temperatura ambiente com o motor frio"), SAFE),
        Family(0x0115..0x0119, "Sensor de temperatura do motor", Level.WARN,
            "A central não confia na temperatura do motor. Isso afeta partida a frio, lenta, ventoinha e consumo.",
            listOf("Sensor com defeito", "Conector oxidado", "Nível de líquido de arrefecimento baixo (sensor no ar)"),
            listOf("Confira o nível do líquido de arrefecimento com o motor frio", "Compare a temperatura no app com o motor frio (deve ser próxima do ambiente)", "Troque o sensor se a leitura for absurda"), CAREFUL),
        Family(0x0120..0x0124, "Sensor de posição da borboleta", Level.WARN,
            "O sinal de posição da borboleta está incoerente. Pode causar lenta irregular, trancos e o carro entrar em modo de emergência.",
            listOf("Corpo de borboleta sujo", "Sensor de posição gasto", "Chicote/conector com mau contato"),
            listOf("Limpe o corpo de borboleta", "Faça a adaptação da borboleta com um scanner do fabricante", "Verifique o conector"), CAREFUL),
        Family(0x0125..0x0129, "Temperatura do motor / termostato", Level.WARN,
            "O motor demora demais para aquecer ou não chega à temperatura de trabalho. Quase sempre é a válvula termostática presa aberta.",
            listOf("Válvula termostática travada aberta", "Sensor de temperatura lendo baixo", "Ventoinha ligada direto"),
            listOf("Veja no relatório da viagem quanto tempo o motor leva até 80 °C", "Troque a válvula termostática", "Verifique o relé da ventoinha"), SAFE),
        Family(0x0130..0x0167, "Sonda lambda", Level.WARN,
            "A sonda lambda está com sinal fora do esperado (baixo, alto, lento ou parado). Ela é quem diz à central se a mistura está certa.",
            listOf("Sonda envelhecida ou contaminada (óleo, aditivos, combustível ruim)", "Vazamento no escapamento antes da sonda", "Mistura realmente pobre/rica por outro motivo"),
            listOf("Rode o teste guiado da Sonda lambda", "Verifique vazamentos no coletor de escape", "Rode o teste de Mistura e correções antes de trocar a sonda"), SAFE),
        Family(0x0170..0x0175, "Mistura ar/combustível", Level.WARN,
            "A central chegou ao limite de correção da mistura. Ela sozinha não consegue mais acertar a quantidade de combustível.",
            listOf("Entrada de ar falsa (mangueiras, junta do coletor, cânister)", "Pressão de combustível baixa ou alta", "Bicos sujos ou pingando", "Sensor MAP/MAF com leitura errada", "Num flex: central com o aprendizado de combustível errado"),
            listOf("Rode o teste guiado de Mistura e correções: ele separa as causas", "Procure entradas de ar com o motor em lenta", "Meça a pressão de combustível"), CAREFUL),
        Family(0x0200..0x0212, "Bicos injetores", Level.WARN,
            "Problema elétrico no circuito de um bico injetor.",
            listOf("Bico com bobina aberta ou em curto", "Conector solto", "Fio rompido no chicote"),
            listOf("Meça a resistência do bico indicado", "Verifique o conector e o chicote"), CAREFUL),
        Family(0x0217..0x0217, "Superaquecimento", Level.BAD,
            "A central registrou o motor acima da temperatura segura.",
            listOf("Falta de líquido de arrefecimento", "Ventoinha não ligando", "Bomba d'água ou válvula termostática com defeito", "Junta do cabeçote"),
            listOf("Não rode até verificar o arrefecimento", "Confira nível, vazamentos e a ventoinha"), STOP),
        Family(0x0220..0x0229, "Sensor de posição da borboleta / pedal", Level.WARN,
            "Os sinais redundantes de posição estão incoerentes entre si.",
            listOf("Corpo de borboleta ou pedal com sensor gasto", "Chicote com mau contato"), listOf("Limpe e adapte a borboleta", "Verifique conectores"), CAREFUL),
        Family(0x0230..0x0233, "Bomba de combustível", Level.WARN,
            "Problema no circuito de comando da bomba de combustível.", listOf("Relé da bomba", "Fusível", "Bomba com consumo alto"),
            listOf("Verifique relé e fusível", "Meça a pressão e a vazão da bomba"), CAREFUL),
        Family(0x0300..0x0312, "Falha de combustão", Level.BAD,
            "Um ou mais cilindros não estão queimando a mistura direito. Além do motor tremer, combustível cru vai para o catalisador e pode destruí-lo.",
            listOf("Vela gasta ou com folga errada", "Cabo de vela ou bobina com defeito", "Bico injetor entupido", "Entrada de ar falsa", "Compressão baixa no cilindro"),
            listOf("Se a luz de injeção estiver PISCANDO, reduza a carga e vá direto à oficina", "Troque de lugar a bobina/vela do cilindro indicado e veja se a falha muda", "Faça teste de compressão se persistir"), CAREFUL),
        Family(0x0325..0x0334, "Sensor de detonação", Level.WARN,
            "O sensor de detonação está com sinal fora da faixa. A central atrasa a ignição por segurança: perde força e aumenta o consumo.",
            listOf("Sensor solto ou com torque errado", "Conector oxidado", "Sensor com defeito"), listOf("Confira o aperto e o conector do sensor"), SAFE),
        Family(0x0335..0x0339, "Sensor de rotação (virabrequim)", Level.BAD,
            "Sinal do sensor de rotação ausente ou incoerente. Sem ele o motor não liga ou apaga sozinho.",
            listOf("Sensor com defeito", "Roda fônica danificada", "Chicote rompido"), listOf("Verifique o sensor e o conector", "Meça a resistência/sinal do sensor"), CAREFUL),
        Family(0x0340..0x0349, "Sensor de fase (comando)", Level.WARN,
            "Sinal do sensor de fase incoerente. O motor pode demorar a pegar.",
            listOf("Sensor com defeito", "Correia dentada pulou um dente", "Chicote"), listOf("Verifique o ponto da correia dentada", "Teste o sensor"), CAREFUL),
        Family(0x0350..0x0362, "Bobina de ignição", Level.WARN,
            "Problema no circuito de uma bobina de ignição.", listOf("Bobina com defeito", "Conector solto", "Fio rompido"),
            listOf("Teste ou troque a bobina indicada", "Verifique velas e cabos"), CAREFUL),
        Family(0x0400..0x0409, "Recirculação de gases (EGR)", Level.WARN,
            "O sistema de recirculação de gases de escape não está funcionando como deveria.",
            listOf("Válvula EGR com carvão", "Passagens entupidas", "Sensor de posição da válvula"), listOf("Limpe a válvula e os dutos de EGR"), SAFE),
        Family(0x0410..0x0419, "Ar secundário", Level.INFO,
            "O sistema de injeção de ar secundário (usado na partida a frio) falhou.", listOf("Bomba de ar", "Válvula de retenção"), listOf("Verifique bomba e válvulas"), SAFE),
        Family(0x0420..0x0439, "Catalisador", Level.WARN,
            "O catalisador está com eficiência abaixo do limite: a sonda depois dele está oscilando quase igual à da frente.",
            listOf("Catalisador envelhecido ou derretido (falhas de combustão no passado)", "Sonda traseira com defeito", "Vazamento no escapamento"),
            listOf("Rode o teste guiado da Sonda lambda (ele olha a sonda de trás)", "Corrija antes falhas de combustão e mistura rica", "Verifique vazamentos no escapamento"), SAFE),
        Family(0x0440..0x0457, "Sistema EVAP (cânister)", Level.INFO,
            "Vazamento ou falha no sistema que recolhe os vapores do tanque.",
            listOf("Tampa do tanque mal fechada", "Mangueira do cânister solta ou rachada", "Válvula de purga travada"),
            listOf("Feche bem a tampa do tanque e apague o código", "Verifique as mangueiras do cânister", "Teste a válvula de purga"), SAFE),
        Family(0x0460..0x0464, "Sensor de nível de combustível", Level.INFO,
            "A leitura do nível do tanque está incoerente.", listOf("Boia do tanque", "Conector"), listOf("Verifique a boia do tanque"), SAFE),
        Family(0x0480..0x0485, "Ventoinha do radiador", Level.WARN,
            "Problema no circuito de comando da ventoinha. Risco de superaquecer no trânsito.",
            listOf("Relé da ventoinha", "Fusível", "Motor da ventoinha"), listOf("Observe a temperatura no app em trânsito parado", "Verifique relé, fusível e motor da ventoinha"), CAREFUL),
        Family(0x0500..0x0503, "Sensor de velocidade", Level.WARN,
            "A central não está recebendo a velocidade do carro corretamente.", listOf("Sensor de velocidade", "Chicote"),
            listOf("Veja se o velocímetro funciona", "Verifique o sensor no câmbio"), SAFE),
        Family(0x0505..0x0509, "Controle da marcha lenta", Level.WARN,
            "A rotação de lenta está fora do que a central queria.",
            listOf("Corpo de borboleta sujo", "Adaptação da borboleta errada", "Entrada de ar falsa"),
            listOf("Rode o teste guiado de Marcha lenta", "Limpe e adapte o corpo de borboleta"), SAFE),
        Family(0x0520..0x0524, "Pressão do óleo", Level.BAD,
            "O sistema de pressão do óleo apresentou leitura fora da faixa.",
            listOf("Nível de óleo baixo", "Bomba de óleo", "Sensor/interruptor de pressão"), listOf("PARE e confira o nível de óleo", "Não rode com a luz do óleo acesa"), STOP),
        Family(0x0560..0x0566, "Tensão do sistema", Level.WARN,
            "A tensão elétrica que chega à central está fora da faixa.", listOf("Bateria fraca", "Alternador/regulador", "Terminais oxidados"),
            listOf("Rode o teste guiado de Bateria e alternador", "Limpe os terminais da bateria"), CAREFUL),
        Family(0x0600..0x0699, "Central do motor", Level.WARN,
            "Falha interna ou de alimentação da central do motor.", listOf("Alimentação/aterramento da central", "Central com defeito", "Software"),
            listOf("Verifique os aterramentos e a bateria", "Procure um especialista em injeção"), CAREFUL),
        Family(0x0700..0x0999, "Câmbio", Level.WARN,
            "Código do câmbio (normalmente automático) registrado na central do motor.", listOf("Óleo do câmbio", "Solenoides", "Sensores do câmbio"),
            listOf("Leia os códigos da central do câmbio com um scanner completo"), CAREFUL),
        Family(0x2100..0x2119, "Borboleta eletrônica", Level.WARN,
            "O motor da borboleta eletrônica não está levando a borboleta para a posição pedida.",
            listOf("Corpo de borboleta sujo ou travando", "Motor da borboleta gasto"), listOf("Limpe e adapte o corpo de borboleta"), CAREFUL),
        Family(0x2120..0x2139, "Sensores do pedal/borboleta", Level.WARN,
            "Os dois sinais do pedal ou da borboleta não batem entre si. O carro costuma entrar em emergência (sem força).",
            listOf("Pedal ou borboleta com sensor gasto", "Conector"), listOf("Verifique os conectores do pedal e da borboleta"), CAREFUL),
        Family(0x2177..0x2190, "Mistura em lenta/fora da lenta", Level.WARN,
            "A mistura está fora do limite numa faixa específica (lenta ou fora dela).",
            listOf("Entrada de ar (aparece mais em lenta)", "Pressão de combustível (aparece mais em carga)"),
            listOf("Rode o teste guiado de Mistura e correções"), CAREFUL),
        Family(0x2195..0x2199, "Sonda lambda travada", Level.WARN,
            "A sonda ficou parada indicando mistura sempre pobre ou sempre rica.", listOf("Sonda com defeito", "Mistura realmente desequilibrada"),
            listOf("Rode o teste guiado da Sonda lambda"), SAFE),
    )

    fun detail(code: String): DtcDetail {
        val c = code.trim().uppercase()
        val specific = DtcInfo.describe(c)
        val maker = c.length >= 2 && c[1] in "13"
        val num = c.drop(1).toIntOrNull(16)
        val fam = if (c.startsWith("P") && !maker && num != null) families.firstOrNull { num in it.range } else null
        if (fam != null) {
            return DtcDetail(c, specific.takeUnless { it.startsWith("Sem descrição") } ?: fam.system, fam.level, fam.system,
                fam.meaning, fam.causes, fam.actions, fam.drive, false)
        }
        val system = when (c.firstOrNull()) {
            'P' -> "Motor / câmbio"; 'C' -> "Chassi (freios, suspensão, direção)"; 'B' -> "Carroceria (airbag, conforto, painel)"
            'U' -> "Comunicação entre módulos"; else -> "—"
        }
        return DtcDetail(
            c, specific, if (c.startsWith("U") || c.startsWith("C")) Level.WARN else Level.INFO, system,
            if (maker) "Código específico do fabricante: o significado exato depende da marca e do modelo. A pesquisa abaixo costuma achar a descrição certa."
            else "Código genérico do sistema indicado.",
            if (c.startsWith("U")) listOf("Módulo sem alimentação ou aterramento", "Fios da rede CAN rompidos ou em curto", "Bateria fraca na partida") else emptyList(),
            listOf("Pesquise o código com a marca e o modelo do carro", "Leia os outros módulos com um scanner do fabricante"),
            "Depende do sistema — se a luz de injeção estiver acesa e o carro estiver normal, dá para rodar até a oficina.",
            maker,
        )
    }

    fun searchUrl(code: String, vehicle: String?): String {
        val q = listOfNotNull(code, "código de falha", vehicle).joinToString(" ")
        return "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
    }

    fun videoUrl(code: String, vehicle: String?): String {
        val q = listOfNotNull(code, vehicle).joinToString(" ")
        return "https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8")
    }

    /** Aceita "p0171", "P 0171", "0171" e devolve "P0171" (ou null). */
    fun normalize(input: String): String? {
        var s = input.uppercase().replace(" ", "").replace("-", "")
        if (s.length == 4 && s.all { it.isDigit() || it in 'A'..'F' }) s = "P$s"
        return if (Regex("^[PCBU][0-3][0-9A-F]{3}$").matches(s)) s else null
    }
}
