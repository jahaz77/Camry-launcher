package com.camry.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject

/** Lugares guardados además de Casa y Trabajo (escuela, gimnasio, casa de tus papás…). */
object Places {
    data class Place(val name: String, val address: String, val lat: Double?, val lon: Double?) {
        /** Lo que se le pasa a Maps: coordenadas si las hay, si no la dirección. */
        val target: String get() = if (lat != null && lon != null) "$lat,$lon" else address
        fun json() = JSONObject().apply { put("n", name); put("a", address); lat?.let { put("la", it) }; lon?.let { put("lo", it) } }
        companion object {
            fun from(o: JSONObject) = Place(o.getString("n"), o.optString("a"),
                o.optDouble("la").takeIf { !it.isNaN() }, o.optDouble("lo").takeIf { !it.isNaN() })
        }
    }

    fun all(ctx: Context): List<Place> = runCatching {
        val a = JSONArray(ThemeManager.prefs(ctx).getString("places", "[]"))
        (0 until a.length()).map { Place.from(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<Place>) =
        ThemeManager.prefs(ctx).edit().putString("places", JSONArray().apply { list.forEach { put(it.json()) } }.toString()).apply()

    fun find(ctx: Context, said: String): Place? {
        val k = AppUtils.normalize(said)
        val list = all(ctx)
        return list.firstOrNull { AppUtils.normalize(it.name) == k }
            ?: list.firstOrNull { val n = AppUtils.normalize(it.name); n.length >= 3 && (k.contains(n) || n.contains(k)) }
    }

    private fun toast(act: Activity, m: String) = act.runOnUiThread { Toast.makeText(act, m, Toast.LENGTH_LONG).show() }

    /** Diálogo para agregar (o editar) un lugar. */
    fun edit(act: Activity, dialog: () -> AlertDialog.Builder, px: (Float) -> Int, old: Place? = null, done: () -> Unit) {
        val input = EditText(act).apply { setText(old?.name ?: ""); hint = "Nombre (p. ej. Gimnasio, Casa de mi mamá)"; setSelection(text.length) }
        dialog().setTitle(if (old == null) "Nuevo lugar" else "Editar ${old.name}")
            .setView(FrameLayout(act).apply { setPadding(px(24f), px(8f), px(24f), 0); addView(input) })
            .setPositiveButton("Siguiente") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isBlank()) return@setPositiveButton
                dialog().setTitle("¿Dónde está $name?")
                    .setItems(arrayOf("Estoy aquí: usar mi ubicación actual", "Escribir la dirección")) { _, w ->
                        if (w == 0) {
                            val la = CarState.lat; val lo = CarState.lon
                            if (la == null || lo == null) { toast(act, "Todavía no tengo tu ubicación del GPS"); return@setItems }
                            put(act, old, Place(name, "", la, lo)); toast(act, "$name guardado"); done()
                        } else {
                            val addr = EditText(act).apply { setText(old?.address ?: ""); hint = "Calle, número, colonia, ciudad" }
                            dialog().setTitle("Dirección de $name")
                                .setView(FrameLayout(act).apply { setPadding(px(24f), px(8f), px(24f), 0); addView(addr) })
                                .setPositiveButton("Guardar") { _, _ ->
                                    val a = addr.text.toString().trim()
                                    Thread {
                                        val ll = Commute.geocode(a)
                                        put(act, old, Place(name, a, ll?.first, ll?.second))
                                        toast(act, if (ll != null) "$name guardado" else "$name guardado (Maps buscará la dirección)")
                                        act.runOnUiThread { done() }
                                    }.start()
                                }.setNegativeButton("Cancelar", null).show()
                        }
                    }.setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun put(ctx: Context, old: Place?, p: Place) {
        val list = all(ctx).filter { it != old && !it.name.equals(p.name, true) }.toMutableList()
        list.add(p)
        save(ctx, list)
    }

    fun remove(ctx: Context, p: Place) = save(ctx, all(ctx).filter { it != p })

    /** Lista para elegir a dónde ir; tocar navega, mantener presionado no aplica aquí (opciones en el diálogo). */
    fun chooser(act: Activity, dialog: () -> AlertDialog.Builder, px: (Float) -> Int, navigate: (String) -> Unit, changed: () -> Unit) {
        val list = all(act)
        val items = (list.map { "📍  " + it.name } + "＋  Agregar lugar" + if (list.isNotEmpty()) listOf("✎  Editar o borrar un lugar") else emptyList()).toTypedArray()
        dialog().setTitle("¿A dónde vamos?")
            .setItems(items) { _, w ->
                when {
                    w < list.size -> navigate(list[w].name)
                    w == list.size -> edit(act, dialog, px, null, changed)
                    else -> dialog().setTitle("¿Cuál lugar?")
                        .setItems(list.map { it.name }.toTypedArray()) { _, k ->
                            val p = list[k]
                            dialog().setTitle(p.name)
                                .setItems(arrayOf("Cambiar", "Borrar")) { _, a ->
                                    if (a == 0) edit(act, dialog, px, p, changed) else { remove(act, p); changed() }
                                }.setNegativeButton("Cancelar", null).show()
                        }.setNegativeButton("Cancelar", null).show()
                }
            }.setNegativeButton("Cerrar", null).show()
    }
}
