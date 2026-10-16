package com.camry.launcher

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Saludo con voz al encender el auto. */
object Greeter {
    const val DEFAULT_TEXT = "Bienvenidos Diana y Jahaz, ¿a dónde vamos hoy?"
    private const val KEY_ON = "greet_on"
    private const val KEY_TEXT = "greet_text"

    private var tts: TextToSpeech? = null

    fun isOn(ctx: Context) = ThemeManager.prefs(ctx).getBoolean(KEY_ON, true)
    fun setOn(ctx: Context, on: Boolean) = ThemeManager.prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    fun text(ctx: Context): String = ThemeManager.prefs(ctx).getString(KEY_TEXT, null) ?: DEFAULT_TEXT
    fun setText(ctx: Context, t: String) = ThemeManager.prefs(ctx).edit().putString(KEY_TEXT, t).apply()

    fun speak(ctx: Context, onError: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        val phrase = text(app)
        tts?.shutdown()
        var engine: TextToSpeech? = null
        engine = TextToSpeech(app) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                onError?.invoke("El estéreo no tiene voz instalada")
                return@TextToSpeech
            }
            val mx = t.setLanguage(Locale("es", "MX"))
            if (mx == TextToSpeech.LANG_MISSING_DATA || mx == TextToSpeech.LANG_NOT_SUPPORTED) {
                val es = t.setLanguage(Locale("es"))
                if (es == TextToSpeech.LANG_MISSING_DATA || es == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onError?.invoke("Falta la voz en español: instala \"Speech Services by Google\"")
                }
            }
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { t.shutdown(); if (tts === t) tts = null }
                @Deprecated("API antigua")
                override fun onError(utteranceId: String?) { t.shutdown(); if (tts === t) tts = null }
            })
            t.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, "saludo")
        }
        tts = engine
    }
}
