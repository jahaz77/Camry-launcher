package com.camry.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Cliente para adaptadores OBD2 ELM327 por Bluetooth clásico (SPP).
 * Lee los datos del motor en un hilo aparte y avisa a quien escuche.
 */
object Obd {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    data class Snapshot(
        val connected: Boolean = false,
        val status: String = "OBD2 sin conectar",
        val rpm: Int? = null,
        val speed: Int? = null,
        val coolant: Int? = null,
        val load: Int? = null,
        val throttle: Int? = null,
        val intake: Int? = null,
        val maf: Float? = null,
        val volts: Float? = null,
        val runtime: Int? = null,
        val kmpl: Float? = null,
        val avgKmpl: Float? = null,
        val dtcCount: Int? = null,
        val dtcCheckedAt: Long = 0L
    )

    @Volatile var snap = Snapshot()
        private set

    private val listeners = CopyOnWriteArraySet<(Snapshot) -> Unit>()
    fun listen(l: (Snapshot) -> Unit) { listeners.add(l); l(snap) }
    fun unlisten(l: (Snapshot) -> Unit) { listeners.remove(l) }

    /** Historial corto para las mini gráficas. */
    val history = HashMap<String, ArrayDeque<Float>>()
    private fun push(key: String, v: Float?) {
        if (v == null) return
        val d = synchronized(history) { history.getOrPut(key) { ArrayDeque() } }
        synchronized(d) { d.addLast(v); while (d.size > 40) d.removeFirst() }
    }
    fun series(key: String): List<Float> = synchronized(history) { history[key] }?.let { synchronized(it) { it.toList() } } ?: emptyList()

    private fun publish(s: Snapshot) {
        snap = s
        CarState.coolant = s.coolant
        listeners.forEach { runCatching { it(s) } }
    }

    fun address(ctx: Context): String? = ThemeManager.prefs(ctx).getString("obd_addr", null)
    fun setAddress(ctx: Context, addr: String?) {
        ThemeManager.prefs(ctx).edit().putString("obd_addr", addr).apply()
        socket?.let { runCatching { it.close() } }
    }

    /** Adaptadores emparejados, para elegir en Opciones. */
    fun bonded(): List<BluetoothDevice> = runCatching {
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty()
            .sortedByDescending { (it.name ?: "").uppercase().let { n -> n.contains("OBD") || n.contains("ELM") || n.contains("V-LINK") } }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ hilo

    @Volatile private var thread: Thread? = null
    @Volatile private var socket: BluetoothSocket? = null
    private val jobs = LinkedBlockingQueue<Pair<String, (String?) -> Unit>>()
    private lateinit var app: Context

    fun start(ctx: Context) {
        app = ctx.applicationContext
        if (thread?.isAlive == true) return
        thread = Thread({ loop() }, "obd").apply { isDaemon = true; start() }
    }

    /** Lee los códigos de falla (modo 03). */
    fun readDtc(done: (List<String>?) -> Unit) {
        if (!snap.connected) { done(null); return }
        jobs.offer("03" to { r ->
            val codes = r?.let { parseDtc(it) }
            if (codes != null) publish(snap.copy(dtcCount = codes.size, dtcCheckedAt = System.currentTimeMillis()))
            done(codes)
        })
    }

    /** Borra los códigos y apaga el check engine (modo 04). */
    fun clearDtc(done: (Boolean) -> Unit) {
        if (!snap.connected) { done(false); return }
        jobs.offer("04" to { r ->
            val ok = r != null && r.contains("44")
            if (ok) publish(snap.copy(dtcCount = 0, dtcCheckedAt = System.currentTimeMillis()))
            done(ok)
        })
    }

    private fun loop() {
        while (true) {
            val addr = address(app)
            if (addr.isNullOrBlank()) {
                publish(Snapshot(status = "Elige tu adaptador en Opciones"))
                SystemClock.sleep(5000); continue
            }
            try {
                publish(snap.copy(connected = false, status = "Conectando al OBD2…"))
                val s = connect(addr)
                socket = s
                session(s.inputStream, s.outputStream)
            } catch (e: Exception) {
                EventLog.add(app, "OBD2: ${e.javaClass.simpleName} ${e.message ?: ""}".trim())
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            publish(Snapshot(status = "OBD2 sin conectar · reintentando"))
            SystemClock.sleep(10_000)
        }
    }

    private fun connect(addr: String): BluetoothSocket {
        val ad = BluetoothAdapter.getDefaultAdapter() ?: throw IllegalStateException("sin Bluetooth")
        val dev = ad.getRemoteDevice(addr)
        runCatching { ad.cancelDiscovery() }
        val tries = listOf<() -> BluetoothSocket>(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket }
        )
        var last: Exception? = null
        for (make in tries) {
            try {
                val s = make()
                s.connect()
                return s
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("no conecta")
    }

    private fun session(inp: InputStream, out: OutputStream) {
        fun cmd(c: String, timeout: Long = 2500L): String? {
            out.write((c + "\r").toByteArray()); out.flush()
            val sb = StringBuilder()
            val end = SystemClock.uptimeMillis() + timeout
            while (SystemClock.uptimeMillis() < end) {
                if (inp.available() > 0) {
                    val ch = inp.read()
                    if (ch < 0) throw java.io.IOException("cerrado")
                    if (ch.toChar() == '>') return sb.toString()
                    sb.append(ch.toChar())
                } else SystemClock.sleep(10)
            }
            return null
        }
        cmd("ATZ", 5000); cmd("ATE0"); cmd("ATL0"); cmd("ATS0"); cmd("ATH0"); cmd("ATSP0")
        val probe = cmd("0100", 8000) ?: throw java.io.IOException("el carro no responde (¿llave puesta?)")
        if (!clean(probe).contains("4100")) throw java.io.IOException("respuesta rara: ${clean(probe).take(20)}")
        EventLog.add(app, "OBD2 conectado")
        var tick = 0
        var lastT = SystemClock.elapsedRealtime()
        var dist = 0.0
        var fuel = 0.0
        var s = Snapshot(connected = true, status = "OBD2 conectado")
        while (true) {
            // trabajos pedidos (leer/borrar códigos)
            jobs.poll(0, TimeUnit.MILLISECONDS)?.let { (c, cb) -> runCatching { cb(cmd(c, 6000)) } }

            val rpm = pid(cmd("010C"), "0C")?.let { ((it[0] * 256) + it[1]) / 4 }
            val spd = pid(cmd("010D"), "0D")?.let { it[0] }
            if (rpm == null && spd == null && tick > 2) throw java.io.IOException("el carro dejó de responder")
            var maf = s.maf
            if (tick % 2 == 0) maf = pid(cmd("0110"), "10")?.let { ((it[0] * 256) + it[1]) / 100f } ?: maf
            var cool = s.coolant; var load = s.load; var thr = s.throttle; var iat = s.intake; var volts = s.volts; var run = s.runtime
            if (tick % 5 == 0) {
                cool = pid(cmd("0105"), "05")?.let { it[0] - 40 } ?: cool
                load = pid(cmd("0104"), "04")?.let { it[0] * 100 / 255 } ?: load
                thr = pid(cmd("0111"), "11")?.let { it[0] * 100 / 255 } ?: thr
            }
            if (tick % 10 == 0) {
                iat = pid(cmd("010F"), "0F")?.let { it[0] - 40 } ?: iat
                run = pid(cmd("011F"), "1F")?.let { it[0] * 256 + it[1] } ?: run
                volts = cmd("ATRV")?.let { Regex("(\\d+(\\.\\d+)?)").find(it)?.value?.toFloatOrNull() } ?: volts
            }
            // consumo: gasolina ≈ 14.7 g de aire por g de gasolina, 745 g por litro
            val now = SystemClock.elapsedRealtime()
            val dtH = (now - lastT) / 3_600_000.0
            lastT = now
            val lph = maf?.let { it * 3600.0 / (14.7 * 745.0) }
            if (spd != null) dist += spd * dtH
            if (lph != null) fuel += lph * dtH
            runCatching { TripLog.obd(app, spd, lph, dtH) }
            val inst = if (lph != null && spd != null && spd > 3 && lph > 0.05) (spd / lph).coerceAtMost(60.0).toFloat() else null
            val avg = if (fuel > 0.01) (dist / fuel).toFloat() else null

            s = s.copy(rpm = rpm ?: s.rpm, speed = spd ?: s.speed, maf = maf, coolant = cool, load = load, throttle = thr,
                intake = iat, volts = volts, runtime = run, kmpl = inst, avgKmpl = avg)
            push("kmpl", inst); push("avg", avg); push("load", load?.toFloat()); push("thr", thr?.toFloat())
            push("iat", iat?.toFloat()); push("run", run?.toFloat())
            publish(s)
            if (tick == 3) jobs.offer("03" to { r -> r?.let { parseDtc(it) }?.let { publish(snap.copy(dtcCount = it.size, dtcCheckedAt = System.currentTimeMillis())) } })
            alerts(s)
            tick++
            SystemClock.sleep(250)
        }
    }

    // ------------------------------------------------------------------ alertas con voz

    private var lastHotAlert = 0L
    private var lastVoltAlert = 0L

    private fun alerts(s: Snapshot) {
        val now = SystemClock.elapsedRealtime()
        val t = s.coolant
        if (t != null && t >= 105 && now - lastHotAlert > 10 * 60_000L) {
            lastHotAlert = now
            EventLog.add(app, "Alerta: motor a $t °C")
            Greeter.say(app, "Atención: el motor está a $t grados. Considera orillarte y revisar el anticongelante.")
        }
        val v = s.volts
        if (v != null && v < 12.2f && (s.rpm ?: 0) > 600 && now - lastVoltAlert > 15 * 60_000L) {
            lastVoltAlert = now
            EventLog.add(app, "Alerta: voltaje bajo $v V con motor encendido")
            Greeter.say(app, "El voltaje está bajo con el motor encendido. Puede ser el alternador; revísalo pronto.")
        }
    }

    // ------------------------------------------------------------------ análisis de respuestas

    private fun clean(r: String) = r.uppercase().replace(Regex("[^0-9A-F]"), "")

    /** Devuelve los bytes de datos de una respuesta "41 xx ..". */
    private fun pid(r: String?, code: String): IntArray? {
        if (r == null) return null
        val c = clean(r)
        val i = c.indexOf("41$code")
        if (i < 0) return null
        val data = c.substring(i + 4)
        val bytes = data.chunked(2).filter { it.length == 2 }.map { it.toInt(16) }
        return if (bytes.isEmpty()) null else bytes.toIntArray().let { if (it.size < 2) intArrayOf(it[0], 0) else it }
    }

    fun parseDtc(r: String): List<String> {
        val codes = mutableListOf<String>()
        for (line in r.uppercase().split('\r', '\n')) {
            var c = line.replace(Regex("[^0-9A-F]"), "")
            val i = c.indexOf("43")
            if (i < 0) continue
            c = c.substring(i + 2)
            if ((c.length / 2) % 2 == 1) c = c.substring(2) // CAN: primer byte = cantidad
            for (chunk in c.chunked(4)) {
                if (chunk.length < 4 || chunk == "0000") continue
                val first = chunk[0].digitToInt(16)
                val letter = "PCBU"[first shr 2]
                codes.add("$letter${first and 3}${chunk.substring(1)}")
            }
        }
        return codes.distinct()
    }
}
