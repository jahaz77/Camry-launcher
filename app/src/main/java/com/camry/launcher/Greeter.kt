package com.camry.launcher

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale

/**
 * Saludo al encender el auto.
 * 1) Si existe CamryLauncher/saludo.mp3 (o .m4a / .wav / .ogg) lo reproduce tal cual.
 * 2) Si no, lo dice con la voz del sistema (texto a voz).
 */
object Greeter {
    const val DEFAULT_TEXT = "Bienvenidos Diana y Jahaz, ¿a dónde vamos hoy?"
    private const val KEY_ON = "greet_on"
    private const val KEY_TEXT = "greet_text"

    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null

    // La voz de navegación se mezcla sobre la radio/música en la mayoría de los estéreos.
    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun isOn(ctx: Context) = ThemeManager.prefs(ctx).getBoolean(KEY_ON, true)
    fun setOn(ctx: Context, on: Boolean) = ThemeManager.prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    fun text(ctx: Context): String = ThemeManager.prefs(ctx).getString(KEY_TEXT, null) ?: DEFAULT_TEXT
    fun setText(ctx: Context, t: String) = ThemeManager.prefs(ctx).edit().putString(KEY_TEXT, t).apply()

    fun customAudio(): File? {
        val dir = File(Environment.getExternalStorageDirectory(), "CamryLauncher")
        return listOf("saludo.mp3", "saludo.m4a", "saludo.wav", "saludo.ogg")
            .map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 0 }
    }

    fun speak(ctx: Context, onError: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        stop()
        customAudio()?.let { file ->
            if (playFile(app, file, onError)) return
        }
        speakTts(app, onError)
    }

    private fun playFile(ctx: Context, file: File, onError: ((String) -> Unit)?): Boolean = try {
        requestFocus(ctx)
        val mp = MediaPlayer()
        mp.setAudioAttributes(attrs)
        mp.setDataSource(file.absolutePath)
        mp.setOnCompletionListener { stop(); abandonFocus(ctx) }
        mp.setOnErrorListener { _, _, _ -> stop(); abandonFocus(ctx); onError?.invoke("No pude reproducir ${file.name}"); true }
        mp.prepare()
        mp.start()
        player = mp
        true
    } catch (e: Exception) {
        onError?.invoke("No pude reproducir ${file.name}, uso la voz del sistema")
        false
    }

    private fun speakTts(app: Context, onError: ((String) -> Unit)?) {
        val phrase = text(app)
        var engine: TextToSpeech? = null
        engine = TextToSpeech(app) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                onError?.invoke("El estéreo no tiene motor de voz. Instala \"Speech Services by Google\".")
                t.shutdown()
                return@TextToSpeech
            }
            val mx = t.setLanguage(Locale("es", "MX"))
            if (mx == TextToSpeech.LANG_MISSING_DATA || mx == TextToSpeech.LANG_NOT_SUPPORTED) {
                val es = t.setLanguage(Locale("es", "ES"))
                if (es == TextToSpeech.LANG_MISSING_DATA || es == TextToSpeech.LANG_NOT_SUPPORTED) {
                    onError?.invoke("La voz del estéreo no tiene español. Instala \"Speech Services by Google\" o pon un saludo.mp3.")
                }
            }
            t.setAudioAttributes(attrs)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { abandonFocus(app); t.shutdown(); if (tts === t) tts = null }
                @Deprecated("API antigua")
                override fun onError(utteranceId: String?) {
                    onError?.invoke("La voz falló al hablar")
                    abandonFocus(app); t.shutdown(); if (tts === t) tts = null
                }
            })
            requestFocus(app)
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            val r = t.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "saludo")
            if (r != TextToSpeech.SUCCESS) onError?.invoke("La voz del sistema rechazó el saludo")
        }
        tts = engine
    }

    /** Texto para el diagnóstico en Opciones. */
    fun diagnose(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sb = StringBuilder()
        sb.append("Archivo saludo: ").append(customAudio()?.name ?: "no hay").append('\n')
        sb.append("Volumen media: ").append(am.getStreamVolume(AudioManager.STREAM_MUSIC))
            .append('/').append(am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).append('\n')
        var engine: TextToSpeech? = null
        engine = TextToSpeech(app) { status ->
            val t = engine
            if (t == null || status != TextToSpeech.SUCCESS) {
                sb.append("Motor de voz: NO DISPONIBLE")
            } else {
                sb.append("Motor de voz: ").append(t.defaultEngine ?: "?").append('\n')
                sb.append("Motores: ").append(t.engines.joinToString { it.name }).append('\n')
                fun lang(l: Locale) = when (t.isLanguageAvailable(l)) {
                    TextToSpeech.LANG_MISSING_DATA -> "falta descargar"
                    TextToSpeech.LANG_NOT_SUPPORTED -> "no"
                    else -> "sí"
                }
                sb.append("Español México: ").append(lang(Locale("es", "MX"))).append('\n')
                sb.append("Español: ").append(lang(Locale("es", "ES")))
            }
            t?.shutdown()
            done(sb.toString())
        }
    }

    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build()
        focus = req
        runCatching { am.requestAudioFocus(req) }
    }

    private fun abandonFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focus?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focus = null
    }

    private fun stop() {
        runCatching { player?.release() }
        player = null
        runCatching { tts?.shutdown() }
        tts = null
    }
}
