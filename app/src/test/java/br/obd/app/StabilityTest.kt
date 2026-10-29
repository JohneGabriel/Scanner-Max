package br.obd.app

import br.obd.app.link.ElmSimulator
import br.obd.app.link.LinkKind
import br.obd.app.link.QueueTransport
import br.obd.app.link.SimTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * Testes de estabilidade da comunicação: o ELM327 do app conversando com o carro simulado,
 * com falhas injetadas (respostas perdidas, lixo na linha, queda do link), acesso concorrente
 * e entradas aleatórias nos interpretadores.
 */
class StabilityTest {

    /** Simulador com defeitos: perde respostas, injeta lixo e pode "cair" depois de N escritas. */
    private class FlakyTransport(
        private val dropRate: Double = 0.0,
        private val garbageRate: Double = 0.0,
        private val dieAfter: Int = Int.MAX_VALUE,
        seed: Int = 42,
    ) : QueueTransport() {
        override val kind = LinkKind.DEMO
        private val sim = ElmSimulator()
        private val rnd = Random(seed)
        private val pending = StringBuilder()
        private var writes = 0
        override fun open() { opened = true }
        override fun write(data: ByteArray) {
            if (!opened) throw IOException("link fechado")
            if (++writes > dieAfter) { opened = false; throw IOException("Broken pipe") }
            for (b in data) {
                val c = b.toInt().toChar()
                if (c != '\r') { pending.append(c); continue }
                val cmd = pending.toString().trim(); pending.clear()
                if (cmd.isEmpty()) continue
                if (rnd.nextDouble() < garbageRate) push("\u0000ÿSEARCHING...\r".toByteArray(Charsets.ISO_8859_1))
                if (rnd.nextDouble() < dropRate) continue // resposta perdida: sem '>'
                push((sim.respond(cmd) + "\r\r>").toByteArray())
            }
        }
        override fun close() { opened = false }
    }

    private fun elm() = Elm327 { }

    @Test
    fun inicializaEIdentificaOAdaptador() = runBlocking {
        val e = elm()
        e.connect(SimTransport())
        val v = e.init()
        assertTrue("versão: $v", v.contains("ELM327"))
        assertTrue(e.isConnected)
        assertTrue("deve ler a tensão", e.profile.canReadVoltage)
        assertFalse("simulador não é clone", e.profile.clone)
        assertNotNull(e.profile.latencyMs)
    }

    @Test
    fun leituraSeguidaSemErros() = runBlocking {
        val e = elm(); e.connect(SimTransport()); e.init()
        e.verbose = false
        var ok = 0
        repeat(300) {
            val rpm = e.readPid("0C", retries = 0)?.let(Pids.RPM.decode)
            if (rpm != null && rpm in 600.0..3500.0) ok++
        }
        assertEquals("todas as 300 leituras devem responder", 300, ok)
        assertEquals(listOf("P0171"), e.readDtcs("03"))
        assertTrue(e.readDtcs("07").isEmpty())
    }

    @Test
    fun acessoConcorrenteNaoMisturaRespostas() = runBlocking {
        val e = elm(); e.connect(SimTransport()); e.init()
        e.verbose = false
        val pids = listOf("0C" to Pids.RPM, "0D" to Pids.SPEED, "05" to Pids.COOLANT, "0B" to Pids.MAP)
        val jobs = pids.map { (pid, def) ->
            async(Dispatchers.Default) {
                (1..60).count { e.readPid(pid, retries = 0)?.let(def.decode) == null }
            }
        }
        val misses = jobs.awaitAll().sum()
        assertEquals("pedidos simultâneos não podem trocar respostas entre si", 0, misses)
    }

    @Test
    fun respostasPerdidasELixoNaoTravam() = runBlocking {
        val e = elm()
        e.connect(FlakyTransport(dropRate = 0.06, garbageRate = 0.10))
        e.init()
        e.verbose = false
        val t0 = System.currentTimeMillis()
        var ok = 0
        repeat(80) { if (e.readPid("0C", retries = 1)?.let(Pids.RPM.decode) != null) ok++ }
        val el = System.currentTimeMillis() - t0
        assertTrue("com 6% de perda e 10% de lixo, ao menos 90% devem responder (respondeu $ok/80)", ok >= 72)
        assertTrue("não pode travar: levou $el ms", el < 60_000)
        assertTrue("o link continua de pé", e.isConnected)
    }

    @Test
    fun quedaDoLinkViraErroClaro() = runBlocking {
        val e = elm()
        e.connect(FlakyTransport(dieAfter = 40))
        e.init()
        e.verbose = false
        try {
            repeat(100) { e.readPid("0C", retries = 0) }
            fail("deveria ter lançado IOException quando o link caiu")
        } catch (ex: IOException) {
            assertTrue(ex.message!!.contains("perdida"))
        }
        assertFalse("depois da queda, isConnected deve ser false", e.isConnected)
    }

    @Test
    fun formulaNaoQuebraComEntradaAleatoria() {
        val rnd = Random(7)
        val chars = "ABCDEFGH0123456789+-*/^(). ,xz#"
        repeat(5000) {
            val f = (1..rnd.nextInt(1, 14)).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
            val v = Formula(f).eval(intArrayOf(12, 200, 7, 0))
            if (v != null) assertTrue("valor não finito para '$f'", v.isFinite())
            Formula(f).check() // nunca lança
        }
        assertEquals(2000.0, Formula("(A*256+B)/4").eval(intArrayOf(31, 64))!!, 1e-9)
        assertEquals(50.0, Formula("A-40").eval(intArrayOf(90))!!, 1e-9)
        assertEquals(null, Formula("B").eval(intArrayOf(1)))
    }

    @Test
    fun todosOsCodigosTemExplicacao() {
        for (letter in "PCBU") for (n in 0 until 0x4000) {
            val code = "%c%04X".format(letter, n)
            val d = DtcGuide.detail(code)
            assertTrue(code, d.title.isNotBlank() && d.meaning.isNotBlank() && d.actions.isNotEmpty())
        }
        assertEquals("P0171", DtcGuide.normalize(" p 0171 "))
        assertEquals("P0420", DtcGuide.normalize("0420"))
        assertEquals(null, DtcGuide.normalize("XYZ"))
    }

    @Test
    fun decodificadoresAguentamBytesAleatorios() {
        val rnd = Random(3)
        repeat(4000) {
            val bytes = IntArray(rnd.nextInt(0, 12)) { rnd.nextInt(0, 256) }
            Pids.ALL.forEach { d -> d.decode(bytes)?.let { assertTrue("${d.key} não finito", it.isFinite()) } }
            ReadinessDecoder.decode(bytes)
            Elm327.decodeDtc("%04X".format(rnd.nextInt(0, 0x10000)))
        }
    }

    @Test
    fun consumoNuncaNegativoNemInfinito() {
        val rnd = Random(11)
        val p = VehicleProfile(id = "x", name = "x")
        repeat(20000) {
            val v = mapOf(
                "rpm" to rnd.nextDouble(0.0, 8000.0), "map" to rnd.nextDouble(10.0, 250.0), "iat" to rnd.nextDouble(-30.0, 80.0),
                "throttle" to rnd.nextDouble(0.0, 100.0), "fuelStatus" to listOf(1.0, 2.0, 4.0, 8.0)[rnd.nextInt(4)],
            )
            FuelModel.rawLph(v, p)?.let { assertTrue("lph=$it", it >= 0 && it.isFinite() && it < 200) }
        }
    }

    @Test
    fun viagemDeOitoHorasNaoEstouraMemoria() {
        var t = 0L
        var saved: TripSummary? = null
        var pts = 0
        var checkpoints = 0
        val tr = TripTracker(clock = { t }, onCheckpoint = { _, _ -> checkpoints++ }) { s, p -> saved = s; pts = p.size }
        val p = VehicleProfile(id = "x", name = "x", displacementL = 1.6, displacementSet = true)
        val v = mapOf("rpm" to 2200.0, "speed" to 80.0, "map" to 55.0, "iat" to 30.0, "coolant" to 92.0, "fuelStatus" to 2.0)
        repeat(8 * 3600) { t += 1000; tr.onTick(v, true, p) }
        tr.finish()
        assertEquals(640.0, saved!!.km, 1.0)
        assertTrue("pontos do gráfico limitados: $pts", pts in 1000..5000)
        assertTrue("checkpoints: $checkpoints", checkpoints >= 400)
    }
}
