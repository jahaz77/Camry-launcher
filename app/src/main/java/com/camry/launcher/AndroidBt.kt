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

    /** Lo prende si está apagado y espera hasta [waitMs]. Devuelve true si quedó prendido. */
    fun ensureOn(ctx: Context, why: String, waitMs: Long = 0L): Boolean {
        val ad = runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull() ?: return false
        if (ad.isEnabled) return true
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
