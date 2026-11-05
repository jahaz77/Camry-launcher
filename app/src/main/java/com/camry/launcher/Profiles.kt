package com.camry.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

/** Perfil de quien maneja: color del launcher, tema, nombre en el saludo y color de las luces. */
data class Profile(
    val id: String,
    val name: String,
    val accent: Int,
    val themeId: String?,
    val lightColor: Int,
    val brightness: Int,
    val phoneAddr: String?,
    val phoneName: String?,
    val effect: Int = 0,
    val speed: Int = 50
) {
    fun json() = JSONObject().apply {
        put("id", id); put("name", name); put("accent", accent); put("themeId", themeId ?: "")
        put("light", lightColor); put("bright", brightness); put("phoneAddr", phoneAddr ?: ""); put("phoneName", phoneName ?: "")
        put("effect", effect); put("speed", speed)
    }
    companion object {
        fun from(o: JSONObject) = Profile(
            o.getString("id"), o.getString("name"), o.getInt("accent"), o.optString("themeId").ifBlank { null },
            o.optInt("light", o.getInt("accent")), o.optInt("bright", 80),
            o.optString("phoneAddr").ifBlank { null }, o.optString("phoneName").ifBlank { null },
            o.optInt("effect", 0), o.optInt("speed", 50)
        )
    }
}

object Profiles {
    val BLUE = Color.rgb(61, 139, 255)
    val PINK = Color.rgb(255, 79, 163)
    /** Colores para elegir (launcher y luces). */
    val PALETTE = intArrayOf(BLUE, Color.rgb(33, 212, 253), Color.rgb(52, 211, 153), Color.rgb(245, 185, 66),
        Color.rgb(255, 90, 95), PINK, Color.rgb(192, 38, 211), Color.rgb(138, 92, 255), Color.WHITE)

    private fun prefs(ctx: Context) = ThemeManager.prefs(ctx)

    /** Lo pone la pantalla de inicio para redibujarse con el color del nuevo perfil. */
    @Volatile var onChange: (() -> Unit)? = null

    fun all(ctx: Context): List<Profile> {
        val raw = prefs(ctx).getString("profiles", null)
        if (raw != null) runCatching {
            val a = JSONArray(raw)
            return (0 until a.length()).map { Profile.from(a.getJSONObject(it)) }
        }
        return listOf(
            Profile("jahaz", "Jahaz", BLUE, null, BLUE, 80, null, null),
            Profile("diana", "Diana", PINK, null, PINK, 70, null, null)
        )
    }

    fun save(ctx: Context, list: List<Profile>) {
        prefs(ctx).edit().putString("profiles", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()
    }

    fun update(ctx: Context, p: Profile) = save(ctx, all(ctx).map { if (it.id == p.id) p else it })

    fun enabled(ctx: Context) = prefs(ctx).getBoolean("profiles_on", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("profiles_on", on).apply()

    fun active(ctx: Context): Profile? {
        if (!enabled(ctx)) return null
        val id = prefs(ctx).getString("profile_active", null) ?: return null
        return all(ctx).firstOrNull { it.id == id }
    }

    /** Cambia de perfil: tema, color y luces. Devuelve true si cambió algo. */
    fun activate(ctx: Context, p: Profile, reason: String): Boolean {
        val before = prefs(ctx).getString("profile_active", null)
        prefs(ctx).edit().putString("profile_active", p.id).apply()
        p.themeId?.let { ThemeManager.select(ctx, it) }
        Lights.address(ctx)?.let { Lights.applyProfile(ctx, p) }
        EventLog.add(ctx, "Perfil ${p.name} activado ($reason)")
        android.os.Handler(android.os.Looper.getMainLooper()).post { onChange?.invoke() }
        return before != p.id
    }

    /** Nombre para el saludo ({nombre}). */
    fun greetName(ctx: Context): String = active(ctx)?.name ?: "Diana y Jahaz"

    private fun norm(s: String?) = AppUtils.normalize(s ?: "")

    /** ¿Este dispositivo Bluetooth es el teléfono de algún perfil? */
    fun match(ctx: Context, addr: String?, name: String?): Profile? = all(ctx).firstOrNull { p ->
        (p.phoneAddr != null && p.phoneAddr.equals(addr, true)) ||
            (p.phoneName != null && name != null && norm(p.phoneName) == norm(name))
    }

    /** Teléfonos conectados ahora al Bluetooth de Android (método oculto isConnected). */
    fun connectedPhones(): List<BluetoothDevice> = runCatching {
        val m = BluetoothDevice::class.java.getMethod("isConnected")
        BluetoothAdapter.getDefaultAdapter()?.bondedDevices.orEmpty().filter { d ->
            runCatching { m.invoke(d) as Boolean }.getOrDefault(false)
        }
    }.getOrDefault(emptyList())

    /**
     * Revisa qué teléfono está conectado (primero el Bluetooth propio del estéreo, luego el de Android)
     * y activa su perfil si no es el que ya está. Devuelve el perfil encontrado.
     */
    fun detect(ctx: Context, reason: String, force: Boolean = false): Profile? {
        if (!enabled(ctx)) return null
        val list = all(ctx)
        var found: Profile? = null
        var how = ""
        val clues = StereoBt.clues(ctx)
        for (p in list) {
            val n = p.phoneName ?: continue
            if (StereoBt.mentions(clues, n)) { found = p; how = "Bluetooth del estéreo: $n"; break }
        }
        if (found == null) for (d in connectedPhones()) {
            val n = runCatching { d.name }.getOrNull()
            val p = match(ctx, d.address, n) ?: continue
            found = p; how = "Bluetooth de Android: $n"; break
        }
        val f = found ?: return null
        if (force || active(ctx)?.id != f.id) activate(ctx, f, "$reason · $how")
        return f
    }
}

/** Pantallas propias abiertas encima del inicio (Perfiles, Apps): no hay que "regresar al inicio" sobre ellas. */
object Ui { @Volatile var ownScreens = 0 }
