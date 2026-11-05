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

    // ------------------------------------------------------------------ efectos

    data class Effect(val code: Int, val name: String)
    /** code 0 = color fijo; -1 = respirar con el color del perfil (el más parecido que trae el controlador). */
    val EFFECTS = listOf(
        Effect(0, "Color fijo"),
        Effect(-1, "Respirar (color del perfil)"),
        Effect(0x8A, "Degradado arcoíris"),
        Effect(0x89, "Degradado rojo, verde y azul"),
        Effect(0x93, "Degradado rojo y azul"),
        Effect(0x94, "Degradado verde y azul"),
        Effect(0x92, "Degradado rojo y verde"),
        Effect(0x88, "Saltos de 7 colores"),
        Effect(0x87, "Saltos rojo, verde y azul"),
        Effect(0x95, "Destellos de colores")
    )
    fun effectName(code: Int) = EFFECTS.firstOrNull { it.code == code }?.name ?: "Color fijo"

    private fun mode(code: Int) = bytes(0x7E, 0x00, 0x03, code, 0x03, 0x00, 0x00, 0x00, 0xEF)
    /** Velocidad del efecto, 0-100. */
    private fun speed(v: Int) = bytes(0x7E, 0x00, 0x02, v.coerceIn(0, 100), 0x00, 0x00, 0x00, 0x00, 0xEF)

    /** Código de "respirar" del controlador más parecido al color (solo trae 7 colores base). */
    private fun breathCode(c: Int): Int {
        val hsv = FloatArray(3); Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.25f) return 0x91                       // blanco
        val h = hsv[0]
        return when {
            h < 25 || h >= 345 -> 0x8B                        // rojo
            h < 75 -> 0x8E                                    // amarillo
            h < 160 -> 0x8C                                   // verde
            h < 200 -> 0x8F                                   // cian
            h < 265 -> 0x8D                                   // azul
            else -> 0x90                                      // magenta / rosa
        }
    }

    /** Pone las luces como dice el perfil (color o efecto), salvo que haya un aviso del motor. */
    fun applyProfile(ctx: Context, p: Profile) {
        if (!isOn(ctx)) { send(ctx, listOf(off())); return }
        alert?.let { showAlert(ctx, it); return }
        when (p.effect) {
            0 -> send(ctx, listOf(on(), rgb(p.lightColor), bright(p.brightness)))
            -1 -> send(ctx, listOf(on(), bright(p.brightness), mode(breathCode(p.lightColor)), speed(p.speed)))
            else -> send(ctx, listOf(on(), bright(p.brightness), mode(p.effect), speed(p.speed)))
        }
    }

    // ------------------------------------------------------------------ avisos del motor

    enum class Alert(val label: String) { CALIENTE("motor caliente"), VOLTAJE("voltaje bajo"), CHECK("check engine") }
    @Volatile var alert: Alert? = null
        private set

    fun alertsOn(ctx: Context) = ThemeManager.prefs(ctx).getBoolean("lights_alerts", true)
    fun setAlertsOn(ctx: Context, on: Boolean) = ThemeManager.prefs(ctx).edit().putBoolean("lights_alerts", on).apply()

    private fun showAlert(ctx: Context, a: Alert) = when (a) {
        Alert.CALIENTE -> send(ctx, listOf(on(), bright(100), mode(0x96), speed(70)))            // rojo parpadeando
        Alert.VOLTAJE -> send(ctx, listOf(on(), bright(100), mode(0x99), speed(40)))             // amarillo parpadeando
        Alert.CHECK -> send(ctx, listOf(on(), rgb(Color.rgb(255, 120, 0)), bright(90)))          // ámbar fijo
    }

    /** Lo llama el OBD2 con el estado actual; solo manda algo si cambia. */
    fun setAlert(ctx: Context, a: Alert?) {
        val app = ctx.applicationContext
        val want = if (alertsOn(app)) a else null
        if (want == alert) return
        alert = want
        if (address(app) == null) return
        EventLog.add(app, "Luces: " + (want?.let { "aviso de ${it.label}" } ?: "aviso terminado, vuelve el color del perfil"))
        if (want != null) showAlert(app, want)
        else (Profiles.active(app) ?: Profiles.all(app).first()).let { applyProfile(app, it) }
    }

    private fun on() = bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF)
    private fun off() = bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF)
    private fun rgb(c: Int) = bytes(0x7E, 0x00, 0x05, 0x03, Color.red(c), Color.green(c), Color.blue(c), 0x00, 0xEF)
    private fun bright(b: Int) = bytes(0x7E, 0x00, 0x01, b.coerceIn(0, 100), 0x00, 0x00, 0x00, 0x00, 0xEF)
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Volatile private var busy = false

    /** Todo lo de Bluetooth corre en su propio hilo y nunca debe tumbar la app. */
    private fun send(ctx: Context, cmds: List<ByteArray>, attempt: Int = 1) {
        val app = ctx.applicationContext
        h.post {
            try { sendNow(app, cmds, attempt) } catch (e: Throwable) {
                busy = false
                log(app, "error ${e.javaClass.simpleName}: ${e.message ?: ""}")
            }
        }
    }

    private fun sendNow(app: Context, cmds: List<ByteArray>, attempt: Int) {
        val addr = address(app) ?: run { log(app, "no hay luces elegidas"); return }
        val ad = BluetoothAdapter.getDefaultAdapter() ?: run { log(app, "sin Bluetooth"); return }
        if (!ad.isEnabled) { log(app, "el Bluetooth de Android está apagado"); return }
        if (busy) { h.postDelayed({ send(app, cmds, attempt) }, 1500L); return }
        busy = true
        val dev: BluetoothDevice = ad.getRemoteDevice(addr)
        var finished = false
        var gatt: BluetoothGatt? = null
        fun finish(msg: String, retry: Boolean = false) {
            if (finished) return
            finished = true
            h.postDelayed({
                runCatching { gatt?.disconnect() }; runCatching { gatt?.close() }
                busy = false
                if (retry && attempt < 3) h.postDelayed({ send(app, cmds, attempt + 1) }, 2000L)
            }, 400L)
            log(app, msg + if (retry && attempt < 3) " (reintento ${attempt + 1})" else "")
        }
        val cb = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                h.post {
                    if (newState == BluetoothProfile.STATE_CONNECTED) runCatching { g.discoverServices() }
                    else if (newState == BluetoothProfile.STATE_DISCONNECTED) finish("desconectado (estado $status)", retry = true)
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                h.post {
                    val ch: BluetoothGattCharacteristic? = runCatching {
                        g.getService(SERVICE)?.getCharacteristic(CHAR)
                            ?: g.services.flatMap { it.characteristics }.firstOrNull {
                                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                            }
                    }.getOrNull()
                    if (ch == null) { finish("no encontré dónde escribir"); return@post }
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    cmds.forEachIndexed { i, c ->
                        h.postDelayed({ runCatching { ch.value = c; g.writeCharacteristic(ch) } }, 150L * i + 100L)
                    }
                    h.postDelayed({ finish("color enviado") }, 150L * cmds.size + 600L)
                }
            }
        }
        gatt = dev.connectGatt(app, false, cb, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) { finish("no pude conectar (Bluetooth de Android no disponible)"); return }
        h.postDelayed({ if (!finished) finish("no respondió (¿la app Lotus está conectada?)", retry = true) }, 12_000L)
    }
}
