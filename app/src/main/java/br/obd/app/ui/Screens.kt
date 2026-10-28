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
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShowChart
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

    Screen {
        if (!connected) { NotConnected(onConnect); return@Screen }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Gauge(v["rpm"], 7000.0, "Rotação", "rpm", Modifier.weight(1f), warnAt = 6000.0, majorStep = 1000.0)
            Gauge(v["speed"], 200.0, "Velocidade", "km/h", Modifier.weight(1f), majorStep = 20.0)
        }

        val coolant = v["coolant"]; val battery = v["battery"]
        val cards = listOf(
            Card4("Motor", fmt(coolant, unit = " °C"), Icons.Filled.Thermostat,
                coolant?.let { if (it > 105) Bad else if (it < 70) Cyan else null }),
            Card4("Carga", fmt(v["load"], unit = "%"), Icons.Filled.Equalizer, null),
            Card4("Borboleta", fmt(v["throttle"], unit = "%"), Icons.Filled.Tune, null),
            Card4("Admissão", fmt(v["iat"], unit = " °C"), Icons.Filled.Air, null),
            Card4("Coletor", fmt(v["map"], unit = " kPa"), Icons.Filled.Compress, null),
            Card4("Avanço", fmt(v["advance"], "%.1f", "°"), Icons.Filled.Bolt, null),
        )
        cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { StatCard(it.label, it.value, Modifier.weight(1f), it.color, it.icon) }
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
            Hint("Último minuto · ~%.1f leituras por segundo (limite da linha K)".format(rate))
        }

        TripCard(recording)
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
    var confirmReset by remember { mutableStateOf(false) }

    Screen {
        if (!connected) { NotConnected(onConnect); return@Screen }

        Section("Correções de combustível", icon = Icons.Filled.Tune) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TrimColumn("Curto prazo", "agora", v["stft"], Modifier.weight(1f))
                TrimColumn("Longo prazo", "aprendido", v["ltft"], Modifier.weight(1f))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            KeyValue("Sistema", Pids.fuelStatusText(v["fuelStatus"]?.toInt()))
            KeyValue("A central informa", Pids.fuelTypeText(fuelType))
            val (advice, tone) = trimAdvice(v["ltft"], v["stft"])
            Callout(advice, tone)
            if (stuck) {
                Callout(
                    "A correção longa está parada em %+.1f%% em todas as leituras. ".format(v["ltft"] ?: 0.0) +
                        "O aprendizado de longo prazo não está se movendo, como se tivesse chegado ao limite.",
                    Tone.WARN,
                )
            }
        }

        Section("Sonda lambda", icon = Icons.Filled.Sensors) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Antes do catalisador", fmt(v["o2s1"], "%.2f", " V"), Modifier.weight(1f))
                StatCard("Depois do catalisador", fmt(v["o2s2"], "%.2f", " V"), Modifier.weight(1f))
            }
            LineChart(history.takeLast(150).map { it.o2 }, min = 0.0, max = 1.0, color = Cyan, reference = 0.45)
            Hint("Motor quente: a sonda de antes do catalisador deve subir e descer sem parar " +
                "entre ~0,1 e ~0,9 V (linha tracejada = 0,45 V).")
        }

        Section("Teste da sonda", icon = Icons.Filled.Science) {
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

        Section("Ajudar a central a reaprender", icon = Icons.Filled.Timeline) {
            listOf(
                "Abasteça bastante (pelo menos 1/4 do tanque): a central detecta o abastecimento pelo nível.",
                "Rode 10 a 15 minutos em velocidade constante, motor quente.",
                "Acompanhe aqui a correção longa: ela deve caminhar para perto de 0%.",
                "Se ela não sair do lugar e a sonda estiver boa, o caminho é um scanner com protocolo VW " +
                    "(ex.: cabo KKL + VCDS) para zerar a adaptação da central.",
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

private fun trimAdvice(ltft: Double?, stft: Double?): Pair<String, Tone> {
    if (ltft == null) return "Ligue o motor para ver as correções." to Tone.INFO
    val total = ltft + (stft ?: 0.0)
    return when {
        total < -10 -> ("A central está tirando %.0f%% do combustível: mistura rica. ".format(-total) +
            "Num flex é o que acontece quando ela acha que tem mais etanol do que realmente tem. " +
            "Outras causas: bico pingando ou pressão alta.") to Tone.WARN
        total > 10 -> ("A central está adicionando %.0f%% de combustível: mistura pobre. ".format(total) +
            "Pode ser entrada falsa de ar, bomba/filtro fracos, ou a central achando que tem gasolina com etanol no tanque.") to Tone.WARN
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

        Section("Testes da sonda (modo 06)", icon = Icons.Filled.Science) {
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
            KeyValue("Protocolo", "ISO 9141-2 (linha K)")
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
