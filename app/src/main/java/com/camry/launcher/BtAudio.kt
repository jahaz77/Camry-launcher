package com.camry.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper

/**
 * Activa la música Bluetooth SIN abrir su pantalla:
 *  1) si ya hay una sesión de música Bluetooth activa, le manda "play";
 *  2) si no, se conecta en segundo plano al servicio de música del estéreo (MediaBrowser),
 *     igual que Android Auto, y le manda "play".
 * Si nada de eso funciona, avisa para usar el método anterior (abrir la app un instante).
 */
object BtAudio {
    private val handler = Handler(Looper.getMainLooper())
    private var browser: MediaBrowser? = null

    private fun looksBluetooth(ctx: Context, pkg: String, label: String?): Boolean {
        val p = pkg.lowercase()
        val l = AppUtils.normalize(label ?: "")
        return p.contains(".bt") || p.contains("bluetooth") || p.contains("a2dp") || p.endsWith("bt") ||
            l.contains("bluetooth") || l.contains("bt music") || l.contains("musica bt") || l.contains("a2dp")
    }

    private fun label(ctx: Context, pkg: String): String? = runCatching {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()

    /** Servicios de música que el estéreo publica (para el diagnóstico). */
    fun browserServices(ctx: Context): List<ComponentName> = runCatching {
        ctx.packageManager.queryIntentServices(Intent("android.media.browse.MediaBrowserService"), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    }.getOrDefault(emptyList())

    /** La app de música Bluetooth: la que elegiste en la barra (Música) o la que lo parezca. */
    private fun btPackage(ctx: Context): String? {
        ThemeManager.prefs(ctx).getString("slot_music", null)?.let { ComponentName.unflattenFromString(it)?.packageName }?.let { return it }
        return null
    }

    fun activate(ctx: Context, done: (ok: Boolean, how: String) -> Unit) {
        val app = ctx.applicationContext
        val chosen = btPackage(app)

        // 1) Sesión ya activa
        val sessions = runCatching {
            (app.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(app, MediaListener::class.java))
        }.getOrNull().orEmpty()
        val session = sessions.firstOrNull { it.packageName == chosen }
            ?: sessions.firstOrNull { looksBluetooth(app, it.packageName, label(app, it.packageName)) }
        if (session != null) {
            session.transportControls.play()
            done(true, "play a la sesión de ${session.packageName}")
            return
        }

        // 2) Servicio de música en segundo plano
        val services = browserServices(app)
        val target = services.firstOrNull { it.packageName == chosen }
            ?: services.firstOrNull { looksBluetooth(app, it.packageName, label(app, it.packageName)) }
        if (target == null) {
            done(false, "no encontré servicio de música Bluetooth (hay: ${services.joinToString { it.packageName }.ifEmpty { "ninguno" }})")
            return
        }
        var finished = false
        val timeout = Runnable {
            if (!finished) { finished = true; runCatching { browser?.disconnect() }; done(false, "el servicio ${target.packageName} no respondió") }
        }
        runCatching { browser?.disconnect() }
        val b = MediaBrowser(app, target, object : MediaBrowser.ConnectionCallback() {
            override fun onConnected() {
                val br = browser ?: return
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
                runCatching {
                    MediaController(app, br.sessionToken).transportControls.play()
                }.onSuccess {
                    done(true, "play en segundo plano a ${target.packageName}")
                }.onFailure {
                    done(false, "falló el play: ${it.message}")
                }
                handler.postDelayed({ runCatching { br.disconnect() } }, 5000L)
            }
            override fun onConnectionFailed() {
                if (finished) return
                finished = true
                handler.removeCallbacks(timeout)
                done(false, "el servicio ${target.packageName} rechazó la conexión")
            }
        }, null)
        browser = b
        handler.postDelayed(timeout, 5000L)
        runCatching { b.connect() }.onFailure {
            if (!finished) { finished = true; handler.removeCallbacks(timeout); done(false, "no pude conectar: ${it.message}") }
        }
    }
}
