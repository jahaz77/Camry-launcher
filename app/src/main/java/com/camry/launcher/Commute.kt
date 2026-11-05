package com.camry.launcher

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Tiempo a Casa y al Trabajo. La ruta la calcula OSRM (gratis, sin llave) y las direcciones
 * se convierten a coordenadas con Nominatim (OpenStreetMap). Estos servicios no tienen tráfico
 * en vivo, así que en horas pico se suma un margen estimado.
 */
object Commute {
    const val UA = "CamryLauncher/1.0 (launcher personal para estereo de auto)"

    data class Eta(val minutes: Int, val km: Double, val rush: Float, val at: Long)

    private val cache = HashMap<String, Eta>()

    fun address(ctx: Context, key: String) = ThemeManager.prefs(ctx).getString(if (key == "home") "addr_home" else "addr_work", "") ?: ""

    fun coords(ctx: Context, key: String): Pair<Double, Double>? {
        val s = ThemeManager.prefs(ctx).getString("ll_$key", null) ?: return null
        val p = s.split(',')
        return runCatching { p[0].toDouble() to p[1].toDouble() }.getOrNull()
    }

    fun setCoords(ctx: Context, key: String, lat: Double, lon: Double) {
        ThemeManager.prefs(ctx).edit().putString("ll_$key", "$lat,$lon").apply()
        cache.remove(key)
    }

    /** Guarda la dirección escrita y la convierte a coordenadas (en segundo plano). */
    fun setAddress(ctx: Context, key: String, text: String, done: (Boolean) -> Unit) {
        ThemeManager.prefs(ctx).edit().putString(if (key == "home") "addr_home" else "addr_work", text).remove("ll_$key").apply()
        cache.remove(key)
        Thread {
            val ll = geocode(text)
            if (ll != null) setCoords(ctx, key, ll.first, ll.second)
            done(ll != null)
        }.start()
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Language", "es")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }

    fun geocode(q: String): Pair<Double, Double>? = runCatching {
        val a = JSONArray(get("https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=mx&q=" + Uri.encode(q)))
        if (a.length() == 0) null else a.getJSONObject(0).let { it.getString("lat").toDouble() to it.getString("lon").toDouble() }
    }.getOrNull()

    /** Calle y ciudad de un punto (para "Estás en"). */
    fun reverse(lat: Double, lon: Double): Pair<String, String>? = runCatching {
        val o = JSONObject(get("https://nominatim.openstreetmap.org/reverse?format=json&zoom=17&lat=$lat&lon=$lon"))
        val a = o.getJSONObject("address")
        val street = a.optString("road").ifBlank { a.optString("neighbourhood").ifBlank { a.optString("suburb") } }
        val city = listOf(a.optString("city").ifBlank { a.optString("town").ifBlank { a.optString("municipality") } }, a.optString("state"))
            .filter { it.isNotBlank() }.joinToString(", ")
        if (street.isBlank()) null else street to city
    }.getOrNull()

    /** Margen por tráfico típico de Monterrey según día y hora (no es tráfico en vivo). */
    fun rushFactor(c: Calendar = Calendar.getInstance()): Float {
        val wd = c.get(Calendar.DAY_OF_WEEK)
        if (wd == Calendar.SUNDAY) return 1.0f
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val sat = wd == Calendar.SATURDAY
        return when {
            !sat && m in 420..570 -> 1.45f   // 7:00–9:30
            !sat && m in 780..900 -> 1.2f    // 13:00–15:00
            !sat && m in 1050..1230 -> 1.5f  // 17:30–20:30
            sat && m in 720..1200 -> 1.2f
            else -> 1.0f
        }
    }

    fun cached(key: String) = cache[key]

    /** Calcula (o devuelve de caché, 5 min) el tiempo desde la posición actual. Llamar en hilo aparte. */
    fun eta(ctx: Context, key: String, fromLat: Double, fromLon: Double): Eta? {
        cache[key]?.let { if (System.currentTimeMillis() - it.at < 5 * 60_000L) return it }
        val to = coords(ctx, key) ?: address(ctx, key).takeIf { it.isNotBlank() }?.let { a ->
            geocode(a)?.also { setCoords(ctx, key, it.first, it.second) }
        } ?: return null
        return runCatching {
            val o = JSONObject(get("https://router.project-osrm.org/route/v1/driving/$fromLon,$fromLat;${to.second},${to.first}?overview=false"))
            val r = o.getJSONArray("routes").getJSONObject(0)
            val rush = rushFactor()
            Eta(((r.getDouble("duration") / 60.0) * rush).toInt().coerceAtLeast(1), r.getDouble("distance") / 1000.0, rush, System.currentTimeMillis())
        }.getOrNull()?.also { cache[key] = it }
    }
}
