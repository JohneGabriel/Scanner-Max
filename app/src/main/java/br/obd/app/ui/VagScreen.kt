package br.obd.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.obd.app.ObdManager
import br.obd.app.VagManager
import br.obd.app.vag.VagMeasure
import kotlinx.coroutines.delay

/** Central VW pelo cabo KKL (USB/OTG): identificação, blocos de medição e códigos. */
@Composable
fun VagScreen() {
    val status by VagManager.status.collectAsState()
    val busy by VagManager.busy.collectAsState()
    val session by VagManager.session.collectAsState()
    val ident by VagManager.ident.collectAsState()
    val group by VagManager.group.collectAsState()
    val dtcs by VagManager.dtcs.collectAsState()
    val live by VagManager.liveGroup.collectAsState()
    val log by ObdManager.logTail.collectAsState()
    val btConnected by ObdManager.connected.collectAsState()

    var address by remember { mutableStateOf("01") }
    var groupText by remember { mutableStateOf("1") }
    var cable by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { while (true) { cable = VagManager.cableDescription(); delay(1500) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Cabo KKL (USB)", icon = Icons.Filled.Usb, trailing = {
            StatusPill(if (cable != null) "detectado" else "não detectado", if (cable != null) Good else Warn)
        }) {
            KeyValue("Cabo", cable ?: "—")
            Hint(status)
            if (busy != null) Text(busy!!, color = Accent, style = MaterialTheme.typography.bodyMedium)
            if (btConnected) {
                Callout("O ELM327 (Bluetooth) ainda está conectado. Só um adaptador pode ficar na tomada OBD: " +
                    "desconecte-o no topo do app antes de plugar o cabo.", Tone.WARN)
            }
            if (session == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        address, { address = it.take(2).uppercase() }, Modifier.width(110.dp),
                        label = { Text("Endereço") }, singleLine = true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Button(
                        onClick = { address.toIntOrNull(16)?.let { VagManager.connect(it) } },
                        enabled = busy == null && cable != null,
                    ) { Text("Conectar") }
                }
                Hint("01 = motor. Ignição ligada (motor pode estar ligado). A primeira tentativa leva uns 3 s; " +
                    "se falhar, o app tenta de novo em outra velocidade.")
            } else {
                KeyValue("Protocolo", "${session!!.protocol} · ${session!!.baud} baud")
                KeyValue("Key bytes", "%02X %02X".format(session!!.kw1, session!!.kw2))
                OutlinedButton(onClick = { VagManager.disconnect() }) { Text("Desconectar") }
            }
        }

        if (ident.isNotEmpty()) {
            Section("Identificação da central", icon = Icons.Filled.Memory) {
                ident.forEach { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tabular)) }
            }
        }

        if (session != null) {
            Section("Blocos de medição", icon = Icons.Filled.ShowChart, trailing = {
                if (live != null) StatusPill("ao vivo", Good)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        groupText, { groupText = it.filter(Char::isDigit).take(3) }, Modifier.width(110.dp),
                        label = { Text("Grupo") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { groupText.toIntOrNull()?.let { VagManager.readGroup(it) } },
                        enabled = busy == null && live == null,
                    ) { Text("Ler") }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = {
                        VagManager.setLiveGroup(if (live == null) groupText.toIntOrNull() else null)
                    }) { Text(if (live == null) "Ao vivo" else "Parar") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(-1, 1).forEach { d ->
                        OutlinedButton(onClick = {
                            val g = ((groupText.toIntOrNull() ?: 1) + d).coerceIn(0, 255)
                            groupText = g.toString()
                            if (live != null) VagManager.setLiveGroup(g) else VagManager.readGroup(g)
                        }, enabled = busy == null) { Text(if (d < 0) "◀ grupo" else "grupo ▶") }
                    }
                }
                group?.let { (g, cells) ->
                    Text("Grupo %03d".format(g), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cells.withIndex().chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEachIndexed { i, (idx, c) ->
                                val v = VagMeasure.decode(c)
                                StatCard(
                                    "Campo ${idx + 1}",
                                    if (v.known) "${v.text} ${v.unit}".trim() else v.text,
                                    Modifier.weight(1f),
                                    if (v.known) null else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (row.size == 1 && i == 0) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    Hint("Campos em cinza são de um tipo que o app ainda não traduz: aparecem crus.")
                }
            }

            Section("Códigos de falha (VW)", icon = Icons.Filled.Warning) {
                FilledTonalButton(onClick = { VagManager.readDtcs() }, enabled = busy == null && live == null) {
                    Text("Ler códigos")
                }
                dtcs?.let { list ->
                    if (list.isEmpty()) Text("Nenhum código registrado.", color = Good)
                    list.forEach { Text(VagMeasure.dtcText(it), style = MaterialTheme.typography.bodyMedium.merge(Tabular)) }
                }
            }
        }

        Callout("Esta primeira versão só lê. Adaptação da borboleta e reset do aprendizado (canal 00) entram " +
            "depois que a comunicação for confirmada com o seu carro.", Tone.INFO)

        Section("Log da linha K", icon = Icons.Filled.Memory) {
            val lines = log.filter { it.contains("[VW]") }.takeLast(40)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background).padding(12.dp),
            ) { Mono(lines.joinToString("\n").ifBlank { "(vazio)" }) }
            Hint("Exporte o log completo pela aba Ferramentas e me mande depois do primeiro teste.")
        }
        Spacer(Modifier.height(24.dp))
    }
}
