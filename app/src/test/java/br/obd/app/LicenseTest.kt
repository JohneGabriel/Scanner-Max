package br.obd.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class LicenseTest {
    private val keys: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val pub = Base64.getEncoder().encodeToString(keys.public.encoded)

    private fun sign(msg: String, kp: KeyPair = keys): String {
        val s = Signature.getInstance("SHA256withECDSA"); s.initSign(kp.private); s.update(msg.toByteArray())
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.sign())
    }

    private fun license(id: String = "a1b2c3d4e5f60718", pedido: String = "2000012345674321", max: Int = 3): License {
        val l = License(id, pedido, "20261029", max, "")
        return l.copy(sig = sign(l.payload))
    }

    private fun receipt(l: License, dev: String, ok: Boolean = true, motivo: String = "ok", used: Int = 1, at: Long = 1_800_000_000): Receipt {
        val r = Receipt(l.id, dev, ok, motivo, used, l.max, at, "")
        return r.copy(sig = sign(r.payload))
    }

    @Test
    fun licencaIdaEVolta() {
        val l = license()
        val p = License.parse(l.encode())
        assertEquals(l, p)
        assertTrue(p!!.valid(pub))
        assertEquals("4321", p.pedidoCurto)
        assertEquals("", license(pedido = "").pedidoCurto)
    }

    @Test
    fun licencaAdulteradaNaoValida() {
        val l = license()
        assertFalse("mudar o limite invalida", l.copy(max = 9).valid(pub))
        assertFalse("mudar o id invalida", l.copy(id = "ffffffffffffffff").valid(pub))
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        assertFalse("assinada por outra chave", l.copy(sig = sign(l.payload, other)).valid(pub))
        assertFalse("chave pública do app não aceita licença de teste", l.valid())
    }

    @Test
    fun textoInvalidoNaoQuebra() {
        listOf(null, "", "SMX1", "SMX1.a.b.c.d.e", "SMX2.a1b2c3d4e5f60718.1.20261029.3.${"A".repeat(60)}",
            "SMX1.A1B2.1.20261029.3.x", "SMX1.a1b2c3d4e5f60718.1.2026.3.${"A".repeat(60)}",
            "SMX1.a1b2c3d4e5f60718.1.20261029.0.${"A".repeat(60)}").forEach { assertNull(it, License.parse(it)) }
        listOf(null, "", "R1", "R1.a.b.1.ok.x.3.1.s", "R1.a.b.1.OK!.1.3.1.s").forEach { assertNull(it, Receipt.parse(it)) }
        assertFalse(LicenseCrypto.verify("x", "lixo***", pub))
        assertFalse(LicenseCrypto.verify("x", "AAAA", "chave-ruim"))
    }

    @Test
    fun reciboIdaEVoltaEAssinatura() {
        val l = license()
        val r = receipt(l, "dev1")
        val p = Receipt.parse(r.encode())
        assertEquals(r, p)
        assertTrue(p!!.valid(pub))
        assertTrue(p.matches(l, "dev1"))
        assertFalse(p.matches(l, "dev2"))
        assertFalse("não dá para mudar a contagem", r.copy(used = 9).valid(pub))
        assertFalse(receipt(l, "dev1", ok = false, motivo = "limite").copy(ok = true).valid(pub))
    }

    private fun eval(
        license: License? = license(), valid: Boolean = true, receipt: Receipt? = null, dev: String = "dev1",
        since: Long = 0, released: Boolean = false, now: Long = 1000, enforced: Boolean = true,
    ) = LicenseLogic.evaluate(enforced, license, valid, receipt, dev, since, released, now)

    @Test
    fun estados() {
        val l = license()
        assertEquals(LicStatus.OFF, eval(enforced = false, license = null).status)
        assertEquals(LicStatus.NONE, eval(license = null).status)
        assertEquals(LicStatus.INVALID, eval(valid = false).status)

        val p = eval(since = 0, now = 1000)
        assertEquals(LicStatus.PENDING, p.status)
        assertTrue("libera tudo durante o prazo", p.full)
        assertEquals(LicenseLogic.GRACE_MS - 1000, p.leftMs)
        assertEquals("4321", p.pedido)

        val e = eval(since = 0, now = LicenseLogic.GRACE_MS + 1)
        assertEquals(LicStatus.EXPIRED, e.status)
        assertFalse(e.full)

        val a = eval(receipt = receipt(l, "dev1", used = 2), now = LicenseLogic.GRACE_MS * 10)
        assertEquals("ativado não expira", LicStatus.ACTIVE, a.status)
        assertEquals(2, a.used); assertEquals(3, a.max)
        assertTrue(a.full)

        assertEquals("recibo de outro aparelho não vale", LicStatus.PENDING, eval(receipt = receipt(l, "outro")).status)
        assertEquals(LicStatus.PENDING, eval(receipt = receipt(license(id = "ffffffffffffffff"), "dev1")).status)

        val lim = eval(receipt = receipt(l, "dev1", ok = false, motivo = "limite", used = 3))
        assertEquals(LicStatus.LIMIT, lim.status); assertFalse(lim.full)
        assertEquals(LicStatus.BLOCKED, eval(receipt = receipt(l, "dev1", ok = false, motivo = "bloqueada")).status)
        assertEquals(LicStatus.RELEASED, eval(released = true, receipt = receipt(l, "dev1")).status)
    }

    @Test
    fun verificacaoPeriodica() {
        val l = license()
        val at = 1_800_000_000L
        val v = eval(receipt = receipt(l, "dev1", at = at))
        assertFalse(LicenseLogic.heartbeatDue(v, at * 1000 + 24 * 3600 * 1000))
        assertTrue(LicenseLogic.heartbeatDue(v, at * 1000 + LicenseLogic.HEARTBEAT_MS + 1))
        assertFalse(LicenseLogic.heartbeatDue(eval(), Long.MAX_VALUE / 2))
    }

    @Test
    fun nomeDaRespostaDependeDoNonce() {
        val l = license()
        val a = LicenseLogic.replyName(l, "dev1", "n1")
        assertEquals(32, a.length)
        assertTrue(a != LicenseLogic.replyName(l, "dev1", "n2"))
        assertTrue(a != LicenseLogic.replyName(l, "dev2", "n1"))
        assertEquals(a, LicenseLogic.replyName(l, "dev1", "n1"))
    }

    @Test
    fun prazoFormatado() {
        val h = 3600_000L
        assertEquals("2 dias e 14 h", LicenseLogic.formatLeft(62 * h))
        assertEquals("1 dia", LicenseLogic.formatLeft(24 * h))
        assertEquals("5 h e 20 min", LicenseLogic.formatLeft(5 * h + 20 * 60_000))
        assertEquals("12 min", LicenseLogic.formatLeft(12 * 60_000))
        assertEquals("0 min", LicenseLogic.formatLeft(-5))
    }

    /** Licença e recibos gerados pelo script do servidor (servidor/licenca.py) com a chave real. */
    @Test
    fun formatoDoServidorBateComOApp() {
        assertNotNull(LicenseCrypto.publicKey(LicenseCrypto.PUBLIC_KEY))
        val l = License.parse("SMX1.754551fdc99829e6097d.2000012345674321.20261029.3.MEYCIQCafz6AEP43ZjfIrTELstDrfUx5V2OKsmQ2laApG4ffGQIhALaU6AsEnnFxapM_7URMDGK7tgFZjGNKKNb-yAPudSAU")
        assertNotNull(l); assertTrue(l!!.valid())
        val dev = "abcdef0123456789abcdef01"
        val ok = Receipt.parse("R1.754551fdc99829e6097d.abcdef0123456789abcdef01.1.ok.1.3.1800000000.MEUCIQDJFmddCON5I5qd0HMEpYkQvaQC1YsLwQ16iKje3SVN8wIgArmUIUEEp5E-LDxQWL-r2_bB1QDcR25DynUCgxUOTdc")!!
        assertTrue(ok.valid()); assertTrue(ok.matches(l, dev))
        assertEquals(LicStatus.ACTIVE, LicenseLogic.evaluate(true, l, true, ok, dev, 0, false, 5).status)
        val lim = Receipt.parse("R1.754551fdc99829e6097d.abcdef0123456789abcdef01.0.limite.3.3.1800000000.MEQCIHZZIdsZaew6JtQKrdzcC3Ccx3m8rqfkSgi-Y6AwgMoQAiAiOcY8xmPrL3yIg1YYbZFZbXhZUaEZQFSESzNu4gxEtA")!!
        assertTrue(lim.valid())
        assertFalse("trocar limite por ok quebra a assinatura", lim.copy(ok = true, motivo = "ok").valid())
        assertEquals(LicStatus.LIMIT, LicenseLogic.evaluate(true, l, true, lim, dev, 0, false, 5).status)
    }
}
