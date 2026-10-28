package br.obd.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.obd.app.CustomPid
import br.obd.app.CustomPids
import br.obd.app.Formula
import br.obd.app.ObdManager
import br.obd.app.Pids
import br.obd.app.Settings

// ------------------------------------------------------------------ HUD

/**
 * Modo HUD: números enormes em fundo preto. Espelhado, o celular no painel reflete a
 * imagem no para-brisa para ser lida sem tirar os olhos da estrada.
 */
@Composable
fun HudScreen() {
    val v by ObdManager.values.collectAsState()
    val prefs by Settings.prefs.collectAsState()
    var mirror by remember { mutableStateOf(false) }
    var showRpm by remember { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    val rpm = v["rpm"]
    val speed = v["speed"]
    val coolant = v["coolant"]
    val hot = (coolant ?: 0.0) >= prefs.coolantWarn
    val frac by animateFloatAsState(((rpm ?: 0.0) / prefs.redline).toFloat().coerceIn(0f, 1f), label = "hudrpm")
    val accent = Accent
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth().graphicsLayer(scaleX = if (mirror) -1f else 1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                FitText((if (showRpm) rpm else speed)?.toInt()?.toString() ?: "—",
                    Tabular.copy(fontSize = 180.sp, fontWeight = FontWeight.Bold), color = if (showRpm && frac > 0.9f) Bad else Color.White)
                Text(if (showRpm) "rpm" else "km/h", color = Color(0xFF9A9A9A), fontSize = 28.sp)
                Spacer(Modifier.height(20.dp))
                Canvas(Modifier.fillMaxWidth(0.9f).height(14.dp)) {
                    drawRoundRect(Color(0xFF222222), cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(if (frac > 0.9f) Bad else accent, size = Size(size.width * frac, size.height), cornerRadius = CornerRadius(size.height / 2))
                }
                Spacer(Modifier.height(12.dp))
                Text(listOfNotNull(if (showRpm) speed?.let { "${it.toInt()} km/h" } else rpm?.let { "${it.toInt()} rpm" },
                    coolant?.let { "${it.toInt()} °C" }).joinToString("   "),
                    color = if (hot) Bad else Color(0xFFBBBBBB), fontSize = 30.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showRpm = !showRpm }, Modifier.weight(1f)) { BtnLabel(if (showRpm) "Mostrar velocidade" else "Mostrar rotação") }
            OutlinedButton(onClick = { mirror = !mirror }, Modifier.weight(1f)) { BtnLabel(if (mirror) "Normal" else "Espelhar (para-brisa)") }
        }
    }
}

// ------------------------------------------------------------------ gráficos

private data class Plot(val key: String, val label: String, val unit: String)

/** Até 3 sensores ao vivo, um sobre o outro, com mínimo/máximo. Os escolhidos são lidos em todo ciclo. */
@Composable
fun GraphsScreen(app: AppActions) {
    val connected by ObdManager.connected.collectAsState()
    val veh by ObdManager.vehicle.collectAsState()
    val unavailable by ObdManager.unavailable.collectAsState()
    val customs by CustomPids.list.collectAsState()
    val tick by ObdManager.seriesTick.collectAsState()
    val v by ObdManager.values.collectAsState()
    val options = remember(veh, unavailable, customs) {
        Pids.ALL.filter { (veh.supported.isEmpty() || veh.has(it)) && it.key !in unavailable && it.bytes > 0 }
            .map { Plot(it.key, it.label, it.unit) } +
            listOf(Plot("battery", "Tensão da bateria", "V")) + customs.map { Plot(it.key, it.name, it.unit) }
    }
    val chosen = remember { mutableStateListOf("rpm", "speed") }
    DisposableEffect(chosen.toList()) { ObdManager.focus = chosen.toSet(); onDispose { ObdManager.focus = emptySet() } }
    val colors = listOf(Accent, Cyan, Good)
    ScreenColumn {
        if (!connected) Callout("Conecte ao carro para ver os gráficos ao vivo.", Tone.INFO)
        Hint("Escolha até 3 sensores. Os escolhidos são lidos com prioridade, então ficam mais suaves.")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { o ->
                FilterChip(selected = o.key in chosen, onClick = {
                    if (o.key in chosen) chosen.remove(o.key) else { if (chosen.size >= 3) chosen.removeAt(0); chosen.add(o.key) }
                }, label = { Text(o.label, maxLines = 1) })
            }
        }
        chosen.forEachIndexed { i, key ->
            val o = options.firstOrNull { it.key == key } ?: Plot(key, key, "")
            val data = remember(tick, key) { ObdManager.series[key]?.toList().orEmpty() }
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(o.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                    FitText(v[key]?.let { "%.1f %s".format(it, o.unit) } ?: "—", MaterialTheme.typography.titleMedium.merge(Tabular), color = colors[i % 3])
                }
                LineChart(data.takeLast(200), color = colors[i % 3], height = 110)
                if (data.isNotEmpty()) Hint("mín. %.1f · máx. %.1f · média %.1f %s".format(data.min(), data.max(), data.average(), o.unit))
            }
        }
    }
}

// ------------------------------------------------------------------ PIDs personalizados

@Composable
fun CustomPidsScreen() {
    val list by CustomPids.list.collectAsState()
    val v by ObdManager.values.collectAsState()
    var edit by remember { mutableStateOf<CustomPid?>(null) }
    ScreenColumn {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Functions, Accent, 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sensores personalizados", style = MaterialTheme.typography.titleMedium)
                    Hint("Leia dados que o app não conhece: PIDs do fabricante (modo 21/22), de outros módulos (com cabeçalho CAN) ou do padrão.")
                }
            }
            Button(onClick = { edit = CustomPid(System.currentTimeMillis().toString(), "", "22", "", "A", "") }, Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null); Spacer(Modifier.width(6.dp)); BtnLabel("Adicionar sensor")
            }
        }
        if (list.isEmpty()) EmptyState(Icons.Filled.Functions, "Nenhum sensor personalizado",
            "Fóruns e listas de PIDs (Torque/Car Scanner) costumam trazer modo, PID, cabeçalho e fórmula para cada marca: é só copiar aqui.")
        list.forEach { p ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Hint("${p.command}${if (p.header.isNotBlank()) " · cab. ${p.header}" else ""} · ${p.formula}")
                    }
                    FitText(v[p.key]?.let { "%.2f %s".format(it, p.unit) } ?: "—", MaterialTheme.typography.titleMedium.merge(Tabular), color = Accent)
                    IconButton(onClick = { edit = p }) { Icon(Icons.Filled.Edit, "Editar") }
                }
            }
        }
        Section("Como escrever a fórmula", icon = Icons.Filled.ShowChart) {
            KeyValue("A, B, C, D…", "bytes da resposta")
            KeyValue("A-40", "temperatura")
            KeyValue("(A*256+B)/4", "rotação")
            KeyValue("A*100/255", "porcentagem")
            Hint("Aceita + − * /, ^ e parênteses. Os sensores entram no painel (Sensores deste carro), nos gráficos e no rodízio da leitura ao vivo.")
        }
    }
    edit?.let { e -> CustomPidDialog(e, onDismiss = { edit = null }) }
}

@Composable
private fun CustomPidDialog(start: CustomPid, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(start.name) }
    var mode by remember { mutableStateOf(start.mode) }
    var pid by remember { mutableStateOf(start.pid) }
    var formula by remember { mutableStateOf(start.formula) }
    var unit by remember { mutableStateOf(start.unit) }
    var header by remember { mutableStateOf(start.header) }
    var result by remember { mutableStateOf<String?>(null) }
    val connected by ObdManager.connected.collectAsState()
    val err = Formula(formula).check()
    val hex = Regex("^[0-9A-Fa-f]*$")
    val valid = name.isNotBlank() && mode.length == 2 && pid.length in 2..6 && hex.matches(mode) && hex.matches(pid) && err == null
    fun current() = CustomPid(start.id, name.trim(), mode.uppercase(), pid.uppercase(), formula.trim(), unit.trim(), header.trim().uppercase())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (start.name.isEmpty()) "Novo sensor" else "Editar sensor") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.take(32) }, label = { Text("Nome") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(mode, { mode = it.take(2) }, Modifier.weight(1f), label = { Text("Modo") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                    OutlinedTextField(pid, { pid = it.take(6) }, Modifier.weight(1.4f), label = { Text("PID") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                }
                OutlinedTextField(formula, { formula = it.take(60) }, label = { Text("Fórmula") }, singleLine = true,
                    isError = err != null, supportingText = { err?.let { Text(it) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(unit, { unit = it.take(8) }, Modifier.weight(1f), label = { Text("Unidade") }, singleLine = true)
                    OutlinedTextField(header, { header = it.take(8) }, Modifier.weight(1f), label = { Text("Cabeçalho") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                }
                Hint("Cabeçalho só em carros CAN (ex.: 7E0 motor, 7E1 câmbio). Deixe vazio para o padrão.")
                if (connected) TextButton(onClick = {
                    result = "Lendo…"
                    ObdManager.testCustom(current()) { value, raw -> result = "Resposta: ${raw.replace("\n", " | ").take(80)}\nValor: ${value?.let { "%.3f".format(it) } ?: "não deu para calcular"}" }
                }, enabled = valid) { BtnLabel("Testar agora") }
                result?.let { Mono(it) }
            }
        },
        confirmButton = { TextButton(onClick = { CustomPids.save(current()); onDismiss() }, enabled = valid) { BtnLabel("Salvar") } },
        dismissButton = {
            Row {
                if (start.name.isNotEmpty()) IconButton(onClick = { CustomPids.delete(start.id); onDismiss() }) { Icon(Icons.Filled.Delete, "Apagar", tint = Bad) }
                TextButton(onClick = onDismiss) { BtnLabel("Cancelar") }
            }
        },
    )
}

