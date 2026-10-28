package br.obd.app

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preferências do usuário (personalização, alertas, feedback). */
data class Prefs(
    /** Índice do tema de cores (ui/Themes). */
    val theme: Int = 0,
    val keepScreenOn: Boolean = false,
    val autoConnect: Boolean = true,
    val haptics: Boolean = true,
    val sounds: Boolean = true,
    val alerts: Boolean = true,
    val coolantWarn: Int = 105,
    val redline: Int = 6200,
    val lowFuelPct: Int = 10,
    /** Cartões escondidos no painel (chaves dos PIDs). */
    val hiddenCards: Set<String> = emptySet(),
    val secondGauge: String = "speed",
    val onboarded: Boolean = false,
    /** "0" automático ou número do protocolo forçado (ATSP). */
    val forcedProtocol: String = "0",
    /** Comandos AT extras na conexão. */
    val extraInit: String = "",
    /** Fala os alertas em voz alta. */
    val voice: Boolean = false,
    /** Notificação fixa de acesso rápido (desconectado). */
    val quickNotif: Boolean = true,
    /** Lembretes periódicos e dicas. */
    val reminders: Boolean = true,
)

object Settings {
    private lateinit var ctx: Context
    private val sp get() = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()

    fun init(c: Context) {
        ctx = c.applicationContext
        val s = sp
        _prefs.value = Prefs(
            theme = s.getInt("theme", 0),
            keepScreenOn = s.getBoolean("screenOn", false), autoConnect = s.getBoolean("autoConnect", true),
            haptics = s.getBoolean("haptics", true), sounds = s.getBoolean("sounds", true),
            alerts = s.getBoolean("alerts", true), coolantWarn = s.getInt("coolantWarn", 105),
            redline = s.getInt("redline", 6200), lowFuelPct = s.getInt("lowFuel", 10),
            hiddenCards = s.getStringSet("hidden", emptySet())!!.toSet(),
            secondGauge = s.getString("gauge2", "speed")!!, onboarded = s.getBoolean("onboarded", false),
            forcedProtocol = s.getString("proto", "0")!!, extraInit = s.getString("extraInit", "")!!, voice = s.getBoolean("voice", false),
            quickNotif = s.getBoolean("quickNotif", true), reminders = s.getBoolean("reminders", true),
        )
    }

    fun update(block: (Prefs) -> Prefs) {
        val p = block(_prefs.value)
        _prefs.value = p
        sp.edit()
            .putInt("theme", p.theme).putBoolean("screenOn", p.keepScreenOn)
            .putBoolean("autoConnect", p.autoConnect).putBoolean("haptics", p.haptics).putBoolean("sounds", p.sounds)
            .putBoolean("alerts", p.alerts).putInt("coolantWarn", p.coolantWarn).putInt("redline", p.redline)
            .putInt("lowFuel", p.lowFuelPct).putStringSet("hidden", p.hiddenCards).putString("gauge2", p.secondGauge)
            .putBoolean("onboarded", p.onboarded).putString("proto", p.forcedProtocol).putString("extraInit", p.extraInit)
            .putBoolean("voice", p.voice).putBoolean("quickNotif", p.quickNotif).putBoolean("reminders", p.reminders)
            .apply()
    }
}

/** Sons curtos (sem arquivos de áudio) e vibração, respeitando as preferências. */
object Feedback {
    private lateinit var ctx: Context
    private var tone: ToneGenerator? = null

    fun init(c: Context) {
        ctx = c.applicationContext
        tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
    }

    private val vibrator: Vibrator?
        get() = if (Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)

    private fun vibrate(pattern: LongArray) {
        if (!Settings.prefs.value.haptics) return
        runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun beep(t: Int, ms: Int) {
        if (!Settings.prefs.value.sounds) return
        runCatching { tone?.startTone(t, ms) }
    }

    /** Toque leve: botão, seleção. */
    fun tick() = vibrate(longArrayOf(0, 12))

    /** Conectou / teste começou. */
    fun start() { vibrate(longArrayOf(0, 25, 60, 25)); beep(ToneGenerator.TONE_PROP_BEEP, 120) }

    /** Mudou de etapa no teste. */
    fun step() { vibrate(longArrayOf(0, 40)); beep(ToneGenerator.TONE_PROP_ACK, 150) }

    /** Ação do usuário agora (ex.: ACELERE). */
    fun cue() { vibrate(longArrayOf(0, 80, 80, 80)); beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 300) }

    /** Resultado bom. */
    fun success() { vibrate(longArrayOf(0, 30, 70, 60)); beep(ToneGenerator.TONE_PROP_ACK, 200) }

    /** Resultado com atenção. */
    fun warning() { vibrate(longArrayOf(0, 120, 100, 120)); beep(ToneGenerator.TONE_PROP_NACK, 300) }

    /** Alerta crítico. */
    fun critical() { vibrate(longArrayOf(0, 300, 150, 300, 150, 300)); beep(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 900) }

    /** Conexão caiu. */
    fun lost() { vibrate(longArrayOf(0, 200)); beep(ToneGenerator.TONE_PROP_NACK, 250) }
}


/** Alertas falados (Text-to-Speech do Android, em português). */
object Speech {
    private var tts: android.speech.tts.TextToSpeech? = null
    private var ready = false
    private lateinit var ctx: Context

    fun init(c: Context) { ctx = c.applicationContext }

    fun say(text: String) {
        if (!Settings.prefs.value.voice) return
        val t = tts
        if (t == null) {
            tts = android.speech.tts.TextToSpeech(ctx) { st ->
                ready = st == android.speech.tts.TextToSpeech.SUCCESS
                if (ready) { tts?.language = java.util.Locale("pt", "BR"); tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "a") }
            }
            return
        }
        if (ready) t.speak(text, android.speech.tts.TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
    }
}
