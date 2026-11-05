package com.camry.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import java.util.UUID

/**
 * Luces ambientales Bluetooth (controladores ELK-BLEDOM, los que usa la app Lotus).
 * Protocolo: servicio FFF0, característica FFF3, comandos de 9 bytes 7E … EF.
 * Ojo: el controlador acepta UNA conexión a la vez; si la app Lotus del celular está
 * conectada, el estéreo no podrá mandarle colores hasta que se suelte.
 */
object Lights {
    private val SERVICE = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
    private val CHAR = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb")

    private val thread = HandlerThread("luces").apply { start() }
    private val h = Handler(thread.looper)
    @Volatile var lastResult = "sin usar"
        private set

    fun address(ctx: Context): String? = ThemeManager.prefs(ctx).getString("lights_addr", null)
    fun name(ctx: Context): String? = ThemeManager.prefs(ctx).getString("lights_name", null)
    fun isOn(ctx: Context) = ThemeManager.prefs(ctx).getBoolean("lights_on", true)
    fun setOn(ctx: Context, on: Boolean) = ThemeManager.prefs(ctx).edit().putBoolean("lights_on", on).apply()

    private fun log(ctx: Context, msg: String) { lastResult = msg; EventLog.add(ctx, "Luces: $msg") }

    /** Busca controladores ELK-BLEDOM cercanos durante ~8 s. */
    fun scan(ctx: Context, done: (List<Pair<String, String>>) -> Unit) {
        val ad = BluetoothAdapter.getDefaultAdapter()
        val sc = runCatching { ad?.bluetoothLeScanner }.getOrNull()
        if (ad == null || sc == null || !ad.isEnabled) { log(ctx, "Bluetooth de Android apagado o sin BLE"); done(emptyList()); return }
        val found = LinkedHashMap<String, String>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = runCatching { result.device.name }.getOrNull() ?: result.scanRecord?.deviceName ?: return
                val u = n.uppercase()
                if (u.startsWith("ELK") || u.contains("BLEDOM") || u.startsWith("MELK") || u.startsWith("LEDBLE") || u.contains("TRIONES"))
                    found[result.device.address] = n
            }
        }
        runCatching { sc.startScan(cb) }.onFailure { log(ctx, "no pude buscar: ${it.message}"); done(emptyList()); return }
        h.postDelayed({
            runCatching { sc.stopScan(cb) }
            log(ctx, "búsqueda: ${found.size} encontrada(s)")
            done(found.map { it.key to it.value })
        }, 8000L)
    }

    fun choose(ctx: Context, addr: String, name: String) {
        ThemeManager.prefs(ctx).edit().putString("lights_addr", addr).putString("lights_name", name).apply()
    }

    /** Enciende y pone el color/brillo (0-100). */
    fun apply(ctx: Context, color: Int, brightness: Int = 100) {
        if (!isOn(ctx)) { send(ctx, listOf(off())); return }
        send(ctx, listOf(on(), rgb(color), bright(brightness)))
    }

    fun turnOff(ctx: Context) = send(ctx, listOf(off()))

    private fun on() = bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
    private fun off() = bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
    private fun rgb(c: Int) = bytes(0x7E, 0x00, 0x05, 0x03, Color.red(c), Color.green(c), Color.blue(c), 0x00, 0xEF)
    private fun bright(b: Int) = bytes(0x7E, 0x00, 0x01, b.coerceIn(0, 100), 0x00, 0x00, 0x00, 0x00, 0xEF)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Volatile private var busy = false

    private fun send(ctx: Context, cmds: List<ByteArray>, attempt: Int = 1) {
        val app = ctx.applicationContext
        val addr = address(app) ?: run { log(app, "no hay luces elegidas"); return }
        val ad = BluetoothAdapter.getDefaultAdapter() ?: run { log(app, "sin Bluetooth"); return }
        if (busy) { h.postDelayed({ send(app, cmds, attempt) }, 1500L); return }
        busy = true
        val dev: BluetoothDevice = ad.getRemoteDevice(addr)
        var finished = false
        lateinit var gatt: BluetoothGatt
        fun finish(msg: String, retry: Boolean = false) {
            if (finished) return
            finished = true
            h.postDelayed({ runCatching { gatt.disconnect(); gatt.close() }; busy = false
                if (retry && attempt < 3) h.postDelayed({ send(app, cmds, attempt + 1) }, 2000L) }, 400L)
            log(app, msg + if (retry && attempt < 3) " (reintento ${attempt + 1})" else "")
        }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) h.post { g.discoverServices() }
                else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish("desconectado (estado $status)", retry = true)
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch: BluetoothGattCharacteristic? = g.getService(SERVICE)?.getCharacteristic(CHAR)
                    ?: g.services.flatMap { it.characteristics }.firstOrNull {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                    }
                if (ch == null) { finish("no encontré dónde escribir"); return }
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                cmds.forEachIndexed { i, c ->
                    h.postDelayed({ ch.value = c; runCatching { g.writeCharacteristic(ch) } }, 150L * i + 100L)
                }
                h.postDelayed({ finish("color enviado") }, 150L * cmds.size + 600L)
            }
        }
        gatt = dev.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE)
        h.postDelayed({ if (!finished) finish("no respondió (¿la app Lotus está conectada?)", retry = true) }, 12_000L)
    }
}
