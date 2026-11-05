package com.camry.launcher

import android.content.Context
import android.net.Uri
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * El iPhone se conecta al Bluetooth PROPIO del estéreo (el de llamadas y música), que Android no ve.
 * Para saber qué teléfono está conectado se buscan pistas donde ese Bluetooth deja el nombre:
 * sus notificaciones, los ajustes del sistema y las propiedades del sistema.
 */
object StereoBt {

    data class Clue(val source: String, val text: String)

    private fun btish(s: String): Boolean {
        val n = s.lowercase()
        return n.contains("bt") || n.contains("blue") || n.contains("phone") || n.contains("dial") || n.contains("hfp") || n.contains("a2dp") || n.contains("tel")
    }

    private fun meaningful(v: String?): Boolean {
        val t = v?.trim() ?: return false
        if (t.length < 3 || t.length > 80) return false
        if (t.matches(Regex("[-0-9.,:xA-Fa-f ]+"))) return false        // números, direcciones MAC
        return t.lowercase() !in setOf("true", "false", "null", "none", "off", "on", "unknown")
    }

    /** Notificaciones de las apps de Bluetooth/teléfono del estéreo. */
    fun notificationClues(ctx: Context): List<Clue> {
        val sbn: Array<StatusBarNotification> = runCatching { MediaListener.instance?.activeNotifications }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Clue>()
        for (n in sbn) {
            if (n.packageName == ctx.packageName) continue
            val label = runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(n.packageName, 0)).toString() }.getOrDefault("")
            if (!btish(n.packageName) && !btish(label)) continue
            val ex = n.notification.extras
            for (k in listOf("android.title", "android.text", "android.subText", "android.bigText", "android.infoText")) {
                val v = ex?.getCharSequence(k)?.toString()
                if (meaningful(v)) out.add(Clue("notificación de ${label.ifBlank { n.packageName }}", v!!.trim()))
            }
        }
        return out
    }

    private fun settingsClues(ctx: Context, table: String): List<Clue> = runCatching {
        val out = mutableListOf<Clue>()
        ctx.contentResolver.query(Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val k = c.getString(0) ?: continue
                val v = c.getString(1)
                if (btish(k) && meaningful(v) && !k.contains("address", true)) out.add(Clue("ajuste $table/$k", v!!.trim()))
            }
        }
        out
    }.getOrDefault(emptyList())

    private fun propClues(): List<Clue> = runCatching {
        val out = mutableListOf<Clue>()
        val p = Runtime.getRuntime().exec("getprop")
        p.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) {
                val m = Regex("^\\[(.+?)]: \\[(.*)]$").find(line) ?: continue
                val k = m.groupValues[1]; val v = m.groupValues[2]
                if (btish(k) && meaningful(v) && !k.contains("version") && !k.contains("addr")) out.add(Clue("propiedad $k", v.trim()))
            }
        }
        out
    }.getOrDefault(emptyList())

    /** Todas las pistas que hay ahora mismo. */
    fun clues(ctx: Context): List<Clue> =
        notificationClues(ctx) + settingsClues(ctx, "system") + settingsClues(ctx, "global") + settingsClues(ctx, "secure") + propClues()

    /** ¿El teléfono con este nombre aparece en alguna pista? */
    fun mentions(clues: List<Clue>, phoneName: String): Boolean {
        val want = AppUtils.normalize(phoneName)
        if (want.length < 3) return false
        return clues.any { AppUtils.normalize(it.text).contains(want) }
    }

    /** Texto para Diagnóstico. */
    fun report(ctx: Context): String {
        val all = clues(ctx)
        val notif = runCatching { MediaListener.instance?.activeNotifications?.map { it.packageName }?.distinct() }.getOrNull()
        return (if (MediaListener.instance == null) "Lector de notificaciones: NO conectado\n" else "Notificaciones activas de: " + (notif?.joinToString().orEmpty().ifEmpty { "ninguna" }) + "\n") +
            (if (all.isEmpty()) "Sin pistas del Bluetooth del estéreo" else all.joinToString("\n") { "• ${it.text}  (${it.source})" })
    }
}

/** Lector de notificaciones: permite ver la música y las notificaciones del Bluetooth del estéreo. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.packageName == packageName) return
        val pkg = sbn.packageName.lowercase()
        if (!(pkg.contains("bt") || pkg.contains("blue") || pkg.contains("phone") || pkg.contains("dial"))) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastCheck < 5000L) return
        lastCheck = now
        // Cuando el Bluetooth del estéreo avisa algo (p. ej. teléfono conectado), revisar perfiles
        val app = applicationContext
        Thread { runCatching { Profiles.detect(app, "aviso del Bluetooth del estéreo") } }.start()
    }
    companion object {
        @Volatile var instance: MediaListener? = null
        @Volatile private var lastCheck = 0L
    }
}
