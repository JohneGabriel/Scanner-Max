package br.obd.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.obd.app.Feedback
import br.obd.app.Level
import br.obd.app.Need
import br.obd.app.ObdManager
import br.obd.app.Pids
import br.obd.app.Reports
import br.obd.app.TestCatalog
import br.obd.app.TestDef
import br.obd.app.TestReport
import br.obd.app.TestRunner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun testIcon(id: String): ImageVector = when (id) {
    "battery" -> Icons.Filled.BatteryChargingFull
    "mixture" -> Icons.Filled.Tune
    "lambda" -> Icons.Filled.Sensors
    "idle" -> Icons.Filled.Speed
    "decel" -> Icons.Filled.ShowChart
    "cooling" -> Icons.Filled.Thermostat
    "temps" -> Icons.Filled.DeviceThermostat
    "map" -> Icons.Filled.Compress
    "tps" -> Icons.Filled.LinearScale
    "misfire" -> Icons.Filled.LocalFireDepartment
    else -> Icons.Filled.Science
}

@Composable
fun TestsScreen(app: AppActions) {
    val history by TestRunner.history.collectAsState()
    val connected by ObdManager.connected.collectAsState()
    ScreenColumn {
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.Science, Accent, 44)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Testes guiados", style = MaterialTheme.typography.titleLarge)
                    Hint("Cada teste explica o que fazer, mede e diz o que o resultado significa — e o que fazer depois.")
                }
            }
            if (!connected) Callout("Conecte ao carro para rodar os testes. Os resultados anteriores ficam aqui embaixo.", Tone.INFO)
        }
        TestCatalog.ALL.forEach { def ->
            val last = history.firstOrNull { it.testId == def.id && (it.vehicleId == br.obd.app.Garage.current.value?.id) }
            AppCard(onClick = { app.nav.go("test", def.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(testIcon(def.id), Accent, 44)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(def.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        Text(def.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(def.duration, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (last != null) { Spacer(Modifier.height(4.dp)); StatusPill(levelWord(last.level), levelColor(last.level)) }
                    }
                }
            }
        }
        if (history.isNotEmpty()) {
            Text("Histórico", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            history.take(20).forEach { t ->
                MenuRow(testIcon(t.testId), t.title + (t.note?.let { " · $it" } ?: ""),
                    "${SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(t.at))} · ${t.headline}", levelColor(t.level)) {
                    app.nav.go("result", t.at.toString())
                }
            }
        }
    }
}

fun levelWord(l: Level) = when (l) { Level.GOOD -> "bom"; Level.WARN -> "atenção"; Level.BAD -> "problema"; Level.INFO -> "info" }

// ------------------------------------------------------------------ tela do teste

@Composable
fun TestRunScreen(id: String, app: AppActions) {
    val def = TestCatalog.byId(id) ?: return
    val live by TestRunner.live.collectAsState()
    val result by TestRunner.result.collectAsState()
    val error by TestRunner.error.collectAsState()
    LaunchedEffect(id) { if (live == null) TestRunner.clearResult() }

    val phase = when {
        live?.testId == id -> 1
        result?.testId == id -> 2
        else -> 0
    }
    AnimatedContent(phase, transitionSpec = {
        (fadeIn(tween(350)) + slideInVertically(tween(350)) { it / 12 }) togetherWith fadeOut(tween(200))
    }, label = "testPhase") { ph ->
        when (ph) {
            0 -> TestIntro(def, error)
            1 -> TestRunning(def)
            else -> result?.let { ReportView(it, app, fresh = true) }
        }
    }
}

@Composable
private fun TestIntro(def: TestDef, error: String?) {
    val v by ObdManager.values.collectAsState()
    val connected by ObdManager.connected.collectAsState()
    val veh by ObdManager.vehicle.collectAsState()
    val adapter by ObdManager.adapter.collectAsState()
    var option by remember { mutableStateOf(def.options.firstOrNull()) }
    val rpm = v["rpm"]

    fun met(n: Need): Boolean? = when (n) {
        Need.ENGINE_RUNNING -> rpm?.let { it >= 400 }
        Need.ENGINE_OFF -> rpm?.let { it < 300 } ?: true
        Need.WARM -> v["coolant"]?.let { it >= 75 }
        Need.STOPPED -> v["speed"]?.let { it < 1 } ?: true
        Need.NARROW_O2 -> veh.supported.isEmpty() || veh.has(Pids.O2S1)
        Need.VOLTAGE -> adapter.features.isEmpty() || adapter.canReadVoltage
        Need.MAP_SENSOR -> veh.supported.isEmpty() || veh.has(Pids.MAP)
        Need.THROTTLE_SENSOR -> veh.supported.isEmpty() || veh.has(Pids.THROTTLE)
        Need.CAN -> if (veh.supported.isEmpty()) null else veh.isCan
        Need.COLD -> v["coolant"]?.let { it < 45 }
    }
    val hardFail = def.needs.any { it.hard && met(it) == false }

    ScreenColumn {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val t = rememberInfiniteTransition(label = "intro")
            val s by t.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "s")
            Box(Modifier.padding(top = 8.dp).scale(s)) { IconBadge(testIcon(def.id), Accent, 92) }
        }
        Text(def.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Text(def.subtitle + " · " + def.duration, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        error?.let { Callout(it, Tone.BAD) }
        Section("O que este teste verifica", icon = Icons.Filled.Info) { Text(def.what, style = MaterialTheme.typography.bodyMedium) }
        Section("Como fazer", icon = Icons.Filled.PlayArrow) { def.howTo.forEachIndexed { i, s -> Step(i + 1, s) } }
        Section("Antes de começar", icon = Icons.Filled.CheckCircle) {
            NeedRow("Conectado ao carro", connected)
            def.needs.forEach { n -> NeedRow(n.label + if (n == Need.ENGINE_RUNNING || n == Need.WARM) live(n, v) else "", met(n)) }
            if (hardFail) Callout("Este teste não se aplica aqui: " +
                def.needs.filter { it.hard && met(it) == false }.joinToString("; ") { it.label.replaceFirstChar { c -> c.lowercase() } } + " — não atendido.", Tone.WARN)
        }
        if (def.options.isNotEmpty()) {
            Section("Situação", icon = Icons.Filled.Tune) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    def.options.forEach { o -> FilterChip(selected = option == o, onClick = { option = o; Feedback.tick() }, label = { Text(o) }) }
                }
                Hint("O app compara com a execução anterior e diz o que mudou.")
            }
        }
        Button(
            onClick = { TestRunner.start(def, option) },
            enabled = connected && !hardFail,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
        ) {
            Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(8.dp))
            Text(if (def.needs.any { !it.hard && met(it) == false }) "Começar mesmo assim" else "Começar", fontSize = 17.sp)
        }
    }
}

private fun live(n: Need, v: Map<String, Double>) = when (n) {
    Need.ENGINE_RUNNING -> v["rpm"]?.let { " (${it.toInt()} rpm)" } ?: ""
    Need.WARM -> v["coolant"]?.let { " (${it.toInt()} °C)" } ?: ""
    else -> ""
}

@Composable
private fun NeedRow(label: String, ok: Boolean?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val c by animateColorAsState(when (ok) { true -> Good; false -> Warn; null -> MaterialTheme.colorScheme.onSurfaceVariant }, label = "need")
        Icon(when (ok) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Warning; null -> Icons.Filled.RadioButtonUnchecked },
            null, Modifier.size(20.dp), tint = c)
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TestRunning(def: TestDef) {
    val l by TestRunner.live.collectAsState()
    val s = l ?: return
    // som e vibração quando muda a etapa ou aparece uma ação urgente
    LaunchedEffect(s.step) { if (s.step > 1) Feedback.step() else Feedback.start() }
    LaunchedEffect(s.urgent) { if (s.urgent) Feedback.cue() }

    val urgentColor by animateColorAsState(if (s.urgent) Warn else Accent, label = "u")
    val pulse = rememberInfiniteTransition(label = "urg")
    val ps by pulse.animateFloat(1f, if (s.urgent) 1.05f else 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "ps")

    ScreenColumn {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(s.steps) { i ->
                val done = i + 1 < s.step; val cur = i + 1 == s.step
                Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (done) Accent else if (cur) Accent.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Text("Etapa ${s.step} de ${s.steps} · ${s.stepTitle}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Box(Modifier.fillMaxWidth().padding(horizontal = 40.dp), contentAlignment = Alignment.Center) {
            Ring(s.progress, Modifier.widthIn(max = 300.dp).fillMaxWidth().scale(ps), color = urgentColor, stroke = 16f) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedContent(s.big, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(120)) }, label = "big") { b ->
                        FitText(b, Tabular.copy(fontSize = 52.sp, fontWeight = FontWeight.Bold), Modifier.padding(horizontal = 28.dp))
                    }
                    Text(s.bigUnit, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    s.secondsLeft?.let { Text("${it}s", style = MaterialTheme.typography.labelLarge.merge(Tabular), color = urgentColor) }
                }
            }
        }
        AnimatedContent(s.instruction, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() }, label = "instr") { txt ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(urgentColor.copy(alpha = 0.14f)).padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(txt, style = if (s.urgent) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                    color = if (s.urgent) urgentColor else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                    fontWeight = if (s.urgent) FontWeight.Bold else FontWeight.Medium)
            }
        }
        if (s.chart.size >= 2) AppCard { LineChart(s.chart, min = s.chartMin, max = s.chartMax, reference = s.chartRef, color = urgentColor, height = 110) }
        if (s.testId == "cooling") Button(onClick = { TestRunner.requestFinish() }, modifier = Modifier.fillMaxWidth()) { BtnLabel("Encerrar e ver o resultado") }
        OutlinedButton(onClick = { TestRunner.cancel() }, modifier = Modifier.fillMaxWidth()) { BtnLabel("Cancelar teste") }
    }
}

// ------------------------------------------------------------------ resultado

@Composable
fun ResultScreen(at: Long, app: AppActions) {
    val history by TestRunner.history.collectAsState()
    val r = history.firstOrNull { it.at == at } ?: run { EmptyState(Icons.Filled.Science, "Resultado não encontrado", ""); return }
    ReportView(r, app, fresh = false)
}

@Composable
fun ReportView(r: TestReport, app: AppActions, fresh: Boolean) {
    val c = levelColor(r.level)
    val scale = remember { Animatable(if (fresh) 0.3f else 1f) }
    LaunchedEffect(r.at) {
        if (fresh) {
            when (r.level) { Level.GOOD -> Feedback.success(); Level.BAD, Level.WARN -> Feedback.warning(); else -> Feedback.step() }
            scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    val prev = remember(r.at) { TestRunner.previous(r.testId, r.vehicleId, r.at) }
    ScreenColumn {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(top = 8.dp).scale(scale.value)) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(30.dp)).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                    Icon(when (r.level) { Level.GOOD -> Icons.Filled.CheckCircle; Level.BAD -> Icons.Filled.Error; Level.WARN -> Icons.Filled.Warning; else -> Icons.Filled.Info },
                        null, Modifier.size(54.dp), tint = c)
                }
            }
            Text(r.headline, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, color = c)
            Hint("${r.title}${r.note?.let { " · $it" } ?: ""} · ${SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(r.at))}")
        }
        AppCard { Text(r.explanation, style = MaterialTheme.typography.bodyMedium) }
        if (r.metrics.isNotEmpty()) {
            Section("Medições", icon = Icons.Filled.ShowChart) {
                r.metrics.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { m -> StatCard(m.label, m.value, Modifier.weight(1f), m.level?.let { levelColor(it) }) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (r.chart.size >= 2) Section(r.chartLabel, icon = Icons.Filled.ShowChart) {
            LineChart(r.chart, min = r.chartMin, max = r.chartMax, reference = r.chartRef, color = c.takeIf { r.level != Level.GOOD } ?: Accent, height = 140)
            r.note?.takeIf { it.length > 30 }?.let { Hint(it) }
        }
        Section("O que fazer", icon = Icons.Filled.CheckCircle) { r.nextSteps.forEachIndexed { i, s -> Step(i + 1, s, c.takeIf { r.level != Level.GOOD } ?: Good) } }
        prev?.let { p ->
            AppCard(onClick = { app.nav.go("result", p.at.toString()) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Execução anterior", style = MaterialTheme.typography.titleSmall)
                        Hint("${SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(p.at))} · ${p.headline}")
                    }
                    StatusPill(levelWord(p.level), levelColor(p.level))
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { TestRunner.clearResult(); if (!fresh) app.nav.go("test", r.testId) }, Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); BtnLabel("Repetir")
            }
            Button(onClick = { app.share(Reports.test(ObdManager.context, r), "application/pdf") }, Modifier.weight(1f)) {
                Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); BtnLabel("PDF")
            }
        }
        if (!fresh) TextButton(onClick = { TestRunner.delete(r); app.nav.back() }) { Text("Apagar este resultado", color = Bad) }
    }
}
