package br.obd.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import br.obd.app.ui.MainActivity
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Serviço em primeiro plano: não faz a comunicação, só mantém o processo vivo para o
 * Android não derrubar o Bluetooth fora do app — e mostra os dados principais na
 * notificação, com atalhos para gravar viagem e desconectar.
 */
class ObdService : Service() {

    private var updater: Job? = null
    private var lastShown: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> { ObdManager.disconnect(); return START_NOT_STICKY }
            ACTION_RECORD -> {
                if (ObdManager.recording.value == null) ObdManager.startRecording() else ObdManager.stopRecording()
            }
        }

        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Conexão com o carro", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Dados ao vivo enquanto o adaptador está conectado"
                    setShowBadge(false)
                }
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ID, build(), type)
        } catch (e: Exception) {
            ObdManager.log("Serviço em primeiro plano não iniciou: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (updater?.isActive != true) {
            updater = ObdManager.scope.launch {
                while (isActive) {
                    // só redesenha quando o conteúdo muda (evita piscar e gastar bateria)
                    val fresh = System.currentTimeMillis() - ObdManager.lastLiveAt < 8000
                    val key = ObdManager.values.value.toString() + ObdManager.status.value + fresh +
                        ObdManager.busy.value + ObdManager.recording.value + ObdManager.connected.value
                    if (key != lastShown) { nm.notify(ID, build()); lastShown = key }
                    delay(2000)
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        val m = ObdManager
        val v = m.values.value
        val busy = m.busy.value
        val status = m.status.value
        val rec = m.recording.value
        val veh = m.vehicle.value
        val ptBr = Locale("pt", "BR")
        fun f(key: String, pattern: String) = v[key]?.let { String.format(ptBr, pattern, it) }

        // dado com mais de 8 s não vale como "agora" (leitura parada ou carro sem resposta)
        val fresh = System.currentTimeMillis() - m.lastLiveAt < 8000
        val rpm = v["rpm"]?.takeIf { fresh }
        val noResponse = status.startsWith("Sem resposta") || status.startsWith("Carro sem resposta")
        val running = rpm != null && rpm >= 300
        val diesel = Pids.isDiesel(veh.fuelType)
        val maxRpm = if (diesel) 5000.0 else 7000.0

        val (title, state, dot) = when {
            busy != null -> Triple("Ocupado", busy, ACCENT)
            !m.connected.value -> Triple("Desconectado", status, GREY)
            noResponse -> Triple("Sem resposta", "Ligue a ignição do carro", AMBER)
            rpm == null -> Triple("Conectado", "Aguardando dados do carro…", GREY)
            !running -> Triple("Motor desligado", "Ignição ligada", AMBER)
            else -> Triple(String.format(ptBr, "%,.0f rpm", rpm), if (rec != null) "Gravando viagem" else "Motor funcionando", GREEN)
        }
        val gauge = gaugeBitmap(rpm, maxRpm, running)

        val temp = f("coolant", "%.0f °C")
        val speed = f("speed", "%.0f km/h").takeIf { running || (v["speed"] ?: 0.0) > 0 }
        val voltKey = if (v["battery"] != null) "battery" else "moduleV"
        val volt = v[voltKey]
        val batt = volt?.let { String.format(ptBr, "%.1f V", it) }
        val ltft = v["ltft"]

        // recolhida
        val small = RemoteViews(packageName, R.layout.notif_small).apply {
            setImageViewBitmap(R.id.gauge, gauge)
            setTextViewText(R.id.title, title)
            setTextColor(R.id.dot, dot)
            val line = if (m.connected.value && !noResponse && busy == null) listOfNotNull(temp, speed, batt) else emptyList()
            setTextViewText(R.id.line, line.joinToString("  ·  ").ifEmpty { state })
            if (ltft != null && running) {
                setTextViewText(R.id.badge, String.format(ptBr, "%+.1f%%", ltft))
                setTextColor(R.id.badge, trimColor(ltft))
            } else setViewVisibility(R.id.badge, View.GONE)
        }

        // expandida
        val big = RemoteViews(packageName, R.layout.notif_big).apply {
            setImageViewBitmap(R.id.gauge, gauge)
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.state, state)
            setTextColor(R.id.dot, dot)
            val vehLine = listOfNotNull(veh.maker, veh.year?.toString(), veh.protocol.takeIf { it != "—" })
                .joinToString(" · ")
            if (vehLine.isNotEmpty()) setTextViewText(R.id.vehicle, vehLine) else setViewVisibility(R.id.vehicle, View.GONE)

            fun cell(value: String?, label: String, color: Int? = null) {
                val c = RemoteViews(packageName, R.layout.notif_cell)
                c.setTextViewText(R.id.value, value ?: "—")
                c.setTextViewText(R.id.label, label)
                if (color != null) c.setTextColor(R.id.value, color)
                c.setInt(R.id.bar, "setColorFilter", color ?: (if (value != null) ACCENT else GREY))
                addView(R.id.cells, c)
            }
            val coolant = v["coolant"]
            cell(temp, "Motor", coolant?.let { if (it > 105) RED else if (it < 70) CYAN else GREEN })
            if (running) cell(f("speed", "%.0f km/h"), "Velocidade")
            else cell(f("fuelLevel", "%.0f%%") ?: f("iat", "%.0f °C"), if (v["fuelLevel"] != null) "Tanque" else "Ar admitido")
            cell(batt, if (voltKey == "battery") "Bateria" else "Central",
                volt?.let { if (it < (if (running) 13.0 else 11.8) || it > 14.8) AMBER else GREEN })
            when {
                ltft != null -> cell(String.format(ptBr, "%+.1f%%", ltft), "Corr. longa", trimColor(ltft))
                v["load"] != null -> cell(f("load", "%.0f%%"), "Carga")
                else -> cell(null, "Corr. longa")
            }
            if (rec != null) {
                setViewVisibility(R.id.recRow, View.VISIBLE)
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - m.recordingStartedAt)
                setChronometer(R.id.chrono, base, null, true)
            }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ObdService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setColor(ACCENT)
            .setContentTitle(title)
            .setContentText(state)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(small)
            .setCustomBigContentView(big)
            .setCustomHeadsUpContentView(small)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .apply { if (m.connected.value) addAction(0, if (rec == null) "Gravar viagem" else "Parar gravação", action(ACTION_RECORD, 1)) }
            .addAction(0, "Desconectar", action(ACTION_DISCONNECT, 2))
            .build()
    }

    /** Mostrador de rotação (arco de 270°) com o valor em milhares no centro. */
    private fun gaugeBitmap(rpm: Double?, max: Double, running: Boolean): Bitmap {
        val px = 168
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = px * 0.10f
        val r = RectF(stroke, stroke, px - stroke, px - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        }
        val redFrom = (max - 1000) / max
        p.color = 0x40808C99
        c.drawArc(r, 135f, 270f, false, p)
        p.color = 0x70EF5350; p.strokeCap = Paint.Cap.BUTT
        c.drawArc(r, 135f + 270f * redFrom.toFloat(), 270f * (1 - redFrom.toFloat()), false, p)
        val frac = ((rpm ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
        val hot = frac >= redFrom
        if (running && frac > 0.005f) {
            p.color = if (hot) RED else ACCENT; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(r, 135f, 270f * frac, false, p)
        }
        // número no meio: 0.8, 2.4… (x1000) ou um traço quando não há leitura
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            color = when { !running -> 0xFF9AA4AE.toInt(); hot -> RED; else -> 0xFFFFFFFF.toInt() }
            textSize = px * 0.30f
        }
        val label = when {
            rpm == null -> "–"
            !running -> "0"
            else -> String.format(Locale.US, "%.1f", rpm / 1000)
        }
        c.drawText(label, px / 2f, px / 2f + t.textSize * 0.35f, t)
        t.typeface = android.graphics.Typeface.DEFAULT; t.textSize = px * 0.12f; t.color = 0xFF9AA4AE.toInt()
        c.drawText("x1000", px / 2f, px * 0.86f, t)
        return bmp
    }

    private fun trimColor(v: Double) = when {
        kotlin.math.abs(v) <= 10 -> GREEN
        kotlin.math.abs(v) <= 20 -> AMBER
        else -> RED
    }

    override fun onDestroy() {
        updater?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "obd"
        const val ID = 1
        const val ACTION_RECORD = "br.obd.app.RECORD"
        const val ACTION_DISCONNECT = "br.obd.app.DISCONNECT"
        const val ACCENT = 0xFFFFA726.toInt()
        const val RED = 0xFFEF5350.toInt()
        const val AMBER = 0xFFFFB74D.toInt()
        const val GREEN = 0xFF66BB6A.toInt()
        const val CYAN = 0xFF4DD0E1.toInt()
        const val GREY = 0xFF8A949E.toInt()
    }
}
