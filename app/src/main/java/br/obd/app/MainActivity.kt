package br.obd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val gauges = linkedMapOf<String, TextView>()
    private var liveJob: Job? = null
    private var busyJob: Job? = null

    /** Log completo da sessão (a tela mostra só o fim); é isso que vai para a exportação. */
    private val fullLog = ArrayList<String>()
    private var lastReport: String? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val elm = Elm327 { msg -> runOnUiThread { appendLog(msg) } }

    private val askPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) pickDevice() else status.text = "Permissão de Bluetooth negada"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        status = TextView(this).apply { text = "Desconectado"; textSize = 16f }
        root.addView(status)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        button("Conectar") { connectClicked() }
        button("Iniciar / parar leitura ao vivo") { toggleLive() }
        button("Ler códigos de falha") { readDtcs() }
        button("Apagar códigos de falha") { confirmClear() }
        button("Testar capacidade do adaptador") { runProbe() }
        button("Exportar log") { exportLog() }

        val grid = GridLayout(this).apply { columnCount = 2 }
        listOf("RPM", "Velocidade", "Temp. motor", "Carga", "Acelerador", "Temp. admissão", "Bateria")
            .forEach { name ->
                val tv = TextView(this).apply {
                    text = "$name\n—"
                    textSize = 18f
                    setPadding(0, pad / 2, pad, pad / 2)
                }
                gauges[name] = tv
                grid.addView(tv)
            }
        root.addView(grid)

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(0, pad, 0, 0)
        }
        root.addView(logView)

        return ScrollView(this).apply { addView(root, MATCH_PARENT, WRAP_CONTENT) }
    }

    private fun connectClicked() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            askPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else pickDevice()
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Ligue o Bluetooth do celular"; return
        }
        val devices = adapter.bondedDevices.toList()
            .sortedByDescending { (it.name ?: "").contains("OBD", ignoreCase = true) }
        if (devices.isEmpty()) {
            status.text = "Pareie o ELM327 nas configurações de Bluetooth (PIN 1234 ou 0000)"; return
        }
        AlertDialog.Builder(this)
            .setTitle("Escolha o adaptador")
            .setItems(devices.map { "${it.name ?: "?"}\n${it.address}" }.toTypedArray()) { _, i ->
                lifecycleScope.launch { connectTo(devices[i]) }
            }
            .show()
    }

    private suspend fun connectTo(device: android.bluetooth.BluetoothDevice): Boolean = try {
        status.text = "Conectando…"
        elm.close()
        elm.connect(device)
        status.text = "Inicializando…"
        val version = elm.init()
        status.text = "Conectado: ${version.lines().last()}"
        true
    } catch (e: Exception) {
        status.text = "Falha: ${e.message}"
        false
    }

    /** Tenta voltar a falar com o último adaptador (até 3 vezes). */
    private suspend fun reconnect(): Boolean {
        val device = elm.lastDevice ?: return false
        for (attempt in 1..3) {
            status.text = "Conexão caiu — reconectando ($attempt/3)…"
            if (connectTo(device)) return true
            delay(2000)
        }
        status.text = "Não foi possível reconectar. Toque em Conectar."
        return false
    }

    private fun toggleLive() {
        if (liveJob?.isActive == true) {
            liveJob?.cancel(); status.text = "Leitura parada"; return
        }
        if (!elm.isConnected) { status.text = "Conecte primeiro"; return }
        if (busyJob?.isActive == true) { status.text = "Espere o teste terminar"; return }
        liveJob = lifecycleScope.launch {
            status.text = "Lendo ao vivo…"
            var tick = 0
            var t0 = System.currentTimeMillis()
            var rpmReads = 0
            // A linha K faz uns 3,5 pedidos/s: gastamos a maior parte com o que muda rápido.
            while (isActive) {
                try {
                    elm.readPid("0C")?.takeIf { it.size >= 2 }?.let {
                        set("RPM", "${(it[0] * 256 + it[1]) / 4} rpm"); rpmReads++
                    }
                    if (tick % 2 == 0) elm.readPid("0D")?.let { set("Velocidade", "${it[0]} km/h") }
                    if (tick % 2 == 1) elm.readPid("11")?.let { set("Acelerador", "${it[0] * 100 / 255} %") }
                    if (tick % 4 == 1) elm.readPid("04")?.let { set("Carga", "${it[0] * 100 / 255} %") }
                    when (tick % 10) {
                        3 -> elm.readPid("05")?.let { set("Temp. motor", "${it[0] - 40} °C") }
                        6 -> elm.readPid("0F")?.let { set("Temp. admissão", "${it[0] - 40} °C") }
                        9 -> set("Bateria", elm.batteryVoltage())
                    }
                    tick++
                    val now = System.currentTimeMillis()
                    if (now - t0 >= 3000) {
                        status.text = "Lendo ao vivo — RPM %.1f×/s".format(rpmReads * 1000.0 / (now - t0))
                        t0 = now; rpmReads = 0
                    }
                } catch (e: Exception) {
                    if (!elm.isConnected && reconnect()) continue
                    status.text = "Erro: ${e.message}"; break
                }
            }
        }
    }

    private fun set(name: String, value: String) {
        gauges[name]?.text = "$name\n$value"
    }

    private fun readDtcs() {
        if (!elm.isConnected) { status.text = "Conecte primeiro"; return }
        liveJob?.cancel()
        lifecycleScope.launch {
            try {
                val stored = elm.readDtcs("03")
                val pending = elm.readDtcs("07")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Códigos de falha")
                    .setMessage(
                        "Confirmados: ${stored.ifEmpty { listOf("nenhum") }.joinToString()}\n\n" +
                            "Pendentes: ${pending.ifEmpty { listOf("nenhum") }.joinToString()}"
                    )
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                status.text = "Erro: ${e.message}"
            }
        }
    }

    private fun confirmClear() {
        if (!elm.isConnected) { status.text = "Conecte primeiro"; return }
        AlertDialog.Builder(this)
            .setTitle("Apagar códigos?")
            .setMessage(
                "Use com a ignição ligada e o motor desligado. Isso apaga também o freeze frame " +
                    "e zera os monitores de emissões. Se o defeito continuar, a luz volta."
            )
            .setPositiveButton("Apagar") { _, _ ->
                liveJob?.cancel()
                lifecycleScope.launch { status.text = "Apagar: ${elm.clearDtcs()}" }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun appendLog(msg: String) {
        if (fullLog.size > 50_000) fullLog.subList(0, 10_000).clear()
        fullLog += "${timeFmt.format(Date())}  $msg"
        val lines = (logView.text.toString() + "\n" + msg).lines().takeLast(60)
        logView.text = lines.joinToString("\n").trim()
    }

    private fun runProbe() {
        if (!elm.isConnected) { status.text = "Conecte primeiro"; return }
        if (busyJob?.isActive == true) { status.text = "Já tem um teste rodando"; return }
        liveJob?.cancel()
        busyJob = lifecycleScope.launch {
            appendLog("Teste: a parte da linha K leva uns 2 minutos — deixe o app aberto")
            val report = AdapterProbe(elm) { msg -> runOnUiThread { status.text = msg } }.run()
            lastReport = report
            report.lines().forEach { appendLog(it) }
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Relatório do adaptador")
                .setMessage(report)
                .setPositiveButton("Exportar") { _, _ -> exportLog() }
                .setNegativeButton("Fechar", null)
                .show()
        }
    }

    private fun exportLog() {
        if (fullLog.isEmpty()) { status.text = "O log está vazio"; return }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "obd-log-$stamp.txt")
        file.writeText(buildString {
            appendLine("OBD ELM327 — log exportado em $stamp")
            appendLine("Status: ${status.text}")
            lastReport?.let { appendLine(); appendLine(it) }
            appendLine()
            appendLine("=== Log completo ===")
            fullLog.forEach { appendLine(it) }
        })
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Exportar log"))
    }

    override fun onDestroy() {
        liveJob?.cancel()
        busyJob?.cancel()
        elm.close()
        super.onDestroy()
    }
}
