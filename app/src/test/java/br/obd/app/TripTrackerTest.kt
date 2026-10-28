package br.obd.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Simula viagens com um relógio falso e confere o que seria salvo. */
class TripTrackerTest {
    private val profile = VehicleProfile(id = "teste", name = "Teste", displacementL = 1.6, displacementSet = true)
    private val driving = mapOf(
        "rpm" to 2000.0, "speed" to 60.0, "map" to 50.0, "iat" to 25.0, "coolant" to 90.0,
        "fuelStatus" to 2.0, "throttle" to 15.0, "battery" to 14.1,
    )

    @Test
    fun dezMinutosA60kmhSalvaAoDesligar() {
        var t = 0L
        var saved: TripSummary? = null
        var points = 0
        var checkpoints = 0
        val tr = TripTracker(clock = { t }, onCheckpoint = { _, _ -> checkpoints++ }) { s, p -> saved = s; points = p.size }
        repeat(600) { t += 1000; tr.onTick(driving, true, profile) }
        assertNotNull("viagem deveria estar em andamento", tr.live.value)
        assertNull("não deve salvar com o motor ligado", saved)
        // motor desligado: a central para de responder
        repeat(60) { t += 1000; tr.onTick(emptyMap(), false, profile) }
        val s = saved!!
        assertEquals(10.0, s.km, 0.1)
        assertEquals(599.0, s.runSec, 2.0)
        assertEquals(599.0, s.durationSec, 2.0) // o fim é quando o motor desligou, não 45 s depois
        assertTrue("litros fora do esperado: ${s.liters}", s.liters in 0.4..0.8)
        assertTrue("km/L fora do esperado: ${s.kmPerL}", (s.kmPerL ?: 0.0) in 12.0..25.0)
        assertEquals(90.0, s.avgCoolant!!, 0.1)
        assertTrue("checkpoints: $checkpoints", checkpoints >= 8)
        assertTrue("pontos do gráfico: $points", points in 250..320)
        assertNull(tr.live.value)
    }

    @Test
    fun ligarEDesligarRapidoNaoSalva() {
        var t = 0L
        var saved = false
        val tr = TripTracker(clock = { t }) { _, _ -> saved = true }
        repeat(20) { t += 1000; tr.onTick(driving + ("speed" to 0.0), true, profile) }
        repeat(60) { t += 1000; tr.onTick(emptyMap(), false, profile) }
        assertTrue(!saved)
    }

    @Test
    fun desconectarFechaAViagem() {
        var t = 0L
        var saved: TripSummary? = null
        val tr = TripTracker(clock = { t }) { s, _ -> saved = s }
        repeat(120) { t += 1000; tr.onTick(driving, true, profile) }
        tr.finish()
        assertEquals(2.0, saved!!.km, 0.05)
        tr.finish() // segunda chamada não salva de novo
    }

    @Test
    fun corteDeCombustivelNaoGasta() {
        var t = 0L
        var saved: TripSummary? = null
        val tr = TripTracker(clock = { t }) { s, _ -> saved = s }
        val cut = driving + ("fuelStatus" to 4.0) + ("throttle" to 0.0) + ("rpm" to 2500.0)
        repeat(120) { t += 1000; tr.onTick(cut, true, profile) }
        tr.finish()
        assertEquals(0.0, saved!!.liters, 0.001)
        assertTrue(saved!!.fuelCutSec > 100)
    }
}
