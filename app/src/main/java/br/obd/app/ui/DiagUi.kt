package br.obd.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.obd.app.DtcGuide
import br.obd.app.Garage
import br.obd.app.Level
import br.obd.app.ObdManager
import br.obd.app.Reports
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsScreen(app: AppActions) {
    val connected by ObdManager.connected.collectAsState()
    val d by ObdManager.diag.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    val reset by ObdManager.resetResult.collectAsState()
    val veh by ObdManager.vehicle.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    ScreenColumn {
        val stored = d.stored.orEmpty(); val pending = d.pending.orEmpty()
        val total = stored.size + pending.size
        val mil = d.readiness?.milOn == true
        val reading = busy?.startsWith("Lendo diagnóstico") == true

        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, color) = when {
                    reading -> Icons.Filled.Refresh to Accent
                    d.readAt == null -> Icons.Filled.Memory to Accent
                    total == 0 && !mil -> Icons.Filled.CheckCircle to Good
                    else -> Icons.Filled.Warning to (if (stored.isNotEmpty() || mil) Bad else Warn)
                }
                val spin = rememberInfiniteTransition(label = "spin")
                val ang by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "a")
                Box(Modifier.rotate(if (reading) ang else 0f)) { IconBadge(icon, color, 56) }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(when {
                        reading -> "Lendo a central…"
                        d.readAt == null -> "Diagnóstico do motor"
                        total == 0 -> "Nenhum código de falha"
                        total == 1 -> "1 código de falha"
                        else -> "$total códigos de falha"
                    }, style = MaterialTheme.typography.titleLarge)
                    Hint(when {
                        d.readAt == null -> "Códigos, luz de injeção, prontidão para inspeção, freeze frame e testes internos da central."
                        else -> "Luz de injeção ${if (mil) "ACESA" else "apagada"} · lido ${SimpleDateFormat("dd/MM 'às' HH:mm", Locale("pt", "BR")).format(Date(d.readAt!!))}" +
                            if (!connected) " (último salvo)" else ""
                    })
                }
            }
            Button(onClick = { ObdManager.readDiagnostics() }, enabled = connected && busy == null, modifier = Modifier.fillMaxWidth()) {
                Text(if (!connected) "Conecte para ler" else if (d.readAt == null) "Ler diagnóstico" else "Ler de novo")
            }
            if (!connected) TextButton(onClick = app.onConnect) { Text("Conectar agora") }
        }

        if (total > 0) {
            Section("Códigos de falha", icon = Icons.Filled.Warning, trailing = { StatusPill("$total", if (stored.isNotEmpty()) Bad else Warn) }) {
                stored.forEach { DtcRow(it, true) { app.nav.go("dtc", it) } }
                pending.forEach { DtcRow(it, false) { app.nav.go("dtc", it) } }
                Hint("Toque num código para ver o que significa, as causas e o que fazer.")
                OutlinedButton(onClick = { confirmClear = true }, enabled = connected && busy == null) { Text("Apagar códigos") }
            }
        } else if (d.readAt != null) {
            Callout("A central não tem nenhum código registrado. Se o carro tem um sintoma, os testes guiados ajudam a achar a causa.", Tone.GOOD)
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
                if (r.monitors.any { !it.second }) Callout("Monitores incompletos ficam prontos sozinhos depois de alguns ciclos de uso " +
                    "(partida a frio + estrada). É normal depois de apagar códigos ou desligar a bateria. Numa vistoria, todos precisam estar prontos.")
            }
        }

        if (d.readAt != null && (d.freezeDtc != null || d.freeze.isNotEmpty())) {
            Section("Freeze frame", icon = Icons.Filled.Memory) {
                Text("Como o motor estava quando apareceu ${d.freezeDtc}", fontWeight = FontWeight.Medium)
                d.freeze.forEach { (k, value) -> KeyValue(k, value) }
                Hint("Ajuda a repetir a condição do defeito: rotação, velocidade e temperatura no instante em que ele foi registrado.")
            }
        }

        if (d.mode06Can.isNotEmpty()) Section("Testes internos da central", icon = Icons.Filled.Science) {
            d.mode06Can.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.name} · teste %02X".format(t.tid), style = MaterialTheme.typography.bodyMedium)
                        Hint("valor ${t.value} · faixa ${t.min}…${t.max}")
                    }
                    StatusPill(if (t.passed) "OK" else "fora", if (t.passed) Good else Bad)
                }
            }
            Hint("Resultados da última vez que a central testou cada componente. \"Fora\" = fora dos limites que ela mesma definiu.")
        }
        if (d.mode06Can.isEmpty() && d.mode06.isNotEmpty()) Section("Testes da sonda (central)", icon = Icons.Filled.Science) {
            d.mode06.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${t.name} · ${t.componentName}", style = MaterialTheme.typography.bodyMedium)
                        Hint("%.3f %s · limite %s %.3f".format(t.value, t.unit, if (t.limitIsMin) "mín." else "máx.", t.limit))
                    }
                    StatusPill(if (t.passed) "OK" else "fora", if (t.passed) Good else Bad)
                }
            }
        }

        // ferramentas de diagnóstico
        Text("Mais diagnósticos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActionTile(Icons.Filled.Science, "Testes guiados", Modifier.weight(1f)) { app.nav.home(2) }
            ActionTile(Icons.Filled.Search, "Consultar código", Modifier.weight(1f), Cyan) { app.nav.go("lookup") }
            ActionTile(Icons.Filled.ListAlt, "Relatório PDF", Modifier.weight(1f), Good) { HealthReport.share(app) }
        }
        if (veh.isVw || Garage.current.collectAsState().value?.maker?.startsWith("Volkswagen") == true) {
            MenuRow(Icons.Filled.Usb, "Central do fabricante (VW)", "Com o cabo KKL: identificação, blocos de medição e códigos VAG", Cyan) { app.nav.go("cable") }
        }

        Section("Zerar adaptação (modo 04)", icon = Icons.Filled.Refresh) {
            Hint("Única escrita que o OBD genérico aceita. Apaga códigos e prontidão; em algumas centrais também zera o aprendizado de combustível. O app mede a correção longa antes e depois.")
            OutlinedButton(onClick = { confirmReset = true }, enabled = connected && busy == null) { Text("Tentar zerar a adaptação") }
            reset?.let { Callout(it, if (it.contains("zerada")) Tone.GOOD else Tone.INFO) }
        }
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Apagar códigos?") },
        text = { Text("Apaga os códigos, a luz de injeção, o freeze frame e os monitores de prontidão. Se o defeito continuar, o código volta. Antes de uma vistoria, os monitores precisam de alguns dias rodando para ficarem prontos de novo.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; ObdManager.clearDtcs() }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Zerar adaptação?") },
        text = { Text("O modo 04 apaga os códigos, o freeze frame e os monitores de prontidão. Não grava nada além disso.") },
        confirmButton = { TextButton(onClick = { confirmReset = false; ObdManager.resetAdaptation() }) { Text("Continuar") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancelar") } },
    )
}

@Composable
fun DtcRow(code: String, confirmed: Boolean, onClick: () -> Unit) {
    val det = remember(code) { DtcGuide.detail(code) }
    val c = if (confirmed) levelColor(det.level).takeIf { det.level != Level.INFO } ?: Bad else Warn
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 9.dp, vertical = 6.dp)) {
            Text(code, style = MaterialTheme.typography.labelLarge.merge(Tabular), fontWeight = FontWeight.Bold, color = c)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(det.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Hint("${if (confirmed) "confirmado" else "pendente"} · ${det.system}")
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Explicação completa de um código, com pesquisa em um toque. */
@Composable
fun DtcScreen(code: String, app: AppActions) {
    val det = remember(code) { DtcGuide.detail(code) }
    val veh = Garage.current.collectAsState().value
    val vehicleQuery = listOfNotNull(veh?.maker?.substringBefore(" ("), veh?.name?.takeIf { it != veh.maker }, veh?.year?.toString())
        .distinct().joinToString(" ").ifBlank { null }
    val c = levelColor(det.level).takeIf { det.level != Level.INFO } ?: Cyan
    ScreenColumn {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.15f)).padding(horizontal = 18.dp, vertical = 10.dp)) {
                Text(det.code, style = Tabular, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = c)
            }
            Text(det.title, style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(det.system, Cyan)
                StatusPill(when (det.level) { Level.BAD -> "grave"; Level.WARN -> "moderado"; else -> "leve" }, c)
                if (det.makerSpecific) StatusPill("do fabricante", Warn)
            }
        }
        Section("O que significa", icon = Icons.Filled.Lightbulb) { Text(det.meaning, style = MaterialTheme.typography.bodyMedium) }
        Callout(det.drive, if (det.level == Level.BAD) Tone.BAD else Tone.INFO, title = "Posso rodar?")
        if (det.causes.isNotEmpty()) Section("Causas mais comuns", icon = Icons.Filled.Warning) {
            det.causes.forEach { Row { Text("•  ", color = Accent); Text(it, style = MaterialTheme.typography.bodyMedium) } }
        }
        Section("O que fazer", icon = Icons.Filled.CheckCircle) { det.actions.forEachIndexed { i, s -> Step(i + 1, s) } }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { app.openUrl(DtcGuide.searchUrl(det.code, vehicleQuery)) }, Modifier.weight(1f),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Pesquisar")
            }
            OutlinedButton(onClick = { app.openUrl(DtcGuide.videoUrl(det.code, vehicleQuery)) }, Modifier.weight(1f)) {
                Icon(Icons.Filled.OndemandVideo, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Vídeos")
            }
        }
        vehicleQuery?.let { Hint("A pesquisa inclui \"$it\" para achar resultados do seu carro.") }
    }
}

@Composable
fun LookupScreen(app: AppActions) {
    var q by remember { mutableStateOf("") }
    val code = DtcGuide.normalize(q)
    val common = listOf("P0171", "P0172", "P0300", "P0420", "P0128", "P0135", "P0505", "P0562", "P0106", "P0130")
    ScreenColumn {
        OutlinedTextField(
            q, { q = it.take(6) }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Código (ex.: P0171)") }, shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            textStyle = MaterialTheme.typography.titleLarge.merge(Tabular),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { code?.let { app.nav.go("dtc", it) } }),
        )
        Button(onClick = { code?.let { app.nav.go("dtc", it) } }, enabled = code != null, modifier = Modifier.fillMaxWidth()) {
            Text(if (code != null) "Ver $code" else "Digite um código válido")
        }
        code?.let {
            val d = DtcGuide.detail(it)
            AppCard(onClick = { app.nav.go("dtc", it) }) {
                Text(d.title, style = MaterialTheme.typography.titleMedium)
                Hint(d.system)
            }
        }
        Section("Entendendo o código", icon = Icons.Filled.Lightbulb) {
            KeyValue("1ª letra", "P motor/câmbio · C chassi · B carroceria · U rede")
            KeyValue("2º dígito", "0 e 2 genérico · 1 e 3 do fabricante")
            KeyValue("3º dígito (P)", "1–2 mistura/ar · 3 ignição · 4 emissões · 5 lenta/velocidade · 6 central · 7–9 câmbio")
        }
        Section("Mais comuns", icon = Icons.Filled.DirectionsCar) {
            common.forEach { c -> DtcRow(c, true) { app.nav.go("dtc", c) } }
        }
    }
}
