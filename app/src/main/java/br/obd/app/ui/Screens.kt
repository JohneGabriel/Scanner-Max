package br.obd.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import br.obd.app.DtcInfo
import br.obd.app.ObdManager
import br.obd.app.Pids
import java.io.File

@Composable
private fun Screen(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) { content(); Spacer(Modifier.height(24.dp)) }
}

private fun fmt(v: Double?, pattern: String = "%.0f", unit: String = "") =
    v?.let { pattern.format(it) + unit } ?: "—"

@Composable
fun NotConnected(onConnect: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Nenhum adaptador conectado", style = MaterialTheme.typography.titleMedium)
        Hint("Ligue a ignição e conecte ao ELM327.")
        Button(onClick = onConnect) { Text("Conectar") }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Gauge(v["rpm"], 7000.0, "Rotação", "rpm", Modifier.weight(1f), warnAt = 6000.0)
            Gauge(v["speed"], 200.0, "Velocidade", "km/h", Modifier.weight(1f))
        }
        val cards = listOf(
            Triple("Temp. motor", fmt(v["coolant"], unit = " °C"), v["coolant"]?.let { if (it > 105) Bad else null }),
            Triple("Carga", fmt(v["load"], unit = " %"), null),
            Triple("Borboleta", fmt(v["throttle"], unit = " %"), null),
            Triple("Admissão", fmt(v["iat"], unit = " °C"), null),
            Triple("Coletor", fmt(v["map"], unit = " kPa"), null),
            Triple("Avanço", fmt(v["advance"], "%.1f", "°"), null),
            Triple("Bateria", fmt(v["battery"], "%.1f", " V"), v["battery"]?.let { if (it < 12.0 || it > 14.8) Warn else null }),
        )
        cards.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (l, value, c) -> StatCard(l, value, Modifier.weight(1f), c) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Section("Rotação — último minuto") {
            LineChart(history.takeLast(200).map { it.rpm }, min = 0.0)
            Hint("O carro responde a ~%.1f pedidos por segundo (limite da linha K).".format(rate))
        }
        Section("Gravar viagem") {
            if (recording == null) {
                Hint("Grava todos os dados uma vez por segundo num arquivo CSV (abre no Excel/Planilhas).")
                Button(onClick = { ObdManager.startRecording() }) {
                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                    Text("Começar a gravar")
                }
            } else {
                Text("Gravando em ${recording!!.name}", color = Bad)
                Button(onClick = { ObdManager.stopRecording() },
                    colors = ButtonDefaults.buttonColors(containerColor = Bad)) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp))
                    Text("Parar")
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
    var confirmReset by remember { mutableStateOf(false) }

    Screen {
        if (!connected) { NotConnected(onConnect); return@Screen }

        Section("Correções de combustível") {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f)) {
                    Hint("Curto prazo (agora)")
                    Text(fmt(v["stft"], "%+.1f", "%"), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = trimColor(v["stft"]))
                    TrimBar(v["stft"])
                }
                Column(Modifier.weight(1f)) {
                    Hint("Longo prazo (aprendido)")
                    Text(fmt(v["ltft"], "%+.1f", "%"), style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = trimColor(v["ltft"]))
                    TrimBar(v["ltft"])
                }
            }
            KeyValue("Sistema", Pids.fuelStatusText(v["fuelStatus"]?.toInt()))
            KeyValue("A central informa", Pids.fuelTypeText(fuelType))
            Text(trimAdvice(v["ltft"], v["stft"]), style = MaterialTheme.typography.bodyMedium)
        }

        Section("Sonda lambda") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Antes do catalisador (B1S1)", fmt(v["o2s1"], "%.2f", " V"), Modifier.weight(1f))
                StatCard("Depois (B1S2)", fmt(v["o2s2"], "%.2f", " V"), Modifier.weight(1f))
            }
            LineChart(history.takeLast(150).map { it.o2 }, min = 0.0, max = 1.0,
                color = MaterialTheme.colorScheme.secondary, reference = 0.45)
            Hint("Com o motor quente, a B1S1 deve subir e descer sem parar entre ~0,1 e ~0,9 V. " +
                "A B1S2 (depois do catalisador) deve ficar mais estável.")
        }

        Section("Teste da sonda lambda") {
            Hint("Lê só a sonda por 30 s e conta as oscilações. Faça com o motor quente, " +
                "de preferência segurando ~2.000 rpm parado.")
            Button(onClick = { ObdManager.lambdaTest(30) }, enabled = busy == null) { Text("Testar sonda (30 s)") }
            lambda?.let { r ->
                HorizontalDivider()
                Text(r.verdict, fontWeight = FontWeight.Medium)
                KeyValue("Leituras", "${r.samples} em ${r.seconds} s")
                KeyValue("Faixa", "%.2f – %.2f V (média %.2f)".format(r.min, r.max, r.avg))
                KeyValue("Trocas por segundo", "%.2f".format(r.switchesPerSec))
                KeyValue("Tempo em rico", "${r.richPct}%")
                KeyValue("Rotação no teste", fmt(r.rpm, unit = " rpm"))
                Hint("Observação: a linha K só deixa ler a sonda ~3,5 vezes por segundo, então oscilações " +
                    "muito rápidas podem não aparecer. Sonda boa: faixa larga e trocas frequentes.")
            }
        }

        Section("Zerar adaptação (modo 04)") {
            Hint("É a única escrita que o OBD genérico aceita. Apaga os códigos de falha e a prontidão; " +
                "em algumas centrais também zera o aprendizado de combustível. O app mede a correção " +
                "longa antes e depois para saber se funcionou. Faça com o motor ligado e quente.")
            OutlinedButton(onClick = { confirmReset = true }, enabled = busy == null) { Text("Tentar zerar a adaptação") }
            reset?.let { HorizontalDivider(); Text(it) }
        }

        Section("Ajudar a central a reaprender") {
            Text(
                "1. Abasteça uma quantidade grande (pelo menos 1/4 do tanque): a central detecta o " +
                    "abastecimento pelo nível do tanque.\n" +
                    "2. Rode 10 a 15 minutos em velocidade constante, com o motor quente.\n" +
                    "3. Acompanhe aqui a correção longa: ela deve caminhar para perto de 0%.\n" +
                    "4. Se a correção continuar muito negativa e a sonda não oscilar no teste, " +
                    "a suspeita principal é a sonda lambda B1S1.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
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

private fun trimAdvice(ltft: Double?, stft: Double?): String {
    if (ltft == null) return "Ligue o motor para ver as correções."
    val total = ltft + (stft ?: 0.0)
    return when {
        total < -10 -> "A central está tirando %.0f%% do combustível: a mistura está rica. ".format(-total) +
            "Num flex isso é o que acontece quando a central acha que tem mais etanol do que realmente " +
            "tem (ela injeta mais para etanol). Outras causas: bico pingando ou pressão alta."
        total > 10 -> "A central está adicionando %.0f%% de combustível: a mistura está pobre. ".format(total) +
            "Pode ser entrada falsa de ar, bomba/filtro fracos, ou a central achando que tem gasolina com etanol no tanque."
        else -> "Correções dentro do normal (±10%): a central está acertando a mistura."
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
        Button(onClick = { ObdManager.readDiagnostics() }, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            Text(if (d.readAt == null) "Ler diagnóstico" else "Ler de novo")
        }
        if (d.readAt == null) {
            Hint("Lê os códigos de falha, a prontidão para inspeção, o freeze frame e os testes que a " +
                "central faz nas sondas (modo 06).")
            return@Screen
        }

        Section("Códigos de falha") {
            val stored = d.stored.orEmpty(); val pending = d.pending.orEmpty()
            if (stored.isEmpty() && pending.isEmpty()) Text("Nenhum código registrado.", color = Good)
            stored.forEach { DtcRow(it, "confirmado") }
            pending.forEach { DtcRow(it, "pendente") }
            if (stored.isNotEmpty() || pending.isNotEmpty()) {
                OutlinedButton(onClick = { confirmClear = true }, enabled = busy == null) { Text("Apagar códigos") }
            }
        }

        d.readiness?.let { r ->
            Section("Prontidão para inspeção") {
                KeyValue("Luz de injeção", if (r.milOn) "ACESA" else "apagada", if (r.milOn) Bad else Good)
                KeyValue("Códigos que acendem a luz", r.dtcCount.toString())
                HorizontalDivider()
                r.monitors.forEach { (name, ok) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.HourglassEmpty, null,
                            tint = if (ok) Good else Warn, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(name, Modifier.weight(1f))
                        Text(if (ok) "pronto" else "incompleto", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (r.monitors.any { !it.second }) {
                    Hint("Monitores incompletos ficam prontos sozinhos depois de alguns ciclos de uso " +
                        "(partida fria + rodar em estrada). É normal depois de apagar códigos ou desligar a bateria.")
                }
            }
        }

        Section("Freeze frame") {
            if (d.freezeDtc == null) Hint("Nenhum quadro congelado (só existe quando há um código registrado).")
            else {
                Text("Gravado quando apareceu ${d.freezeDtc}", fontWeight = FontWeight.Medium)
                d.freeze.forEach { (k, value) -> KeyValue(k, value) }
            }
        }

        Section("Testes da sonda feitos pela central (modo 06)") {
            if (d.mode06.isEmpty()) Hint("A central não devolveu resultados.")
            d.mode06.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.name} · ${t.componentName}", style = MaterialTheme.typography.bodyMedium)
                        Hint("valor %.3f %s · limite %s %.3f".format(t.value, t.unit, if (t.limitIsMin) "mín." else "máx.", t.limit))
                    }
                    Text(if (t.passed) "OK" else "FORA", color = if (t.passed) Good else Bad, fontWeight = FontWeight.Bold)
                }
            }
            if (d.mode06.isNotEmpty()) {
                Hint("Tempos de troca altos ou faixa de tensão estreita indicam sonda lenta. " +
                    "Os valores são da última vez que a central rodou o teste.")
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Apagar códigos?") },
            text = { Text("Apaga os códigos, o freeze frame e os monitores de prontidão. Se o defeito continuar, o código volta.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; ObdManager.clearDtcs() }) { Text("Apagar") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DtcRow(code: String, kind: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(code, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = if (kind == "confirmado") Bad else Warn)
            Spacer(Modifier.size(8.dp))
            Hint(kind)
        }
        Text(DtcInfo.describe(code), style = MaterialTheme.typography.bodyMedium)
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
    var cmd by remember { mutableStateOf("") }
    var showReport by remember { mutableStateOf(false) }

    Screen {
        Section("Terminal") {
            Hint("Manda qualquer comando ao adaptador (AT…) ou ao carro (modo + PID em hexadecimal). " +
                "Cuidado: comandos como 04 apagam dados da central.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    cmd, { cmd = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("ex.: ATRV, 010C, 0902") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { ObdManager.sendRaw(cmd); cmd = "" }),
                )
                IconButton(onClick = { ObdManager.sendRaw(cmd); cmd = "" }, enabled = connected && busy == null) {
                    Icon(Icons.Filled.Send, "Enviar")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ATRV", "ATDP", "0100", "0101", "0902").forEach { c ->
                    AssistChip(onClick = { ObdManager.sendRaw(c) }, label = { Text(c) }, enabled = connected && busy == null)
                }
            }
            if (terminal.isNotEmpty()) Mono(terminal.takeLast(40).joinToString("\n"))
        }

        Section("Adaptador") {
            KeyValue("Conectado a", adapter ?: "—")
            Button(onClick = { ObdManager.runProbe() }, enabled = connected && busy == null) { Text("Testar capacidade do adaptador") }
            report?.let {
                TextButton(onClick = { showReport = !showReport }) { Text(if (showReport) "Esconder relatório" else "Ver último relatório") }
                if (showReport) Mono(it)
            }
        }

        Section("Viagens gravadas") {
            if (trips.isEmpty()) Hint("Nenhuma viagem gravada ainda (grave pelo Painel).")
            trips.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyMedium)
                        Hint("${f.length() / 1024} KB")
                    }
                    IconButton(onClick = { onShare(f, "text/csv") }) { Icon(Icons.Filled.Share, "Compartilhar") }
                    IconButton(onClick = { ObdManager.deleteTrip(f) }) { Icon(Icons.Filled.Delete, "Apagar") }
                }
            }
        }

        Section("Log") {
            Button(onClick = { onShare(ObdManager.exportLogFile(), "text/plain") }) {
                Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text("Exportar log")
            }
            Mono(log.takeLast(30).joinToString("\n"))
        }
    }
}
