package br.obd.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.obd.app.FuelKind
import br.obd.app.Garage
import br.obd.app.Maintenance
import br.obd.app.MaintItem
import br.obd.app.ObdManager
import br.obd.app.PerfRunner
import br.obd.app.PerfState
import br.obd.app.Reports
import br.obd.app.TripStore

@Composable
fun ToolsHubScreen(app: AppActions) {
    ScreenColumn {
        MenuRow(Icons.Filled.Search, "Consultar código de falha", "Explicação, causas e pesquisa") { app.nav.go("lookup") }
        MenuRow(Icons.Filled.LocalGasStation, "Etanol ou gasolina?", "Qual compensa no seu carro") { app.nav.go("flex") }
        MenuRow(Icons.Filled.Timer, "Aceleração 0–100 km/h") { app.nav.go("perf") }
        MenuRow(Icons.Filled.Build, "Manutenção", "Lembretes por quilometragem") { app.nav.go("maint") }
        MenuRow(Icons.Filled.Usb, "Central do fabricante", "Cabo KKL (VW/Audi)") { app.nav.go("cable") }
        MenuRow(Icons.Filled.Terminal, "Terminal") { app.nav.go("terminal") }
    }
}

// ------------------------------------------------------------------ etanol x gasolina

private fun parseNum(s: String) = s.replace(",", ".").trim().toDoubleOrNull()

@Composable
fun FlexScreen() {
    val profile by Garage.current.collectAsState()
    var gas by remember { mutableStateOf(profile?.takeIf { it.fuel == FuelKind.GASOLINA }?.pricePerL?.let { "%.2f".format(it) } ?: "") }
    var eth by remember { mutableStateOf(profile?.takeIf { it.fuel == FuelKind.ETANOL }?.pricePerL?.let { "%.2f".format(it) } ?: "") }
    var kmG by remember { mutableStateOf("") }
    var kmE by remember { mutableStateOf("") }
    val g = parseNum(gas); val e = parseNum(eth)
    val kg = parseNum(kmG); val ke = parseNum(kmE)
    // sem consumos medidos, usa a regra de 70% (etanol rende ~30% menos)
    val ratio = if (kg != null && ke != null && kg > 0) ke / kg else 0.70
    ScreenColumn {
        AppCard {
            Text("Preço do litro", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(gas, { gas = it }, Modifier.weight(1f), label = { Text("Gasolina R$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(eth, { eth = it }, Modifier.weight(1f), label = { Text("Etanol R$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
            }
            Text("Consumo do seu carro (opcional)", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(kmG, { kmG = it }, Modifier.weight(1f), label = { Text("km/L gasolina") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(kmE, { kmE = it }, Modifier.weight(1f), label = { Text("km/L etanol") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
            }
            Hint("Sem os consumos, o app usa a regra dos 70%: o etanol compensa se custar até 70% da gasolina.")
        }
        if (g != null && e != null && g > 0) {
            val pct = e / g
            val ethBetter = pct < ratio
            val costG = kg?.let { g / it }; val costE = ke?.let { e / it }
            AnimatedContent(ethBetter, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "flex") { better ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background((if (better) Good else Accent).copy(alpha = 0.14f)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Abasteça com", style = MaterialTheme.typography.titleSmall)
                    FitText(if (better) "ETANOL" else "GASOLINA", MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold), color = if (better) Good else Accent)
                    Text("O etanol custa %.0f%% da gasolina (limite: %.0f%%).".format(pct * 100, ratio * 100), textAlign = TextAlign.Center)
                }
            }
            if (costG != null && costE != null) AppCard {
                KeyValue("Custo por km na gasolina", "R$ %.3f".format(costG))
                KeyValue("Custo por km no etanol", "R$ %.3f".format(costE))
                KeyValue("A cada 1.000 km você economiza", "R$ %.2f".format(kotlin.math.abs(costG - costE) * 1000))
            }
        }
        val tot = TripStore.trips.collectAsState().value.take(10).let { br.obd.app.TripTotals.of(it) }
        tot.kmPerL?.let { Hint("Média medida pelo app nas últimas viagens: %.1f km/L (${profile?.fuelLabel ?: ""}).".format(it)) }
    }
}

// ------------------------------------------------------------------ 0–100

@Composable
fun PerfScreen(app: AppActions) {
    val st by PerfRunner.state.collectAsState()
    val best by PerfRunner.best.collectAsState()
    val connected by ObdManager.connected.collectAsState()
    val veh by Garage.current.collectAsState()
    var accepted by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(PerfState.Mode.ACCEL) }
    ScreenColumn {
        Callout("Use só em local fechado e seguro, nunca em vias públicas. Concentre-se na direção: o app avisa com som e vibração.", Tone.WARN)
        val s = st
        if (s == null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == PerfState.Mode.ACCEL, onClick = { mode = PerfState.Mode.ACCEL }, label = { Text("Aceleração") })
                FilterChip(selected = mode == PerfState.Mode.BRAKE, onClick = { mode = PerfState.Mode.BRAKE }, label = { Text("Frenagem") })
            }
            AppCard {
                Text("Como funciona", style = MaterialTheme.typography.titleMedium)
                if (mode == PerfState.Mode.ACCEL) {
                    Step(1, "Pare o carro completamente.")
                    Step(2, "Toque em Armar. Quando aparecer PRONTO, acelere.")
                    Step(3, "O cronômetro começa sozinho e marca 40, 60, 80, 100 km/h e os 400 m.")
                    if (veh?.massKg == null) Hint("Informe o peso do carro em Meu veículo para o app estimar a potência nas rodas.")
                } else {
                    Step(1, "Toque em Armar e passe de 80 km/h.")
                    Step(2, "Freie forte até parar. O app mede tempo e distância e converte para 100→0 km/h.")
                }
                Hint("A precisão depende de quantas leituras por segundo o carro responde: em CAN fica em décimos de segundo; na linha K, ~0,3 s.")
            }
            Button(onClick = { if (accepted) PerfRunner.start(mode) else accepted = true }, enabled = connected, modifier = Modifier.fillMaxWidth()) {
                BtnLabel(if (!connected) "Conecte para medir" else if (accepted) "Armar" else "Entendi, estou em local seguro")
            }
        } else {
            val color = when (s.phase) { PerfState.Phase.READY -> Good; PerfState.Phase.RUNNING -> Accent; PerfState.Phase.DONE -> Cyan; else -> Warn }
            val t = rememberInfiniteTransition(label = "perf")
            val sc by t.animateFloat(1f, if (s.phase == PerfState.Phase.READY) 1.05f else 1f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "sc")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(color.copy(alpha = 0.12f)).padding(22.dp).scale(sc),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when {
                    s.phase == PerfState.Phase.WAITING_STOP -> "PARE O CARRO"
                    s.phase == PerfState.Phase.READY && s.mode == PerfState.Mode.ACCEL -> "PRONTO — ACELERE"
                    s.phase == PerfState.Phase.READY -> "PASSE DE 80 km/h E FREIE"
                    s.phase == PerfState.Phase.RUNNING -> "MEDINDO"
                    else -> "CONCLUÍDO"
                }, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.Bold)
                FitText("%.2f s".format(s.elapsed), Tabular.copy(fontSize = 56.sp, fontWeight = FontWeight.Bold))
                Text("${s.speed?.toInt() ?: "—"} km/h · ${s.distance.toInt()} m · %.1f leituras/s".format(s.sampleHz),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            AppCard {
                if (s.mode == PerfState.Mode.ACCEL) {
                    PerfRunner.TARGETS.forEach { tgt ->
                        KeyValue("0 – $tgt km/h", s.splits[tgt]?.let { "%.2f s".format(it) } ?: "—",
                            if (s.splits[tgt] != null && (best[tgt] ?: 999.0) >= s.splits[tgt]!!) Good else null)
                    }
                    KeyValue("400 m", s.quarter?.let { "%.2f s · %.0f km/h".format(it.first, it.second) } ?: "—")
                    s.powerCv?.let { KeyValue("Potência estimada nas rodas", "%.0f cv".format(it)) }
                } else {
                    s.brake?.let { (v, tt, d) ->
                        KeyValue("De", "%.0f km/h".format(v))
                        KeyValue("Tempo até parar", "%.2f s".format(tt))
                        KeyValue("Distância", "%.1f m".format(d))
                        KeyValue("Equivalente 100 → 0", "%.1f m".format(d * (100 / v) * (100 / v)))
                    } ?: Hint("Aguardando a frenagem…")
                }
                if (s.trace.size >= 2) LineChart(s.trace.map { it.second }, min = 0.0, max = maxOf(110.0, s.trace.maxOf { it.second } * 1.1), color = Accent, height = 100)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { PerfRunner.stop() }, Modifier.weight(1f)) { BtnLabel(if (s.phase == PerfState.Phase.DONE) "Fechar" else "Cancelar") }
                if (s.phase == PerfState.Phase.DONE) Button(onClick = { val m = s.mode; PerfRunner.stop(); PerfRunner.start(m) }, Modifier.weight(1f)) { BtnLabel("De novo") }
            }
        }
        if (best.isNotEmpty()) Section("Seus recordes", icon = Icons.Filled.Speed) {
            PerfRunner.TARGETS.forEach { t -> best[t]?.let { KeyValue("0 – $t km/h", "%.2f s".format(it)) } }
            best[400]?.let { KeyValue("400 m", "%.2f s".format(it)) }
            best[-1]?.let { KeyValue("Frenagem 100 → 0", "%.1f m".format(it)) }
            best[-2]?.let { KeyValue("Potência estimada", "%.0f cv".format(it)) }
        }
    }
}

// ------------------------------------------------------------------ manutenção

@Composable
fun MaintenanceScreen() {
    val items by Maintenance.items.collectAsState()
    val veh by Garage.current.collectAsState()
    var odoText by remember { mutableStateOf(Maintenance.odometer()?.toInt()?.toString() ?: "") }
    var edit by remember { mutableStateOf<MaintItem?>(null) }
    val odo = Maintenance.odometer()
    ScreenColumn {
        AppCard {
            Text("Hodômetro", style = MaterialTheme.typography.titleMedium)
            Hint("Informe uma vez o que o painel do carro mostra. Depois o app soma os quilômetros das viagens.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(odoText, { odoText = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f), singleLine = true,
                    label = { Text("km") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(onClick = { odoText.toDoubleOrNull()?.let { Maintenance.setOdometer(it) } }) { BtnLabel("Salvar") }
            }
            odo?.let { Hint("Estimado agora: ${it.toInt()} km · ${veh?.name ?: ""}") }
        }
        items.forEach { i ->
            val rem = Maintenance.remaining(i)
            val c = when { rem == null -> MaterialTheme.colorScheme.onSurfaceVariant; rem < 0 -> Bad; rem <= 1000 -> Warn; else -> Good }
            AppCard(onClick = { edit = i }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Build, c, 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(i.name, style = MaterialTheme.typography.titleSmall)
                        Hint("a cada ${i.intervalKm} km" + (i.lastKm?.let { " · última aos ${it.toInt()} km" } ?: " · sem registro"))
                    }
                    Text(when { rem == null -> "—"; rem < 0 -> "vencida"; else -> "${rem.toInt()} km" }, color = c,
                        style = MaterialTheme.typography.labelLarge.merge(Tabular))
                }
                if (rem != null && i.lastKm != null) {
                    val frac = (1 - rem / i.intervalKm).toFloat().coerceIn(0f, 1f)
                    ShareBar("Uso do intervalo", frac, c)
                }
            }
        }
    }
    edit?.let { i ->
        var interval by remember(i) { mutableStateOf(i.intervalKm.toString()) }
        var last by remember(i) { mutableStateOf(i.lastKm?.toInt()?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = { Text(i.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(interval, { interval = it.filter(Char::isDigit) }, label = { Text("Intervalo (km)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(last, { last = it.filter(Char::isDigit) }, label = { Text("Feita aos (km)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    if (odo != null) TextButton(onClick = { Maintenance.done(i.id); edit = null }) { Text("Fiz agora (${odo.toInt()} km)") }
                }
            },
            confirmButton = { TextButton(onClick = {
                interval.toIntOrNull()?.let { Maintenance.setInterval(i.id, it) }
                Maintenance.setLast(i.id, last.toDoubleOrNull())
                edit = null
            }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { edit = null }) { BtnLabel("Cancelar") } },
        )
    }
}

// ------------------------------------------------------------------ terminal e log

@Composable
fun TerminalScreen() {
    val connected by ObdManager.connected.collectAsState()
    val terminal by ObdManager.terminal.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    var cmd by remember { mutableStateOf("") }
    val canSend = connected && busy == null
    ScreenColumn {
        Callout("Comandos do adaptador (AT…) ou do carro (modo + PID em hexadecimal). Cuidado: comandos como 04 apagam dados da central.", Tone.WARN)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                cmd, { cmd = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("ATRV, 010C, 0902…") },
                shape = RoundedCornerShape(14.dp), textStyle = MaterialTheme.typography.bodyLarge.merge(Tabular),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) { ObdManager.sendRaw(cmd); cmd = "" } }),
            )
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { ObdManager.sendRaw(cmd); cmd = "" }, enabled = canSend && cmd.isNotBlank()) {
                Icon(Icons.Filled.Send, "Enviar", tint = if (canSend && cmd.isNotBlank()) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ATRV", "ATDP", "ATI", "AT@1", "STI", "0100", "0101", "0902", "03").forEach { c ->
                SuggestionChip(onClick = { ObdManager.sendRaw(c) }, label = { Text(c) }, enabled = canSend)
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
            Mono(terminal.takeLast(60).joinToString("\n").ifBlank { "(nada enviado ainda)" })
        }
    }
}

@Composable
fun LogScreen(app: AppActions) {
    val log by ObdManager.logTail.collectAsState()
    ScreenColumn {
        AppCard {
            Text("Log técnico", style = MaterialTheme.typography.titleMedium)
            Hint("Tudo o que o app conversou com o adaptador nesta sessão. Exporte e anexe ao reportar um problema.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { app.share(ObdManager.exportLogFile(), "text/plain") }) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); BtnLabel("Exportar log")
                }
                OutlinedButton(onClick = { Support.bug(app) }) { BtnLabel("Reportar") }
            }
        }
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
            Mono(log.takeLast(120).joinToString("\n").ifBlank { "(vazio)" })
        }
    }
}
