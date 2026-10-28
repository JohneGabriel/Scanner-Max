package br.obd.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import br.obd.app.ui.MainActivity
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** Abre o app numa tela (atalhos do ícone e toques nas notificações). */
fun openIntent(ctx: Context, go: String?, code: Int): PendingIntent = PendingIntent.getActivity(
    ctx, code,
    Intent(ctx, MainActivity::class.java).setAction("br.obd.app.SHORTCUT").putExtra("go", go)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

private fun canNotify(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

/**
 * Notificação fixa de acesso rápido enquanto o app não está conectado: abre o app,
 * conecta com um toque e tem o botão "Remover" (que desliga a opção).
 */
object QuickNotif {
    private const val CHANNEL = "quick"
    const val ID = 7

    fun channel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Acesso rápido", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Notificação fixa com atalhos (pode ser removida)"; setShowBadge(false)
            })
    }

    /** Mostra ou tira, conforme a preferência e a conexão. */
    fun refresh(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!Settings.prefs.value.quickNotif || ObdManager.connected.value || !canNotify(ctx)) { nm.cancel(ID); return }
        channel(ctx)
        val v = Garage.current.value
        val d = ObdManager.diag.value
        val codes = d.stored.orEmpty().size + d.pending.orEmpty().size
        val line = listOfNotNull(
            v?.name,
            if (d.readAt != null) (if (codes == 0) "sem códigos" else "$codes código(s)") else null,
            Maintenance.items.value.count { (Maintenance.remaining(it) ?: Double.MAX_VALUE) <= 1000 }.takeIf { it > 0 }?.let { "manutenção próxima" },
        ).joinToString(" · ").ifBlank { "Toque para abrir" }
        val hide = PendingIntent.getBroadcast(ctx, 30, Intent(ctx, NotifReceiver::class.java).setAction(NotifReceiver.HIDE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setColor(0xFFFFA726.toInt())
            .setContentTitle("Scanner Max")
            .setContentText(line)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openIntent(ctx, null, 31))
            .addAction(0, "Conectar", openIntent(ctx, "connect", 32))
            .addAction(0, "Ler códigos", openIntent(ctx, "codes", 33))
            .addAction(0, "Remover", hide)
            .build()
        runCatching { nm.notify(ID, n) }
    }
}

class NotifReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == HIDE) {
            ObdManager.init(ctx)
            Settings.update { it.copy(quickNotif = false) }
            ctx.getSystemService(NotificationManager::class.java).cancel(QuickNotif.ID)
        }
    }
    companion object { const val HIDE = "br.obd.app.HIDE_QUICK" }
}

/**
 * Lembretes periódicos (no máximo um por dia, o mais importante): manutenção vencida,
 * códigos registrados, teste com problema, resumo da semana, check-up e dicas.
 */
class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        ObdManager.init(ctx)
        QuickNotif.refresh(ctx)
        if (!Settings.prefs.value.reminders || !canNotify(ctx)) return Result.success()
        val sp = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (hour < 9 || hour > 21) return Result.success()                 // não incomoda de madrugada
        if (now - sp.getLong("last", 0) < 20 * 3600_000L) return Result.success()
        if (ObdManager.connected.value) return Result.success()

        val msg = pick(ctx, sp, now) ?: return Result.success()
        val (key, title, text, go) = msg
        sp.edit().putLong("last", now).putLong("sent:$key", now).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Lembretes e dicas", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Manutenção, check-ups, resumo da semana e dicas"
            })
        runCatching {
            nm.notify(100 + key.hashCode() % 50, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_obd)
                .setColor(0xFFFFA726.toInt())
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openIntent(ctx, go, 40 + key.hashCode() % 20))
                .build())
        }
        return Result.success()
    }

    private data class Msg(val key: String, val title: String, val text: String, val go: String?)

    private fun pick(ctx: Context, sp: android.content.SharedPreferences, now: Long): Msg? {
        fun since(k: String) = now - sp.getLong("sent:$k", 0)
        val day = 86_400_000L
        val v = Garage.current.value
        val name = v?.name ?: "seu carro"

        // 1) manutenção vencida ou chegando
        val due = Maintenance.items.value.mapNotNull { i -> Maintenance.remaining(i)?.let { i to it } }.filter { it.second <= 500 }
        if (due.isNotEmpty() && since("maint") > 3 * day) {
            val (i, r) = due.minBy { it.second }
            return Msg("maint", if (r < 0) "Manutenção vencida" else "Manutenção chegando",
                if (r < 0) "${i.name} do $name venceu há ${(-r).toInt()} km." else "${i.name} do $name em ${r.toInt()} km.", "maint")
        }
        // 2) códigos registrados no último diagnóstico
        val d = ObdManager.diag.value
        val codes = d.stored.orEmpty() + d.pending.orEmpty()
        if (codes.isNotEmpty() && since("codes") > 4 * day) {
            return Msg("codes", "${codes.size} código(s) de falha no $name",
                "${codes.first()}: ${DtcInfo.describe(codes.first())}. Veja o que significa e o que fazer.", "codes")
        }
        // 3) teste recente com problema
        val bad = TestRunner.history.value.firstOrNull { it.vehicleId == v?.id && it.level == Level.BAD && now - it.at < 14 * day }
        if (bad != null && since("test:${bad.at}") > 30 * day) {
            return Msg("test:${bad.at}", "Lembrete: ${bad.title}", "${bad.headline}. Já resolveu? Rode o teste de novo para confirmar.", "tests")
        }
        // 4) resumo da semana (domingo/segunda)
        val dow = Calendar.getInstance().get(Calendar.DAY_OF_WEEK)
        val week = TripStore.trips.value.filter { it.vehicleId == v?.id && now - it.start < 7 * day }
        if (week.isNotEmpty() && (dow == Calendar.SUNDAY || dow == Calendar.MONDAY) && since("week") > 5 * day) {
            val t = TripTotals.of(week)
            return Msg("week", "Sua semana com o $name",
                "${Reports.num(t.km, 0)} km em ${t.count} viagem(ns)" + (t.kmPerL?.let { ", média de ${Reports.num(it)} km/L" } ?: "") +
                    ", gasto estimado de ${Reports.money(t.cost)}.", "trips")
        }
        // 5) check-up: diagnóstico com mais de 30 dias
        val lastDiag = d.readAt ?: 0L
        if (v != null && now - lastDiag > 30 * day && since("checkup") > 10 * day) {
            return Msg("checkup", "Hora de um check-up", "Faz tempo que o $name não passa por um diagnóstico. Leva 1 minuto: conecte e toque em Ler códigos.", "codes")
        }
        // 6) dica, só se o app não é aberto há alguns dias
        val opened = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).getLong("opened", now)
        if (now - opened > 4 * day && since("tip") > 6 * day) {
            val tips = listOf(
                "O teste de Bateria e alternador mostra se a bateria aguenta mais um inverno. Leva 1 minuto." to "tests",
                "Calibre o consumo no próximo abastecimento: o app passa a mostrar km/L real." to "vehicle",
                "Luz de injeção acesa? O app explica o código e diz se dá para rodar." to "codes",
                "Temperatura subindo no trânsito? Rode o teste de Arrefecimento com o carro parado." to "tests",
            )
            val (tip, go) = tips[((now / day) % tips.size).toInt()]
            return Msg("tip", "Dica do Scanner Max", tip, go)
        }
        return null
    }

    companion object {
        const val CHANNEL = "reminders"
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("reminders", ExistingPeriodicWorkPolicy.KEEP, req)
        }
        /** O app foi aberto (dicas só vão para quem sumiu). */
        fun opened(ctx: Context) = ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit()
            .putLong("opened", System.currentTimeMillis()).apply()
    }
}
