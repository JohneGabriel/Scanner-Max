package br.obd.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
                    val n = build()
                    // só redesenha quando o conteúdo muda (evita piscar e gastar bateria)
                    val key = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString() +
                        n.extras.getCharSequence(NotificationCompat.EXTRA_BIG_TEXT) +
                        n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE) + ObdManager.recording.value
                    if (key != lastShown) { nm.notify(ID, n); lastShown = key }
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

        fun f(key: String, pattern: String) = v[key]?.let { String.format(Locale("pt", "BR"), pattern, it) }
        val rpm = f("rpm", "%.0f rpm")
        val temp = f("coolant", "%.0f °C")
        val speed = f("speed", "%.0f km/h")
        val batt = f("battery", "%.1f V")
        val ltft = f("ltft", "%+.1f%%")

        val noResponse = status.startsWith("Sem resposta") || status.startsWith("Carro sem resposta")
        val title = when {
            busy != null -> busy
            noResponse -> "Carro sem resposta"
            !m.connected.value -> status
            rec != null -> "Gravando viagem"
            else -> "Conectado ao carro"
        }
        val line = listOfNotNull(rpm, temp, speed).joinToString("  ·  ").ifEmpty {
            if (noResponse) "Ligue a ignição para continuar" else "Aguardando dados…"
        }
        val details = buildString {
            append(line)
            val extra = listOfNotNull(batt?.let { "Bateria $it" }, ltft?.let { "Correção longa $it" })
            if (extra.isNotEmpty()) append("\n").append(extra.joinToString("  ·  "))
            m.adapterName.value?.let { append("\n").append(it) }
        }

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, br.obd.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(a: String, code: Int) = PendingIntent.getService(
            this, code, Intent(this, ObdService::class.java).setAction(a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setColor(0xFFFFA726.toInt())
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(details))
            .setSubText(if (rec != null) "gravando" else null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (rec != null) {
                    setWhen(m.recordingStartedAt).setUsesChronometer(true).setShowWhen(true)
                } else {
                    setShowWhen(false)
                }
            }
            .setContentIntent(open)
            .addAction(0, if (rec == null) "Gravar viagem" else "Parar gravação", action(ACTION_RECORD, 1))
            .addAction(0, "Desconectar", action(ACTION_DISCONNECT, 2))
            .build()
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
    }
}
