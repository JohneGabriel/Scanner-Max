package br.obd.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import br.obd.app.Feedback
import br.obd.app.FuelKind
import br.obd.app.Garage
import br.obd.app.Settings

/**
 * Setup inicial (pode ser pulado a qualquer momento): apresentação, permissões, dados do
 * carro, tipo de adaptador e tema. Tudo pode ser mudado depois no menu.
 */
@Composable
fun SetupScreen(onDone: (connect: Boolean) -> Unit) {
    val ctx = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    val pages = 5
    // respostas
    val cur = Garage.current.value
    var name by remember { mutableStateOf(cur?.name ?: "") }
    var disp by remember { mutableStateOf(cur?.takeIf { it.displacementSet }?.displacementL?.let { "%.1f".format(it).replace(",", ".") } ?: "") }
    var fuel by remember { mutableStateOf(cur?.fuel ?: FuelKind.GASOLINA) }
    var price by remember { mutableStateOf("") }
    var permsAsked by remember { mutableStateOf(Permissions.hasBluetooth(ctx)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsAsked = true }

    fun finish(connect: Boolean) {
        Garage.applySetup(name.takeIf { it.isNotBlank() }, disp.replace(",", ".").toDoubleOrNull(), fuel,
            price.replace(",", ".").toDoubleOrNull())
        Settings.update { it.copy(onboarded = true) }
        Feedback.success()
        onDone(connect)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Column(Modifier.fillMaxSize().widthIn(max = 640.dp).align(Alignment.TopCenter)) {
            // topo: progresso e pular
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(pages) { i ->
                        val w by animateDpAsState(if (i == page) 22.dp else 8.dp, label = "dot")
                        val c by animateColorAsState(if (i <= page) Accent else MaterialTheme.colorScheme.surfaceVariant, label = "dotc")
                        Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(c))
                    }
                }
                TextButton(onClick = { Settings.update { it.copy(onboarded = true) }; onDone(false) }) { BtnLabel("Pular") }
            }
            AnimatedContent(page, Modifier.weight(1f), transitionSpec = {
                val fwd = targetState > initialState
                (fadeIn(tween(280)) + slideInHorizontally(tween(280)) { if (fwd) it / 6 else -it / 6 }) togetherWith
                    (fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { if (fwd) -it / 8 else it / 8 })
            }, label = "setup") { p ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (p) {
                        0 -> {
                            Spacer(Modifier.height(12.dp))
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { LogoMark(96) }
                            Text("Bem-vindo ao Scanner-Max", style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            Text("Em um minuto o app fica pronto para o seu carro. Dá para pular e ajustar depois no menu.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                            Feature(Icons.Filled.Warning, "Códigos de falha explicados", "O que significam, se dá para rodar e o que fazer.")
                            Feature(Icons.Filled.Science, "10 testes guiados", "Bateria, mistura, sonda, arrefecimento e mais.")
                            Feature(Icons.Filled.Route, "Viagens e consumo", "Registradas sozinhas, com custo e relatório.")
                            Feature(Icons.Filled.NotificationsActive, "Alertas", "Superaquecimento, alternador e códigos novos.")
                        }
                        1 -> {
                            StepTitle(Icons.Filled.Bluetooth, "Permissões", "Para encontrar o adaptador e avisar você de problemas no carro.")
                            Feature(Icons.Filled.Bluetooth, "Bluetooth e dispositivos próximos", "Conectar ao adaptador OBD2.")
                            Feature(Icons.Filled.NotificationsActive, "Notificações", "Dados ao vivo e alertas críticos, mesmo com a tela apagada.")
                            Callout("Nada sai do seu aparelho: o app não tem servidor nem conta.", Tone.INFO)
                            if (permsAsked) Callout("Permissões concedidas (ou já pedidas).", Tone.GOOD)
                            else Button(onClick = { launcher.launch(Permissions.bluetoothList()) }, Modifier.fillMaxWidth()) { BtnLabel("Permitir") }
                        }
                        2 -> {
                            StepTitle(Icons.Filled.DirectionsCar, "Seu carro", "O app lê chassi, protocolo e sensores sozinho. Isto aqui melhora o cálculo de consumo.")
                            OutlinedTextField(name, { name = it.take(30) }, Modifier.fillMaxWidth(), label = { Text("Nome (ex.: Gol G5)") },
                                singleLine = true, shape = RoundedCornerShape(14.dp))
                            Text("Motor (cilindrada)", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("1.0", "1.3", "1.4", "1.5", "1.6", "1.8", "2.0", "2.4").forEach { d ->
                                    FilterChip(selected = disp == d, onClick = { disp = d; Feedback.tick() }, label = { Text(d) })
                                }
                            }
                            OutlinedTextField(disp, { disp = it.take(4) }, Modifier.fillMaxWidth(), label = { Text("Ou digite (litros)") }, singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
                            Text("Combustível que costuma usar", style = MaterialTheme.typography.titleSmall)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(FuelKind.GASOLINA to "Gasolina", FuelKind.ETANOL to "Etanol", FuelKind.MISTURA to "Flex/misturado", FuelKind.DIESEL to "Diesel")
                                    .forEach { (k, l) -> FilterChip(selected = fuel == k, onClick = { fuel = k; Feedback.tick() }, label = { Text(l) }) }
                            }
                            OutlinedTextField(price, { price = it.take(6) }, Modifier.fillMaxWidth(), label = { Text("Preço do litro (R$, opcional)") },
                                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(14.dp))
                        }
                        3 -> {
                            StepTitle(Icons.Filled.BluetoothSearching, "Seu adaptador", "O app funciona com qualquer ELM327 ou compatível. Você escolhe na hora de conectar.")
                            Feature(Icons.Filled.Bluetooth, "Bluetooth comum", "Pareie nas configurações do celular (PIN 1234 ou 0000).")
                            Feature(Icons.Filled.BluetoothSearching, "Bluetooth LE", "Vgate, Veepeak, OBDLink CX… sem parear: o app procura.")
                            Feature(Icons.Filled.Wifi, "Wi-Fi", "Conecte o celular na rede do adaptador.")
                            Feature(Icons.Filled.Usb, "USB (OTG)", "ELM327 com cabo USB.")
                            Feature(Icons.Filled.PlayCircle, "Sem adaptador?", "Use o modo demonstração para conhecer o app.")
                        }
                        else -> {
                            StepTitle(Icons.Filled.Palette, "Escolha um tema", "Dá para trocar quando quiser em Personalizar.")
                            ThemePicker()
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            // rodapé
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (page > 0) OutlinedButton(onClick = { page-- }, Modifier.weight(1f)) { BtnLabel("Voltar") }
                if (page < pages - 1) Button(onClick = { page++; Feedback.tick() }, Modifier.weight(1f)) { BtnLabel(if (page == 0) "Começar" else "Próximo") }
                else {
                    OutlinedButton(onClick = { finish(false) }, Modifier.weight(1f)) { BtnLabel("Concluir") }
                    Button(onClick = { finish(true) }, Modifier.weight(1f)) { BtnLabel("Conectar agora") }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        IconBadge(icon, Accent, 52)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Feature(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, Accent, 36)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
