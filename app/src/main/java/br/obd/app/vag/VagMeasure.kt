package br.obd.app.vag

import kotlin.math.abs

/**
 * Tradução das células dos blocos de medição da VW. Cada célula traz uma "fórmula"
 * (tipo de dado) e dois bytes a e b. Só as fórmulas mais comuns estão aqui; as outras
 * aparecem cruas (fórmula, a, b), sem inventar valor.
 */
object VagMeasure {

    data class Value(val text: String, val unit: String, val known: Boolean)

    fun decode(c: MeasureCell): Value {
        val a = c.a.toDouble(); val b = c.b.toDouble()
        fun v(x: Double, unit: String, digits: Int = 1) = Value("%.${digits}f".format(x), unit, true)
        return when (c.formula) {
            0x01 -> v(0.2 * a * b, "rpm", 0)
            0x02 -> v(a * 0.002 * b, "%")
            0x03 -> v(0.002 * a * b, "°")
            0x04 -> v(abs(b - 127) * 0.01 * a, if (b > 127) "° APMS" else "° DPMS")
            0x05 -> v(a * (b - 100) * 0.1, "°C")
            0x06 -> v(0.001 * a * b, "V", 2)
            0x07 -> v(0.01 * a * b, "km/h", 0)
            0x08 -> v(0.1 * a * b, "", 1)
            0x0F -> v(0.01 * a * b, "ms", 2)
            0x10 -> Value(Integer.toBinaryString(c.b).padStart(8, '0'), "bits", true)
            0x11 -> Value("${c.a.toChar()}${c.b.toChar()}", "", true)
            0x12 -> v(0.04 * a * b, "mbar", 0)
            0x14 -> v(a * (b - 128) / 128, "%")
            0x15 -> v(0.001 * a * b, "V", 3)
            0x16 -> v(0.001 * a * b, "ms", 2)
            0x17 -> v(b / 256 * a, "%")
            0x1B -> v(abs(b - 128) * 0.01 * a, if (b > 128) "° APMS" else "° DPMS")
            0x21 -> v(if (a == 0.0) 100 * b else 100 * b / a, "%")
            0x22 -> v((b - 128) * 0.01 * a, "kW")
            0x23 -> v(0.01 * a * b, "l/h", 2)
            0x25 -> Value("%02X %02X".format(c.a, c.b), "texto", true)
            else -> Value("f%02X  a=%d  b=%d".format(c.formula, c.a, c.b), "", false)
        }
    }

    /** Códigos de falha VW de 5 dígitos (ex.: 16684); os 0x4000+ costumam mapear para Pxxxx. */
    fun dtcText(d: VagDtc): String {
        val vw = "%05d".format(d.code)
        // 16384 + n = P0nnn; 17408 + n = P1nnn (ex.: 16684 = P0300, 17965 = P1557)
        val obd = when (d.code) {
            in 16384..17383 -> "  (P0%03d)".format(d.code - 16384)
            in 17408..18407 -> "  (P1%03d)".format(d.code - 17408)
            else -> ""
        }
        // o significado exato do status varia por central: mostramos o valor cru
        val status = "status %02X".format(d.status and 0x7F)
        return "$vw$obd · $status${if (d.status and 0x80 != 0) " · intermitente" else ""}"
    }
}
