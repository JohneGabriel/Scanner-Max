package br.obd.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.obd.app.AdapterProfile
import br.obd.app.Feedback
import br.obd.app.FuelKind
import br.obd.app.Garage
import br.obd.app.ObdManager
import br.obd.app.Pids
import br.obd.app.Reports
import br.obd.app.Settings

// ------------------------------------------------------------------ veículo

@Composable
fun VehicleScreen(app: AppActions) {
    val p by Garage.current.collectAsState()
    val all by Garage.vehicles.collectAsState()
    var calibDialog by remember { mutableStateOf(false) }
    val v = p
    if (v == null) {
        ScreenColumn {
            EmptyState(Icons.Filled.DirectionsCar, "Nenhum carro ainda", "Conecte ao carro uma vez: o app lê o chassi, identifica o modelo e cria o perfil sozinho.") {
                Button(onClick = app.onConnect) { BtnLabel("Conectar") }
            }
        }
        return
    }
    var name by remember(v.id) { mutableStateOf(v.name) }
    var disp by remember(v.id) { mutableStateOf(Reports.num(v.displacementL)) }
    var price by remember(v.id) { mutableStateOf("%.2f".format(v.pricePerL)) }
    var power by remember(v.id) { mutableStateOf(v.powerCv?.toString() ?: "") }
    var tank by remember(v.id) { mutableStateOf(v.tankL?.toInt()?.toString() ?: "") }
    var mass by remember(v.id) { mutableStateOf(v.massKg?.toString() ?: "") }
    var factor by remember(v.id) { mutableStateOf("%.2f".format(v.calib)) }
    val live by ObdManager.vehicle.collectAsState()
    var lookup by remember { mutableStateOf<String?>(null) }
    var looking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ScreenColumn {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.DirectionsCar, Accent, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(v.name, style = MaterialTheme.typography.titleLarge)
                    Hint(listOfNotNull(v.maker, v.year?.toString()).joinToString(" · ").ifBlank { "Fabricante não identificado" })
                }
            }
            v.model?.let { KeyValue("Modelo", it) }
            live.ecuName?.let { KeyValue("Central do motor", it) }
            live.calId?.let { KeyValue("Calibração (software)", it) }
            v.vin?.let { KeyValue("Chassi (VIN)", it) }
            if (v.powerCv != null || v.cylinders != null) KeyValue("Motor", listOfNotNull(Reports.num(v.displacementL) + " L",
                v.powerCv?.let { "$it cv" }, v.cylinders?.let { "$it cilindros" }).joinToString(" · "))
            v.protocol?.let { KeyValue("Protocolo", it) }
            KeyValue("Rodados com o app", "${Reports.num(v.totalKm, 0)} km")
        }
        Section("Dados do carro", icon = Icons.Filled.Tune) {
            OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("Nome (ex.: Gol G5)") }, singleLine = true, shape = RoundedCornerShape(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(disp, { disp = it }, Modifier.weight(1f), label = { Text("Motor (litros)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(price, { price = it }, Modifier.weight(1f), label = { Text("R$ por litro") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(tank, { tank = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f), label = { Text("Tanque (L)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
                OutlinedTextField(mass, { mass = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Text("Peso (kg)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
            }
            Hint("Tanque: mostra a autonomia no painel (se o carro informa o nível). Peso com motorista: estima a potência no teste de desempenho.")
            OutlinedTextField(power, { power = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("Potência (cv, opcional)") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("1.0", "1.4", "1.6", "1.8", "2.0").forEach { d ->
                    FilterChip(selected = disp.replace(",", ".") == d, onClick = { disp = d }, label = { Text(d) })
                }
            }
            Button(onClick = {
                Garage.update {
                    it.copy(
                        name = name.ifBlank { it.name },
                        displacementL = disp.replace(",", ".").toDoubleOrNull()?.coerceIn(0.6, 8.0) ?: it.displacementL,
                        displacementSet = disp.replace(",", ".").toDoubleOrNull() != null || it.displacementSet,
                        pricePerL = price.replace(",", ".").toDoubleOrNull() ?: it.pricePerL,
                        powerCv = power.toIntOrNull() ?: it.powerCv,
                        tankL = tank.toDoubleOrNull() ?: it.tankL,
                        massKg = mass.toIntOrNull() ?: it.massKg,
                    )
                }
                Feedback.success()
            }) { BtnLabel("Salvar") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Hint("A central do motor não informa cilindrada nem potência pelo OBD. Dá para tentar pelo chassi numa base pública (NHTSA, EUA): funciona bem para carros vendidos lá e, nos brasileiros, às vezes só traz a marca.")
            OutlinedButton(onClick = {
                looking = true
                scope.launch {
                    lookup = Garage.lookupVin(); looking = false
                    Garage.current.value?.let { n -> disp = Reports.num(n.displacementL); power = n.powerCv?.toString() ?: power }
                }
            }, enabled = v.vin != null && !looking) { BtnLabel(if (looking) "Buscando…" else "Buscar pelo chassi (internet)") }
            lookup?.let { Callout(it, if (it.startsWith("Encontrado")) Tone.GOOD else Tone.INFO) }
        }
        Section("Combustível no tanque", icon = Icons.Filled.LocalGasStation) {
            FuelKind.entries.forEach { k ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { Garage.update { it.copy(fuel = k) } }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (v.fuel == k) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (v.fuel == k) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Text(k.label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            AnimatedVisibility(v.fuel == FuelKind.MISTURA) {
                Column {
                    Text("Etanol no tanque: ${v.ethanolPct}%", style = MaterialTheme.typography.bodyMedium)
                    Slider(v.ethanolPct.toFloat(), { x -> Garage.update { it.copy(ethanolPct = x.toInt()) } }, valueRange = 0f..100f, steps = 9)
                }
            }
            Hint("Usado para estimar o consumo (cada combustível precisa de uma quantidade diferente de ar).")
        }
        Section("Calibrar o consumo", icon = Icons.Filled.Science) {
            Text(if (v.calibCount > 0) "Calibrado com ${v.calibCount} abastecimento(s) · fator ${Reports.num(v.calib, 2)}"
                else "Ainda não calibrado", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
                color = if (v.calibCount > 0) Good else Warn)
            Step(1, "Encha o tanque até o automático desarmar e toque em \"Enchi o tanque\".")
            Step(2, "Rode normalmente com o app conectado (pelo menos uns 50 km).")
            Step(3, "Encha de novo e informe quantos litros entraram. O app compara com a estimativa e se ajusta.")
            v.rawSinceFull?.let { KeyValue("Estimado desde a última marcação", "${Reports.num(it * v.calib, 2)} L") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(factor, { factor = it.take(5) }, Modifier.weight(1f), label = { Text("Fator manual (1,00 = sem ajuste)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    factor.replace(",", ".").toDoubleOrNull()?.coerceIn(0.3, 3.0)?.let { k -> Garage.update { it.copy(calib = k) }; Feedback.success() }
                }) { BtnLabel("Aplicar") }
            }
            Hint("Se o consumo do app sai, por exemplo, 10% acima do real, use 0,90.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { Garage.markFull(); Feedback.success() }, Modifier.weight(1f)) { BtnLabel("Enchi o tanque") }
                FilledTonalButton(onClick = { calibDialog = true }, Modifier.weight(1f), enabled = (v.rawSinceFull ?: 0.0) > 0.5) { BtnLabel("Informar litros") }
            }
        }
        if (all.size > 1) Section("Outros carros", icon = Icons.Filled.DirectionsCar) {
            all.filter { it.id != v.id }.forEach { o ->
                MenuRow(Icons.Filled.DirectionsCar, o.name, listOfNotNull(o.maker, o.year?.toString()).joinToString(" · ")) { Garage.select(o.id) }
            }
            Hint("O app troca sozinho quando conecta a outro carro (pelo chassi).")
        }
    }
    if (calibDialog) {
        var liters by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { calibDialog = false },
            title = { Text("Quantos litros entraram?") },
            text = {
                OutlinedTextField(liters, { liters = it }, label = { Text("Litros") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            },
            confirmButton = { TextButton(onClick = {
                val k = liters.replace(",", ".").toDoubleOrNull()?.let { Garage.calibrate(it) }
                calibDialog = false
                if (k != null) Feedback.success()
            }) { Text("Calibrar") } },
            dismissButton = { TextButton(onClick = { calibDialog = false }) { BtnLabel("Cancelar") } },
        )
    }
}

// ------------------------------------------------------------------ adaptador

@Composable
fun AdapterScreen(app: AppActions) {
    val a by ObdManager.adapter.collectAsState()
    val connected by ObdManager.connected.collectAsState()
    val link by ObdManager.link.collectAsState()
    val rate by ObdManager.rate.collectAsState()
    val report by ObdManager.report.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    var showReport by remember { mutableStateOf(false) }
    ScreenColumn {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Memory, if (a.clone) Warn else Good, 48)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (a.reported == "—") "Nenhum adaptador ainda" else a.chipName, style = MaterialTheme.typography.titleLarge)
                    Hint(listOfNotNull(link?.display, link?.kind?.label).joinToString(" · "))
                }
                StatusPill(if (connected) "conectado" else "desconectado", if (connected) Good else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (a.reported != "—") {
                Callout(a.verdict, if (a.clone) Tone.INFO else Tone.GOOD)
                KeyValue("Se apresenta como", a.reported)
                a.description?.let { KeyValue("Descrição (AT@1)", it) }
                a.stn?.let { KeyValue("Chip STN", it) }
                KeyValue("Comandos funcionam até", a.estimatedVersion?.let { "v$it" } ?: "—")
                a.latencyMs?.let { KeyValue("Tempo por comando", "$it ms", if (it > 120) Warn else null) }
                if (connected) KeyValue("Leituras por segundo agora", "%.1f".format(rate))
                a.carProtocol?.let { KeyValue("Protocolo com este carro", it) }
                KeyValue("Pedidos agrupados (multi-PID)", if (a.multiPid) "sim" else "não", if (a.multiPid) Good else null)
                KeyValue("Temporização", if (a.aggressiveTiming) "agressiva (AT2)" else "adaptativa (AT1)")
            }
        }
        if (a.features.isNotEmpty()) Section("Recursos testados", icon = Icons.Filled.CheckCircle) {
            a.features.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (f.ok) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null, Modifier.size(18.dp),
                        tint = if (f.ok) Good else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.label, style = MaterialTheme.typography.bodyMedium)
                        Hint("${f.cmd} · desde a v${f.since}")
                    }
                }
            }
            Hint("Comandos de leitura inofensivos, enviados na conexão. Um clone que diz \"v2.1\" mas recusa comandos da v1.3/1.4 é identificado assim.")
        }
        Section("Padrões OBD2", icon = Icons.Filled.Code) {
            AdapterProfile.PROTOCOLS.forEach { (n, name) ->
                val active = a.carProtocol != null && name.contains(a.carProtocol!!.substringBefore(" (").take(10), ignoreCase = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n, style = MaterialTheme.typography.labelLarge.merge(Tabular), color = Accent, modifier = Modifier.width(22.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (active) StatusPill("em uso", Good)
                }
            }
            Hint("O padrão ELM327 cobre todos estes. Clones baratos às vezes não têm o circuito do J1850 (Ford/GM antigos). A detecção é automática.")
        }
        AdvancedLink()
        Section("Teste completo do adaptador", icon = Icons.Filled.Science) {
            Hint("Mede tudo o que o adaptador e o carro aceitam: módulos, PIDs, velocidade, monitoramento. Leva até 1 minuto. Só leitura.")
            FilledTonalButton(onClick = { ObdManager.runProbe() }, enabled = connected && busy == null) { BtnLabel("Rodar teste completo") }
            report?.let {
                TextButton(onClick = { showReport = !showReport }) { BtnLabel(if (showReport) "Esconder relatório" else "Ver relatório") }
                AnimatedVisibility(showReport) { Mono(it) }
            }
        }
        if (connected) OutlinedButton(onClick = { ObdManager.disconnect() }, Modifier.fillMaxWidth()) { BtnLabel("Desconectar") }
        else Button(onClick = app.onConnect, Modifier.fillMaxWidth()) { BtnLabel("Conectar") }
    }
}

// ------------------------------------------------------------------ personalizar

@Composable
fun CustomizeScreen() {
    val prefs by Settings.prefs.collectAsState()
    ScreenColumn {
        Section("Tema", icon = Icons.Filled.Palette) { ThemePicker() }
        Section("Painel", icon = Icons.Filled.Dashboard) {
            Text("Segundo mostrador", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("speed" to "Velocidade", "coolant" to "Temperatura", "load" to "Carga", "boost" to "Coletor").forEach { (k, l) ->
                    FilterChip(selected = prefs.secondGauge == k, onClick = { Settings.update { it.copy(secondGauge = k) } }, label = { Text(l) })
                }
            }
            Text("Cartões visíveis", style = MaterialTheme.typography.titleSmall)
            listOf("coolant" to "Temperatura do motor", "battery" to "Bateria", "load" to "Carga", "throttle" to "Borboleta",
                "iat" to "Temp. de admissão", "map" to "Pressão do coletor", "maf" to "Fluxo de ar", "advance" to "Avanço",
                "fuelLevel" to "Nível do tanque", "oilTemp" to "Temp. do óleo", "ethanol" to "Etanol medido")
                .filter { (k, _) -> val v = ObdManager.vehicle.value; k == "battery" || v.supported.isEmpty() || Pids.byKey[k]?.let { v.has(it) } == true }
                .forEach { (k, l) ->
                ToggleRow(l, k !in prefs.hiddenCards) { on ->
                    Settings.update { it.copy(hiddenCards = if (on) it.hiddenCards - k else it.hiddenCards + k) }
                }
            }
            ToggleRow("Manter a tela ligada", prefs.keepScreenOn) { on -> Settings.update { it.copy(keepScreenOn = on) } }
            ToggleRow("Conectar sozinho ao abrir", prefs.autoConnect) { on -> Settings.update { it.copy(autoConnect = on) } }
        }
        Section("Alertas", icon = Icons.Filled.NotificationsActive) {
            ToggleRow("Alertas ligados", prefs.alerts) { on -> Settings.update { it.copy(alerts = on) } }
            Text("Motor quente a partir de ${prefs.coolantWarn} °C", style = MaterialTheme.typography.bodyMedium)
            Slider(prefs.coolantWarn.toFloat(), { x -> Settings.update { it.copy(coolantWarn = x.toInt()) } }, valueRange = 95f..115f, steps = 19)
            Text("Giro no limite a partir de ${prefs.redline} rpm", style = MaterialTheme.typography.bodyMedium)
            Slider(prefs.redline.toFloat(), { x -> Settings.update { it.copy(redline = (x / 100).toInt() * 100) } }, valueRange = 4000f..8000f)
            Text("Reserva com ${prefs.lowFuelPct}% no tanque", style = MaterialTheme.typography.bodyMedium)
            Slider(prefs.lowFuelPct.toFloat(), { x -> Settings.update { it.copy(lowFuelPct = x.toInt()) } }, valueRange = 5f..25f, steps = 19)
            Hint("Também avisa: superaquecimento, alternador sem carregar, tensão alta, bateria baixa, óleo quente, correção no limite e código de falha novo.")
        }
        Section("Som e vibração", icon = Icons.Filled.Vibration) {
            ToggleRow("Vibração", prefs.haptics) { on -> Settings.update { it.copy(haptics = on) }; if (on) Feedback.tick() }
            ToggleRow("Sons", prefs.sounds) { on -> Settings.update { it.copy(sounds = on) }; if (on) Feedback.success() }
            ToggleRow("Notificação fixa de acesso rápido", prefs.quickNotif) { on -> Settings.update { it.copy(quickNotif = on) } }
            ToggleRow("Lembretes e dicas (até 1 por dia)", prefs.reminders) { on -> Settings.update { it.copy(reminders = on) } }
            ToggleRow("Falar os alertas em voz alta", prefs.voice) { on -> Settings.update { it.copy(voice = on) }; if (on) br.obd.app.Speech.say("Alertas por voz ligados") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Feedback.success() }) { BtnLabel("Testar ok") }
                OutlinedButton(onClick = { Feedback.warning() }) { BtnLabel("Testar alerta") }
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).pressable { onChange(!on) }.padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(on, onChange)
    }
}

// ------------------------------------------------------------------ sobre

@Composable
fun AboutScreen(app: AppActions) {
    ScreenColumn {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LogoMark(96)
            Text("Scanner Max", style = MaterialTheme.typography.headlineSmall)
            Hint("Versão ${AppInfo.version}")
            Text("Diagnóstico OBD2 completo, gratuito e sem anúncios, para qualquer carro com tomada OBD2 (a partir de ~2010 no Brasil, e muitos antes).",
                style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Section("O que ele faz", icon = Icons.Filled.CheckCircle) {
            listOf(
                "Painel ao vivo com os sensores que o seu carro informa",
                "Viagens automáticas: distância, tempo, consumo e custo",
                "Testes guiados: bateria, mistura, sonda, lenta, retorno à lenta",
                "Códigos de falha explicados, com pesquisa em um toque",
                "Alertas de superaquecimento, alternador e códigos novos",
                "Relatórios em PDF e planilhas CSV",
                "Bluetooth, Bluetooth LE, Wi-Fi e USB",
            ).forEach { Row { Text("•  ", color = Accent); Text(it, style = MaterialTheme.typography.bodyMedium) } }
        }
        Section("Ajude a melhorar", icon = Icons.Filled.Lightbulb) {
            MenuRow(Icons.Filled.BugReport, "Reportar problema", "Abre um relato no GitHub com as informações do app", Bad) { Support.bug(app) }
            MenuRow(Icons.Filled.Lightbulb, "Sugerir função", color = Good) { Support.feature(app) }
            MenuRow(Icons.Filled.Code, "Código-fonte", REPO.removePrefix("https://"), Cyan) { app.openUrl(REPO) }
        }
        Section("Legal", icon = Icons.Filled.Gavel) {
            MenuRow(Icons.Filled.Gavel, "Termos de uso") { app.nav.go("terms") }
            MenuRow(Icons.Filled.PrivacyTip, "Política de privacidade") { app.nav.go("privacy") }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Hint("Bibliotecas: Jetpack Compose e AndroidX (Apache 2.0), Kotlin Coroutines (Apache 2.0), usb-serial-for-android (MIT).")
        }
    }
}


/** Grade de temas com prévia das cores (usada no setup e em Personalizar). */
@Composable
fun ThemePicker() {
    val prefs by Settings.prefs.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        THEMES.chunked(3).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEachIndexed { c, t ->
                    val i = r * 3 + c
                    val sel = prefs.theme == i
                    val border by animateColorAsState(if (sel) t.accent else MaterialTheme.colorScheme.outlineVariant, label = "b")
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).border(BorderStroke(if (sel) 2.dp else 1.dp, border), RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(listOf(t.bg, t.surface)))
                            .pressable { Settings.update { it.copy(theme = i) }; Feedback.tick() }.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(16.dp).clip(CircleShape).background(t.accent))
                            Box(Modifier.size(16.dp).clip(CircleShape).background(t.accent2))
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.highest))
                        Box(Modifier.fillMaxWidth(0.6f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(t.accent.copy(alpha = 0.7f)))
                        Text(t.name, style = MaterialTheme.typography.labelMedium, color = t.text, maxLines = 1)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    
    }
}


/** Ajustes manuais de comunicação (protocolo forçado, comandos extras). */
@Composable
private fun AdvancedLink() {
    val prefs by Settings.prefs.collectAsState()
    var extra by remember { mutableStateOf(prefs.extraInit) }
    Section("Ajustes de comunicação", icon = Icons.Filled.Tune) {
        Text("Protocolo", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (listOf("0" to "Automático") + AdapterProfile.PROTOCOLS.map { it.first to "${it.first} · ${it.second.substringBefore(" (").take(22)}" }).forEach { (n, l) ->
                FilterChip(selected = prefs.forcedProtocol == n, onClick = { Settings.update { it.copy(forcedProtocol = n) } }, label = { Text(l, maxLines = 1) })
            }
        }
        Hint("No automático o app procura sozinho e, se falhar, testa um protocolo por vez. Force só se souber qual o seu carro usa.")
        OutlinedTextField(extra, { extra = it.take(120) }, Modifier.fillMaxWidth(), label = { Text("Comandos extras na conexão") },
            placeholder = { Text("ex.: ATST 64; ATSH 7E0") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { Settings.update { it.copy(extraInit = extra.trim()) }; Feedback.success() }) { BtnLabel("Salvar") }
            TextButton(onClick = { extra = ""; Settings.update { it.copy(extraInit = "", forcedProtocol = "0") } }) { BtnLabel("Restaurar padrão") }
        }
        Hint("Valem a partir da próxima conexão.")
    }
}
