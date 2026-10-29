package br.obd.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings.Secure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** Onde ficam as automações de venda/ativação no GitHub. */
object Repos {
    const val OWNER = "JohneGabriel"
    /** Público: site, APKs e versao.json. */
    const val SITE = "scannermax"
    /** Privado: recebe os pedidos de ativação do app e guarda as respostas assinadas. */
    const val ACTIVATION = "scannermax-ativacao"
    const val SITE_URL = "https://johnegabriel.github.io/scannermax/"
}

/** Etapas visíveis da ativação (a tela anima cada uma). */
enum class ActPhase { IDLE, SENDING, WAITING, DONE, OFFLINE, ERROR }

/**
 * Licença no aparelho: lê a licença embutida no APK, guarda o recibo do servidor e faz a ativação
 * silenciosa (pedido via `repository_dispatch` + leitura da resposta assinada).
 */
@SuppressLint("StaticFieldLeak")
object Licensing {
    private const val ASSET = "licenca.txt"
    private lateinit var ctx: Context
    private lateinit var sp: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _view = MutableStateFlow(LicView(LicStatus.OFF))
    val view: StateFlow<LicView> = _view
    private val _phase = MutableStateFlow(ActPhase.IDLE)
    val phase: StateFlow<ActPhase> = _phase
    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message
    /** Pedido para abrir a tela de licença (ex.: tentou conectar sem licença ativa). */
    val showRequest = MutableStateFlow(false)

    /** Sem token embutido, esta build não exige licença (desenvolvimento e builds antigas). */
    val enforced: Boolean get() = token.isNotEmpty()

    private val token: String by lazy {
        val hex = BuildConfig.ACT_TOKEN_X
        if (hex.isEmpty()) "" else {
            val k = "ScannerMax".toByteArray()
            String(ByteArray(hex.length / 2) { i -> (hex.substring(i * 2, i * 2 + 2).toInt(16) xor k[i % k.size].toInt()).toByte() })
        }
    }

    /** Identificador deste aparelho para a licença (o ANDROID_ID já é exclusivo deste app neste aparelho). */
    val device: String by lazy {
        @SuppressLint("HardwareIds")
        val id = Secure.getString(ctx.contentResolver, Secure.ANDROID_ID) ?: "sem-id"
        LicenseCrypto.sha256Hex("smx-dev:$id").take(24)
    }

    var license: License? = null
        private set

    fun init(context: Context) {
        if (::ctx.isInitialized) return
        ctx = context.applicationContext
        sp = ctx.getSharedPreferences("licenca", Context.MODE_PRIVATE)
        // a licença vem dentro do APK personalizado; atualizações (APK genérico) não a trazem,
        // então a cópia guardada vale dali em diante
        val bundled = runCatching { ctx.assets.open(ASSET).bufferedReader().use { it.readText() } }.getOrNull()
        val fromApk = License.parse(bundled)
        if (fromApk != null && fromApk.encode() != sp.getString("lic", null)) {
            val old = License.parse(sp.getString("lic", null))
            sp.edit().putString("lic", fromApk.encode()).apply {
                // licença nova (outra venda): recomeça o prazo e esquece o recibo antigo
                if (old?.id != fromApk.id) remove("receipt").remove("pendingSince").remove("released")
            }.apply()
        }
        license = License.parse(sp.getString("lic", null))
        refresh()
        auto()
    }

    /** Relógio que nunca volta: atrasar o relógio do aparelho não estende o prazo. */
    private fun now(): Long {
        val t = System.currentTimeMillis()
        val seen = sp.getLong("maxSeen", 0)
        return if (t > seen) { sp.edit().putLong("maxSeen", t).apply(); t } else seen
    }

    fun refresh() {
        val l = license
        val now = now()
        var since = sp.getLong("pendingSince", 0)
        if (l != null && since == 0L) { since = now; sp.edit().putLong("pendingSince", since).apply() }
        _view.value = LicenseLogic.evaluate(
            enforced = enforced,
            license = l,
            licenseValid = l?.valid() == true,
            receipt = Receipt.parse(sp.getString("receipt", null))?.takeIf { it.valid() },
            device = device,
            pendingSince = since,
            released = sp.getBoolean("released", false),
            now = now,
        )
    }

    /** Ao abrir o app: ativa se ainda falta, ou faz a verificação de rotina se já passou do tempo. */
    fun auto() {
        val v = _view.value
        if (!enforced || license == null) return
        if (v.status == LicStatus.PENDING || v.status == LicStatus.EXPIRED || LicenseLogic.heartbeatDue(v, now())) activate(silent = true)
    }

    fun online(): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Pode conectar num carro de verdade? Se não, pede a tela de licença. */
    fun allowReal(): Boolean {
        if (!::ctx.isInitialized) return true
        refresh()
        if (_view.value.full) return true
        showRequest.value = true
        return false
    }

    fun clearRequest() { showRequest.value = false }

    /** Conta como "vista" a tela automática (para não insistir mais de uma vez por dia). */
    fun markPrompted() { sp.edit().putLong("prompted", System.currentTimeMillis()).apply() }
    val lastPrompt: Long get() = sp.getLong("prompted", 0)
    var welcomed: Boolean
        get() = sp.getBoolean("welcomed", false)
        set(v) { sp.edit().putBoolean("welcomed", v).apply() }

    /** Pede a ativação (ou a verificação de rotina) ao servidor e espera a resposta assinada. */
    fun activate(silent: Boolean = false) {
        val l = license ?: return
        if (!enforced || job?.isActive == true) return
        if (_view.value.status == LicStatus.RELEASED) sp.edit().remove("released").remove("receipt").apply()
        job = scope.launch { exchange(l, "ativar", silent) }
    }

    /** Libera a vaga deste aparelho (para usar a licença em outro celular). */
    fun releaseThisDevice() {
        val l = license ?: return
        if (!enforced || job?.isActive == true) return
        job = scope.launch {
            if (exchange(l, "liberar", silent = false)) {
                sp.edit().putBoolean("released", true).remove("receipt").apply()
                refresh()
            }
        }
    }

    private suspend fun exchange(l: License, kind: String, silent: Boolean): Boolean {
        if (!online()) {
            _phase.value = ActPhase.OFFLINE
            _message.value = "Sem internet. Assim que conectar, a ativação termina sozinha."
            if (!silent) Feedback.warning()
            return false
        }
        _phase.value = ActPhase.SENDING
        _message.value = if (kind == "liberar") "Liberando a vaga deste aparelho…" else "Enviando o pedido de ativação…"
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val sent = runCatching { dispatch(kind, l, nonce) }
        if (sent.isFailure) {
            _phase.value = ActPhase.ERROR
            _message.value = "Não foi possível falar com o servidor agora. Tentaremos de novo sozinhos."
            ObdManager.log("Licença: falha no envio (${sent.exceptionOrNull()?.message})")
            return false
        }
        _phase.value = ActPhase.WAITING
        _message.value = if (kind == "liberar") "Aguardando a confirmação…" else "Confirmando a licença…"
        val name = LicenseLogic.replyName(l, device, nonce)
        val deadline = System.currentTimeMillis() + 4 * 60_000
        delay(12_000) // o servidor leva uns segundos para começar
        while (scope.isActive && System.currentTimeMillis() < deadline) {
            val text = runCatching { fetchReply(name) }.getOrNull()
            val r = Receipt.parse(text)?.takeIf { it.valid() && it.matches(l, device) }
            if (r != null) {
                if (kind == "ativar") sp.edit().putString("receipt", r.encode()).remove("released").apply()
                refresh()
                _phase.value = ActPhase.DONE
                _message.value = when {
                    kind == "liberar" -> "Vaga liberada. Você pode instalar em outro aparelho."
                    r.ok -> "Licença ativada neste aparelho."
                    r.motivo == "limite" -> "Esta licença já está no limite de aparelhos."
                    else -> "A licença não pôde ser ativada."
                }
                if (r.ok || kind == "liberar") Feedback.success() else Feedback.warning()
                return true
            }
            delay(6_000)
        }
        _phase.value = ActPhase.ERROR
        _message.value = "O servidor está demorando. Continuamos tentando quando você abrir o app."
        return false
    }

    private suspend fun dispatch(kind: String, l: License, nonce: String) = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("event_type", kind)
            .put("client_payload", JSONObject()
                .put("l", l.encode()).put("d", device).put("n", nonce)
                .put("v", BuildConfig.VERSION_CODE))
        val c = URL("https://api.github.com/repos/${Repos.OWNER}/${Repos.ACTIVATION}/dispatches").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
        } finally { c.disconnect() }
    }

    private suspend fun fetchReply(name: String): String? = withContext(Dispatchers.IO) {
        val c = URL("https://api.github.com/repos/${Repos.OWNER}/${Repos.ACTIVATION}/contents/l/$name.txt").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("Accept", "application/vnd.github.raw")
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally { c.disconnect() }
    }
}
