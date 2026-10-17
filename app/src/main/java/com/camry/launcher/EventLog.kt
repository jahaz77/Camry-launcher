package com.camry.launcher

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registro corto de eventos (encendido, saludo, red) para el diagnóstico en Opciones. */
object EventLog {
    private const val KEY = "event_log"
    private const val MAX = 25

    fun add(ctx: Context, msg: String) {
        val p = ThemeManager.prefs(ctx)
        val line = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        val lines = (p.getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }.toMutableList()
        lines.add(line)
        while (lines.size > MAX) lines.removeAt(0)
        p.edit().putString(KEY, lines.joinToString("\n")).apply()
    }

    fun text(ctx: Context): String =
        (ThemeManager.prefs(ctx).getString(KEY, "") ?: "").split('\n').filter { it.isNotBlank() }.reversed().joinToString("\n")
            .ifBlank { "(sin eventos todavía)" }
}
