package com.camry.launcher

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Si la app se cierra por un error, guarda el motivo para verlo después (Diagnóstico) y poder arreglarlo. */
object CrashLog {
    @Volatile private var installed = false

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
                val txt = "$stamp · hilo ${t.name} · v${BuildConfigVersion.name(app)}\n" + sw.toString().lines().take(30).joinToString("\n")
                ThemeManager.prefs(app).edit().putString("last_crash", txt).putBoolean("last_crash_seen", false).commit()
                runCatching { File(android.os.Environment.getExternalStorageDirectory(), "CamryLauncher/errores.txt").appendText(txt + "\n\n") }
            }
            prev?.uncaughtException(t, e)
        }
    }

    fun last(ctx: Context): String? = ThemeManager.prefs(ctx).getString("last_crash", null)
    fun unseen(ctx: Context): String? = if (ThemeManager.prefs(ctx).getBoolean("last_crash_seen", true)) null else last(ctx)
    fun markSeen(ctx: Context) = ThemeManager.prefs(ctx).edit().putBoolean("last_crash_seen", true).apply()
}

object BuildConfigVersion {
    fun name(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
}
