package com.camry.launcher

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ventana del asistente: escucha, pregunta a Claude, ejecuta lo que pidas y responde con voz.
 * Se abre con el micrófono del launcher, el botón de voz del volante o como "asistente" del sistema.
 */
class AssistantActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var skin: LauncherTheme
    private var s = 1f
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private lateinit var answer: TextView
    private var themeChanged = false
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skin = ThemeManager.current(this)
        val dm = resources.displayMetrics
        s = min(maxOf(dm.widthPixels, dm.heightPixels) / 1024f, min(dm.widthPixels, dm.heightPixels) / 600f)
        setContentView(buildUi())
        if (Assistant.apiKey(this).isBlank()) {
            show("Falta la llave", "", "Pega tu llave de la API de Claude en Opciones del launcher (botón de la paleta).")
            return
        }
        listen()
    }

    private fun px(v: Float) = (v * s).roundToInt()

    private fun text(size: Float, color: Int, medium: Boolean = false) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
        setTextColor(color)
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(0f, 1.2f)
    }

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            setOnClickListener { close() }
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply { cornerRadius = 24f * s; setColor(skin.card) }
            setPadding(px(28f), px(24f), px(28f), px(24f))
            isClickable = true
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val mic = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(skin.accent) }
            setOnClickListener { if (!busy) listen() }
        }
        mic.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_mic)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
        }, FrameLayout.LayoutParams(px(30f), px(30f), Gravity.CENTER))
        top.addView(mic, LinearLayout.LayoutParams(px(64f), px(64f)))
        status = text(22f, skin.text, medium = true).apply { setPadding(px(18f), 0, 0, 0) }
        top.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val closeBtn = text(16f, skin.textDim).apply {
            text = "Cerrar"
            gravity = Gravity.CENTER
            setPadding(px(16f), px(12f), px(16f), px(12f))
            setOnClickListener { close() }
        }
        top.addView(closeBtn)
        panel.addView(top)
        heard = text(17f, skin.textDim).apply { setPadding(0, px(18f), 0, 0) }
        answer = text(22f, skin.text).apply { setPadding(0, px(10f), 0, 0) }
        panel.addView(heard)
        panel.addView(answer)
        root.addView(panel, FrameLayout.LayoutParams(px(760f), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        return root
    }

    private fun show(st: String, h: String, a: String) {
        status.text = st
        heard.text = h
        heard.visibility = if (h.isBlank()) View.GONE else View.VISIBLE
        answer.text = a
        answer.visibility = if (a.isBlank()) View.GONE else View.VISIBLE
    }

    private fun listen() {
        Greeter.shutUp()
        show("Te escucho…", "", "")
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "¿En qué te ayudo?")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_SPEECH)
        } catch (e: ActivityNotFoundException) {
            show("Sin reconocimiento de voz", "", "El estéreo no tiene reconocimiento de voz. Instala la app de Google desde Play Store.")
        }
    }

    @Deprecated("API clásica de resultados")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SPEECH) return
        val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode != RESULT_OK || said.isNullOrBlank()) {
            show("No te escuché", "", "Toca el micrófono para intentar de nuevo.")
            return
        }
        think(said)
    }

    private fun think(question: String) {
        busy = true
        show("Pensando…", "“$question”", "")
        val app = applicationContext
        Thread {
            val reply = Assistant.ask(app, question) { name, input -> runOnMain { runTool(name, input) } }
            handler.post { speakReply(question, reply) }
        }.start()
    }

    /** Ejecuta una herramienta en el hilo principal y espera el resultado. */
    private fun runOnMain(block: () -> String): String {
        var out = ""
        val latch = CountDownLatch(1)
        handler.post {
            out = runCatching(block).getOrElse { "Error: ${it.message}" }
            latch.countDown()
        }
        latch.await()
        return out
    }

    private fun runTool(name: String, input: JSONObject): String = when (name) {
        "navegar" -> CarActions.navigate(this, input.optString("destino"))
        "musica" -> CarActions.media(this, input.optString("accion"))
        "abrir_app" -> CarActions.openApp(this, input.optString("app"))
        "cambiar_tema" -> CarActions.setTheme(this, input.optString("tema")).also { themeChanged = true }
        else -> "Herramienta desconocida"
    }

    private fun speakReply(question: String, reply: String) {
        show("Asistente", "“$question”", reply)
        Greeter.say(this, reply, onError = { msg -> handler.post { answer.text = "$reply\n\n($msg)" } }) {
            handler.post {
                busy = false
                status.text = "Toca el micrófono para seguir"
                handler.postDelayed({ if (!busy) close() }, 6000L)
            }
        }
    }

    private fun close() {
        Greeter.shutUp()
        handler.removeCallbacksAndMessages(null)
        if (themeChanged) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val REQ_SPEECH = 11
    }
}
