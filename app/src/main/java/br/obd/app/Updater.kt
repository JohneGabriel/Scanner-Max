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
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
 * Atualização sem loja: confere o `versao.json` do site, baixa o APK genérico e entrega ao
 * instalador do Android (o cliente só confirma). A licença fica guardada nos dados do app,
 * então sobrevive à atualização.
 */
@SuppressLint("StaticFieldLeak")
object Updater {
    private const val INFO = "https://raw.githubusercontent.com/${Repos.OWNER}/${Repos.SITE}/main/versao.json"
    private const val EVERY_MS = 12L * 3600 * 1000
    private lateinit var ctx: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdState>(UpdState.Idle)
    val state: StateFlow<UpdState> = _state
    /** O cliente fechou o aviso nesta sessão. */
    val dismissed = MutableStateFlow(false)

    fun init(context: Context) { ctx = context.applicationContext }

    private val sp get() = ctx.getSharedPreferences("update", Context.MODE_PRIVATE)

    /** Verifica no máximo a cada 12 h (ou agora, se [force]). */
    fun check(force: Boolean = false) {
        if (!::ctx.isInitialized || _state.value is UpdState.Checking || _state.value is UpdState.Downloading) return
        if (!force && System.currentTimeMillis() - sp.getLong("checked", 0) < EVERY_MS) {
            // o resultado anterior ainda vale
            parse(sp.getString("last", null))?.let { if (it.code > BuildConfig.VERSION_CODE) _state.value = UpdState.Available(it) }
            return
        }
        _state.value = UpdState.Checking
        scope.launch {
            val text = runCatching { get(INFO) }.getOrNull()
            val r = parse(text)
            if (text != null) sp.edit().putLong("checked", System.currentTimeMillis()).putString("last", text).apply()
            _state.value = when {
                r == null -> if (force) UpdState.Failed("Não foi possível verificar agora. Confira a internet.") else UpdState.Idle
                r.code > BuildConfig.VERSION_CODE -> UpdState.Available(r)
                else -> UpdState.UpToDate
            }
        }
    }

    private fun parse(text: String?): Release? = runCatching {
        val j = JSONObject(text ?: return null)
        Release(j.getInt("code"), j.getString("nome"), j.optString("notas"), j.getString("url"))
    }.getOrNull()

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

    private val apk get() = File(ctx.cacheDir, "atualizacao.apk")

    fun download(r: Release) {
        if (_state.value is UpdState.Downloading) return
        _state.value = UpdState.Downloading(r, 0f)
        scope.launch {
            try {
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
                val tmp = File(ctx.cacheDir, "atualizacao.part")
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
                _state.value = UpdState.Ready(r)
                install()
            } catch (e: Exception) {
                ObdManager.log("Atualização: ${e.message}")
                _state.value = UpdState.Failed("O download falhou. Tente de novo com uma internet estável.")
            }
        }
    }

    /** Entrega o APK baixado ao instalador do Android (ele mostra a confirmação). */
    fun install() {
        if (!apk.exists()) return
        if (!canInstall()) { ctx.startActivity(installPermissionIntent()); return }
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
        if (status != PackageInstaller.STATUS_SUCCESS && status != PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val r = (_state.value as? UpdState.Ready)?.r
            _state.value = if (status == PackageInstaller.STATUS_FAILURE_ABORTED && r != null) UpdState.Ready(r)
                else UpdState.Failed("A instalação não foi concluída${msg?.let { " ($it)" } ?: ""}.")
        }
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
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        Updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
