package com.camry.launcher

import android.content.Context
import android.graphics.Color
import android.os.Environment
import org.json.JSONObject
import java.io.File

/** Un tema: solo colores y, opcionalmente, una imagen de fondo. */
data class LauncherTheme(
    val id: String,
    val name: String,
    val background: Int,
    val card: Int,
    val button: Int,
    val road: Int,
    val accent: Int,
    val text: Int,
    val textDim: Int,
    val carBody: Int,
    val carRoof: Int,
    val backgroundImage: String?,
    /** "camino" = auto dibujado avanzando; "foto" = foto del auto, sin animación. */
    val centerMode: String = "camino",
    /** Ruta de la foto del auto: "asset:carros/x.png" o una ruta de archivo. */
    val carImage: String? = null
) {
    val isLight: Boolean
        get() = (0.299 * Color.red(card) + 0.587 * Color.green(card) + 0.114 * Color.blue(card)) > 150
}

object ThemeManager {
    private const val PREFS = "camry_prefs"
    private const val KEY_THEME = "theme_id"

    /** Carpeta donde el usuario puede poner sus propios temas. */
    fun userDir(): File = File(Environment.getExternalStorageDirectory(), "CamryLauncher/temas")

    fun loadAll(ctx: Context): List<LauncherTheme> {
        val list = mutableListOf<LauncherTheme>()
        ctx.assets.list("temas")?.sorted()?.forEach { f ->
            runCatching {
                val json = ctx.assets.open("temas/$f").bufferedReader().use { it.readText() }
                list.add(parse("interno:$f", JSONObject(json), null))
            }
        }
        userDir().listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { d ->
            val jf = File(d, "tema.json")
            if (jf.exists()) {
                runCatching { list.add(parse("usuario:${d.name}", JSONObject(jf.readText()), d)) }
            }
        }
        if (list.isEmpty()) list.add(parse("interno:default", JSONObject(), null))
        return list
    }

    fun current(ctx: Context): LauncherTheme {
        val all = loadAll(ctx)
        val id = prefs(ctx).getString(KEY_THEME, null)
        return all.firstOrNull { it.id == id } ?: all.first()
    }

    fun select(ctx: Context, id: String) {
        prefs(ctx).edit().putString(KEY_THEME, id).apply()
    }

    fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun color(o: JSONObject, key: String, def: String): Int =
        runCatching { Color.parseColor(o.optString(key, def).trim()) }.getOrElse { Color.parseColor(def) }

    private fun parse(id: String, o: JSONObject, dir: File?): LauncherTheme {
        val img = o.optString("imagenFondo", "").trim()
        val imgPath = if (img.isNotEmpty() && dir != null) File(dir, img).takeIf { it.exists() }?.absolutePath else null
        val carName = o.optString("imagenCarro", "").trim()
        val carPath = when {
            carName.isEmpty() -> null
            dir == null -> "asset:carros/$carName"
            File(dir, carName).exists() -> File(dir, carName).absolutePath
            else -> "asset:carros/$carName"
        }
        val mode = if (o.optString("centro", "camino").trim() == "foto" && carPath != null) "foto" else "camino"
        return LauncherTheme(
            id = id,
            name = o.optString("nombre", dir?.name ?: "Tema"),
            background = color(o, "fondo", "#06080B"),
            card = color(o, "tarjeta", "#10141A"),
            button = color(o, "boton", "#181D25"),
            road = color(o, "camino", "#0A0D12"),
            accent = color(o, "acento", "#3D8BFF"),
            text = color(o, "texto", "#E8EDF3"),
            textDim = color(o, "textoSecundario", "#8D97A5"),
            carBody = color(o, "carroceria", "#F4F6F8"),
            carRoof = color(o, "techo", "#1F4FA8"),
            backgroundImage = imgPath,
            centerMode = mode,
            carImage = carPath
        )
    }

    /** Crea la carpeta de temas con un ejemplo y un instructivo, si no existe. */
    fun ensureUserDir(ctx: Context) {
        runCatching {
            val dir = userDir()
            if (dir.exists()) return
            val example = File(dir, "MiTema")
            example.mkdirs()
            val json = ctx.assets.open("temas/1_azul.json").bufferedReader().use { it.readText() }
                .replace("Azul Nocturno", "Mi tema (ejemplo)")
            File(example, "tema.json").writeText(json)
            File(dir, "LEEME.txt").writeText(
                """
                TEMAS DE CAMRY LAUNCHER

                Cada tema es una carpeta aquí dentro con un archivo "tema.json".
                Copia la carpeta "MiTema", cámbiale el nombre y edita los colores.

                Claves (colores en formato #RRGGBB, o #AARRGGBB para transparencia):
                  nombre            Nombre que aparece en el menú de temas
                  fondo             Color de fondo de la pantalla
                  tarjeta           Color de los paneles (usa #CC... para que se vea la imagen de fondo)
                  boton             Color de los botones secundarios
                  camino            Fondo del panel central del camino
                  acento            Color principal (botones, líneas del camino)
                  texto             Texto principal
                  textoSecundario   Texto secundario
                  carroceria        Color de la carrocería del auto
                  techo             Color del techo del auto
                  imagenFondo       (opcional) nombre de un PNG/JPG dentro de la misma carpeta, 1024x600
                  centro            "camino" (auto animado) o "foto" (foto de tu auto, sin animación)
                  imagenCarro       con centro "foto": PNG con fondo transparente dentro de la carpeta
                                    (o "camry.png" para usar la foto incluida del Camry)

                Después, en el launcher toca el botón de la paleta y elige el tema.
                """.trimIndent()
            )
        }
    }
}
