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

/**
 * Serviço em primeiro plano: não faz a comunicação, só mantém o processo vivo
 * (com a notificação fixa) para o Android não derrubar o Bluetooth quando você
 * sai do app — era isso que causava o "Broken pipe".
 */
class ObdService : Service() {

    private var updater: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Conexão com o adaptador", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ID, build("Conectado ao adaptador"), type)
        } catch (e: Exception) {
            ObdManager.log("Serviço em primeiro plano não iniciou: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        if (updater?.isActive != true) {
            updater = ObdManager.scope.launch {
                while (isActive) {
                    delay(3000)
                    val v = ObdManager.values.value
                    val parts = listOfNotNull(
                        v["rpm"]?.let { "%.0f rpm".format(it) },
                        v["coolant"]?.let { "%.0f °C".format(it) },
                        v["ltft"]?.let { "corr. %+.1f%%".format(it) },
                    )
                    val text = ObdManager.busy.value
                        ?: if (parts.isEmpty()) ObdManager.status.value else parts.joinToString(" · ")
                    nm.notify(ID, build(text))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, br.obd.app.ui.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Scanner-Max")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    override fun onDestroy() {
        updater?.cancel()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "obd"
        const val ID = 1
    }
}
