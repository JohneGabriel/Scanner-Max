package br.obd.app.link

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Um carro CAN de mentira para o modo demonstração: motor 1.6 a gasolina andando num
 * ciclo de 90 s (lenta → acelera → cruzeiro → desacelera → para). Responde aos comandos
 * AT e aos modos 01, 02, 03, 04, 06, 07, 09 como um ELM327 v1.5 com ATS0 e ATH0.
 */
class ElmSimulator {
    private val t0 = System.currentTimeMillis()
    private var codes = mutableListOf("0171")
    private val rnd = java.util.Random()

    private val supported = sortedSetOf(
        0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x13, 0x14, 0x15,
        0x1C, 0x1F, 0x21, 0x2F, 0x31, 0x33, 0x42, 0x46, 0x51,
    )

    private data class State(
        val rpm: Double, val speed: Double, val throttle: Double, val map: Double,
        val coolant: Double, val fuelCut: Boolean, val runSec: Double,
    )

    private fun noise(a: Double) = (rnd.nextDouble() * 2 - 1) * a

    private fun state(): State {
        val s = (System.currentTimeMillis() - t0) / 1000.0
        val c = s % 90
        var fuelCut = false
        val (rpm, speed, thr) = when {
            c < 20 -> Triple(800 + noise(20.0), 0.0, 3.0)
            c < 35 -> {
                val f = (c - 20) / 15
                val gear = (f * 3).toInt()
                val inGear = (f * 3) % 1
                Triple(1300 + inGear * 1700, f * 52, 25 + inGear * 15)
            }
            c < 60 -> Triple(2100 + noise(40.0), 55 + noise(1.5), 14 + noise(1.0))
            c < 70 -> { fuelCut = true; val f = (c - 60) / 10; Triple(1900 - f * 800, 55 - f * 40, 0.0) }
            c < 78 -> { val f = (c - 70) / 8; Triple(1100 - f * 300, 15 * (1 - f), 0.0) }
            else -> Triple(800 + noise(20.0), 0.0, 3.0)
        }
        val map = when {
            fuelCut -> 22.0
            thr < 4 -> 33.0 + noise(1.0)
            else -> min(98.0, 30 + thr * 1.6)
        }
        val coolant = min(90.0, 45 + s * 0.4) + noise(0.3)
        return State(rpm, max(0.0, speed), thr, map, coolant, fuelCut, s)
    }

    fun respond(raw: String): String {
        val cmd = raw.uppercase().replace(" ", "")
        if (cmd.startsWith("AT")) return at(cmd.removePrefix("AT"))
        if (cmd.startsWith("ST")) return "?"
        if (cmd.length < 2 || cmd.any { it !in "0123456789ABCDEF" }) return "?"
        return when (cmd.substring(0, 2)) {
            "01" -> mode01(cmd.drop(2))
            "02" -> mode02(cmd.drop(2))
            "03" -> "43" + "%02X".format(codes.size) + codes.joinToString("")
            "07" -> "4700"
            "0A" -> "4A00"
            "04" -> { codes.clear(); "44" }
            "06" -> mode06(cmd.drop(2))
            "09" -> mode09(cmd.drop(2))
            else -> "NO DATA"
        }
    }

    private fun at(c: String): String = when {
        c == "Z" -> "ELM327 v1.5"
        c == "I" -> "ELM327 v1.5"
        c == "@1" -> "OBDII to RS232 Interpreter"
        c == "DP" -> "ISO 15765-4 (CAN 11/500)"
        c == "DPN" -> "A6"
        c == "RV" -> "%.1fV".format(java.util.Locale.US, 14.1 + noise(0.05))
        c == "IGN" -> "ON"
        c == "PPS" -> "00:FF F 01:FF F 02:FF F"
        else -> "OK"
    }

    private fun mask(base: Int): String {
        var m = 0L
        for (i in 1..32) if (base + i in supported) m = m or (1L shl (32 - i))
        if (supported.any { it > base + 32 }) m = m or 1L
        return "%08X".format(m)
    }

    private fun pidData(pid: Int, st: State): String? {
        val o2phase = sin(st.runSec * 2 * PI * 1.4)
        fun b(v: Double) = "%02X".format(v.toInt().coerceIn(0, 255))
        fun w(v: Double) = "%04X".format(v.toInt().coerceIn(0, 65535))
        val maf = st.rpm * st.map * 1.6 * 0.85 / (120 * 0.287 * 305)
        return when (pid) {
            0x00, 0x20, 0x40 -> mask(pid)
            0x01 -> "%02X076500".format((if (codes.isEmpty()) 0 else 0x80) or codes.size)
            0x03 -> if (st.coolant < 60) "0100" else if (st.fuelCut) "0400" else "0200"
            0x04 -> b((if (st.fuelCut) 0.0 else st.map / 100 * 90) * 2.55)
            0x05 -> b(st.coolant + 40)
            0x06 -> b((if (st.coolant < 60) 0.0 else o2phase * 4) * 1.28 + 128)
            0x07 -> b(-4.7 * 1.28 + 128)
            0x0B -> b(st.map)
            0x0C -> w(st.rpm * 4)
            0x0D -> b(st.speed)
            0x0E -> b((if (st.fuelCut) 2.0 else 10 + st.rpm / 150) * 2 + 128)
            0x0F -> b(32.0 + 40)
            0x10 -> w(maf * 100)
            0x11 -> b(st.throttle * 2.55)
            0x13 -> "03"
            0x14 -> b(if (st.fuelCut) 10.0 else (0.47 + 0.38 * o2phase) * 200) + "80"
            0x15 -> b(0.66 * 200 + noise(4.0)) + "FF"
            0x1C -> "01"
            0x1F -> w(st.runSec)
            0x21 -> "0000"
            0x2F -> b(62 * 2.55)
            0x31 -> w(812.0)
            0x33 -> b(94.0)
            0x42 -> w(14100.0 + noise(60.0))
            0x46 -> b(27.0 + 40)
            0x51 -> "01"
            else -> null
        }
    }

    private fun mode01(rest: String): String {
        if (rest.isEmpty() || rest.length % 2 != 0) return "?"
        val st = state()
        val pids = rest.chunked(2).map { it.toInt(16) }.take(6)
        val sb = StringBuilder("41")
        var any = false
        for (p in pids) {
            val d = pidData(p, st) ?: continue
            if (p != 0 && p != 0x20 && p != 0x40 && p !in supported) continue
            if (any) Unit
            sb.append("%02X".format(p)).append(d)
            any = true
        }
        return if (any) sb.toString() else "NO DATA"
    }

    private fun mode02(rest: String): String {
        if (rest.length < 4) return "?"
        val pid = rest.take(2).toInt(16)
        if (pid == 0x02) return "420200" + (codes.firstOrNull() ?: "0000")
        if (codes.isEmpty()) return "NO DATA"
        val frozen = State(2450.0, 72.0, 22.0, 64.0, 88.0, false, 400.0)
        val d = pidData(pid, frozen) ?: return "NO DATA"
        return "42%02X00".format(pid) + d
    }

    private fun mode06(rest: String): String = when (rest) {
        "00" -> "4600C0000000"
        "01" -> "4601010A01900064038401020A005000000100"
        "02" -> "4602010A030000000400"
        else -> "NO DATA"
    }

    private fun mode09(rest: String): String = when (rest) {
        "00" -> "490054400000"
        "02" -> "490201" + "SMXDEMO1A2B3C4D5E".map { "%02X".format(it.code) }.joinToString("")
        "04" -> "490401" + "DEMO-ECU-0001".map { "%02X".format(it.code) }.joinToString("")
        "0A" -> "490A01" + "ECM-EngineControl".map { "%02X".format(it.code) }.joinToString("")
        else -> "NO DATA"
    }
}
