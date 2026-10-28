package br.obd.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fórmula de PID personalizado, no estilo dos outros apps: letras A, B, C… são os bytes de
 * dados da resposta (A = primeiro). Aceita + − × ÷, ^, parênteses e números decimais.
 * Exemplos: "A-40", "(A*256+B)/4", "A*100/255".
 */
class Formula(private val src: String) {
    private var i = 0
    private lateinit var vars: IntArray

    fun eval(bytes: IntArray): Double? = try {
        vars = bytes; i = 0
        val v = expr()
        skip()
        if (i != src.length) null else v.takeIf { !it.isNaN() && !it.isInfinite() }
    } catch (e: Exception) { null }

    /** null se a fórmula está escrita certo; senão, o problema. */
    fun check(): String? = try {
        vars = IntArray(8) { 100 }; i = 0
        expr(); skip()
        if (i != src.length) "Caractere inesperado: '${src[i]}'" else null
    } catch (e: Exception) { e.message ?: "Fórmula inválida" }

    private fun skip() { while (i < src.length && src[i] == ' ') i++ }
    private fun expr(): Double {
        var v = term()
        while (true) {
            skip()
            if (i >= src.length) return v
            when (src[i]) { '+' -> { i++; v += term() }; '-' -> { i++; v -= term() }; else -> return v }
        }
    }
    private fun term(): Double {
        var v = power()
        while (true) {
            skip()
            if (i >= src.length) return v
            when (src[i]) {
                '*', 'x', '×' -> { i++; v *= power() }
                '/', '÷' -> { i++; v /= power() }
                else -> return v
            }
        }
    }
    private fun power(): Double {
        val b = unary(); skip()
        return if (i < src.length && src[i] == '^') { i++; Math.pow(b, power()) } else b
    }
    private fun unary(): Double { skip(); return if (i < src.length && src[i] == '-') { i++; -unary() } else atom() }
    private fun atom(): Double {
        skip()
        if (i >= src.length) throw IllegalArgumentException("Fórmula incompleta")
        val c = src[i]
        if (c == '(') { i++; val v = expr(); skip(); if (i >= src.length || src[i] != ')') throw IllegalArgumentException("Falta ')'"); i++; return v }
        if (c.isDigit() || c == '.' || c == ',') {
            val st = i
            while (i < src.length && (src[i].isDigit() || src[i] == '.' || src[i] == ',')) i++
            return src.substring(st, i).replace(',', '.').toDouble()
        }
        if (c.uppercaseChar() in 'A'..'H') {
            i++
            val idx = c.uppercaseChar() - 'A'
            return vars.getOrNull(idx)?.toDouble() ?: throw IllegalArgumentException("A resposta não tem o byte $c")
        }
        throw IllegalArgumentException("Caractere inesperado: '$c'")
    }
}

/** Um sensor definido pelo usuário (PID do fabricante, por exemplo). */
data class CustomPid(
    val id: String,
    val name: String,
    /** Modo em hexadecimal: 01 (padrão), 21/22 (fabricante)… */
    val mode: String,
    /** PID/DID em hexadecimal: 2 dígitos no modo 01, 4 no 22. */
    val pid: String,
    val formula: String,
    val unit: String,
    /** Cabeçalho CAN opcional (ATSH), ex.: 7E0. Vazio = o padrão. */
    val header: String = "",
) {
    val key get() = "custom:$id"
    val command get() = (mode + pid).uppercase()
    private val prefix get() = "%02X".format((mode.toIntOrNull(16) ?: 1) + 0x40) + pid.uppercase()

    /** Tira os bytes de dados de uma resposta do adaptador. */
    fun parse(resp: String): IntArray? {
        val hex = Elm327.joinFrames(resp)
        val at = hex.indexOf(prefix)
        if (at < 0) return null
        return hex.substring(at + prefix.length).chunked(2).mapNotNull { it.toIntOrNull(16) }.toIntArray().takeIf { it.isNotEmpty() }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("mode", mode); put("pid", pid); put("f", formula); put("unit", unit); put("h", header)
    }

    companion object {
        fun fromJson(o: JSONObject) = CustomPid(o.getString("id"), o.optString("name"), o.optString("mode", "01"),
            o.optString("pid"), o.optString("f"), o.optString("unit"), o.optString("h"))
    }
}

object CustomPids {
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences("custom_pids", Context.MODE_PRIVATE)
    private val _list = MutableStateFlow<List<CustomPid>>(emptyList())
    val list: StateFlow<List<CustomPid>> = _list.asStateFlow()

    fun init(c: Context) {
        ctx = c.applicationContext
        val a = runCatching { JSONArray(sp.getString("list", "[]")) }.getOrDefault(JSONArray())
        _list.value = (0 until a.length()).mapNotNull { runCatching { CustomPid.fromJson(a.getJSONObject(it)) }.getOrNull() }
    }

    private fun persist() {
        val a = JSONArray(); _list.value.forEach { a.put(it.toJson()) }
        sp.edit().putString("list", a.toString()).apply()
    }

    fun save(p: CustomPid) { _list.value = _list.value.filter { it.id != p.id } + p; persist() }
    fun delete(id: String) { _list.value = _list.value.filter { it.id != id }; persist() }

    /** Lê um PID personalizado agora (com o adaptador já reservado). Devolve valor e resposta crua. */
    suspend fun read(elm: Elm327, p: CustomPid, can29: Boolean): Pair<Double?, String> {
        val h = p.header.trim().uppercase()
        if (h.isNotEmpty()) elm.send("ATSH$h")
        val resp = try { elm.send(p.command, 3000) } finally {
            if (h.isNotEmpty()) elm.send(if (can29) "ATSH18DB33F1" else "ATSH7DF")
        }
        val bytes = p.parse(resp)
        return (bytes?.let { Formula(p.formula).eval(it) }) to resp
    }
}
