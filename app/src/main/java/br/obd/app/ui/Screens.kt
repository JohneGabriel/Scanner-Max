package br.obd.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.obd.app.DtcInfo
import br.obd.app.IdleResult
import br.obd.app.Stats
import br.obd.app.ObdManager
import br.obd.app.Pids
import java.io.File

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content(); Spacer(Modifier.height(24.dp)) }
}

private fun fmt(v: Double?, pattern: String = "%.0f", unit: String = "") =
    v?.let { pattern.format(it) + unit } ?: "—"

@Composable
private fun BigIcon(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, size: Int = 64) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size((size * 0.5).dp), tint = tint) }
}

@Composable
fun NotConnected(onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigIcon(Icons.Filled.Bluetooth, Accent, 88)
        Spacer(Modifier.height(4.dp))
        Text("Nenhum adaptador conectado", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ligue a ignição (ou o motor) e conecte ao ELM327.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Button(onClick = onConnect, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
            Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Conectar")
        }
    }
}

// ============================================================== PAINEL

@Composable
fun DashboardScreen(onConnect: () -> Unit) {
    val connected by ObdManager.connected.collectAsState()
    val v by ObdManager.values.collectAsState()
    val history by ObdManager.history.collectAsState()
    val recording by ObdManager.recording.collectAsState()
    val rate by ObdManager.rate.collectAsState()
    val veh by ObdManager.vehicle.collectAsState()
    var showAll by remember { mutableStateOf(false) }

    Screen {
        if (!connected) { NotConnected(onConnect); return@Screen }

        VehicleCard(veh)

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val diesel = Pids.isDiesel(veh.fuelType)
            Gauge(v["rpm"], if (diesel) 5000.0 else 7000.0, "Rotação", "rpm", Modifier.weight(1f),
                warnAt = if (diesel) 4200.0 else 6000.0, majorStep = 1000.0)
            Gauge(v["speed"], 200.0, "Velocidade", "km/h", Modifier.weight(1f), majorStep = 20.0)
        }

        // só os cartões que o carro suporta (antes de identificar, mostra os básicos)
        fun has(d: br.obd.app.PidDef) = veh.supported.isEmpty() || veh.has(d)
        val coolant = v["coolant"]; val battery = v["battery"]
        val cards = buildList {
            if (has(Pids.COOLANT)) add(Card4("Motor", fmt(coolant, unit = " °C"), Icons.Filled.Thermostat,
                coolant?.let { if (it > 105) Bad else if (it < 70) Cyan else null }))
            if (has(Pids.LOAD)) add(Card4("Carga", fmt(v["load"], unit = "%"), Icons.Filled.Equalizer, null))
            if (has(Pids.THROTTLE)) add(Card4("Borboleta", fmt(v["throttle"], unit = "%"), Icons.Filled.Tune, null))
            if (has(Pids.IAT)) add(Card4("Admissão", fmt(v["iat"], unit = " °C"), Icons.Filled.Air, null))
            if (has(Pids.MAP)) add(Card4("Coletor", fmt(v["map"], unit = " kPa"), Icons.Filled.Compress, null))
            if (veh.has(Pids.MAF)) add(Card4("Fluxo de ar", fmt(v["maf"], "%.1f", " g/s"), Icons.Filled.Air, null))
            if (has(Pids.ADVANCE)) add(Card4("Avanço", fmt(v["advance"], "%.1f", "°"), Icons.Filled.Bolt, null))
            if (veh.has(Pids.FUEL_LEVEL)) add(Card4("Tanque", fmt(v["fuelLevel"], unit = "%"), Icons.Filled.LocalGasStation,
                v["fuelLevel"]?.let { if (it < 12) Warn else null }))
        }
        cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { StatCard(it.label, it.value, Modifier.weight(1f), it.color, it.icon) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        StatCard(
            "Bateria / alternador", fmt(battery, "%.1f", " V"), Modifier.fillMaxWidth(),
            battery?.let { if (it < 12.0 || it > 14.8) Warn else Good }, Icons.Filled.BatteryChargingFull,
        )

        Section("Rotação", icon = Icons.Filled.ShowChart, trailing = {
            val peak = history.takeLast(200).mapNotNull { it.rpm }.maxOrNull()
            if (peak != null) Text("pico %.0f".format(peak), style = MaterialTheme.typography.labelMedium.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) {
            val rpms = history.takeLast(200).map { it.rpm }
            LineChart(rpms, min = 0.0, max = maxOf(3000.0, (rpms.filterNotNull().maxOrNull() ?: 0.0) * 1.15))
            Hint("Último minuto · ~%.1f leituras por segundo%s".format(rate,
                if (veh.isCan) "" else if (veh.supported.isNotEmpty()) " (limite da linha K)" else ""))
        }

        if (veh.supported.isNotEmpty()) {
            val all = Pids.ALL.filter { veh.has(it) }
            Section("Todos os sensores (${all.size})", icon = Icons.Filled.Sensors, trailing = {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Esconder" else "Mostrar") }
            }) {
                if (showAll) {
                    all.forEach { d ->
                        val value = v[d.key]
                        KeyValue(d.label, when {
                            value == null -> "…"
                            d == Pids.FUEL_STATUS -> Pids.fuelStatusText(value.toInt())
                            else -> d.format(value)
                        })
                    }
                    Hint("Os sensores fora do painel são lidos em rodízio: cada um atualiza a cada poucos segundos.")
                } else {
                    Hint("Tudo o que este carro informa pelo OBD, incluindo o que não aparece no painel.")
                }
            }
        }

        TripCard(recording)
    }
}

/** Fabricante, ano, protocolo e combustível do carro conectado. */
@Composable
private fun VehicleCard(veh: br.obd.app.VehicleInfo) {
    if (veh.supported.isEmpty() && veh.vin == null) return
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOfNotNull(veh.maker, veh.year?.toString()).joinToString(" · ").ifEmpty { "Veículo conectado" },
                    style = MaterialTheme.typography.titleMedium,
                )
                Hint(listOfNotNull(veh.protocol, veh.fuelType?.let { Pids.fuelTypeText(it) }).joinToString(" · "))
                veh.vin?.let { Hint("Chassi $it") }
            }
            StatusPill("${veh.supported.size} sensores", Cyan)
        }
    }
}

private data class Card4(
    val label: String,
    val value: String,
    val icon: ImageVector,
    val color: androidx.compose.ui.graphics.Color?,
)

@Composable
private fun TripCard(recording: File?) {
    Section("Gravar viagem", icon = Icons.Filled.Route) {
        AnimatedVisibility(recording == null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Grava todos os dados uma vez por segundo num CSV (abre no Excel ou Planilhas). " +
                    "Os arquivos ficam em Ferramentas.")
                Button(onClick = { ObdManager.startRecording() }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Começar a gravar")
                }
            }
        }
        AnimatedVisibility(recording != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    StatusPill("Gravando", Bad)
                    Spacer(Modifier.height(6.dp))
                    Hint(recording?.name ?: "")
                }
                Button(
                    onClick = { ObdManager.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = Bad, contentColor = androidx.compose.ui.graphics.Color.White),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Parar")
                }
            }
        }
    }
}

// ============================================================== COMBUSTÍVEL

@Composable
fun FuelScreen(onConnect: () -> Unit) {
    val connected by ObdManager.connected.collectAsState()
    val v by ObdManager.values.collectAsState()
    val history by ObdManager.history.collectAsState()
    val lambda by ObdManager.lambda.collectAsState()
    val reset by ObdManager.resetResult.collectAsState()
    val fuelType by ObdManager.fuelType.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    val stuck by ObdManager.ltftStuck.collectAsState()
    val veh by ObdManager.vehicle.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    val flex = Pids.isFlex(fuelType)
    val diesel = Pids.isDiesel(fuelType)
    val narrowO2 = veh.supported.isEmpty() || veh.has(Pids.O2S1)
    val wideO2 = veh.has(Pids.WB_LAMBDA) || veh.has(Pids.WB_LAMBDA_I)

    Screen {
        if (!connected) { NotConnected(onConnect); return@Screen }
        if (diesel) {
            Callout("Motor a diesel: as correções de mistura e a sonda lambda funcionam de outro jeito e a " +
                "maioria dos carros a diesel não informa esses dados pelo OBD. Veja os sensores no Painel.", Tone.INFO)
        }

        Section("Correções de combustível", icon = Icons.Filled.Tune) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrimColumn("Curto prazo", "agora", v["stft"], Modifier.weight(1f))
                TrimColumn("Longo prazo", "aprendido", v["ltft"], Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KeyValue("Sistema", Pids.fuelStatusText(v["fuelStatus"]?.toInt()))
            if (fuelType != null) KeyValue("A central informa", Pids.fuelTypeText(fuelType))
            v["ethanol"]?.let { KeyValue("Etanol no tanque (medido)", "%.0f%%".format(it)) }
            val (advice, tone) = trimAdvice(v["ltft"], v["stft"], flex)
            Callout(advice, tone)
            if (stuck) {
                Callout(
                    "A correção longa está parada em %+.1f%% em todas as leituras. ".format(v["ltft"] ?: 0.0) +
                        "O aprendizado de longo prazo não está se movendo, como se tivesse chegado ao limite.",
                    Tone.WARN,
                )
            }
        }

        if (narrowO2) Section("Sonda lambda", icon = Icons.Filled.Sensors) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Antes do catalisador", fmt(v["o2s1"], "%.2f", " V"), Modifier.weight(1f))
                StatCard("Depois do catalisador", fmt(v["o2s2"], "%.2f", " V"), Modifier.weight(1f))
            }
            LineChart(history.takeLast(150).map { it.o2 }, min = 0.0, max = 1.0, color = Cyan, reference = 0.45)
            Hint("Motor quente: a sonda de antes do catalisador deve subir e descer sem parar " +
                "entre ~0,1 e ~0,9 V (linha tracejada = 0,45 V).")
        }
        if (!narrowO2 && wideO2) Section("Sonda lambda (banda larga)", icon = Icons.Filled.Sensors) {
            StatCard("Lambda medido", fmt(v["wbLambda"] ?: v["wbLambdaI"], "%.3f", " λ"), Modifier.fillMaxWidth())
            Hint("Sonda de banda larga: mostra a mistura direto. 1,000 = mistura ideal; abaixo = rica; acima = pobre.")
        }

        if (narrowO2) Section("Teste da sonda", icon = Icons.Filled.Science) {
            Hint("Lê só a sonda por 30 s e conta as oscilações. Motor quente, de preferência a ~2.000 rpm.")
            FilledTonalButton(onClick = { ObdManager.lambdaTest(30) }, enabled = busy == null) { Text("Testar sonda (30 s)") }
            lambda?.let { r ->
                val good = r.verdict.contains("normalmente")
                Callout(r.verdict, if (good) Tone.GOOD else Tone.WARN)
                KeyValue("Leituras", "${r.samples} em ${r.seconds} s")
                KeyValue("Faixa", "%.2f – %.2f V".format(r.min, r.max))
                KeyValue("Trocas por segundo", "%.2f".format(r.switchesPerSec))
                KeyValue("Tempo em rico", "${r.richPct}%")
                KeyValue("Rotação no teste", fmt(r.rpm, unit = " rpm"))
            }
        }

        Section("Zerar adaptação (modo 04)", icon = Icons.Filled.Refresh) {
            Hint("Única escrita que o OBD genérico aceita. Apaga códigos e prontidão; em algumas centrais " +
                "também zera o aprendizado. O app mede a correção longa antes e depois.")
            OutlinedButton(onClick = { confirmReset = true }, enabled = busy == null) { Text("Tentar zerar a adaptação") }
            reset?.let { Callout(it, if (it.contains("zerada")) Tone.GOOD else Tone.INFO) }
        }

        if (flex) Section("Ajudar a central a reaprender o combustível", icon = Icons.Filled.Timeline) {
            listOf(
                "Abasteça bastante (pelo menos 1/4 do tanque): muitas centrais flex detectam o abastecimento pelo nível.",
                "Rode 10 a 15 minutos em velocidade constante, motor quente.",
                "Acompanhe aqui a correção longa: ela deve caminhar para perto de 0%.",
                "Se ela não sair do lugar e a sonda estiver boa, é preciso zerar a adaptação com um scanner " +
                    "que fale o protocolo do fabricante" + if (veh.isVw) " (na VW: aba VW com o cabo KKL)." else ".",
            ).forEachIndexed { i, t -> Step(i + 1, t) }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Filled.Refresh, null) },
            title = { Text("Zerar adaptação?") },
            text = {
                Text("O modo 04 apaga os códigos de falha, o freeze frame e os monitores de prontidão " +
                    "(eles voltam a ficar prontos depois de alguns dias rodando). Não grava nada além disso.")
            },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; ObdManager.resetAdaptation() }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun TrimColumn(title: String, sub: String, value: Double?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.width(4.dp))
            Hint(sub)
        }
        Text(fmt(value, "%+.1f", "%"), style = Tabular, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = trimColor(value))
        TrimBar(value)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelMedium, color = Accent) }
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun trimAdvice(ltft: Double?, stft: Double?, flex: Boolean): Pair<String, Tone> {
    if (ltft == null) return "Ligue o motor para ver as correções." to Tone.INFO
    val total = ltft + (stft ?: 0.0)
    return when {
        total < -10 -> ("A central está tirando %.0f%% do combustível: mistura rica. ".format(-total) +
            (if (flex) "Num flex é o que acontece quando ela acha que tem mais etanol do que realmente tem. " else "") +
            "Outras causas comuns: bico pingando, pressão de combustível alta ou sonda com defeito.") to Tone.WARN
        total > 10 -> ("A central está adicionando %.0f%% de combustível: mistura pobre. ".format(total) +
            "Causas comuns: entrada falsa de ar, bomba ou filtro fracos, bicos sujos" +
            (if (flex) " ou a central achando que tem gasolina com etanol no tanque." else ".")) to Tone.WARN
        else -> "Correções dentro do normal (±10%): a central está acertando a mistura." to Tone.GOOD
    }
}

// ============================================================== DIAGNÓSTICO

@Composable
fun DiagnosticsScreen(onConnect: () -> Unit) {
    val connected by ObdManager.connected.collectAsState()
    val d by ObdManager.diag.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Screen {
        if (!connected) { NotConnected(onConnect); return@Screen }

        val stored = d.stored.orEmpty(); val pending = d.pending.orEmpty()
        val total = stored.size + pending.size
        val mil = d.readiness?.milOn == true

        // resumo
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    d.readAt == null -> BigIcon(Icons.Filled.Memory, Accent, 56)
                    total == 0 && !mil -> BigIcon(Icons.Filled.CheckCircle, Good, 56)
                    else -> BigIcon(Icons.Filled.Warning, if (stored.isNotEmpty() || mil) Bad else Warn, 56)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            d.readAt == null -> "Diagnóstico do motor"
                            total == 0 -> "Nenhum código de falha"
                            total == 1 -> "1 código de falha"
                            else -> "$total códigos de falha"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Hint(
                        if (d.readAt == null) "Códigos, prontidão, freeze frame e testes da sonda."
                        else "Luz de injeção ${if (mil) "acesa" else "apagada"}"
                    )
                }
            }
            Button(onClick = { ObdManager.readDiagnostics() }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (d.readAt == null) "Ler diagnóstico" else "Ler de novo")
            }
        }
        IdleSection(busy)
        DecelSection(busy)

        if (d.readAt == null) return@Screen

        if (total > 0) {
            Section("Códigos de falha", icon = Icons.Filled.Warning) {
                stored.forEach { DtcRow(it, true) }
                pending.forEach { DtcRow(it, false) }
                OutlinedButton(onClick = { confirmClear = true }, enabled = busy == null) { Text("Apagar códigos") }
            }
        }

        d.readiness?.let { r ->
            val done = r.monitors.count { it.second }
            Section("Prontidão para inspeção", icon = Icons.Filled.CheckCircle, trailing = {
                StatusPill("$done de ${r.monitors.size}", if (done == r.monitors.size) Good else Warn)
            }) {
                r.monitors.forEach { (name, ok) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.HourglassEmpty, null,
                            tint = if (ok) Good else Warn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Text(if (ok) "pronto" else "incompleto", style = MaterialTheme.typography.labelMedium,
                            color = if (ok) MaterialTheme.colorScheme.onSurfaceVariant else Warn)
                    }
                }
                if (r.monitors.any { !it.second }) {
                    Callout("Monitores incompletos ficam prontos sozinhos depois de alguns ciclos de uso " +
                        "(partida a frio + estrada). É normal depois de apagar códigos ou desligar a bateria.")
                }
            }
        }

        Section("Freeze frame", icon = Icons.Filled.Memory) {
            if (d.freezeDtc == null) Hint("Nenhum quadro congelado — ele só existe quando há um código registrado.")
            else {
                Text("Gravado quando apareceu ${d.freezeDtc}", fontWeight = FontWeight.Medium)
                d.freeze.forEach { (k, value) -> KeyValue(k, value) }
            }
        }

        if (d.mode06Can.isNotEmpty()) Section("Testes dos monitores (modo 06)", icon = Icons.Filled.Science) {
            d.mode06Can.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.name} · teste %02X".format(t.tid), style = MaterialTheme.typography.bodyMedium)
                        Hint("valor ${t.value} · faixa ${t.min}…${t.max} (unidade %02X)".format(t.uas))
                    }
                    StatusPill(if (t.passed) "OK" else "fora", if (t.passed) Good else Bad)
                }
            }
            Hint("Resultados brutos da última vez que a central rodou cada teste. \"Fora\" significa que " +
                "o valor ficou fora dos limites que a própria central definiu.")
        }

        if (d.mode06Can.isEmpty()) Section("Testes da sonda (modo 06)", icon = Icons.Filled.Science) {
            if (d.mode06.isEmpty()) Hint("A central não devolveu resultados.")
            d.mode06.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.name} · ${t.componentName}", style = MaterialTheme.typography.bodyMedium)
                        Hint("%.3f %s · limite %s %.3f".format(t.value, t.unit, if (t.limitIsMin) "mín." else "máx.", t.limit))
                    }
                    StatusPill(if (t.passed) "OK" else "fora", if (t.passed) Good else Bad)
                }
            }
            if (d.mode06.isNotEmpty()) {
                Hint("Resultados da última vez que a central rodou o teste. Tempos de troca altos " +
                    "ou faixa de tensão estreita indicam sonda lenta.")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Filled.Delete, null) },
            title = { Text("Apagar códigos?") },
            text = { Text("Apaga os códigos, o freeze frame e os monitores de prontidão. Se o defeito continuar, o código volta.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; ObdManager.clearDtcs() }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

// -------------------------------------------------------------- marcha lenta

private val idleLabels = listOf("Situação A", "Situação B", "Ar-condicionado ligado", "Faróis e ventilação ligados")

@Composable
private fun IdleSection(busy: String?) {
    val runs by ObdManager.idleRuns.collectAsState()
    var label by remember { mutableStateOf(idleLabels[0]) }
    var custom by remember { mutableStateOf("") }
    val chosen = custom.trim().ifEmpty { label }

    Section("Teste de marcha lenta", icon = Icons.Filled.Speed, trailing = {
        if (runs.isNotEmpty()) TextButton(onClick = { ObdManager.clearIdleRuns() }) { Text("Limpar") }
    }) {
        Hint("Motor quente, parado, sem acelerar. Rode 45 s numa situação, mude uma coisa (ligar o ar, " +
            "depois de uma limpeza, uma mangueira desconectada…) e rode de novo: o app compara as duas. " +
            "Se o motor morrer, o teste para e registra.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            idleLabels.forEach { l ->
                FilterChip(selected = custom.isBlank() && label == l, onClick = { label = l; custom = "" }, label = { Text(l) })
            }
        }
        OutlinedTextField(custom, { custom = it.take(40) }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("ou descreva a situação") }, shape = RoundedCornerShape(14.dp))
        FilledTonalButton(onClick = { ObdManager.idleTest(chosen) }, enabled = busy == null) {
            Text("Testar: $chosen", maxLines = 1)
        }
        runs.forEach { IdleCard(it) }
        idleComparison(runs)?.let { (text, tone) -> Callout(text, tone) }
    }
}

@Composable
private fun IdleCard(r: IdleResult) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.background).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (r.stalled) StatusPill("morreu em ${r.seconds}s", Bad)
            else {
                val unstable = (r.rpm?.std ?: 0.0) > 60
                StatusPill(if (unstable) "instável" else "estável", if (unstable) Warn else Good)
            }
        }
        fun s(x: Stats?, p: String, u: String) = x?.let { "${p.format(it.avg)}$u  (${p.format(it.min)}–${p.format(it.max)})" } ?: "—"
        KeyValue("Rotação", s(r.rpm, "%.0f", " rpm"))
        KeyValue("Oscilação da rotação", r.rpm?.let { "±%.0f rpm".format(it.std) } ?: "—",
            r.rpm?.let { if (it.std > 60) Warn else null })
        KeyValue("Coletor", s(r.map, "%.0f", " kPa"), r.map?.let { if (it.avg > 45) Warn else null })
        KeyValue("Borboleta", s(r.throttle, "%.1f", "%"))
        KeyValue("Avanço", s(r.advance, "%.1f", "°"))
        r.coolant?.let { KeyValue("Temp. motor no início", "%.0f °C".format(it), if (it < 80) Cyan else null) }
    }
}

@Composable
private fun DecelSection(busy: String?) {
    val r by ObdManager.decel.collectAsState()
    Section("Teste de desaceleração", icon = Icons.Filled.ShowChart) {
        Hint("Motor quente, parado. Toque em iniciar, espere 3 s em lenta, acelere até ~2.500 rpm e solte " +
            "o pedal de uma vez. O app mede o quanto a rotação afunda abaixo da lenta e quanto demora para voltar.")
        FilledTonalButton(onClick = { ObdManager.decelTest() }, enabled = busy == null) { Text("Iniciar (20 s)") }
        r?.let { d ->
            LineChart(d.samples.map { it.second }, min = 0.0,
                max = maxOf(3000.0, (d.peak ?: 0.0) * 1.1), reference = d.baseline)
            KeyValue("Lenta antes", d.baseline?.let { "%.0f rpm".format(it) } ?: "—")
            KeyValue("Pico", d.peak?.let { "%.0f rpm".format(it) } ?: "—")
            KeyValue("Mínimo depois de soltar", if (d.stalled) "MORREU" else d.dip?.let { "%.0f rpm".format(it) } ?: "—",
                if (d.stalled) Bad else null)
            KeyValue("Afundou abaixo da lenta", d.undershoot?.takeIf { !d.stalled }?.let { "%.0f rpm".format(it) } ?: "—",
                d.undershoot?.let { if (it > 150) Warn else null })
            KeyValue("Tempo para voltar", d.recoverySec?.let { "%.1f s".format(it) } ?: "—")
            val (txt, tone) = when {
                d.stalled -> "O motor morreu na desaceleração. Ao soltar o pedal, a borboleta volta para a posição " +
                    "de repouso que a central aprendeu — pequena demais para o ar que o motor precisa. É o sintoma " +
                    "clássico de adaptação errada (ou aprendida com a entrada de ar falsa aberta)." to Tone.BAD
                (d.peak ?: 0.0) < 1500 -> "Não detectei a acelerada: acelere até ~2.500 rpm e solte." to Tone.INFO
                (d.undershoot ?: 0.0) > 150 -> "A rotação afundou %.0f rpm abaixo da lenta antes de voltar: ".format(d.undershoot) +
                    "está no limite de morrer. Mesma causa provável: posição de repouso aprendida pequena demais." to Tone.WARN
                else -> "Desaceleração normal: a rotação voltou para a lenta sem afundar." to Tone.GOOD
            }
            Callout(txt, tone)
        }
    }
}

/** Compara as duas últimas execuções; com uma só, comenta o que ela mostra. */
private fun idleComparison(runs: List<IdleResult>): Pair<String, Tone>? {
    if (runs.isEmpty()) return null
    val last = runs.last()
    if (runs.size >= 2) {
        val prev = runs[runs.size - 2]
        val dThr = (last.throttle?.avg ?: 0.0) - (prev.throttle?.avg ?: 0.0)
        val dRpm = (last.rpm?.avg ?: 0.0) - (prev.rpm?.avg ?: 0.0)
        val dMap = (last.map?.avg ?: 0.0) - (prev.map?.avg ?: 0.0)
        val worse = last.stalled || dRpm < -80 || (last.rpm?.std ?: 0.0) > (prev.rpm?.std ?: 0.0) + 30
        val base = "De \"${prev.label}\" para \"${last.label}\": borboleta %+.1f pontos, rotação %+.0f rpm, coletor %+.0f kPa. "
            .format(dThr, dRpm, dMap)
        return when {
            !worse -> base + "A lenta se manteve: a central compensou a mudança. Lembre que a desaceleração " +
                "(teste abaixo) é onde problemas de borboleta mais aparecem." to Tone.INFO
            dThr >= 1.5 -> base + "A central ABRIU mais a borboleta e mesmo assim a lenta caiu: o ar não está passando " +
                "como deveria. Aponta para sujeira/obstrução no corpo de borboleta ou sensor de posição lendo errado." to Tone.WARN
            else -> base + "A central quase NÃO abriu a borboleta para compensar. Aponta para posição de repouso " +
                "aprendida errada (resolve com a adaptação da borboleta no scanner do fabricante) ou limite de abertura." to Tone.WARN
        }
    }
    return when {
        last.stalled -> "O motor morreu durante o teste (${last.seconds}s)." to Tone.BAD
        (last.map?.avg ?: 0.0) > 45 && (last.throttle?.avg ?: 99.0) < 3 ->
            ("Coletor alto (%.0f kPa) com a borboleta quase fechada (%.1f%%): está entrando ar por fora da borboleta. " +
                "Causas comuns: mangueira solta ou rachada, válvula de purga do cânister presa aberta, junta do coletor.")
                .format(last.map?.avg, last.throttle?.avg) to Tone.WARN
        (last.rpm?.std ?: 0.0) > 60 -> "Lenta instável (±%.0f rpm).".format(last.rpm?.std) to Tone.WARN
        else -> "Lenta estável. Rode outra situação para comparar." to Tone.GOOD
    }
}

@Composable
private fun DtcRow(code: String, confirmed: Boolean) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).background((if (confirmed) Bad else Warn).copy(alpha = 0.14f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Text(code, style = MaterialTheme.typography.labelLarge.merge(Tabular), fontWeight = FontWeight.Bold,
                color = if (confirmed) Bad else Warn)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(DtcInfo.describe(code), style = MaterialTheme.typography.bodyMedium)
            Hint(if (confirmed) "confirmado" else "pendente")
        }
    }
}

// ============================================================== FERRAMENTAS

@Composable
fun ToolsScreen(onShare: (File, String) -> Unit) {
    val connected by ObdManager.connected.collectAsState()
    val adapter by ObdManager.adapterName.collectAsState()
    val terminal by ObdManager.terminal.collectAsState()
    val report by ObdManager.report.collectAsState()
    val trips by ObdManager.trips.collectAsState()
    val log by ObdManager.logTail.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    val rate by ObdManager.rate.collectAsState()
    var cmd by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(false) }
    val canSend = connected && busy == null

    Screen {
        Section("Adaptador", icon = Icons.Filled.Bluetooth, trailing = {
            StatusPill(if (connected) "conectado" else "desconectado",
                if (connected) Good else MaterialTheme.colorScheme.onSurfaceVariant)
        }) {
            KeyValue("Dispositivo", adapter ?: "—")
            KeyValue("Protocolo", ObdManager.vehicle.collectAsState().value.protocol)
            KeyValue("Velocidade atual", if (connected) "%.1f leituras/s".format(rate) else "—")
            FilledTonalButton(onClick = { ObdManager.runProbe() }, enabled = canSend) { Text("Testar capacidade do adaptador") }
            report?.let {
                TextButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Esconder relatório" else "Ver último relatório") }
                AnimatedVisibility(showReport) { Mono(it) }
            }
        }

        Section("Terminal", icon = Icons.Filled.Terminal) {
            Hint("Comandos do adaptador (AT…) ou do carro (modo + PID em hexadecimal). " +
                "Cuidado: comandos como 04 apagam dados da central.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    cmd, { cmd = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("ATRV, 010C, 0902…") },
                    shape = RoundedCornerShape(14.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.merge(Tabular),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outline),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) { ObdManager.sendRaw(cmd); cmd = "" } }),
                )
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = { ObdManager.sendRaw(cmd); cmd = "" }, enabled = canSend && cmd.isNotBlank()) {
                    Icon(Icons.Filled.Send, "Enviar", tint = if (canSend && cmd.isNotBlank()) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ATRV", "ATDP", "ATI", "0100", "0101", "0902", "0904").forEach { c ->
                    SuggestionChip(onClick = { ObdManager.sendRaw(c) }, label = { Text(c) }, enabled = canSend)
                }
            }
            if (terminal.isNotEmpty()) {
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.background).padding(12.dp),
                ) { Mono(terminal.takeLast(40).joinToString("\n")) }
            }
        }

        Section("Viagens gravadas", icon = Icons.Filled.Route) {
            if (trips.isEmpty()) Hint("Nenhuma viagem gravada ainda — grave pelo Painel.")
            trips.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tripTitle(f), style = MaterialTheme.typography.bodyMedium)
                        Hint("${(f.length() / 1024).coerceAtLeast(1)} KB · CSV")
                    }
                    IconButton(onClick = { onShare(f, "text/csv") }) { Icon(Icons.Filled.Share, "Compartilhar") }
                    IconButton(onClick = { ObdManager.deleteTrip(f) }) {
                        Icon(Icons.Filled.Delete, "Apagar", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Section("Log", icon = Icons.Filled.Timeline, trailing = {
            TextButton(onClick = { onShare(ObdManager.exportLogFile(), "text/plain") }) {
                Icon(Icons.Filled.Share, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Exportar")
            }
        }) {
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background).padding(12.dp),
            ) { Mono(log.takeLast(30).joinToString("\n").ifBlank { "(vazio)" }) }
        }
    }
}

/** "viagem-20261028-105732.csv" -> "28/10 às 10:57" */
private fun tripTitle(f: File): String {
    val m = Regex("viagem-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})").find(f.name) ?: return f.name
    val (_, mo, d, h, mi) = m.destructured
    return "Viagem de $d/$mo às $h:$mi"
}
