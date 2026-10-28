package br.obd.app

import br.obd.app.link.LinkKind

/** Um recurso do adaptador testado com um comando inofensivo. */
data class AdapterFeature(val label: String, val cmd: String, val since: String, val ok: Boolean)

/**
 * O que o adaptador realmente é e aceita. Clones costumam se apresentar como
 * "ELM327 v2.1" mas só implementam o conjunto da v1.4 ou menos; os chips STN
 * (OBDLink e afins) respondem ao STI. O app usa isto para escolher como conversar.
 */
data class AdapterProfile(
    val link: LinkKind? = null,
    val reported: String = "—",
    val description: String? = null,
    val stn: String? = null,
    val estimatedVersion: String? = null,
    val clone: Boolean = false,
    val features: List<AdapterFeature> = emptyList(),
    val latencyMs: Int? = null,
    /** Ligado depois que o carro é identificado. */
    val carProtocol: String? = null,
    val multiPid: Boolean = false,
    val aggressiveTiming: Boolean = false,
) {
    fun has(cmd: String) = features.firstOrNull { it.cmd == cmd }?.ok == true
    val canReadVoltage get() = has("ATRV")

    val chipName get() = when {
        stn != null -> stn
        clone -> "Clone de ELM327"
        else -> "ELM327"
    }

    val summary get() = buildString {
        append(chipName)
        append(" · diz ").append(reported)
        estimatedVersion?.let { append(" · comandos até v").append(it) }
        latencyMs?.let { append(" · ").append(it).append(" ms por comando") }
        link?.let { append(" · ").append(it.label) }
    }

    /** Uma linha de avaliação para a tela. */
    val verdict get() = when {
        stn != null -> "Chip STN genuíno: rápido e completo."
        clone -> "Clone: funciona para o OBD genérico, mas não implementa tudo o que a versão anunciada promete. " +
            "O app evita os comandos que ele recusa."
        else -> "Adaptador compatível com ELM327 ${estimatedVersion?.let { "v$it" } ?: ""}."
    }

    companion object {
        /** Protocolos que o padrão ELM327 cobre (número do ATSP/ATDPN). */
        val PROTOCOLS = listOf(
            "1" to "SAE J1850 PWM (Ford antigos)",
            "2" to "SAE J1850 VPW (GM antigos)",
            "3" to "ISO 9141-2 (linha K)",
            "4" to "ISO 14230-4 KWP lento",
            "5" to "ISO 14230-4 KWP rápido",
            "6" to "ISO 15765-4 CAN 11 bits 500 kbps",
            "7" to "ISO 15765-4 CAN 29 bits 500 kbps",
            "8" to "ISO 15765-4 CAN 11 bits 250 kbps",
            "9" to "ISO 15765-4 CAN 29 bits 250 kbps",
            "A" to "SAE J1939 CAN (caminhões)",
        )

        private val TESTS = listOf(
            Triple("Protocolo atual (número)", "ATDPN", "1.0"),
            Triple("Tensão da bateria", "ATRV", "1.0"),
            Triple("Parâmetros programáveis", "ATPPS", "1.1"),
            Triple("Temporização agressiva", "ATAT2", "1.2"),
            Triple("Esquecer eventos", "ATFE", "1.3"),
            Triple("Entrada de ignição", "ATIGN", "1.4"),
            Triple("Filtro de recepção CAN", "ATCRA", "1.4"),
            Triple("Monitor de atividade", "ATAMC", "2.0"),
        )

        private fun versionOf(s: String): Double? =
            Regex("v(\\d+)\\.(\\d+)").find(s)?.let { "${it.groupValues[1]}.${it.groupValues[2]}".toDoubleOrNull() }

        suspend fun detect(elm: Elm327, link: LinkKind?): AdapterProfile {
            val was = elm.verbose
            val ati = elm.send("ATI").lines().lastOrNull { it.isNotBlank() } ?: "—"
            val desc = elm.send("AT@1").takeUnless { Elm327.isError(it) }
            val sti = elm.send("STI").takeUnless { Elm327.isError(it) || it.isBlank() }
            val feats = TESTS.map { (label, cmd, since) ->
                val r = elm.send(cmd, 1500)
                AdapterFeature(label, cmd, since, !r.contains("?") && r.isNotBlank())
            }
            elm.send("ATAT1") // volta ao padrão depois do teste do AT2
            // versão mais alta cujos comandos todos funcionaram
            val versions = listOf("1.0", "1.1", "1.2", "1.3", "1.4", "2.0")
            var est: String? = null
            for (v in versions) {
                val need = feats.filter { (it.since.toDoubleOrNull() ?: 9.0) <= v.toDouble() }
                if (need.all { it.ok }) est = v else break
            }
            val reportedV = versionOf(ati)
            val estV = est?.toDoubleOrNull() ?: 0.0
            val clone = sti == null && reportedV != null && reportedV >= 1.5 && estV < minOf(reportedV, 2.0)
            // latência: média de 5 comandos curtos que não mexem no carro
            elm.verbose = false
            val probe = if (feats.firstOrNull { it.cmd == "ATRV" }?.ok == true) "ATRV" else "ATI"
            val t = System.currentTimeMillis()
            repeat(5) { elm.send(probe) }
            val lat = ((System.currentTimeMillis() - t) / 5).toInt()
            elm.verbose = was
            return AdapterProfile(
                link = link, reported = ati, description = desc, stn = sti?.lines()?.firstOrNull(),
                estimatedVersion = est, clone = clone, features = feats, latencyMs = lat,
            )
        }
    }
}
