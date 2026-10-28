package br.obd.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private val gauges = linkedMapOf<String, TextView>()
    private var liveJob: Job? = null

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
                lifecycleScope.launch {
                    try {
                        status.text = "Conectando…"
                        elm.close()
                        elm.connect(devices[i])
                        status.text = "Inicializando…"
                        val version = elm.init()
                        status.text = "Conectado: $version"
                    } catch (e: Exception) {
                        status.text = "Falha: ${e.message}"
                    }
                }
            }
            .show()
    }

    private fun toggleLive() {
        if (liveJob?.isActive == true) {
            liveJob?.cancel(); status.text = "Leitura parada"; return
        }
        if (!elm.isConnected) { status.text = "Conecte primeiro"; return }
        liveJob = lifecycleScope.launch {
            status.text = "Lendo ao vivo…"
            while (isActive) {
                try {
                    elm.readPid("0C")?.takeIf { it.size >= 2 }?.let { set("RPM", "${(it[0] * 256 + it[1]) / 4} rpm") }
                    elm.readPid("0D")?.let { set("Velocidade", "${it[0]} km/h") }
                    elm.readPid("05")?.let { set("Temp. motor", "${it[0] - 40} °C") }
                    elm.readPid("04")?.let { set("Carga", "${it[0] * 100 / 255} %") }
                    elm.readPid("11")?.let { set("Acelerador", "${it[0] * 100 / 255} %") }
                    elm.readPid("0F")?.let { set("Temp. admissão", "${it[0] - 40} °C") }
                    set("Bateria", elm.batteryVoltage())
                } catch (e: Exception) {
                    status.text = "Erro: ${e.message}"; break
                }
                delay(200)
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
        val lines = (logView.text.toString() + "\n" + msg).lines().takeLast(60)
        logView.text = lines.joinToString("\n").trim()
    }

    override fun onDestroy() {
        liveJob?.cancel()
        elm.close()
        super.onDestroy()
    }
}
