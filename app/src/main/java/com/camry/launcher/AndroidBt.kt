package com.camry.launcher

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.SystemClock

/**
 * El Bluetooth "de Android" (el que usan el OBD2 y las luces) es distinto del Bluetooth del estéreo
 * para el celular. Tu estéreo lo deja apagado al arrancar desde cero, así que lo prendemos cuando hace falta.
 */
object AndroidBt {
    @Volatile private var lastTry = 0L

    fun isOn(): Boolean = runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)

    /**
     * OJO: en tu estéreo prender el Bluetooth de Android por código hizo que se reiniciara completo
     * (choca con el Bluetooth del estéreo). Por eso ya NO se prende solo: solo si lo activas tú en Opciones.
     */
    fun autoAllowed(ctx: Context) = ThemeManager.prefs(ctx).getBoolean("android_bt_auto", false)
    fun setAutoAllowed(ctx: Context, on: Boolean) = ThemeManager.prefs(ctx).edit().putBoolean("android_bt_auto", on).apply()

    /** Una sola vez: apaga el Bluetooth de Android que encendió la versión 32. */
    fun undoV32(ctx: Context) {
        val p = ThemeManager.prefs(ctx)
        if (p.getBoolean("bt_v32_undone", false)) return
        p.edit().putBoolean("bt_v32_undone", true).apply()
        if (autoAllowed(ctx)) return
        val ad = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return
        if (ad.isEnabled) {
            val ok = runCatching { @Suppress("DEPRECATION") ad.disable() }.getOrDefault(false)
            EventLog.add(ctx, "Apagué el Bluetooth de Android que encendió la versión anterior: " + if (ok) "OK" else "no me dejó")
        }
    }

    /** Lo prende si está apagado (solo si lo permitiste) y espera hasta [waitMs]. Devuelve true si quedó prendido. */
    fun ensureOn(ctx: Context, why: String, waitMs: Long = 0L): Boolean {
        val ad = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return false
        if (ad.isEnabled) return true
        if (!autoAllowed(ctx)) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastTry > 30_000L) {
            lastTry = now
            val ok = runCatching { @Suppress("DEPRECATION") ad.enable() }.getOrDefault(false)
            EventLog.add(ctx, "Bluetooth de Android apagado: " + (if (ok) "lo encendí" else "no me dejó encenderlo") + " ($why)")
        }
        var waited = 0L
        while (!ad.isEnabled && waited < waitMs) { SystemClock.sleep(300); waited += 300 }
        return ad.isEnabled
    }

    /** ¿Hay algo que necesite el Bluetooth de Android (OBD2 o luces)? */
    fun needed(ctx: Context): Boolean =
        !Obd.address(ctx).isNullOrBlank() || Lights.address(ctx) != null
}
