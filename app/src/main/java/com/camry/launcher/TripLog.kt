package com.camry.launcher

import android.content.Context
import android.location.Location
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Historial de viajes, rendimiento de gasolina, kilometraje y mantenimiento.
 * Con el OBD2 conectado se mide la gasolina real (sensor MAF); sin él solo los km por GPS.
 * El estéreo cierra todo al apagar el auto, por eso se guarda seguido.
 */
object TripLog {

    data class Trip(
        val start: Long, val end: Long, val km: Double, val liters: Double, val movingMs: Long,
        val sLat: Double?, val sLon: Double?, val eLat: Double?, val eLon: Double?
    ) {
        val kmpl: Double? get() = if (liters > 0.05 && km > 0.5) km / liters else null
        val minutes: Int get() = ((end - start) / 60_000L).toInt()
        val avgSpeed: Int get() = if (movingMs > 0) (km / (movingMs / 3_600_000.0)).toInt() else 0
        fun json() = JSONObject().apply {
            put("s", start); put("e", end); put("km", km); put("l", liters); put("mv", movingMs)
            sLat?.let { put("sla", it) }; sLon?.let { put("slo", it) }; eLat?.let { put("ela", it) }; eLon?.let { put("elo", it) }
        }
        companion object {
            fun from(o: JSONObject) = Trip(o.getLong("s"), o.getLong("e"), o.getDouble("km"), o.getDouble("l"), o.optLong("mv"),
                o.optDouble("sla").takeIf { !it.isNaN() }, o.optDouble("slo").takeIf { !it.isNaN() },
                o.optDouble("ela").takeIf { !it.isNaN() }, o.optDouble("elo").takeIf { !it.isNaN() })
        }
    }

    data class Item(val key: String, val name: String, val defEvery: Int)
    val ITEMS = listOf(
        Item("aceite", "Aceite y filtro", 10_000),
        Item("aire", "Filtro de aire", 20_000),
        Item("llantas", "Rotación de llantas", 10_000),
        Item("frenos", "Revisión de frenos", 20_000),
        Item("bujias", "Bujías", 50_000)
    )

    private const val GAP_MS = 5 * 60_000L   // 5 min sin moverse ni datos = otro viaje
    private var cur: Trip? = null
    private var lastSaveAt = 0L
    private var lastObdAt = 0L
    private var lastGps: Location? = null
    private var lastLat: Double? = null
    private var lastLon: Double? = null
    @Volatile private var loaded = false

    private fun prefs(ctx: Context) = ThemeManager.prefs(ctx)

    // ------------------------------------------------------------------ datos de entrada

    /** Desde el hilo del OBD2: velocidad (km/h), litros por hora y horas transcurridas. */
    @Synchronized
    fun obd(ctx: Context, speed: Int?, lph: Double?, dtH: Double) {
        if (dtH <= 0 || dtH > 0.01) return            // huecos de más de 36 s no se cuentan
        lastObdAt = SystemClock.elapsedRealtime()
        val km = (speed ?: 0) * dtH
        val l = (lph ?: 0.0) * dtH
        add(ctx, km, l, if ((speed ?: 0) > 2) (dtH * 3_600_000).toLong() else 0L)
    }

    /** Desde el GPS: solo cuenta km si el OBD2 no está mandando datos. */
    @Synchronized
    fun gps(ctx: Context, l: Location) {
        lastLat = l.latitude; lastLon = l.longitude
        val prev = lastGps
        lastGps = l
        if (SystemClock.elapsedRealtime() - lastObdAt < 10_000L) return
        if (prev == null || l.hasAccuracy() && l.accuracy > 40f) return
        val d = prev.distanceTo(l) / 1000.0
        val dt = (l.time - prev.time) / 1000.0
        if (dt <= 0 || dt > 30 || d / (dt / 3600.0) > 220) return    // saltos raros del GPS
        if (d < 0.003) return
        add(ctx, d, 0.0, (dt * 1000).toLong())
    }

    private fun add(ctx: Context, km: Double, liters: Double, movingMs: Long) {
        load(ctx)
        val now = System.currentTimeMillis()
        val c = cur
        cur = if (c == null || now - c.end > GAP_MS) {
            c?.let { finish(ctx, it) }
            if (km <= 0 && liters <= 0) null
            else Trip(now, now, km, liters, movingMs, lastLat, lastLon, lastLat, lastLon)
        } else c.copy(end = now, km = c.km + km, liters = c.liters + liters, movingMs = c.movingMs + movingMs,
            eLat = lastLat ?: c.eLat, eLon = lastLon ?: c.eLon)
        if (km > 0) prefs(ctx).edit().putFloat("odo_added", (prefs(ctx).getFloat("odo_added", 0f) + km).toFloat()).apply()
        if (now - lastSaveAt > 20_000L) { lastSaveAt = now; saveCurrent(ctx) }
    }

    private fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        prefs(ctx).getString("trip_cur", null)?.let { runCatching { cur = Trip.from(JSONObject(it)) } }
    }

    private fun saveCurrent(ctx: Context) {
        prefs(ctx).edit().putString("trip_cur", cur?.json()?.toString()).apply()
    }

    private fun finish(ctx: Context, t: Trip) {
        if (t.km < 0.3) return
        val list = trips(ctx).toMutableList()
        list.add(0, t)
        prefs(ctx).edit().putString("trips", JSONArray().apply { list.take(120).forEach { put(it.json()) } }.toString())
            .remove("trip_cur").apply()
    }

    // ------------------------------------------------------------------ consultas

    fun trips(ctx: Context): List<Trip> = runCatching {
        val a = JSONArray(prefs(ctx).getString("trips", "[]"))
        (0 until a.length()).map { Trip.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    /** Viajes guardados + el viaje en curso. */
    @Synchronized
    fun recent(ctx: Context): List<Trip> {
        load(ctx)
        return listOfNotNull(cur?.takeIf { it.km >= 0.3 }) + trips(ctx)
    }

    /** km/L por día de los últimos [days] días (null si ese día no hubo datos de gasolina). */
    fun daily(ctx: Context, days: Int = 14): List<Pair<Calendar, Double?>> {
        val all = recent(ctx)
        return (days - 1 downTo 0).map { back ->
            val d = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -back); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val from = d.timeInMillis; val to = from + 86_400_000L
            val ts = all.filter { it.start in from until to }
            val km = ts.sumOf { it.km }; val l = ts.sumOf { it.liters }
            d to (if (l > 0.1 && km > 0.5) km / l else null)
        }
    }

    data class Summary(val km: Double, val liters: Double, val kmpl: Double?, val prevKmpl: Double?)

    fun summary(ctx: Context, days: Int = 14): Summary {
        val now = System.currentTimeMillis()
        val all = recent(ctx)
        fun range(a: Long, b: Long) = all.filter { it.start in a until b && it.liters > 0 }
        val cur = range(now - days * 86_400_000L, now)
        val prev = range(now - 2 * days * 86_400_000L, now - days * 86_400_000L)
        val km = cur.sumOf { it.km }; val l = cur.sumOf { it.liters }
        val pk = prev.sumOf { it.km }; val pl = prev.sumOf { it.liters }
        return Summary(all.filter { it.start > now - days * 86_400_000L }.sumOf { it.km }, l,
            if (l > 0.2) km / l else null, if (pl > 0.2) pk / pl else null)
    }

    // ------------------------------------------------------------------ gasolina

    fun fuelPrice(ctx: Context): Float = prefs(ctx).getFloat("fuel_price", 24.5f)
    fun fuelType(ctx: Context): String = prefs(ctx).getString("fuel_type", "Magna") ?: "Magna"
    fun setFuel(ctx: Context, price: Float, type: String) = prefs(ctx).edit().putFloat("fuel_price", price).putString("fuel_type", type).apply()

    // ------------------------------------------------------------------ kilometraje y mantenimiento

    fun hasOdometer(ctx: Context) = prefs(ctx).contains("odo_base")
    fun odometer(ctx: Context): Int = (prefs(ctx).getInt("odo_base", 0) + prefs(ctx).getFloat("odo_added", 0f)).toInt()
    fun setOdometer(ctx: Context, km: Int) = prefs(ctx).edit().putInt("odo_base", km).putFloat("odo_added", 0f).apply()

    fun every(ctx: Context, it: Item) = prefs(ctx).getInt("mant_${it.key}_every", it.defEvery)
    fun doneAt(ctx: Context, it: Item): Int? = prefs(ctx).getInt("mant_${it.key}_at", -1).takeIf { v -> v >= 0 }
    fun markDone(ctx: Context, it: Item, atKm: Int = odometer(ctx)) = prefs(ctx).edit().putInt("mant_${it.key}_at", atKm).apply()
    fun setEvery(ctx: Context, it: Item, km: Int) = prefs(ctx).edit().putInt("mant_${it.key}_every", km).apply()

    /** Km que faltan (negativo = ya se pasó). null si nunca se registró. */
    fun remaining(ctx: Context, it: Item): Int? = doneAt(ctx, it)?.let { at -> at + every(ctx, it) - odometer(ctx) }

    /** Texto para avisar por voz al encender, si algo ya toca. */
    fun dueText(ctx: Context): String? {
        if (!hasOdometer(ctx)) return null
        val due = ITEMS.filter { (remaining(ctx, it) ?: Int.MAX_VALUE) <= 300 }
        if (due.isEmpty()) return null
        return "Recordatorio: ya toca " + due.joinToString(" y ") { it.name.lowercase() } + "."
    }

    /** Nombre corto del lugar si está cerca de casa o trabajo. */
    fun placeName(ctx: Context, lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null) return null
        for ((key, name) in listOf("home" to "Casa", "work" to "Trabajo")) {
            val ll = Commute.coords(ctx, key) ?: continue
            val r = FloatArray(1)
            Location.distanceBetween(lat, lon, ll.first, ll.second, r)
            if (r[0] < 500f) return name
        }
        return null
    }
}
