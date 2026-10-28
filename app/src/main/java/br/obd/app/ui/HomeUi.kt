package br.obd.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.obd.app.Alert
import br.obd.app.Alerts
import br.obd.app.FuelModel
import br.obd.app.Garage
import br.obd.app.Level
import br.obd.app.Maintenance
import br.obd.app.ObdManager
import br.obd.app.PidDef
import br.obd.app.Pids
import br.obd.app.Reports
import br.obd.app.Settings
import br.obd.app.TestRunner
import br.obd.app.TripStore
import br.obd.app.TripTotals
import br.obd.app.link.LinkTarget
import kotlinx.coroutines.delay

@Composable
fun ScreenColumn(content: @Composable () -> Unit) {
    // conteúdo centralizado e com largura máxima: em tablet/paisagem não estica de ponta a ponta
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content(); Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
fun HomeScreen(app: AppActions) {
    val connected by ObdManager.connected.collectAsState()
    if (connected) LiveDashboard(app) else OfflineHome(app)
}

// ============================================================== desconectado

@Composable
private fun OfflineHome(app: AppActions) {
    val veh by Garage.current.collectAsState()
    val trips by TripStore.trips.collectAsState()
    val tests by TestRunner.history.collectAsState()
    val status by ObdManager.status.collectAsState()
    val connecting = status.startsWith("Conectando") || status.startsWith("Inicializando") || status.startsWith("Conexão caiu")

    ScreenColumn {
        // herói
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
                .background(Brush.linearGradient(listOf(Accent.copy(alpha = 0.14f), MaterialTheme.colorScheme.surfaceContainer)))
                .padding(20.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val t = rememberInfiniteTransition(label = "idle")
                    val sw by t.animateFloat(0.15f, 0.75f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sw")
                    LogoMark(52, if (connecting) sw else 0.25f + sw * 0.1f)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (connecting) "Conectando…" else veh?.name ?: "Conecte seu carro",
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (connecting) status else "Adaptador na tomada OBD e ignição ligada.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = app.onConnect, modifier = Modifier.weight(1f), contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Filled.Bluetooth, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); BtnLabel("Conectar")
                    }
                    OutlinedButton(onClick = { ObdManager.connect(LinkTarget.DEMO) }, modifier = Modifier.weight(1f),
                        contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                        Icon(Icons.Filled.PlayCircle, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); BtnLabel("Demonstração")
                    }
                }
            }
        }

        // carro
        veh?.let { v ->
            val mine = trips.filter { it.vehicleId == v.id }
            val month = mine.filter { it.start >= System.currentTimeMillis() - 30L * 86400000 }
            val tot = TripTotals.of(month)
            AppCard(onClick = { app.nav.go("vehicle") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.DirectionsCar, Accent, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(v.name, style = MaterialTheme.typography.titleMedium)
                        Hint(listOfNotNull(v.year?.toString(), v.fuelLabel, v.protocol).joinToString(" · "))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("30 dias", "${Reports.num(tot.km, 0)} km", Modifier.weight(1f))
                    MiniStat("Consumo", tot.kmPerL?.let { "${Reports.num(it)} km/L" } ?: "—", Modifier.weight(1f))
                    MiniStat("Gasto", Reports.money(tot.cost), Modifier.weight(1f))
                }
                DiagSummaryLine()
            }
        }

        MaintenanceBanner(app)

        // última viagem
        trips.firstOrNull()?.let { t ->
            AppCard(onClick = { app.nav.go("trip", t.id.toString()) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.Route, Cyan, 36)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Última viagem", style = MaterialTheme.typography.titleSmall)
                        Hint(TripsUiText.when_(t.start))
                    }
                    Text("${Reports.num(t.km)} km", style = MaterialTheme.typography.titleMedium.merge(Tabular))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("Duração", Reports.hm(t.durationSec), Modifier.weight(1f))
                    MiniStat("Consumo", t.kmPerL?.let { "${Reports.num(it)} km/L" } ?: "—", Modifier.weight(1f))
                    MiniStat("Temp. média", t.avgCoolant?.let { "${it.toInt()} °C" } ?: "—", Modifier.weight(1f))
                }
            }
        }

        QuickActions(app)

        if (veh == null) {
            Section("Como começar", icon = Icons.Filled.CheckCircle) {
                Step(1, "Plugue o adaptador OBD2 na tomada do carro (geralmente embaixo do volante).")
                Step(2, "Ligue a ignição (ou o motor).")
                Step(3, "Bluetooth clássico: pareie nas configurações do celular (PIN 1234 ou 0000). BLE e Wi-Fi: conecte direto pelo app.")
                Step(4, "Toque em Conectar. O app identifica o carro, o protocolo e os sensores sozinho.")
            }
        }
        TipCard()
        if (tests.isNotEmpty()) RecentTests(app)
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier = Modifier, color: Color? = null) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        FitText(value, MaterialTheme.typography.titleSmall.merge(Tabular), color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun DiagSummaryLine() {
    val d by ObdManager.diag.collectAsState()
    if (d.readAt == null) return
    val n = d.stored.orEmpty().size + d.pending.orEmpty().size
    StatusPill(if (n == 0) "Sem códigos de falha no último diagnóstico" else "$n código(s) de falha no último diagnóstico", if (n == 0) Good else Warn)
}

@Composable
private fun MaintenanceBanner(app: AppActions) {
    val items by Maintenance.items.collectAsState()
    val due = items.filter { (Maintenance.remaining(it) ?: Double.MAX_VALUE) <= 1000 }
    if (due.isEmpty()) return
    AppCard(onClick = { app.nav.go("maint") }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Build, Warn, 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Manutenção chegando", style = MaterialTheme.typography.titleSmall)
                Hint(due.joinToString(" · ") { i ->
                    val r = Maintenance.remaining(i) ?: 0.0
                    if (r < 0) "${i.name}: vencida" else "${i.name}: ${r.toInt()} km"
                })
            }
        }
    }
}

@Composable
private fun QuickActions(app: AppActions) {
    val items = listOf(
        Triple(Icons.Filled.Science, "Testes") { app.nav.home(2) },
        Triple(Icons.Filled.Route, "Viagens") { app.nav.home(1) },
        Triple(Icons.Filled.Search, "Códigos") { app.nav.go("lookup") },
        Triple(Icons.Filled.Assessment, "Relatório") { HealthReport.share(app) },
        Triple(Icons.Filled.LocalGasStation, "Combustível") { app.nav.go("flex") },
        Triple(Icons.Filled.Build, "Manutenção") { app.nav.go("maint") },
    )
    Text("Atalhos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
    AdaptiveGrid(items, minCell = 96.dp, maxCols = 6) { (icon, label, action), m -> ActionTile(icon, label, m, onClick = action) }
}

private val TIPS = listOf(
    "Rode o teste de Bateria e alternador com o motor desligado: ele mede a partida e a recarga em menos de um minuto.",
    "O consumo fica bem mais preciso depois de calibrar com um abastecimento: Menu → Meu veículo.",
    "Luz de injeção piscando significa falha de combustão forte: reduza a carga e procure a oficina.",
    "Correção de combustível longa acima de ±10% indica que a central está compensando algo — o teste de Mistura diz o quê.",
    "Viagens são registradas sozinhas quando o motor liga com o app conectado. Nada para apertar.",
    "Os alertas avisam superaquecimento, alternador sem carregar e códigos novos — mesmo com a tela desligada.",
    "Toque em qualquer código de falha para ver o que significa, as causas comuns e pesquisar com um toque.",
)

@Composable
private fun TipCard() {
    var i by remember { mutableIntStateOf((System.currentTimeMillis() / 60000 % TIPS.size).toInt()) }
    LaunchedEffect(Unit) { while (true) { delay(9000); i = (i + 1) % TIPS.size } }
    AppCard {
        Row(verticalAlignment = Alignment.Top) {
            IconBadge(Icons.Filled.Bolt, Cyan, 32)
            Spacer(Modifier.width(10.dp))
            androidx.compose.animation.AnimatedContent(i, transitionSpec = {
                fadeIn(tween(400)) togetherWith fadeOut(tween(300))
            }, label = "tip") { k ->
                Column {
                    Text("Dica", style = MaterialTheme.typography.labelMedium, color = Cyan)
                    Text(TIPS[k], style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RecentTests(app: AppActions) {
    val tests by TestRunner.history.collectAsState()
    Section("Testes recentes", icon = Icons.Filled.Science) {
        tests.take(3).forEach { t ->
            MenuRow(Icons.Filled.Science, t.title, t.headline, levelColor(t.level)) { app.nav.go("result", t.at.toString()) }
        }
    }
}

// ============================================================== conectado

@Composable
private fun LiveDashboard(app: AppActions) {
    val v by ObdManager.values.collectAsState()
    val history by ObdManager.history.collectAsState()
    val rate by ObdManager.rate.collectAsState()
    val veh by ObdManager.vehicle.collectAsState()
    val profile by Garage.current.collectAsState()
    val trip by ObdManager.trip.live.collectAsState()
    val alerts by Alerts.active.collectAsState()
    val prefs by Settings.prefs.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    var showAll by remember { mutableStateOf(false) }

    val fresh = System.currentTimeMillis() - ObdManager.lastLiveAt < 8000
    val rpm = v["rpm"]?.takeIf { fresh }
    val running = (rpm ?: 0.0) >= 300
    val diesel = Pids.isDiesel(veh.fuelType)
    fun has(d: PidDef) = if (veh.supported.isEmpty()) d in listOf(Pids.RPM, Pids.SPEED, Pids.COOLANT, Pids.LOAD, Pids.THROTTLE, Pids.MAP, Pids.IAT) else veh.has(d)

    val link by ObdManager.link.collectAsState()
    ScreenColumn {
        // modo demonstração: sempre visível e com saída clara
        if (link?.kind == br.obd.app.link.LinkKind.DEMO) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Cyan.copy(alpha = 0.14f)).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PlayCircle, null, Modifier.size(20.dp), tint = Cyan)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Modo demonstração", style = MaterialTheme.typography.titleSmall, color = Cyan, maxLines = 1)
                    Text("Dados simulados, não é o seu carro.", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { ObdManager.disconnect() }, colors = ButtonDefaults.buttonColors(containerColor = Cyan)) { BtnLabel("Sair") }
            }
        }
        // alertas
        alerts.forEach { a -> AlertBanner(a) }

        // cabeçalho do carro
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    IconBadge(Icons.Filled.DirectionsCar, if (running) Good else Accent, 46)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile?.name ?: listOfNotNull(veh.maker, veh.year?.toString()).joinToString(" ").ifBlank { "Identificando o carro…" },
                        style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(listOfNotNull(veh.protocol.takeIf { it != "—" }, if (veh.supported.isNotEmpty()) "${veh.supported.size} sensores" else null,
                        "%.1f/s".format(rate)).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (running) PulseDot(Good, 7)
                        Text(when { busy != null -> "Ocupado"; rpm == null -> "Aguardando"; running -> "Ligado"; else -> "Desligado" },
                            style = MaterialTheme.typography.labelLarge, maxLines = 1, color = if (running) Good else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val mil = v["milOn"] == 1.0
                    val codes = v["milCount"]?.toInt()
                    if (codes != null) Text(if (codes == 0) "sem códigos" else "$codes código(s)", style = MaterialTheme.typography.labelSmall,
                        color = if (codes == 0 && !mil) Good else Warn)
                }
            }
        }

        // viagem em andamento
        AnimatedVisibility(trip != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            trip?.let { t ->
                AppCard(onClick = { app.nav.home(1) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PulseDot(Accent, 7)
                        Text("Viagem em andamento", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(Reports.hm(t.durationSec), style = MaterialTheme.typography.titleSmall.merge(Tabular), color = Accent)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MiniStat("Distância", "${Reports.num(t.km)} km", Modifier.weight(1f))
                        MiniStat("Média", t.kmPerL?.let { "${Reports.num(it)} km/L" } ?: "—", Modifier.weight(1f))
                        MiniStat("Gasto", Reports.money(t.cost), Modifier.weight(1f))
                    }
                }
            }
        }

        // mostradores
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Gauge(rpm, if (diesel) 5000.0 else 7000.0, "Rotação", "rpm", Modifier.weight(1f),
                warnAt = if (diesel) 4200.0 else prefs.redline.toDouble(), majorStep = 1000.0)
            when (prefs.secondGauge) {
                "coolant" -> Gauge(v["coolant"], 130.0, "Motor", "°C", Modifier.weight(1f), warnAt = prefs.coolantWarn.toDouble(), majorStep = 20.0)
                "load" -> Gauge(v["load"], 100.0, "Carga", "%", Modifier.weight(1f), majorStep = 20.0)
                "boost" -> Gauge(v["map"], 250.0, "Coletor", "kPa", Modifier.weight(1f), majorStep = 50.0)
                else -> Gauge(v["speed"], 200.0, "Velocidade", "km/h", Modifier.weight(1f), majorStep = 20.0)
            }
        }

        // consumo agora
        ConsumptionCard(v, running, profile)

        // cartões dos sensores
        val coolant = v["coolant"]; val battery = v["battery"] ?: v["moduleV"]
        val cards = buildList {
            if (has(Pids.COOLANT)) add(CardSpec("coolant", "Motor", fmt(coolant, unit = " °C"), Icons.Filled.Thermostat,
                coolant?.let { if (it >= prefs.coolantWarn) Bad else if (it < 70) Cyan else null }))
            add(CardSpec("battery", if (v["battery"] != null) "Bateria" else "Central", fmt(battery, "%.1f", " V"), Icons.Filled.BatteryChargingFull,
                battery?.let { if (running && it < 13.0) Bad else if (it < 12.0 || it > 15.0) Warn else Good }))
            if (has(Pids.LOAD)) add(CardSpec("load", "Carga", fmt(v["load"], unit = "%"), Icons.Filled.Equalizer, null))
            if (has(Pids.THROTTLE)) add(CardSpec("throttle", "Borboleta", fmt(v["throttle"], "%.1f", "%"), Icons.Filled.Tune, null))
            if (has(Pids.IAT)) add(CardSpec("iat", "Admissão", fmt(v["iat"], unit = " °C"), Icons.Filled.Air, null))
            if (has(Pids.MAP)) add(CardSpec("map", "Coletor", fmt(v["map"], unit = " kPa"), Icons.Filled.Compress,
                v["map"]?.let { if (running && (v["speed"] ?: 0.0) < 2 && (v["throttle"] ?: 99.0) < 5 && it > 45) Warn else null }))
            if (veh.has(Pids.MAF)) add(CardSpec("maf", "Fluxo de ar", fmt(v["maf"], "%.1f", " g/s"), Icons.Filled.Air, null))
            if (has(Pids.ADVANCE)) add(CardSpec("advance", "Avanço", fmt(v["advance"], "%.1f", "°"), Icons.Filled.Bolt, null))
            if (veh.has(Pids.FUEL_LEVEL)) add(CardSpec("fuelLevel", "Tanque", fmt(v["fuelLevel"], unit = "%"), Icons.Filled.LocalGasStation,
                v["fuelLevel"]?.let { if (it <= prefs.lowFuelPct) Warn else null }))
            if (veh.has(Pids.OIL_TEMP)) add(CardSpec("oilTemp", "Óleo", fmt(v["oilTemp"], unit = " °C"), Icons.Filled.Opacity, null))
            if (veh.has(Pids.ETHANOL)) add(CardSpec("ethanol", "Etanol", fmt(v["ethanol"], unit = "%"), Icons.Filled.WaterDrop, null))
        }.filter { it.key !in prefs.hiddenCards }
        AdaptiveGrid(cards, minCell = 100.dp, maxCols = 6) { c, m -> StatCard(c.label, c.value, m, c.color, c.icon) }

        // correções
        if (has(Pids.STFT) || has(Pids.LTFT)) {
            Section("Mistura (correções)", icon = Icons.Filled.Tune, trailing = {
                TextButton(onClick = { app.nav.go("test", "mixture") }) { BtnLabel("Testar") }
            }) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TrimCol("Curto prazo", v["stft"], Modifier.weight(1f))
                    TrimCol("Longo prazo", v["ltft"], Modifier.weight(1f))
                }
                Hint(Pids.fuelStatusText(v["fuelStatus"]?.toInt()))
            }
        }

        Section("Rotação", icon = Icons.Filled.ShowChart, trailing = {
            val peak = history.takeLast(200).mapNotNull { it.rpm }.maxOrNull()
            if (peak != null) Text("pico %.0f".format(peak), style = MaterialTheme.typography.labelMedium.merge(Tabular),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }) {
            val rpms = history.takeLast(200).map { it.rpm }
            LineChart(rpms, min = 0.0, max = maxOf(3000.0, (rpms.filterNotNull().maxOrNull() ?: 0.0) * 1.15))
            Hint("Últimos minutos" + if (!veh.isCan && veh.supported.isNotEmpty()) " · linha K: ~3 leituras/s é o limite do protocolo" else "")
        }

        // ações rápidas conectado
        AdaptiveGrid(listOf<Triple<ImageVector, String, () -> Unit>>(
            Triple(Icons.Filled.Warning, "Ler códigos") { app.nav.home(3); ObdManager.readDiagnostics(); Unit },
            Triple(Icons.Filled.BatteryChargingFull, "Bateria") { app.nav.go("test", "battery") },
            Triple(Icons.Filled.Timer, "0–100") { app.nav.go("perf") },
        ), minCell = 96.dp, maxCols = 3) { (icon, label, action), m -> ActionTile(icon, label, m, onClick = action) }

        if (veh.supported.isNotEmpty()) {
            val all = Pids.ALL.filter { veh.has(it) }
            Section("Todos os sensores (${all.size})", icon = Icons.Filled.Sensors, trailing = {
                TextButton(onClick = { showAll = !showAll }) { BtnLabel(if (showAll) "Esconder" else "Mostrar") }
            }) {
                Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    } else Hint("Tudo o que este carro informa pelo OBD, incluindo o que não aparece no painel.")
                }
            }
        }
    }
}

private data class CardSpec(val key: String, val label: String, val value: String, val icon: ImageVector, val color: Color?)

@Composable
private fun TrimCol(title: String, value: Double?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(fmt(value, "%+.1f", "%"), style = Tabular, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = trimColor(value))
        TrimBar(value)
    }
}

@Composable
private fun ConsumptionCard(v: Map<String, Double>, running: Boolean, profile: br.obd.app.VehicleProfile?) {
    val p = profile ?: return
    val lph = FuelModel.rawLph(v, p)?.times(p.calib)
    val speed = v["speed"] ?: 0.0
    val kmpl = if (lph != null && lph > 0.05 && speed > 3) speed / lph else null
    val cut = lph == 0.0 && running
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.LocalGasStation, Accent, 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Consumo agora", style = MaterialTheme.typography.titleSmall)
                Hint(if (p.calibCount > 0) "calibrado · ${p.fuelLabel}" else "estimado · ${p.fuelLabel}")
            }
            Column(horizontalAlignment = Alignment.End) {
                FitText(
                    when { !running -> "—"; cut -> "corte"; kmpl != null -> "%.1f km/L".format(kmpl); lph != null -> "%.2f L/h".format(lph); else -> "—" },
                    Tabular.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold), color = if (cut) Good else MaterialTheme.colorScheme.onSurface,
                )
                Hint(when { cut -> "sem injeção (desacelerando)"; kmpl != null && lph != null -> "%.2f L/h".format(lph); lph != null -> "parado em lenta"; else -> "" })
            }
        }
        if (!p.displacementSet) Callout("Informe a cilindrada do motor em Menu → Meu veículo para o consumo ficar certo (hoje: ${Reports.num(p.displacementL)} L).", Tone.INFO)
    }
}

@Composable
fun AlertBanner(a: Alert) {
    val c = levelColor(a.level)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.14f)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (a.level == Level.BAD) PulseDot(c, 8) else Icon(Icons.Filled.Warning, null, Modifier.size(20.dp), tint = c)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.titleSmall, color = c)
            Text(a.message, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = { Alerts.dismiss(a.id) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Filled.Close, "Dispensar", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
