package br.obd.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.app.NotificationChannel
import android.app.NotificationManager
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Onde o app é publicado (repositório público do site). */
object Repos {
    const val OWNER = "JohneGabriel"
    const val SITE = "scannermax"
    const val SITE_URL = "https://johnegabriel.github.io/scannermax/"
}

/** Versão publicada no site (arquivo `versao.json`). */
data class Release(val code: Int, val name: String, val notes: String, val url: String)

sealed class UpdState {
    data object Idle : UpdState()
    data object Checking : UpdState()
    data object UpToDate : UpdState()
    data class Available(val r: Release) : UpdState()
    data class Downloading(val r: Release, val frac: Float) : UpdState()
    data class Ready(val r: Release) : UpdState()
    data class Failed(val msg: String) : UpdState()
}

/**
 * Atualização sem loja, o mais automática que o Android permite:
 *  - em segundo plano (Wi-Fi, a cada 6 h) o app confere o `versao.json` e já baixa a versão nova;
 *  - a instalação acontece só em hora segura: app fechado e sem carro conectado (segundo plano),
 *    ou ao abrir o app antes de conectar;
 *  - no Android 12+, se a versão atual foi instalada pelo próprio app, o Android instala sem perguntar;
 *    nos demais casos ele mostra a confirmação "Instalar" (uma toque).
 */
@SuppressLint("StaticFieldLeak")
object Updater {
    private const val INFO = "https://raw.githubusercontent.com/${Repos.OWNER}/${Repos.SITE}/main/versao.json"
    private const val EVERY_MS = 6L * 3600 * 1000
    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdState>(UpdState.Idle)
    val state: StateFlow<UpdState> = _state
    /** O cliente fechou o aviso nesta sessão. */
    val dismissed = MutableStateFlow(false)
    /** Acabou de atualizar: nome da versão (para o aviso "Atualizado para…"). */
    val justUpdated = MutableStateFlow<String?>(null)
    /** A tela do app está aberta (instalar agora fecharia o app na cara do usuário). */
    @Volatile var foreground = false

    private val mutex = Mutex()

    fun init(context: Context) {
        ctx = context.applicationContext
        // a versão baixada já foi instalada (ou ficou velha): limpa
        if (sp.getInt("apkCode", 0) <= BuildConfig.VERSION_CODE) { apk.delete(); sp.edit().remove("apkCode").remove("apkInfo").apply() }
        val seen = sp.getInt("seenCode", 0)
        if (seen in 1 until BuildConfig.VERSION_CODE) justUpdated.value = BuildConfig.VERSION_NAME
        sp.edit().putInt("seenCode", BuildConfig.VERSION_CODE).apply()
        downloaded()?.let { _state.value = UpdState.Ready(it) }
    }

    private val sp get() = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)
    private val apk get() = File(ctx.noBackupFilesDir, "atualizacao.apk")

    /** Versão nova já baixada e pronta para instalar. */
    private fun downloaded(): Release? =
        parse(sp.getString("apkInfo", null))?.takeIf { it.code > BuildConfig.VERSION_CODE && apk.exists() }

    /** Verifica (no máximo a cada 6 h, ou agora se [force]) e, se tiver versão nova, já baixa. */
    fun check(force: Boolean = false) {
        if (!::ctx.isInitialized) return
        scope.launch { checkAndDownload(force, userAsked = force) }
    }

    /** Usado pela tela e pelo trabalho em segundo plano. Devolve a versão pronta para instalar, se houver. */
    suspend fun checkAndDownload(force: Boolean = false, userAsked: Boolean = false): Release? = mutex.withLock {
        downloaded()?.let { _state.value = UpdState.Ready(it); return it }
        val fresh = force || System.currentTimeMillis() - sp.getLong("checked", 0) >= EVERY_MS
        val r = if (!fresh) parse(sp.getString("last", null)) else {
            _state.value = UpdState.Checking
            val text = runCatching { get(INFO) }.getOrNull()
            if (text != null) sp.edit().putLong("checked", System.currentTimeMillis()).putString("last", text).apply()
            parse(text)
        }
        when {
            r == null -> { _state.value = if (userAsked) UpdState.Failed("Não foi possível verificar agora. Confira a internet.") else UpdState.Idle; null }
            r.code <= BuildConfig.VERSION_CODE -> { _state.value = UpdState.UpToDate; null }
            else -> if (downloadNow(r)) r else null
        }
    }

    private fun parse(text: String?): Release? = runCatching {
        val j = JSONObject(text ?: return null)
        Release(j.getInt("code"), j.getString("nome"), j.optString("notas"), j.getString("url"))
    }.getOrNull()

    private fun Release.json() = JSONObject().put("code", code).put("nome", name).put("notas", notes).put("url", url).toString()

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** O Android exige a permissão "instalar apps desconhecidos" para este app. */
    fun canInstall(): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent() = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** O Android vai instalar sem perguntar? (12+, permissão dada e a versão atual instalada pelo próprio app) */
    fun canSilent(): Boolean = Build.VERSION.SDK_INT >= 31 && canInstall() && runCatching {
        ctx.packageManager.getInstallSourceInfo(ctx.packageName).installingPackageName == ctx.packageName
    }.getOrDefault(false)

    /** Hora segura para instalar: o app fecha durante a instalação. */
    fun safeNow(): Boolean = !ObdManager.connected.value && !ObdManager.status.value.startsWith("Conectando") && VagManager.session.value == null

    fun download(r: Release) { scope.launch { mutex.withLock { downloadNow(r) } } }

    private fun downloadNow(r: Release): Boolean {
        _state.value = UpdState.Downloading(r, 0f)
        return try {
            var url = URL(r.url)
            var c: HttpURLConnection
            var hops = 0
            while (true) { // o GitHub redireciona os downloads
                c = url.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                val code = c.responseCode
                if (code in 300..399 && hops++ < 6) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
                if (code != 200) throw IllegalStateException("HTTP $code")
                break
            }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: 2_000_000L
            val tmp = File(ctx.noBackupFilesDir, "atualizacao.part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        _state.value = UpdState.Downloading(r, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            c.disconnect()
            if (tmp.length() < 100_000) throw IllegalStateException("arquivo incompleto")
            apk.delete(); tmp.renameTo(apk)
            sp.edit().putInt("apkCode", r.code).putString("apkInfo", r.json()).apply()
            _state.value = UpdState.Ready(r)
            true
        } catch (e: Exception) {
            ObdManager.log("Atualização: ${e.message}")
            _state.value = UpdState.Failed("O download falhou. Tentaremos de novo mais tarde.")
            false
        }
    }

    /** Entrega o APK baixado ao instalador do Android (sem pergunta quando o Android permite). */
    fun install() {
        if (!apk.exists()) return
        if (!canInstall()) { if (foreground) ctx.startActivity(installPermissionIntent()); return }
        runCatching {
            val pi = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            val id = pi.createSession(params)
            pi.openSession(id).use { s ->
                s.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val cb = PendingIntent.getBroadcast(ctx, 77, Intent(ctx, InstallReceiver::class.java), flags)
                s.commit(cb.intentSender)
            }
        }.onFailure {
            ObdManager.log("Instalador: ${it.message}")
            _state.value = UpdState.Failed("O instalador do Android recusou o arquivo. Baixe pelo site.")
        }
    }

    internal fun onInstallStatus(status: Int, msg: String?) {
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) return
        // cancelou a confirmação: continua pronta para instalar depois
        val r = downloaded()
        _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && r != null) UpdState.Ready(r)
            else UpdState.Failed("A instalação não foi concluída${msg?.let { " ($it)" } ?: ""}.")
    }

    // ------------------------------------------------------------------ segundo plano

    /** Agenda a verificação periódica (Wi-Fi, bateria ok). */
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<UpdateWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("atualizacao", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}

/** Baixa a versão nova sozinho e, se o Android deixar instalar sem perguntar, instala com o app fechado. */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Updater.init(applicationContext)
        Updater.checkAndDownload() ?: return Result.success()
        if (!Updater.foreground && Updater.safeNow() && Updater.canSilent()) Updater.install()
        return Result.success()
    }
}

/** Recebe o andamento da instalação; abre a confirmação do Android quando ela é necessária. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
            // fora da tela do app, não interrompe o usuário: fica para a próxima abertura
            if (Updater.foreground) context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else Updater.onInstallStatus(PackageInstaller.STATUS_FAILURE_ABORTED, null)
            return
        }
        Updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}

/** Depois de uma atualização: avisa "Scanner Max atualizado para X". */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        nm.createNotificationChannel(NotificationChannel("updates", "Atualizações do app", NotificationManager.IMPORTANCE_LOW))
        val notes = runCatching {
            JSONObject(context.getSharedPreferences("update", Context.MODE_PRIVATE).getString("last", null) ?: "").optString("notas")
        }.getOrDefault("")
        nm.notify(9, NotificationCompat.Builder(context, "updates")
            .setSmallIcon(R.drawable.ic_stat_obd)
            .setContentTitle("Scanner Max atualizado para ${BuildConfig.VERSION_NAME}")
            .setContentText(notes.ifBlank { "Toque para abrir." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifBlank { "Toque para abrir." }))
            .setContentIntent(openIntent(context, null, 9))
            .setAutoCancel(true)
            .build())
    }
}
