package com.camry.launcher

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import kotlin.math.roundToInt

/** Perfiles Jahaz / Diana: teléfono, color, tema y luces ambientales. */
class ProfilesActivity : Activity() {

    private var s = 1f
    private lateinit var skin: LauncherTheme
    private fun px(v: Float) = (v * s).roundToInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun gap(v: Float) = View(this).apply { layoutParams = lp(px(v), px(v)) }
    private fun text(str: String, size: Float, color: Int, medium: Boolean = false, light: Boolean = false) = TextView(this).apply {
        text = str
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
        setTextColor(color)
        typeface = Typeface.create(if (light) "sans-serif-light" else if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }
    private fun overline(str: String) = text(str, 11f, skin.textDim).apply { letterSpacing = 0.16f }
    private fun dialog() = AlertDialog.Builder(this, if (skin.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()
    private fun pill(color: Int, r: Float, stroke: Int = Color.argb(20, 255, 255, 255)) = GradientDrawable().apply {
        cornerRadius = r * s; setColor(color); setStroke(1, stroke)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        s = min(maxOf(dm.widthPixels, dm.heightPixels) / 1024f, min(dm.widthPixels, dm.heightPixels) / 600f)
        render()
    }

    private fun render() {
        skin = ThemeManager.current(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = AmbientDrawable(skin)
            setPadding(px(20f), px(18f), px(20f), px(18f))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(text("Perfiles", 22f, skin.text))
        header.addView(gap(14f))
        val active = Profiles.active(this)
        val phones = Profiles.connectedPhones().mapNotNull { runCatching { it.name }.getOrNull() }
        val status = when {
            !Profiles.enabled(this) -> "Perfiles desactivados"
            phones.isNotEmpty() -> "Conectado: " + phones.joinToString()
            else -> "Ningún teléfono conectado al Bluetooth de Android"
        }
        val badge = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14f), 0, px(14f), 0)
            background = pill(Style.alpha(Color.rgb(52, 211, 153), 30), 16f, Style.alpha(Color.rgb(52, 211, 153), 70))
        }
        badge.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.rgb(52, 211, 153)) } }, lp(px(8f), px(8f)))
        badge.addView(gap(8f))
        badge.addView(text(status, 13f, Color.rgb(110, 231, 183)))
        header.addView(badge, lp(WRAP, px(32f)))
        header.addView(View(this), lp(0, 0, 1f))
        header.addView(text("Luces: " + (Lights.name(this) ?: "sin elegir"), 14f, skin.text).apply {
            gravity = Gravity.CENTER; setPadding(px(16f), 0, px(16f), 0)
            background = pill(Color.argb(14, 255, 255, 255), 20f)
            setOnClickListener { lightsMenu() }
        }, lp(WRAP, px(40f)))
        header.addView(gap(10f))
        header.addView(text("Listo", 14f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER; setPadding(px(22f), 0, px(22f), 0)
            background = Style.accentFill(skin, 20f * s)
            setOnClickListener { finish() }
        }, lp(WRAP, px(40f)))
        root.addView(header, lp(MATCH, px(44f)))
        root.addView(gap(16f))

        val cards = LinearLayout(this)
        Profiles.all(this).forEachIndexed { i, p ->
            if (i > 0) cards.addView(gap(16f))
            cards.addView(card(p, p.id == active?.id), lp(0, MATCH, 1f))
        }
        root.addView(cards, lp(MATCH, 0, 1f))
        setContentView(root)
    }

    private fun card(p: Profile, on: Boolean): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22f), px(20f), px(22f), px(20f))
            background = GradientDrawable().apply {
                cornerRadius = 26f * s
                setColor(if (skin.isLight) Color.argb(200, 255, 255, 255) else Color.argb(14, 255, 255, 255))
                setStroke(if (on) px(2f) else 1, if (on) p.accent else Color.argb(18, 255, 255, 255))
            }
        }
        if (on) Style.glow(c, p.accent, 10f * s)
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text(p.name.take(1), 28f, Color.WHITE, medium = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Style.lighten(p.accent, 0.2f), Style.mix(p.accent, Color.BLACK, 0.3f))).apply { shape = GradientDrawable.OVAL }
        }, lp(px(62f), px(62f)))
        top.addView(gap(16f))
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        names.addView(text(p.name, 26f, skin.text))
        names.addView(gap(4f))
        names.addView(text(p.phoneName?.let { "Teléfono: $it" } ?: "Toca \"Teléfono\" para vincularlo", 14f, skin.textDim))
        top.addView(names, lp(0, WRAP, 1f))
        top.addView(text(if (on) "Activo" else "En espera", 12f, if (on) Color.WHITE else skin.textDim).apply {
            setPadding(px(10f), px(5f), px(10f), px(5f))
            background = pill(if (on) Style.alpha(p.accent, 60) else Color.argb(12, 255, 255, 255), 10f)
        })
        c.addView(top)
        c.addView(gap(16f))

        val grid = LinearLayout(this)
        fun cell(title: String, value: String, swatch: Int?, onClick: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(14f), px(12f), px(14f), px(12f))
            background = pill(Color.argb(12, 255, 255, 255), 16f, Color.TRANSPARENT)
            setOnClickListener { onClick() }
            addView(overline(title))
            addView(gap(6f))
            val row = LinearLayout(this@ProfilesActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            swatch?.let { row.addView(View(this@ProfilesActivity).apply { background = pill(it, 4f, Color.TRANSPARENT) }, lp(px(14f), px(14f))); row.addView(gap(8f)) }
            row.addView(text(value, 15f, skin.text).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            addView(row)
        }
        val themeName = p.themeId?.let { id -> ThemeManager.loadAll(this).firstOrNull { it.id == id }?.name } ?: "El actual"
        grid.addView(cell("COLOR", colorName(p.accent), p.accent) { pickColor("Color de ${p.name}") { col -> save(p.copy(accent = col, lightColor = if (p.lightColor == p.accent) col else p.lightColor)) } }, lp(0, WRAP, 1f))
        grid.addView(gap(10f))
        grid.addView(cell("TEMA", themeName, null) { pickTheme(p) }, lp(0, WRAP, 1f))
        grid.addView(gap(10f))
        grid.addView(cell("TELÉFONO", p.phoneName ?: "Vincular", null) { pickPhone(p) }, lp(0, WRAP, 1f))
        c.addView(grid)
        c.addView(gap(12f))

        // luces
        val lights = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16f), px(14f), px(16f), px(14f))
            background = pill(Color.argb(12, 255, 255, 255), 18f, Color.TRANSPARENT)
        }
        val lh = LinearLayout(this)
        lh.addView(overline("LUCES AMBIENTALES"), lp(0, WRAP, 1f))
        val brightTxt = text("${p.brightness}%", 13f, skin.textDim)
        lh.addView(brightTxt)
        lights.addView(lh)
        lights.addView(gap(12f))
        val sw = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (col in Profiles.PALETTE) {
            val selected = col == p.lightColor
            val dot = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(col)
                    if (selected) setStroke(px(3f), if (skin.isLight) Color.BLACK else Color.WHITE)
                }
                setOnClickListener {
                    val np = p.copy(lightColor = col)
                    save(np)
                    if (Lights.address(this@ProfilesActivity) != null) Lights.apply(this@ProfilesActivity, col, np.brightness)
                }
            }
            sw.addView(dot, lp(px(34f), px(34f)))
            sw.addView(gap(9f))
        }
        lights.addView(sw)
        lights.addView(gap(10f))
        val bar = SeekBar(this).apply {
            max = 100; progress = p.brightness
            progressTintList = android.content.res.ColorStateList.valueOf(p.lightColor)
            thumbTintList = android.content.res.ColorStateList.valueOf(p.lightColor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { brightTxt.text = "$v%" }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    val np = p.copy(brightness = sb.progress.coerceAtLeast(5))
                    Profiles.update(this@ProfilesActivity, np)
                    if (Lights.address(this@ProfilesActivity) != null) Lights.apply(this@ProfilesActivity, np.lightColor, np.brightness)
                }
            })
        }
        lights.addView(bar, lp(MATCH, WRAP))
        c.addView(lights, lp(MATCH, 0, 1f))
        c.addView(gap(12f))
        c.addView(text(if (on) "Perfil activo" else "Activar ahora", 15f, if (on) Color.WHITE else skin.text, medium = true).apply {
            gravity = Gravity.CENTER
            background = if (on) GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Style.lighten(p.accent, 0.15f), p.accent)).apply { cornerRadius = 14f * s }
            else pill(Color.argb(20, 255, 255, 255), 14f)
            setOnClickListener { Profiles.activate(this@ProfilesActivity, p, "elegido a mano"); render() }
        }, lp(MATCH, px(48f)))
        return c
    }

    private fun save(p: Profile) {
        Profiles.update(this, p)
        if (Profiles.active(this)?.id == p.id) Profiles.onChange?.invoke()
        render()
    }

    private fun colorName(c: Int) = when (c) {
        Profiles.BLUE -> "Azul"; Profiles.PINK -> "Rosa"
        Profiles.PALETTE[1] -> "Cian"; Profiles.PALETTE[2] -> "Verde"; Profiles.PALETTE[3] -> "Ámbar"
        Profiles.PALETTE[4] -> "Rojo"; Profiles.PALETTE[6] -> "Magenta"; Profiles.PALETTE[7] -> "Violeta"; Color.WHITE -> "Blanco"
        else -> "Personal"
    }

    private fun pickColor(title: String, done: (Int) -> Unit) {
        val cols = Profiles.PALETTE
        dialog().setTitle(title).setItems(cols.map { colorName(it) }.toTypedArray()) { _, w -> done(cols[w]) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun pickTheme(p: Profile) {
        val all = ThemeManager.loadAll(this)
        dialog().setTitle("Tema de ${p.name}")
            .setItems((listOf("No cambiar (el que esté puesto)") + all.map { it.name }).toTypedArray()) { _, w ->
                save(p.copy(themeId = if (w == 0) null else all[w - 1].id))
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun pickPhone(p: Profile) {
        val bonded = runCatching { android.bluetooth.BluetoothAdapter.getDefaultAdapter()?.bondedDevices?.toList().orEmpty() }.getOrDefault(emptyList())
            .filter { d -> !(runCatching { d.name }.getOrNull() ?: "").uppercase().let { it.contains("OBD") || it.contains("ELM") || it.contains("ELK") } }
        val connected = Profiles.connectedPhones().map { it.address }.toSet()
        val labels = bonded.map { (runCatching { it.name }.getOrNull() ?: it.address) + if (it.address in connected) "  (conectado)" else "" }
        val items = (labels + "Escribir el nombre del teléfono…" + "Quitar teléfono").toTypedArray()
        if (bonded.isEmpty()) toast("El Bluetooth de Android no tiene teléfonos emparejados. Puedes escribir el nombre como aparece en Diagnóstico (\"Bluetooth conectado: …\").")
        dialog().setTitle("Teléfono de ${p.name}")
            .setItems(items) { _, w ->
                when {
                    w < bonded.size -> save(p.copy(phoneAddr = bonded[w].address, phoneName = runCatching { bonded[w].name }.getOrNull()))
                    w == bonded.size -> {
                        val input = EditText(this).apply { setText(p.phoneName ?: ""); hint = "iPhone de ${p.name}" }
                        dialog().setTitle("Nombre del teléfono").setView(FrameLayout(this).apply { setPadding(px(24f), px(8f), px(24f), 0); addView(input) })
                            .setPositiveButton("Guardar") { _, _ -> save(p.copy(phoneAddr = null, phoneName = input.text.toString().trim().ifBlank { null })) }
                            .setNegativeButton("Cancelar", null).show()
                    }
                    else -> save(p.copy(phoneAddr = null, phoneName = null))
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun lightsMenu() {
        val on = Lights.isOn(this)
        dialog().setTitle("Luces: " + (Lights.name(this) ?: "sin elegir") + " · " + Lights.lastResult)
            .setItems(arrayOf("Buscar luces (ELK-BLEDOM)", "Probar con el color del perfil activo", if (on) "Apagar las luces" else "Encender las luces")) { _, w ->
                when (w) {
                    0 -> {
                        toast("Buscando luces… (8 segundos). Si la app Lotus del celular está conectada, ciérrala.")
                        Lights.scan(this) { found ->
                            runOnUiThread {
                                if (found.isEmpty()) { toast("No encontré luces. Cierra la app Lotus del celular y vuelve a intentar."); return@runOnUiThread }
                                dialog().setTitle("Elige tus luces")
                                    .setItems(found.map { "${it.second}  (${it.first})" }.toTypedArray()) { _, k ->
                                        Lights.choose(this, found[k].first, found[k].second)
                                        val p = Profiles.active(this) ?: Profiles.all(this).first()
                                        Lights.apply(this, p.lightColor, p.brightness)
                                        render()
                                    }.setNegativeButton("Cancelar", null).show()
                            }
                        }
                    }
                    1 -> {
                        val p = Profiles.active(this) ?: Profiles.all(this).first()
                        Lights.apply(this, p.lightColor, p.brightness)
                        toast("Mandando color… revisa Diagnóstico si no cambian")
                    }
                    2 -> {
                        Lights.setOn(this, !on)
                        if (on) Lights.turnOff(this) else (Profiles.active(this) ?: Profiles.all(this).first()).let { Lights.apply(this, it.lightColor, it.brightness) }
                        render()
                    }
                }
            }
            .setNeutralButton(if (Profiles.enabled(this)) "Desactivar perfiles" else "Activar perfiles") { _, _ ->
                Profiles.setEnabled(this, !Profiles.enabled(this)); Profiles.onChange?.invoke(); render()
            }
            .setNegativeButton("Cerrar", null).show()
    }
}
