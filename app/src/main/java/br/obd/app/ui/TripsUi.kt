package br.obd.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import br.obd.app.Garage
import br.obd.app.ObdManager
import br.obd.app.Reports
import br.obd.app.TripStore
import br.obd.app.TripSummary
import br.obd.app.TripTotals
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object TripsUiText {
    private val ptBr = Locale("pt", "BR")
    fun when_(t: Long): String {
        val c = Calendar.getInstance(); val today = c.get(Calendar.DAY_OF_YEAR); val y = c.get(Calendar.YEAR)
        c.timeInMillis = t
        val hour = SimpleDateFormat("HH:mm", ptBr).format(Date(t))
        return when {
            c.get(Calendar.YEAR) == y && c.get(Calendar.DAY_OF_YEAR) == today -> "Hoje às $hour"
            c.get(Calendar.YEAR) == y && c.get(Calendar.DAY_OF_YEAR) == today - 1 -> "Ontem às $hour"
            else -> SimpleDateFormat("EEE, dd/MM 'às' HH:mm", ptBr).format(Date(t)).replaceFirstChar { it.uppercase() }
        }
    }
}

@Composable
fun TripsScreen(app: AppActions) {
    val all by TripStore.trips.collectAsState()
    val veh by Garage.current.collectAsState()
    val live by ObdManager.trip.live.collectAsState()
    var period by remember { mutableIntStateOf(1) }
    val periods = listOf("7 dias" to 7, "30 dias" to 30, "Tudo" to 36500)
    val since = System.currentTimeMillis() - periods[period].second * 86400000L
    val mine = all.filter { veh == null || it.vehicleId == veh!!.id || it.vehicleId == null }
    val list = mine.filter { it.start >= since }
    val tot = TripTotals.of(list)

    ScreenColumn {
        live?.let { t ->
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(Accent, 7)
                    Text("Viagem em andamento", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Text(Reports.hm(t.durationSec), style = MaterialTheme.typography.titleSmall.merge(Tabular), color = Accent)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Distância", "${Reports.num(t.km)} km", Modifier.weight(1f))
                    StatCard("Consumo", t.kmPerL?.let { "${Reports.num(it)}" } ?: "—", Modifier.weight(1f), sub = "km/L")
                    StatCard("Combustível", Reports.num(t.liters, 2), Modifier.weight(1f), sub = "litros")
                }
            }
        }

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            periods.forEachIndexed { i, (l, _) ->
                FilterChip(selected = period == i, onClick = { period = i; br.obd.app.Feedback.tick() }, label = { Text(l) })
            }
        }

        if (list.isEmpty()) {
            EmptyState(Icons.Filled.Route, "Nenhuma viagem ${if (period < 2) "neste período" else "ainda"}",
                "As viagens são registradas sozinhas: com o app conectado, comece a contar quando o motor liga e salva quando ele fica desligado por 45 s. Distância, tempo, consumo, temperatura e como você dirigiu.")
        } else {
            // resumo do período
            AppCard {
                Text("Resumo · ${periods[period].first}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Distância", "${Reports.num(tot.km, 0)} km", Modifier.weight(1f), icon = Icons.Filled.Route)
                    StatCard("Consumo médio", tot.kmPerL?.let { Reports.num(it) } ?: "—", Modifier.weight(1f), Accent, Icons.Filled.LocalGasStation, "km/L")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Combustível", "${Reports.num(tot.liters)} L", Modifier.weight(1f))
                    StatCard("Gasto", Reports.money(tot.cost), Modifier.weight(1f))
                    StatCard("Ao volante", Reports.hm(tot.runSec), Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Viagens", "${tot.count}", Modifier.weight(1f))
                    StatCard("Temp. média", tot.avgCoolant?.let { "${it.toInt()} °C" } ?: "—", Modifier.weight(1f), icon = Icons.Filled.Thermostat)
                    StatCard("Parado", if (tot.runSec > 0) "${(tot.idleSec / tot.runSec * 100).toInt()}%" else "—", Modifier.weight(1f))
                }
                val kpl = list.take(12).reversed().map { it.kmPerL }
                if (kpl.count { it != null } >= 2) {
                    Text("Consumo por viagem (km/L)", style = MaterialTheme.typography.labelLarge)
                    BarChart(kpl, height = 90)
                }
            }
            CalibrationHint(app)
            Text("Viagens", style = MaterialTheme.typography.titleMedium)
            list.forEach { TripRow(it) { app.nav.go("trip", it.id.toString()) } }
        }
    }
}

@Composable
private fun CalibrationHint(app: AppActions) {
    val p by Garage.current.collectAsState()
    val v = p ?: return
    if (v.calibCount > 0 && v.displacementSet) return
    Callout(
        if (!v.displacementSet) "O consumo é estimado com motor ${Reports.num(v.displacementL)} L. Informe a cilindrada certa e calibre com um abastecimento para ficar preciso."
        else "Consumo ainda não calibrado. Marque \"tanque cheio\" no próximo abastecimento e, no seguinte, informe os litros: o app ajusta sozinho.",
        Tone.INFO, Modifier.pressable { app.nav.go("vehicle") },
    )
}

@Composable
private fun TripRow(t: TripSummary, onClick: () -> Unit) {
    AppCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Filled.Route, Cyan, 36)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(TripsUiText.when_(t.start), style = MaterialTheme.typography.titleSmall)
                Hint("${Reports.hm(t.durationSec)} · ${Reports.num(t.avgSpeed, 0)} km/h de média")
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${Reports.num(t.km)} km", style = MaterialTheme.typography.titleMedium.merge(Tabular))
                Text(t.kmPerL?.let { "${Reports.num(it)} km/L" } ?: Reports.money(t.cost), style = MaterialTheme.typography.labelMedium, color = Accent)
            }
        }
    }
}

@Composable
fun TripDetailScreen(id: Long, app: AppActions) {
    val trips by TripStore.trips.collectAsState()
    val t = trips.firstOrNull { it.id == id }
    if (t == null) { EmptyState(Icons.Filled.Route, "Viagem não encontrada", "Ela pode ter sido apagada."); return }
    val pts = remember(id) { TripStore.points(id) }
    val profile = Garage.vehicles.collectAsState().value.firstOrNull { it.id == t.vehicleId } ?: Garage.current.collectAsState().value
    var confirm by remember { mutableStateOf(false) }

    ScreenColumn {
        AppCard {
            Text(TripsUiText.when_(t.start), style = MaterialTheme.typography.titleLarge)
            Hint(listOfNotNull(t.vehicleName, "${Reports.hm(t.durationSec)} de viagem").joinToString(" · "))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Distância", "${Reports.num(t.km)} km", Modifier.weight(1f), icon = Icons.Filled.Route)
                StatCard("Consumo", t.kmPerL?.let { Reports.num(it) } ?: "—", Modifier.weight(1f), Accent, Icons.Filled.LocalGasStation, "km/L")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Combustível", "${Reports.num(t.liters, 2)} L", Modifier.weight(1f))
                StatCard("Custo", Reports.money(t.cost), Modifier.weight(1f))
                StatCard("L/100 km", t.lPer100?.let { Reports.num(it) } ?: "—", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("Vel. média", "${Reports.num(t.avgSpeed, 0)} km/h", Modifier.weight(1f))
                StatCard("Vel. máx.", "${Reports.num(t.maxSpeed, 0)} km/h", Modifier.weight(1f))
                StatCard("Giro máx.", "${Reports.num(t.maxRpm, 0)}", Modifier.weight(1f), sub = "rpm")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { app.share(Reports.trip(ObdManager.context, t, pts, profile), "application/pdf") }, Modifier.weight(1f),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); BtnLabel("Relatório PDF")
            }
            OutlinedButton(onClick = { app.share(TripStore.csv(id, File(ObdManager.context.cacheDir, "logs")), "text/csv") }, Modifier.weight(1f)) {
                Icon(Icons.Filled.TableChart, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); BtnLabel("Planilha CSV")
            }
        }
        if (pts.size >= 2) {
            Section("Velocidade", icon = Icons.Filled.Speed) { LineChart(pts.map { it.speed }, min = 0.0, color = Cyan) }
            Section("Rotação", icon = Icons.Filled.ShowChart) { LineChart(pts.map { it.rpm }, min = 0.0) }
            Section("Temperatura do motor", icon = Icons.Filled.Thermostat) {
                LineChart(pts.map { it.coolant }, color = Bad, reference = 90.0, height = 100)
                KeyValue("Média", t.avgCoolant?.let { "${it.toInt()} °C" } ?: "—")
                KeyValue("Máxima", t.maxCoolant?.let { "${it.toInt()} °C" } ?: "—", t.maxCoolant?.let { if (it > 105) Bad else null })
                KeyValue("Aquecimento até 80 °C", t.warmupSec?.let { Reports.hm(it) } ?: if ((t.startCoolant ?: 0.0) >= 50) "já estava quente" else "não chegou")
                if (t.warmupSec != null && t.warmupSec > 15 * 60) Callout("O motor demorou mais de 15 min para aquecer: sinal de válvula termostática presa aberta.", Tone.WARN)
                if ((t.startCoolant ?: 0.0) < 50 && t.warmupSec == null && t.durationSec > 15 * 60)
                    Callout("O motor não chegou a 80 °C em ${Reports.hm(t.durationSec)}: verifique a válvula termostática.", Tone.WARN)
            }
            Section("Consumo instantâneo", icon = Icons.Filled.LocalGasStation) { LineChart(pts.map { it.lph }, min = 0.0, color = Good, height = 90); Hint("Litros por hora ao longo da viagem.") }
        }
        Section("Como você dirigiu", icon = Icons.Filled.Speed) {
            val tot = t.rpmBands.sum().coerceAtLeast(1.0)
            val labels = listOf("Até 1.500 rpm", "1.500 – 2.500 rpm", "2.500 – 3.500 rpm", "Acima de 3.500 rpm")
            val colors = listOf(Good, Cyan, Warn, Bad)
            t.rpmBands.forEachIndexed { i, s -> ShareBar(labels[i], (s / tot).toFloat(), colors[i]) }
            KeyValue("Tempo parado em lenta", "${Reports.hm(t.idleSec)} (${t.idlePct.toInt()}%)")
            KeyValue("Corte de combustível (descidas)", Reports.hm(t.fuelCutSec))
            KeyValue("Acelerações bruscas", "${t.hardAccel}", if (t.hardAccel > 3) Warn else null)
            KeyValue("Frenagens bruscas", "${t.hardBrake}", if (t.hardBrake > 3) Warn else null)
            t.minBattery?.let { KeyValue("Bateria (mín. / média)", "${Reports.num(it)} / ${Reports.num(t.avgBattery)} V", if (it < 12.5) Warn else null) }
            val eco = ecoScore(t)
            Callout("Nota de condução econômica: $eco/100. " + ecoTip(t), if (eco >= 75) Tone.GOOD else if (eco >= 50) Tone.INFO else Tone.WARN)
        }
        Hint(Reports.consumptionNote(t, profile))
        TextButton(onClick = { confirm = true }) {
            Icon(Icons.Filled.Delete, null, Modifier.size(16.dp), tint = Bad); Spacer(Modifier.width(6.dp)); Text("Apagar viagem", color = Bad)
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Apagar esta viagem?") },
        confirmButton = { TextButton(onClick = { confirm = false; TripStore.delete(id); app.nav.back() }) { BtnLabel("Apagar") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { BtnLabel("Cancelar") } },
    )
}

/** 0–100: penaliza giro alto, lenta longa e acelerações/frenagens bruscas. */
fun ecoScore(t: TripSummary): Int {
    val tot = t.rpmBands.sum().coerceAtLeast(1.0)
    var s = 100.0
    s -= t.rpmBands.getOrElse(2) { 0.0 } / tot * 40
    s -= t.rpmBands.getOrElse(3) { 0.0 } / tot * 90
    s -= (t.idlePct - 20).coerceAtLeast(0.0) * 0.5
    s -= (t.hardAccel + t.hardBrake) * 3
    return s.coerceIn(0.0, 100.0).toInt()
}

private fun ecoTip(t: TripSummary): String {
    val tot = t.rpmBands.sum().coerceAtLeast(1.0)
    return when {
        (t.rpmBands.getOrElse(2) { 0.0 } + t.rpmBands.getOrElse(3) { 0.0 }) / tot > 0.25 -> "Trocar de marcha antes de 2.500 rpm economiza bastante."
        t.idlePct > 35 -> "Muito tempo parado em lenta: em paradas longas, vale desligar o motor."
        t.hardAccel + t.hardBrake > 4 -> "Acelerar e frear com mais suavidade economiza combustível e freios."
        else -> "Boa condução."
    }
}
