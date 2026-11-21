package com.camry.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detector de congelamientos: si la pantalla (hilo principal) deja de responder más de 6 s,
 * guarda qué estaba haciendo para poder arreglarlo. Se ve en Diagnóstico → "ÚLTIMO CONGELAMIENTO".
 */
object Watchdog {
    private const val LIMIT_MS = 6000L
    @Volatile private var pong = 0L
    @Volatile private var started = false

    fun start(ctx: Context) {
        if (started) return
        started = true
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        pong = SystemClock.uptimeMillis()
        Thread({
            var reported = false
            while (true) {
                main.post { pong = SystemClock.uptimeMillis() }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { return@Thread }
                val late = SystemClock.uptimeMillis() - pong
                if (late > LIMIT_MS && !reported) {
                    reported = true
                    runCatching {
                        val st = Looper.getMainLooper().thread.stackTrace
                            .filter { !it.className.startsWith("java.lang.Thread") }
                            .take(14).joinToString("\n") { "  at ${it.className.substringAfterLast('.')}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
                        val txt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date()) +
                            " · la pantalla no respondió ${late / 1000} s · v${BuildConfigVersion.name(app)}\n$st"
                        ThemeManager.prefs(app).edit().putString("last_freeze", txt).apply()
                        EventLog.add(app, "Congelamiento de ${late / 1000} s (ver Diagnóstico)")
                    }
                } else if (late < LIMIT_MS) reported = false
            }
        }, "vigilante").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    fun last(ctx: Context): String? = ThemeManager.prefs(ctx).getString("last_freeze", null)
}
