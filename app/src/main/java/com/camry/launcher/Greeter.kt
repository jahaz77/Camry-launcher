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
 * 1) Si hay audios en CamryLauncher/saludos, reproduce uno al azar.
 * 2) Si no, dice una de las frases (una por línea) con la voz del sistema.
 */
object Greeter {
    const val DEFAULT_TEXT = "{saludo}, Diana y Jahaz, ¿a dónde vamos hoy?"
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
    fun text(ctx: Context): String = ThemeManager.prefs(ctx).getString(KEY_TEXT, null)
        ?.takeIf { it != "Bienvenidos Diana y Jahaz, ¿a dónde vamos hoy?" } ?: DEFAULT_TEXT
    fun setText(ctx: Context, t: String) = ThemeManager.prefs(ctx).edit().putString(KEY_TEXT, t).apply()

    private val AUDIO_EXT = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac")

    fun baseDir(): File = File(Environment.getExternalStorageDirectory(), "CamryLauncher")
    fun greetingsDir(): File = File(baseDir(), "saludos")

    /** Horarios: mañana 5:00–11:59, tarde 12:00–18:59, noche 19:00–4:59. */
    enum class Slot(val folder: String, val label: String, val hello: String) {
        MANANA("manana", "Mañana", "Buenos días"),
        TARDE("tarde", "Tarde", "Buenas tardes"),
        NOCHE("noche", "Noche", "Buenas noches");

        companion object {
            fun now(): Slot {
                val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                return when (h) { in 5..11 -> MANANA; in 12..18 -> TARDE; else -> NOCHE }
            }
        }
    }

    private fun audiosIn(dir: File): List<File> =
        dir.listFiles()?.filter { it.isFile && it.length() > 0 && it.extension.lowercase() in AUDIO_EXT }.orEmpty()

    /** Audios para cualquier hora: CamryLauncher/saludos (y el saludo.mp3 de antes). */
    fun anytimeAudios(): List<File> {
        val legacy = baseDir().listFiles()?.filter { it.isFile && it.nameWithoutExtension.lowercase() == "saludo" && it.extension.lowercase() in AUDIO_EXT }.orEmpty()
        return (audiosIn(greetingsDir()) + legacy).sortedBy { it.name }
    }

    fun slotAudios(slot: Slot): List<File> = audiosIn(File(greetingsDir(), slot.folder)).sortedBy { it.name }

    /** Todos los audios, para la lista en Opciones. */
    fun audioFiles(): List<File> = anytimeAudios() + Slot.values().flatMap { slotAudios(it) }

    /** Etiqueta de horario de un audio. */
    fun slotLabel(f: File): String = Slot.values().firstOrNull { f.parentFile?.name == it.folder }?.label ?: "Cualquier hora"

    /** Elige al azar entre los de este horario y los de cualquier hora, sin repetir el último. */
    private fun pickAudio(ctx: Context): File? {
        val all = slotAudios(Slot.now()) + anytimeAudios()
        if (all.isEmpty()) return null
        val prefs = ThemeManager.prefs(ctx)
        val last = prefs.getString("greet_last", null)
        val pool = if (all.size > 1) all.filter { it.absolutePath != last } else all
        val f = pool.random()
        prefs.edit().putString("greet_last", f.absolutePath).apply()
        return f
    }

    /**
     * Frases para la voz del sistema, una por línea. Se pueden marcar con "mañana:", "tarde:" o "noche:"
     * al inicio para que solo salgan en ese horario; sin marca salen a cualquier hora.
     * {saludo} se cambia por "Buenos días", "Buenas tardes" o "Buenas noches".
     */
    private fun pickPhrase(ctx: Context): String {
        val slot = Slot.now()
        val tags = mapOf("manana" to Slot.MANANA, "tarde" to Slot.TARDE, "noche" to Slot.NOCHE)
        val lines = text(ctx).split('\n').map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { line ->
            val colon = line.indexOf(':')
            val tag = if (colon in 1..8) tags[AppUtils.normalize(line.substring(0, colon).trim())] else null
            when {
                tag == null -> line
                tag == slot -> line.substring(colon + 1).trim()
                else -> null
            }
        }.filter { it.isNotEmpty() }
        val prefs = ThemeManager.prefs(ctx)
        val last = prefs.getString("greet_last_text", null)
        val pool = if (lines.size > 1) lines.filter { it != last } else lines
        val p = pool.randomOrNull() ?: DEFAULT_TEXT
        prefs.edit().putString("greet_last_text", p).apply()
        return p.replace("{saludo}", slot.hello)
    }

    fun speak(ctx: Context, onError: ((String) -> Unit)? = null) {
        val app = ctx.applicationContext
        stop()
        pickAudio(app)?.let { file ->
            if (playFile(app, file, onError)) { EventLog.add(app, "Saludo: reproduciendo ${file.name}"); return }
        }
        EventLog.add(app, "Saludo: usando la voz del sistema")
        speakTts(app, pickPhrase(app), onError, null)
    }

    /** Dice cualquier texto (lo usa el asistente). onDone se llama al terminar o fallar. */
    fun say(ctx: Context, text: String, onError: ((String) -> Unit)? = null, onDone: (() -> Unit)? = null) {
        val app = ctx.applicationContext
        stop()
        speakTts(app, text, onError, onDone)
    }

    fun shutUp() = stop()

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

    private fun speakTts(app: Context, phrase: String, onError: ((String) -> Unit)?, onDone: (() -> Unit)?) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(app) { status ->
            val t = engine ?: return@TextToSpeech
            if (status != TextToSpeech.SUCCESS) {
                onError?.invoke("El estéreo no tiene motor de voz. Instala \"Speech Services by Google\".")
                t.shutdown()
                onDone?.invoke()
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
                override fun onDone(utteranceId: String?) { abandonFocus(app); t.shutdown(); if (tts === t) tts = null; onDone?.invoke() }
                @Deprecated("API antigua")
                override fun onError(utteranceId: String?) {
                    onError?.invoke("La voz falló al hablar")
                    abandonFocus(app); t.shutdown(); if (tts === t) tts = null
                    onDone?.invoke()
                }
            })
            requestFocus(app)
            val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
            val r = t.speak(phrase, TextToSpeech.QUEUE_FLUSH, params, "saludo")
            if (r != TextToSpeech.SUCCESS) { onError?.invoke("La voz del sistema rechazó el texto"); onDone?.invoke() }
        }
        tts = engine
    }

    /** Texto para el diagnóstico en Opciones. */
    fun diagnose(ctx: Context, done: (String) -> Unit) {
        val app = ctx.applicationContext
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val sb = StringBuilder()
        sb.append("Horario actual: ").append(Slot.now().label).append('\n').append("Audios de saludo: ").append(audioFiles().size).append('\n')
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
