package br.obd.app.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.obd.app.ObdManager
import br.obd.app.link.LinkKind
import br.obd.app.link.LinkTarget
import br.obd.app.usb.UsbKLinePort
import kotlinx.coroutines.delay

@SuppressLint("MissingPermission")
@Composable
fun ConnectScreen(app: AppActions) {
    val ctx = LocalContext.current
    val connected by ObdManager.connected.collectAsState()
    val status by ObdManager.status.collectAsState()
    var granted by remember { mutableStateOf(Permissions.hasBluetooth(ctx)) }
    var refresh by remember { mutableStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Permissions.hasBluetooth(ctx); refresh++
    }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Permissions.bluetoothList()) }
    // conectou: volta para o painel
    LaunchedEffect(connected) { if (connected) { delay(400); app.nav.home(0) } }

    fun go(t: LinkTarget) {
        if (t.kind == LinkKind.BT_CLASSIC || t.kind == LinkKind.BLE) app.ensureBt(true) { ObdManager.connect(t) }
        else ObdManager.connect(t)
    }
    val connecting = status.startsWith("Conectando") || status.startsWith("Inicializando")

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AnimatedVisibility(connecting || status.startsWith("Falha")) {
            Callout(status, if (status.startsWith("Falha")) Tone.BAD else Tone.INFO)
        }
        if (!granted) {
            Section("Permissão do Bluetooth", icon = Icons.Filled.Bluetooth) {
                Hint("O Android pede autorização para o app ver e conectar ao adaptador. Nada é enviado para fora do aparelho.")
                Button(onClick = { launcher.launch(Permissions.bluetoothList()) }) { BtnLabel("Permitir") }
                TextButton(onClick = {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)))
                }) { Text("Abrir configurações do app") }
            }
        }

        ObdManager.savedTarget?.let { last ->
            AppCard(onClick = { go(last) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Filled.History, Accent, 40)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Conectar ao último", style = MaterialTheme.typography.titleMedium)
                        Hint("${last.display} · ${last.kind.label}")
                    }
                    if (connecting) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }

        // ---------------- Bluetooth clássico (pareados)
        val bonded = remember(granted, refresh) { if (granted) ObdManager.bondedDevices() else emptyList() }
        var showAll by remember { mutableStateOf(false) }
        Section("Bluetooth pareados", icon = Icons.Filled.Bluetooth) {
            if (!ObdManager.bluetoothOn()) {
                Callout("O Bluetooth está desligado.", Tone.WARN)
                Button(onClick = { app.ensureBt(true) { refresh++ } }) { BtnLabel("Ativar Bluetooth") }
            }
            val obd = bonded.filter { ObdManager.looksLikeObd(it.name) }
            val others = bonded - obd.toSet()
            if (bonded.isEmpty()) Hint("Nenhum aparelho pareado. Pareie o adaptador nas configurações de Bluetooth do celular (PIN 1234 ou 0000) — adaptadores BLE não precisam: use a busca abaixo.")
            obd.forEach { DeviceRow(it) { t -> go(t) } }
            if (others.isNotEmpty()) {
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Esconder outros aparelhos" else "Mostrar outros ${others.size} aparelhos") }
                AnimatedVisibility(showAll) { Column { others.forEach { DeviceRow(it) { t -> go(t) } } } }
            }
            TextButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_BLUETOOTH_SETTINGS)) }) { BtnLabel("Parear um adaptador novo") }
        }

        // ---------------- BLE
        BleScanSection(granted) { go(it) }

        // ---------------- Wi-Fi
        var host by remember { mutableStateOf(LinkTarget.WIFI_DEFAULT) }
        Section("Wi-Fi", icon = Icons.Filled.Wifi) {
            Hint("Conecte o celular na rede Wi-Fi do adaptador (ex.: WiFi_OBDII, V-LINK) e toque em conectar. O endereço padrão quase sempre é 192.168.0.10:35000.")
            OutlinedTextField(host, { host = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Endereço:porta") },
                shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { go(LinkTarget(LinkKind.WIFI, host, "Adaptador Wi-Fi")) }, enabled = !connecting) { BtnLabel("Conectar") }
                TextButton(onClick = { ctx.startActivity(Intent(AndroidSettings.ACTION_WIFI_SETTINGS)) }) { BtnLabel("Abrir Wi-Fi") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("192.168.0.10:35000", "192.168.0.123:35000", "192.168.1.10:35000").forEach { h ->
                    androidx.compose.material3.SuggestionChip(onClick = { host = h }, label = { Text(h.substringBefore(":").substringAfterLast(".").let { "…$it" }) })
                }
            }
        }

        // ---------------- USB
        var usb by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) { while (true) { usb = UsbKLinePort.findDrivers(ctx).firstOrNull()?.let { UsbKLinePort.describe(it.device) }; delay(1500) } }
        Section("USB (OTG)", icon = Icons.Filled.Usb, trailing = { StatusPill(if (usb != null) "detectado" else "nada plugado", if (usb != null) Good else MaterialTheme.colorScheme.onSurfaceVariant) }) {
            Hint("ELM327 com cabo USB (FTDI, CH340, CP210x, PL2303). Cabos KKL da VW não são ELM327: use Menu → Central do fabricante.")
            usb?.let { KeyValue("Dispositivo", it) }
            FilledTonalButton(onClick = { go(LinkTarget(LinkKind.USB, "usb", usb ?: "USB")) }, enabled = usb != null && !connecting) { BtnLabel("Conectar pelo USB") }
        }

        // ---------------- demo
        AppCard(onClick = { go(LinkTarget.DEMO) }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Filled.PlayCircle, Cyan, 40)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Modo demonstração", style = MaterialTheme.typography.titleMedium)
                    Hint("Um carro simulado andando: veja o painel, viagens, alertas e testes sem adaptador.")
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DeviceRow(d: BluetoothDevice, onPick: (LinkTarget) -> Unit) {
    val type = d.type
    val ble = type == BluetoothDevice.DEVICE_TYPE_LE
    val name = d.name ?: d.address
    MenuRow(
        if (ble) Icons.Filled.BluetoothSearching else Icons.Filled.Bluetooth, name,
        "${d.address} · ${when (type) { BluetoothDevice.DEVICE_TYPE_LE -> "BLE"; BluetoothDevice.DEVICE_TYPE_DUAL -> "duplo"; else -> "clássico" }}",
        if (ObdManager.looksLikeObd(d.name)) Accent else MaterialTheme.colorScheme.onSurfaceVariant,
    ) { onPick(LinkTarget(if (ble) LinkKind.BLE else LinkKind.BT_CLASSIC, d.address, name)) }
}

private data class Found(val address: String, val name: String, val rssi: Int)

@SuppressLint("MissingPermission")
@Composable
private fun BleScanSection(granted: Boolean, onPick: (LinkTarget) -> Unit) {
    val ctx = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<Found>() }
    val scanner = remember { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner }
    val cb = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.device.name ?: result.scanRecord?.deviceName ?: return
                val f = Found(result.device.address, n, result.rssi)
                val i = found.indexOfFirst { it.address == f.address }
                if (i >= 0) found[i] = f else found.add(f)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { if (scanning) runCatching { scanner?.stopScan(cb) } } }
    LaunchedEffect(scanning) {
        if (!scanning) return@LaunchedEffect
        found.clear()
        runCatching { scanner?.startScan(cb) }
        delay(10_000)
        runCatching { scanner?.stopScan(cb) }
        scanning = false
    }
    Section("Bluetooth LE", icon = Icons.Filled.BluetoothSearching, trailing = {
        if (scanning) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }) {
        Hint("Adaptadores BLE (Vgate iCar Pro BLE, Veepeak BLE, OBDLink CX, \"IOS-Vlink\"…) não aparecem nos pareados: procure aqui.")
        FilledTonalButton(onClick = { scanning = true }, enabled = granted && !scanning && scanner != null) {
            Text(if (scanning) "Procurando…" else "Procurar adaptadores BLE")
        }
        found.sortedWith(compareByDescending<Found> { ObdManager.looksLikeObd(it.name) }.thenByDescending { it.rssi }).forEach { f ->
            MenuRow(Icons.Filled.BluetoothSearching, f.name, "${f.address} · sinal ${f.rssi} dBm",
                if (ObdManager.looksLikeObd(f.name)) Accent else MaterialTheme.colorScheme.onSurfaceVariant) {
                scanning = false
                onPick(LinkTarget(LinkKind.BLE, f.address, f.name))
            }
        }
        if (!scanning && found.isEmpty()) Hint("Ligue a ignição para o adaptador acordar e toque em procurar.")
    }
}
