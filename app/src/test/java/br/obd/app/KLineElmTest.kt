package br.obd.app

import br.obd.app.link.KLineElm
import br.obd.app.vag.KLinePort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/**
 * Cabo KKL + central ISO 9141-2 simulados: o cabo devolve o eco de tudo que é enviado,
 * a central responde à inicialização a 5 baud e aos pedidos OBD2 (como o Gol G5).
 */
class KLineElmTest {

    private class FakeKLine(
        var awake: Boolean = true,
        val kb: Pair<Int, Int> = 0x08 to 0x08,
        val ecus: Int = 1,
        var dropEcho: Boolean = false,
    ) : KLinePort {
        val rx = ArrayDeque<Int>()
        private var sawBreak = false
        private var waitingInv = false
        private var inSession = false
        private val req = ArrayList<Int>()
        var requests = 0
        val inits get() = initCount
        private var initCount = 0

        override fun setBaud(baud: Int) = Unit
        override fun setBreak(on: Boolean) { if (on) sawBreak = true }
        override fun write(b: Int) {
            if (!dropEcho) rx.add(b)
            if (waitingInv) {
                waitingInv = false
                if (b == (kb.second.inv() and 0xFF)) { rx.add(0xCC); inSession = true }
                return
            }
            if (!inSession) return
            req += b
            if (req.size >= 5 && (req.dropLast(1).sum() and 0xFF) == req.last()) {
                requests++
                val data = req.subList(3, req.size - 1).toList()
                req.clear()
                answer(data)
            }
        }

        private fun answer(d: List<Int>) {
            val key = d.joinToString("") { "%02X".format(it) }
            val resp: List<List<Int>> = when (key) {
                "0100" -> listOf(listOf(0x41, 0x00, 0xBE, 0x1F, 0xB8, 0x10))
                "010C" -> listOf(listOf(0x41, 0x0C, 0x0B, 0xB8))
                "0105" -> listOf(listOf(0x41, 0x05, 0x7B))
                "03" -> listOf(listOf(0x43, 0x01, 0x71, 0x00, 0x00, 0x00, 0x00))
                "0902" -> listOf(
                    listOf(0x49, 0x02, 0x01, 0x00, 0x00, 0x00, 0x39),
                    listOf(0x49, 0x02, 0x02, 0x42, 0x57, 0x41, 0x42),
                )
                else -> emptyList()
            }
            repeat(ecus) { e ->
                for (r in resp) {
                    val f = listOf(0x48, 0x6B, 0x10 + e) + r
                    rx.addAll(f + (f.sum() and 0xFF))
                }
            }
        }

        override fun read(timeoutMs: Int): Int = rx.pollFirst() ?: -1

        override fun purge() {
            rx.clear(); req.clear()
            if (sawBreak) {
                sawBreak = false
                inSession = false
                if (awake) { initCount++; rx.addAll(listOf(0x55, kb.first, kb.second)); waitingInv = true }
            }
        }

        override fun close() = Unit
    }

    private fun elm(port: FakeKLine, clock: () -> Long = System::currentTimeMillis) = KLineElm(port, bitMs = 1, clock = clock)

    /** Resposta sem eco, sem o '>' final e sem linhas vazias. */
    private fun KLineElm.ask(cmd: String) = handle(cmd).removeSuffix(">").split('\r').map { it.trim() }.filter { it.isNotEmpty() }

    private fun KLineElm.setup() { handle("ATZ"); handle("ATE0"); handle("ATL0"); handle("ATS0"); handle("ATH0"); handle("ATSP0") }

    @Test
    fun conectaELePids() {
        val port = FakeKLine()
        val e = elm(port)
        e.setup()
        assertEquals(listOf("4100BE1FB810"), e.ask("0100"))
        assertTrue(e.connected)
        assertEquals(listOf("A3"), e.ask("ATDPN"))
        assertEquals(listOf("AUTO, ISO 9141-2"), e.ask("ATDP"))
        assertEquals(listOf("410C0BB8"), e.ask("010C"))
        assertEquals(listOf("41057B"), e.ask("0105"))
        assertEquals("uma inicialização só", 1, port.inits)
    }

    @Test
    fun codigosEVinMultiplosQuadros() {
        val e = elm(FakeKLine())
        e.setup()
        assertEquals(listOf("43017100000000"), e.ask("03"))
        assertEquals(listOf("49020100000039", "49020242574142"), e.ask("0902"))
        // o app lê os códigos a partir do formato do ELM
        val resp = e.ask("03").joinToString("\n")
        assertTrue(resp.contains("0171"))
    }

    @Test
    fun formatoComEspacosECabecalho() {
        val e = elm(FakeKLine())
        e.handle("ATE0")
        assertEquals(listOf("41 0C 0B B8"), e.ask("010C"))
        e.handle("ATH1")
        val r = e.ask("010C").single()
        assertTrue(r, r.startsWith("48 6B 10 41 0C 0B B8"))
    }

    @Test
    fun duasCentraisRespondendo() {
        val e = elm(FakeKLine(ecus = 2))
        e.setup()
        assertEquals(listOf("4100BE1FB810", "4100BE1FB810"), e.ask("0100"))
    }

    @Test
    fun ecoDoComandoQuandoLigado() {
        val e = elm(FakeKLine())
        val r = e.handle("ATI")
        assertTrue(r, r.startsWith("ATI\r") && r.contains("ELM327") && r.endsWith(">"))
    }

    @Test
    fun recursosQueOCaboNaoTem() {
        val e = elm(FakeKLine())
        e.setup()
        assertEquals(listOf("?"), e.ask("ATRV"))
        assertEquals(listOf("?"), e.ask("ATMA"))
        assertEquals(listOf("OK"), e.ask("ATAT1"))
        e.handle("ATSP6")
        assertEquals("CAN não existe na linha K", listOf("UNABLE TO CONNECT"), e.ask("0100"))
    }

    @Test
    fun centralDesligadaDaErroDeInicializacao() {
        val e = elm(FakeKLine(awake = false))
        e.setup()
        assertEquals(listOf("BUS INIT: ...ERROR"), e.ask("0100"))
        assertFalse(e.connected)
    }

    @Test
    fun semEcoDoCaboFechaASessao() {
        val port = FakeKLine()
        val e = elm(port)
        e.setup()
        assertEquals(listOf("4100BE1FB810"), e.ask("0100"))
        port.dropEcho = true
        assertEquals(listOf("NO DATA"), e.ask("010C"))
        assertFalse(e.connected)
        port.dropEcho = false
        assertEquals("reconecta sozinho", listOf("410C0BB8"), e.ask("010C"))
    }

    @Test
    fun keepAliveSoQuandoParado() {
        var t = 100_000L
        val port = FakeKLine()
        val e = elm(port) { t }
        e.setup()
        e.ask("0100")
        val before = port.requests
        t += 1000; e.keepAlive()
        assertEquals("ainda não", before, port.requests)
        t += KLineElm.KEEPALIVE_MS; e.keepAlive()
        assertEquals("manteve a central acordada", before + 1, port.requests)
    }

    @Test
    fun kwp2000PorCincoBaud() {
        val e = elm(FakeKLine(kb = 0xE9 to 0x8F))
        e.setup()
        e.ask("ATDPN") // antes de conectar
        // a central simulada responde com cabeçalho ISO; aqui só conferimos a negociação
        e.ask("0100")
        assertEquals(listOf("A4"), e.ask("ATDPN"))
    }
}
