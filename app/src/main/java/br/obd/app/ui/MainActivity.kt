package br.obd.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import br.obd.app.LiveMode
import br.obd.app.ObdManager
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObdManager.init(this)
        setContent { ScannerTheme { AppRoot() } }
    }
}

private data class Tab(val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("Painel", Icons.Filled.Speed),
    Tab("Combustível", Icons.Filled.LocalGasStation),
    Tab("Diagnóstico", Icons.Filled.Warning),
    Tab("Ferramentas", Icons.Filled.Build),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppRoot() {
    val context = LocalContext.current
    val connected by ObdManager.connected.collectAsState()
    val status by ObdManager.status.collectAsState()
    val busy by ObdManager.busy.collectAsState()
    val recording by ObdManager.recording.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var picker by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    val needed = buildList {
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val btOk = Build.VERSION.SDK_INT < 31 || res[Manifest.permission.BLUETOOTH_CONNECT] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        if (btOk) picker = true
    }
    val onConnect: () -> Unit = {
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) picker = true else permLauncher.launch(missing.toTypedArray())
    }
    val onShare: (File, String) -> Unit = { file, mime ->
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Compartilhar ${file.name}"))
    }

    // a leitura ao vivo segue a aba aberta (ou fica ligada enquanto grava uma viagem)
    LaunchedEffect(tab, connected, recording) {
        if (!connected) return@LaunchedEffect
        ObdManager.setLive(
            when {
                tab == 1 -> LiveMode.FUEL
                tab == 0 || recording != null -> LiveMode.DASHBOARD
                else -> LiveMode.OFF
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Scanner-Max", style = MaterialTheme.typography.titleLarge)
                            Text(busy ?: status, style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    actions = {
                        IconButton(onClick = { if (connected) confirmDisconnect = true else onConnect() }) {
                            Icon(
                                if (connected) Icons.Filled.BluetoothConnected else Icons.Filled.Bluetooth,
                                if (connected) "Desconectar" else "Conectar",
                                tint = if (connected) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                if (busy != null) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                tabs.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, null) }, label = { Text(t.label) },
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> DashboardScreen(onConnect)
                1 -> FuelScreen(onConnect)
                2 -> DiagnosticsScreen(onConnect)
                else -> ToolsScreen(onShare)
            }
        }
    }

    if (picker) DevicePicker(onDismiss = { picker = false })

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Desconectar?") },
            text = { Text("A leitura e a gravação de viagem param.") },
            confirmButton = { TextButton(onClick = { confirmDisconnect = false; ObdManager.disconnect() }) { Text("Desconectar") } },
            dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancelar") } },
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun DevicePicker(onDismiss: () -> Unit) {
    val devices = remember { ObdManager.bondedDevices() }
    val saved = ObdManager.savedAddress
    fun pick(d: BluetoothDevice) { onDismiss(); ObdManager.connect(d) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolha o adaptador") },
        text = {
            if (devices.isEmpty()) {
                Text("Nenhum dispositivo pareado. Ligue o Bluetooth e pareie o ELM327 nas configurações " +
                    "do celular (PIN 1234 ou 0000).")
            } else {
                Column {
                    devices.forEach { d ->
                        Column(Modifier.fillMaxWidth().clickable { pick(d) }.padding(vertical = 10.dp)) {
                            Text((d.name ?: "?") + if (d.address == saved) "  · último usado" else "",
                                style = MaterialTheme.typography.bodyLarge)
                            Hint(d.address)
                        }
                    }
                }
            }
        },
        confirmButton = {
            val last = devices.firstOrNull { it.address == saved }
            if (last != null) TextButton(onClick = { pick(last) }) { Text("Usar o último") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}
