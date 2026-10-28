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
                    val key = ObdManager.values.value.toString() + ObdManager.status.value +
                        ObdManager.busy.value + ObdManager.recording.value
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
        val ptBr = Locale("pt", "BR")
        fun f(key: String, pattern: String) = v[key]?.let { String.format(ptBr, pattern, it) }

        val rpm = v["rpm"]
        val noResponse = status.startsWith("Sem resposta") || status.startsWith("Carro sem resposta")
        val state = when {
            busy != null -> busy
            noResponse -> "Carro sem resposta — ligue a ignição"
            !m.connected.value -> status
            rpm != null && rpm < 300 -> "Ignição ligada · motor parado"
            rec != null -> "Gravando viagem"
            else -> "Motor ligado"
        }
        val rpmText = rpm?.let { String.format(ptBr, "%.0f rpm", it) } ?: "— rpm"
        val temp = f("coolant", "%.0f °C"); val speed = f("speed", "%.0f km/h")
        val batt = f("battery", "%.1f V"); val ltft = v["ltft"]
        val gauge = gaugeBitmap(((rpm ?: 0.0) / 7000).toFloat(), (rpm ?: 0.0) >= 6000)

        // recolhida
        val small = RemoteViews(packageName, R.layout.notif_small).apply {
            setImageViewBitmap(R.id.gauge, gauge)
            setTextViewText(R.id.rpm, rpmText)
            setTextViewText(R.id.line, listOfNotNull(temp, speed, batt).joinToString("  ·  ").ifEmpty { state })
            if (ltft != null) {
                setTextViewText(R.id.badge, String.format(ptBr, "%+.1f%%", ltft))
                setTextColor(R.id.badge, trimColor(ltft))
            } else setViewVisibility(R.id.badge, View.GONE)
        }

        // expandida
        val big = RemoteViews(packageName, R.layout.notif_big).apply {
            setImageViewBitmap(R.id.gauge, gauge)
            setTextViewText(R.id.rpm, rpmText)
            setTextViewText(R.id.state, state)
            fun cell(value: String?, label: String, color: Int? = null) {
                val c = RemoteViews(packageName, R.layout.notif_cell)
                c.setTextViewText(R.id.value, value ?: "—")
                c.setTextViewText(R.id.label, label)
                if (color != null) c.setTextColor(R.id.value, color)
                addView(R.id.cells, c)
            }
            val coolant = v["coolant"]; val battery = v["battery"]
            cell(temp, "motor", coolant?.let { if (it > 105) RED else if (it < 70) CYAN else null })
            cell(speed, "velocidade")
            cell(batt, "bateria", battery?.let { if (it < 12.0 || it > 14.8) AMBER else null })
            cell(ltft?.let { String.format(ptBr, "%+.1f%%", it) }, "corr. longa", ltft?.let { trimColor(it) })
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
            .setContentTitle(rpmText)
            .setContentText(state)
            .setSubText(state)
            .setLargeIcon(gauge)
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
            .addAction(0, if (rec == null) "Gravar viagem" else "Parar gravação", action(ACTION_RECORD, 1))
            .addAction(0, "Desconectar", action(ACTION_DISCONNECT, 2))
            .build()
    }

    /** Mini mostrador de rotação (arco de 270°) desenhado na hora. */
    private fun gaugeBitmap(frac: Float, hot: Boolean): Bitmap {
        val px = 144
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = px * 0.11f
        val r = RectF(stroke, stroke, px - stroke, px - stroke)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND
        }
        p.color = 0x55808C99
        c.drawArc(r, 135f, 270f, false, p)
        p.color = 0x66EF5350; p.strokeCap = Paint.Cap.BUTT
        c.drawArc(r, 135f + 270f * (6000f / 7000f), 270f * (1000f / 7000f), false, p)
        val f = frac.coerceIn(0f, 1f)
        if (f > 0.005f) {
            p.color = if (hot) RED else ACCENT; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(r, 135f, 270f * f, false, p)
        }
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
    }
}
