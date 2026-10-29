package br.obd.app

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Licença do Scanner Max (parte pura, sem Android: testável na JVM).
 *
 * Cada venda gera uma licença assinada (ECDSA P-256) que vai dentro do APK personalizado.
 * O app confere a assinatura offline; a ativação registra o aparelho no servidor (GitHub Actions)
 * e devolve um recibo, também assinado, que vale como prova offline dali em diante.
 */
object LicenseCrypto {
    /** Chave pública das licenças (a privada fica só nos segredos do GitHub). */
    const val PUBLIC_KEY =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEwH/f7N0IiYsasoFvqU7nXx8o7Uy3CQAPDv4jcq1aBpDF+DFaMVjknZyb+ppRKpCjoZ4mxxwMQt1h76O5EuXXUA=="

    private val cache = HashMap<String, PublicKey>()

    fun publicKey(b64: String): PublicKey = synchronized(cache) {
        cache.getOrPut(b64) { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(b64))) }
    }

    /** Assinatura em base64 url-safe (sem '='), formato DER do ECDSA. */
    fun verify(message: String, sigB64: String, pub: String = PUBLIC_KEY): Boolean = runCatching {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(publicKey(pub))
        s.update(message.toByteArray(Charsets.UTF_8))
        s.verify(Base64.getUrlDecoder().decode(sigB64))
    }.getOrDefault(false)

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** A licença de uma venda: `SMX1.<id>.<pedido>.<emitida AAAAMMDD>.<máx. aparelhos>.<assinatura>`. */
data class License(val id: String, val pedido: String, val emitida: String, val max: Int, val sig: String) {
    val payload get() = "SMX1|$id|$pedido|$emitida|$max"
    fun encode() = "SMX1.$id.$pedido.$emitida.$max.$sig"
    fun valid(pub: String = LicenseCrypto.PUBLIC_KEY) = LicenseCrypto.verify(payload, sig, pub)

    /** "final 4321" (nunca mostra o pedido inteiro). */
    val pedidoCurto: String get() = if (pedido.isBlank() || pedido == "0") "" else pedido.takeLast(4)

    companion object {
        private val ID = Regex("[0-9a-f]{16,40}")
        private val NUM = Regex("[0-9]{0,24}")
        private val DATE = Regex("[0-9]{8}")
        private val SIG = Regex("[A-Za-z0-9_-]{40,200}")

        fun parse(text: String?): License? {
            val p = text?.trim()?.split('.') ?: return null
            if (p.size != 6 || p[0] != "SMX1") return null
            val max = p[4].toIntOrNull() ?: return null
            if (!ID.matches(p[1]) || !NUM.matches(p[2]) || !DATE.matches(p[3]) || max !in 1..20 || !SIG.matches(p[5])) return null
            return License(p[1], p[2], p[3], max, p[5])
        }
    }
}

/**
 * Resposta do servidor a um pedido de ativação (ou de verificação periódica):
 * `R1.<id>.<aparelho>.<ok 1/0>.<motivo>.<usados>.<máx>.<quando, epoch s>.<assinatura>`.
 * Motivos: `ok`, `limite`, `invalida`, `bloqueada`, `liberado`.
 */
data class Receipt(
    val id: String, val device: String, val ok: Boolean, val motivo: String,
    val used: Int, val max: Int, val at: Long, val sig: String,
) {
    val payload get() = "R1|$id|$device|${if (ok) 1 else 0}|$motivo|$used|$max|$at"
    fun encode() = "R1.$id.$device.${if (ok) 1 else 0}.$motivo.$used.$max.$at.$sig"
    fun valid(pub: String = LicenseCrypto.PUBLIC_KEY) = LicenseCrypto.verify(payload, sig, pub)
    fun matches(l: License, dev: String) = id == l.id && device == dev

    companion object {
        fun parse(text: String?): Receipt? {
            val p = text?.trim()?.split('.') ?: return null
            if (p.size != 9 || p[0] != "R1") return null
            if (!Regex("[a-z]{1,12}").matches(p[4])) return null
            return Receipt(
                p[1], p[2], p[3] == "1", p[4],
                p[5].toIntOrNull() ?: return null, p[6].toIntOrNull() ?: return null,
                p[7].toLongOrNull() ?: return null, p[8],
            )
        }
    }
}

enum class LicStatus {
    /** Licenciamento desligado nesta build (sem servidor configurado): tudo liberado. */
    OFF,
    /** APK sem licença (o genérico do site): só demonstração. */
    NONE,
    /** Licença com assinatura inválida (APK adulterado). */
    INVALID,
    /** Licença boa, ainda sem confirmação do servidor: tudo liberado durante o prazo. */
    PENDING,
    /** Prazo para ativar acabou sem confirmação: só demonstração até ativar. */
    EXPIRED,
    /** Ativado neste aparelho. */
    ACTIVE,
    /** A licença já está no número máximo de aparelhos. */
    LIMIT,
    /** O cliente liberou a vaga deste aparelho. */
    RELEASED,
    /** O vendedor bloqueou a licença (reembolso, fraude). */
    BLOCKED,
}

/** Tudo que a tela de licença precisa mostrar. */
data class LicView(
    val status: LicStatus,
    val pedido: String = "",
    val used: Int = 0,
    val max: Int = 3,
    /** Tempo restante para ativar (PENDING), em ms. */
    val leftMs: Long = 0,
    val activatedAt: Long = 0,
    val lastCheck: Long = 0,
) {
    /** Pode conectar a um carro de verdade? */
    val full get() = status == LicStatus.OFF || status == LicStatus.PENDING || status == LicStatus.ACTIVE
    /** Ainda precisa falar com o servidor? */
    val needsServer get() = status == LicStatus.PENDING || status == LicStatus.EXPIRED || status == LicStatus.RELEASED
}

object LicenseLogic {
    /** Prazo para ativar sem internet. */
    const val GRACE_MS = 3L * 24 * 3600 * 1000
    /** Verificação de rotina de um aparelho já ativado (mantém a vaga viva no servidor). */
    const val HEARTBEAT_MS = 20L * 24 * 3600 * 1000
    /** Sem abrir o app por esse tempo, o servidor libera a vaga do aparelho sozinho. */
    const val IDLE_RELEASE_DAYS = 60

    /**
     * @param pendingSince quando o app viu esta licença pela primeira vez (início do prazo)
     * @param now relógio "monotônico" do app: o maior horário já visto (voltar o relógio não estende o prazo)
     */
    fun evaluate(
        enforced: Boolean,
        license: License?,
        licenseValid: Boolean,
        receipt: Receipt?,
        device: String,
        pendingSince: Long,
        released: Boolean,
        now: Long,
    ): LicView {
        if (!enforced) return LicView(LicStatus.OFF)
        if (license == null) return LicView(LicStatus.NONE)
        if (!licenseValid) return LicView(LicStatus.INVALID)
        val base = LicView(LicStatus.PENDING, pedido = license.pedidoCurto, max = license.max)
        if (released) return base.copy(status = LicStatus.RELEASED)
        val r = receipt?.takeIf { it.matches(license, device) }
        if (r != null) {
            val v = base.copy(used = r.used, max = r.max, lastCheck = r.at * 1000)
            return when {
                r.ok -> v.copy(status = LicStatus.ACTIVE, activatedAt = r.at * 1000)
                r.motivo == "limite" -> v.copy(status = LicStatus.LIMIT)
                r.motivo == "bloqueada" || r.motivo == "invalida" -> v.copy(status = LicStatus.BLOCKED)
                r.motivo == "liberado" -> v.copy(status = LicStatus.RELEASED)
                else -> v.copy(status = LicStatus.LIMIT)
            }
        }
        val left = pendingSince + GRACE_MS - now
        return if (left > 0) base.copy(leftMs = left) else base.copy(status = LicStatus.EXPIRED)
    }

    fun heartbeatDue(v: LicView, now: Long) = v.status == LicStatus.ACTIVE && now - v.lastCheck > HEARTBEAT_MS

    /** Nome do arquivo de resposta (não dá para adivinhar sem o nonce do pedido). */
    fun replyName(l: License, dev: String, nonce: String) = LicenseCrypto.sha256Hex("${l.id}:$dev:$nonce").take(32)

    /** "2 dias e 14 h", "5 h e 20 min", "12 min". */
    fun formatLeft(ms: Long): String {
        val min = (ms / 60_000).coerceAtLeast(0)
        val d = min / 1440; val h = (min % 1440) / 60; val m = min % 60
        return when {
            d > 0 -> "$d ${if (d == 1L) "dia" else "dias"}" + if (h > 0) " e $h h" else ""
            h > 0 -> "$h h" + if (m > 0) " e $m min" else ""
            else -> "$m min"
        }
    }
}
