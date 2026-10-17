package com.camry.launcher

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.KeyEvent

/** Lo que el launcher sabe del momento (lo actualiza MainActivity). */
object CarState {
    @Volatile var speedKmh = 0f
    @Volatile var lat: Double? = null
    @Volatile var lon: Double? = null
    @Volatile var song: String? = null
}

/** Acciones que comparten el launcher y el asistente de voz. */
object CarActions {

    fun home(ctx: Context): String = ThemeManager.prefs(ctx).getString("addr_home", "") ?: ""
    fun work(ctx: Context): String = ThemeManager.prefs(ctx).getString("addr_work", "") ?: ""

    /** Abre Google Maps navegando al destino. Devuelve un texto con el resultado para el asistente. */
    fun navigate(ctx: Context, destination: String): String {
        val d = destination.trim()
        val key = AppUtils.normalize(d)
        val target = when {
            key == "casa" || key.contains("mi casa") -> home(ctx).ifBlank { return "No hay dirección de casa guardada. Se configura en Opciones del launcher." }
            key == "trabajo" || key.contains("mi trabajo") || key == "oficina" -> work(ctx).ifBlank { return "No hay dirección de trabajo guardada. Se configura en Opciones del launcher." }
            else -> d
        }
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(target)))
            .setPackage("com.google.android.apps.maps")
        if (AppUtils.start(ctx, nav)) return "Navegación iniciada hacia: $target"
        val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(target)))
        return if (AppUtils.start(ctx, geo)) "Mapa abierto buscando: $target" else "No hay app de mapas instalada"
    }

    fun media(ctx: Context, action: String): String {
        val code = when (AppUtils.normalize(action)) {
            "reproducir", "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pausar", "pausa", "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "siguiente", "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "anterior", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "Acción de música desconocida: $action"
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return "Hecho: $action"
    }

    /** Abre una app conocida; respeta la app que el usuario eligió con pulsación larga en la barra. */
    fun openApp(ctx: Context, app: String): String {
        val a = AppUtils.normalize(app)
        val slot = when (a) {
            "carplay" -> "slot_carplay"; "mapas", "maps" -> "slot_maps"; "musica" -> "slot_music"
            "telefono" -> "slot_phone"; "radio" -> "slot_radio"; "camara" -> "slot_camera"; "ajustes" -> "slot_settings"
            else -> null
        }
        slot?.let { k ->
            ThemeManager.prefs(ctx).getString(k, null)?.let { saved ->
                android.content.ComponentName.unflattenFromString(saved)?.let { if (AppUtils.launch(ctx, it)) return "Abierto: $app" }
            }
        }
        val ok = when (a) {
            "carplay" -> AppUtils.findByLabel(ctx, "tlink", "carplay", "zlink", "carlink")?.let { AppUtils.launch(ctx, it) } ?: false
            "mapas", "maps" -> AppUtils.launchPackage(ctx, "com.google.android.apps.maps") || AppUtils.launchPackage(ctx, "com.waze")
            "musica" -> AppUtils.start(ctx, Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
            "telefono" -> AppUtils.findByLabel(ctx, "bluetooth", "telefono", "phone", "llamada")?.let { AppUtils.launch(ctx, it) }
                ?: AppUtils.start(ctx, Intent(Intent.ACTION_DIAL))
            "radio" -> AppUtils.findByLabel(ctx, "radio")?.let { AppUtils.launch(ctx, it) } ?: false
            "camara" -> AppUtils.findByLabel(ctx, "camara", "camera", "avm", "360")?.let { AppUtils.launch(ctx, it) } ?: false
            "ajustes" -> AppUtils.start(ctx, Intent(Settings.ACTION_SETTINGS))
            else -> AppUtils.findByLabel(ctx, app)?.let { AppUtils.launch(ctx, it) } ?: false
        }
        return if (ok) "Abierto: $app" else "No encontré la app: $app"
    }

    fun setTheme(ctx: Context, name: String): String {
        val all = ThemeManager.loadAll(ctx)
        val n = AppUtils.normalize(name)
        val t = all.firstOrNull { AppUtils.normalize(it.name) == n }
            ?: all.firstOrNull { AppUtils.normalize(it.name).contains(n) || n.contains(AppUtils.normalize(it.name)) }
            ?: return "No existe ese tema. Disponibles: " + all.joinToString { it.name }
        ThemeManager.select(ctx, t.id)
        return "Tema cambiado a ${t.name}"
    }
}
