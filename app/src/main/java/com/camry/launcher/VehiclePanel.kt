package com.camry.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/** Pantalla "Mi Camry": datos del motor en tiempo real (OBD2), check engine y llantas. */
class VehiclePanel(
    private val act: Activity,
    private val skin: LauncherTheme,
    private val s: Float,
    private val onBack: () -> Unit
) {
    private fun px(v: Float) = (v * s).roundToInt()
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun gap(v: Float) = View(act).apply { layoutParams = lp(px(v), px(v)) }

    private fun text(str: String, size: Float, color: Int, light: Boolean = false, medium: Boolean = false) = TextView(act).apply {
        text = str
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
        setTextColor(color)
        typeface = Typeface.create(if (light) "sans-serif-light" else if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        includeFontPadding = false
    }

    private fun overline(str: String) = text(str, 11f, skin.textDim).apply { letterSpacing = 0.16f }

    private val green = Color.rgb(52, 211, 153)
    private val amber = Color.rgb(245, 185, 66)
    private val red = Color.rgb(255, 90, 95)
    private val cyan = Color.rgb(33, 212, 253)
    private val violet = Color.rgb(138, 92, 255)
    private val off = Color.rgb(58, 67, 80)

    private lateinit var obdDot: View
    private lateinit var obdLabel: TextView
    private lateinit var gTemp: GaugeView
    private lateinit var gRpm: GaugeView
    private lateinit var gVolt: GaugeView
    private val tiles = mutableListOf<SparkTile>()
    private lateinit var celRing: FrameLayout
    private lateinit var celIcon: ImageView
    private lateinit var celTitle: TextView
    private lateinit var celSub: TextView

    val view: View by lazy { build() }

    private fun build(): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16f), px(16f), px(16f), px(16f))
            background = AmbientDrawable(skin)
            isClickable = true
        }

        // ---- encabezado
        val header = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(10f), 0, px(14f), 0)
            background = pill(Color.argb(13, 255, 255, 255))
            setOnClickListener { onBack() }
            contentDescription = "Volver al inicio"
        }
        back.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(skin.text)
        }, lp(px(18f), px(18f)))
        back.addView(gap(6f))
        back.addView(text("Inicio", 14f, skin.text))
        header.addView(back, lp(WRAP, px(40f)))
        header.addView(gap(14f))
        val titles = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(overline("TOYOTA CAMRY 2008").apply { letterSpacing = 0.2f })
        titles.addView(gap(2f))
        titles.addView(text("Mi Camry", 22f, skin.text))
        header.addView(titles, lp(0, WRAP, 1f))
        header.addView(tabs(), lp(WRAP, px(42f)))
        header.addView(gap(14f))
        val chip = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16f), 0, px(16f), 0)
            background = pill(Color.argb(13, 255, 255, 255))
            setOnClickListener { (act as? MainActivity)?.chooseObdAdapter() }
        }
        obdDot = View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(amber) } }
        chip.addView(obdDot, lp(px(8f), px(8f)))
        chip.addView(gap(8f))
        obdLabel = text("OBD2 sin conectar", 14f, skin.text)
        chip.addView(obdLabel)
        header.addView(chip, lp(WRAP, px(36f)))
        root.addView(header, lp(MATCH, px(44f)))
        root.addView(gap(14f))

        // ---- cuerpo
        val body = LinearLayout(act)
        val pages = FrameLayout(act)
        pages.addView(body, FrameLayout.LayoutParams(MATCH, MATCH))
        perf = PerfPages(act, skin, s)
        pageViews = listOf(body, perf.rendimiento, perf.mantenimiento)
        pages.addView(perf.rendimiento, FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(perf.mantenimiento, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(pages, lp(MATCH, 0, 1f))
        showPage(0)

        val left = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(left, lp(0, MATCH, 1f))
        body.addView(gap(14f))
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        body.addView(right, lp(px(320f), MATCH))

        val gauges = LinearLayout(act)
        gTemp = GaugeView(act, skin, s, "MOTOR", "°C", "Anticongelante", "40", "125")
        gRpm = GaugeView(act, skin, s, "REVOLUCIONES", "rpm", "Motor", "0", "7k")
        gVolt = GaugeView(act, skin, s, "BATERÍA", "V", "Alternador", "11", "15")
        listOf(gTemp, gRpm, gVolt).forEachIndexed { i, g ->
            if (i > 0) gauges.addView(gap(14f))
            g.background = Style.glass(skin, 24f * s)
            gauges.addView(g, lp(0, MATCH, 1f))
        }
        left.addView(gauges, lp(MATCH, px(262f)))
        left.addView(gap(14f))

        val defs = listOf(
            Triple("Consumo ahora", "km/L", "kmpl"), Triple("Promedio del viaje", "km/L", "avg"), Triple("Carga del motor", "%", "load"),
            Triple("Acelerador", "%", "thr"), Triple("Aire de admisión", "°C", "iat"), Triple("Motor encendido", "h", "run")
        )
        val grid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        for (row in 0..1) {
            if (row > 0) grid.addView(gap(14f))
            val r = LinearLayout(act)
            for (col in 0..2) {
                if (col > 0) r.addView(gap(14f))
                val d = defs[row * 3 + col]
                val t = SparkTile(act, skin, s, d.first, d.second, d.third)
                t.background = Style.glass(skin, 18f * s)
                tiles.add(t)
                r.addView(t, lp(0, MATCH, 1f))
            }
            grid.addView(r, lp(MATCH, 0, 1f))
        }
        left.addView(grid, lp(MATCH, 0, 1f))

        // check engine
        val cel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.glass(skin, 24f * s)
            setPadding(px(18f), px(16f), px(18f), px(16f))
        }
        cel.addView(overline("CHECK ENGINE"))
        cel.addView(gap(14f))
        val celRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        celRing = FrameLayout(act)
        celIcon = ImageView(act).apply { setImageResource(R.drawable.ic_engine) }
        celRing.addView(celIcon, FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER))
        celRow.addView(celRing, lp(px(54f), px(54f)))
        celRow.addView(gap(14f))
        val celTexts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        celTitle = text("Sin datos", 19f, skin.text, medium = true)
        celSub = text("Conecta el OBD2 para revisar", 13f, skin.textDim)
        celTexts.addView(celTitle); celTexts.addView(gap(3f)); celTexts.addView(celSub)
        celRow.addView(celTexts, lp(0, WRAP, 1f))
        cel.addView(celRow)
        cel.addView(gap(14f))
        val btns = LinearLayout(act)
        val read = TextView(act).apply {
            text = "Leer códigos"; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * s)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            background = Style.accentFill(skin, 14f * s)
            setOnClickListener { readCodes() }
        }
        Style.glow(read, skin.accent, 8f * s)
        val clear = TextView(act).apply {
            text = "Borrar"; gravity = Gravity.CENTER
            setTextColor(skin.text); setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * s)
            background = pill(Color.argb(15, 255, 255, 255), 14f)
            setOnClickListener { clearCodes() }
        }
        btns.addView(read, lp(0, px(46f), 1f)); btns.addView(gap(8f)); btns.addView(clear, lp(0, px(46f), 1f))
        cel.addView(btns)
        right.addView(cel, lp(MATCH, WRAP))
        right.addView(gap(14f))

        // llantas (próximamente)
        val tires = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = Style.glass(skin, 24f * s)
            setPadding(px(18f), px(16f), px(18f), px(14f))
        }
        val th = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        th.addView(overline("LLANTAS"), lp(0, WRAP, 1f))
        th.addView(text("PRÓXIMAMENTE", 11f, amber).apply {
            letterSpacing = 0.08f
            setPadding(px(10f), px(5f), px(10f), px(5f))
            background = pill(Style.alpha(amber, 30), 10f)
        })
        tires.addView(th)
        val tb = LinearLayout(act).apply { gravity = Gravity.CENTER }
        fun tire(label: String, end: Boolean) = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            gravity = if (end) Gravity.END else Gravity.START
            setPadding(px(12f), px(8f), px(12f), px(8f))
            background = pill(Color.argb(10, 255, 255, 255), 12f)
            addView(text("— psi", 20f, Color.rgb(107, 116, 128), light = true))
            addView(text(label, 11f, skin.textDim))
        }
        val lcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        lcol.addView(tire("Del. izq.", true)); lcol.addView(gap(54f)); lcol.addView(tire("Tras. izq.", true))
        val rcol = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        rcol.addView(tire("Del. der.", false)); rcol.addView(gap(54f)); rcol.addView(tire("Tras. der.", false))
        tb.addView(lcol, lp(0, WRAP, 1f))
        tb.addView(TopCarView(act, skin), lp(px(96f), px(200f)))
        tb.addView(rcol, lp(0, WRAP, 1f))
        tires.addView(tb, lp(MATCH, 0, 1f))
        tires.addView(text("Se activa con sensores TPMS internos por Bluetooth", 12f, skin.textDim).apply { gravity = Gravity.CENTER }, lp(MATCH, WRAP))
        right.addView(tires, lp(MATCH, 0, 1f))

        update(Obd.snap)
        return root
    }

    private lateinit var perf: PerfPages
    private var pageViews: List<View> = emptyList()
    private val tabViews = mutableListOf<TextView>()
    var page = 0
        private set

    private fun tabs(): View {
        val box = LinearLayout(act).apply {
            setPadding(px(4f), px(4f), px(4f), px(4f))
            background = pill(Color.argb(13, 255, 255, 255))
        }
        listOf("En vivo", "Rendimiento", "Mantenimiento").forEachIndexed { i, name ->
            val t = text(name, 14f, skin.textDim).apply {
                gravity = Gravity.CENTER
                setPadding(px(16f), 0, px(16f), 0)
                setOnClickListener { showPage(i) }
            }
            tabViews.add(t)
            box.addView(t, lp(WRAP, MATCH))
        }
        return box
    }

    fun showPage(i: Int) {
        page = i
        pageViews.forEachIndexed { k, v -> v.visibility = if (k == i) View.VISIBLE else View.GONE }
        tabViews.forEachIndexed { k, t ->
            t.setTextColor(if (k == i) Color.WHITE else skin.textDim)
            t.typeface = Typeface.create(if (k == i) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            t.background = if (k == i) Style.accentFill(skin, 999f) else null
        }
        if (i > 0 && ::perf.isInitialized) perf.refresh()
    }

    private fun pill(color: Int, r: Float = 999f) = GradientDrawable().apply {
        cornerRadius = if (r >= 999f) 999f else r * s
        setColor(color)
        setStroke(1, Color.argb(20, 255, 255, 255))
    }

    /** Refresca todo con la última lectura. Llamar en el hilo principal. */
    fun update(o: Obd.Snapshot) {
        if (!::obdDot.isInitialized) return
        val on = o.connected
        (obdDot.background as GradientDrawable).setColor(if (on) green else amber)
        obdLabel.text = o.status

        val t = o.coolant
        val tc = when { t == null -> off; t >= 105 -> red; t < 70 -> Color.rgb(95, 180, 255); else -> green }
        val tc2 = when { t == null -> off; t >= 105 -> amber; t < 70 -> skin.accent; else -> cyan }
        gTemp.set(t?.toString() ?: "—", when { t == null -> "SIN DATOS"; t >= 105 -> "CALIENTE"; t < 70 -> "CALENTANDO"; else -> "NORMAL" },
            t?.let { (it - 40) / 85f } ?: 0f, tc, tc2)
        val rpm = o.rpm
        gRpm.set(rpm?.let { "%,d".format(it) } ?: "—", o.speed?.let { "$it KM/H" } ?: "SIN DATOS",
            rpm?.let { it / 7000f } ?: 0f, if (rpm != null) Style.lighten(skin.accent, 0.2f) else off, if (rpm != null) violet else off)
        val v = o.volts
        val vOk = v != null && v >= 12.2f
        gVolt.set(v?.let { "%.1f".format(it) } ?: "—", when { v == null -> "SIN DATOS"; !vOk -> "BAJO"; v >= 13.2f -> "CARGANDO"; else -> "APAGADO" },
            v?.let { (it - 11f) / 4f } ?: 0f, if (v == null) off else if (vOk) green else red, if (v == null) off else if (vOk) cyan else amber)

        fun f1(x: Float?) = x?.let { "%.1f".format(it) } ?: "—"
        val values = listOf(
            f1(o.kmpl), f1(o.avgKmpl), o.load?.toString() ?: "—", o.throttle?.toString() ?: "—", o.intake?.toString() ?: "—",
            o.runtime?.let { "%d:%02d".format(it / 3600, (it / 60) % 60) } ?: "—"
        )
        tiles.forEachIndexed { i, tile -> tile.set(values[i]) }

        val n = o.dtcCount
        val cc = when { !on || n == null -> off; n == 0 -> green; else -> amber }
        celRing.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(px(2f), cc); setColor(Style.alpha(cc, 30)) }
        celIcon.imageTintList = ColorStateList.valueOf(if (cc == off) Color.rgb(107, 116, 128) else cc)
        celTitle.text = when { !on -> "Sin datos"; n == null -> "Revisando…"; n == 0 -> "Sin fallas"; n == 1 -> "1 falla registrada"; else -> "$n fallas registradas" }
        celSub.text = when {
            !on -> "Conecta el OBD2 para revisar"
            o.dtcCheckedAt == 0L -> "Leyendo la computadora del carro"
            else -> "Revisado a las " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(o.dtcCheckedAt))
        }
    }

    private fun dialog() = AlertDialog.Builder(act, if (skin.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun readCodes() {
        if (!Obd.snap.connected) { dialog().setTitle("OBD2 sin conectar").setMessage("Conecta el adaptador y pon la llave para leer los códigos.").setPositiveButton("OK", null).show(); return }
        Obd.readDtc { codes ->
            act.runOnUiThread {
                val msg = when {
                    codes == null -> "No pude leer los códigos. Intenta de nuevo."
                    codes.isEmpty() -> "No hay códigos de falla guardados. Todo en orden."
                    else -> codes.joinToString("\n") { "• $it" } + "\n\nPregúntale al asistente qué significa cada código."
                }
                dialog().setTitle("Códigos de falla").setMessage(msg).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun clearCodes() {
        if (!Obd.snap.connected) { readCodes(); return }
        dialog().setTitle("¿Borrar los códigos?")
            .setMessage("Se apagará el check engine. Si la falla sigue, volverá a encenderse. Hazlo con el motor apagado y la llave en encendido.")
            .setPositiveButton("Borrar") { _, _ ->
                Obd.clearDtc { ok -> act.runOnUiThread { dialog().setMessage(if (ok) "Códigos borrados." else "No se pudieron borrar.").setPositiveButton("OK", null).show() } }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}

/** Medidor circular segmentado de 270° con brillo. */
class GaugeView(
    ctx: Context, private val skin: LauncherTheme, private val s: Float,
    private val title: String, private val unit: String, private val sub: String, private val min: String, private val max: String
) : View(ctx) {
    private var value = "—"
    private var state = "SIN DATOS"
    private var frac = 0f
    private var c1 = Color.GRAY
    private var c2 = Color.GRAY
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // necesario para el brillo difuminado

    fun set(v: String, st: String, f: Float, col1: Int, col2: Int) {
        value = v; state = st; frac = f.coerceIn(0f, 1f); c1 = col1; c2 = col2
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val pad = 16f * s
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        tp.textSize = 11f * s; tp.letterSpacing = 0.16f
        tp.textAlign = Paint.Align.LEFT; tp.color = skin.textDim
        c.drawText(title, pad, pad + 10f * s, tp)
        tp.textAlign = Paint.Align.RIGHT; tp.color = c1
        c.drawText(state, w - pad, pad + 10f * s, tp)

        val r = 72f * s
        val cx = w / 2f
        val cy = pad + 18f * s + 90f * s
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val dash = DashPathEffect(floatArrayOf(3f * s, 4f * s), 0f)
        arc.strokeWidth = 14f * s
        arc.pathEffect = dash
        arc.shader = null
        arc.color = if (skin.isLight) Color.argb(20, 0, 0, 0) else Color.argb(22, 255, 255, 255)
        c.drawArc(box, 135f, 270f, false, arc)
        if (frac > 0f) {
            val grad = LinearGradient(cx - r, cy + r, cx + r, cy - r, c2, c1, Shader.TileMode.CLAMP)
            glowP.strokeWidth = 14f * s
            glowP.pathEffect = dash
            glowP.shader = grad
            glowP.maskFilter = BlurMaskFilter(6f * s, BlurMaskFilter.Blur.NORMAL)
            glowP.alpha = 200
            c.drawArc(box, 135f, 270f * frac, false, glowP)
            arc.shader = grad
            c.drawArc(box, 135f, 270f * frac, false, arc)
        }
        arc.pathEffect = null; arc.shader = null
        arc.strokeWidth = 1f
        arc.color = Color.argb(13, 255, 255, 255)
        c.drawCircle(cx, cy, 56f * s, arc)

        tp.textAlign = Paint.Align.CENTER
        tp.letterSpacing = -0.02f
        tp.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        tp.textSize = 44f * s; tp.color = skin.text
        val vw = tp.measureText(value)
        tp.textSize = 16f * s
        val uw = tp.measureText(" $unit")
        val startX = cx - (vw + uw) / 2f
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = 44f * s; tp.color = skin.text
        c.drawText(value, startX, cy + 12f * s, tp)
        tp.textSize = 16f * s; tp.color = skin.textDim
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        c.drawText(" $unit", startX + vw, cy + 12f * s, tp)
        tp.textAlign = Paint.Align.CENTER; tp.letterSpacing = 0f
        tp.textSize = 12f * s
        c.drawText(sub, cx, cy + 34f * s, tp)
        tp.textSize = 11f * s; tp.color = Color.rgb(95, 104, 115)
        c.drawText(min, cx - r * 0.72f, cy + r * 0.72f + 18f * s, tp)
        c.drawText(max, cx + r * 0.72f, cy + r * 0.72f + 18f * s, tp)
    }
}

/** Mosaico con valor y mini gráfica de tendencia. */
class SparkTile(ctx: Context, private val skin: LauncherTheme, private val s: Float,
                private val label: String, private val unit: String, private val key: String) : View(ctx) {
    private var value = "—"
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(v: String) { value = v; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val pts = Obd.series(key)
        if (pts.size >= 2 && value != "—") {
            val mn = pts.minOrNull()!!; val mx = pts.maxOrNull()!!
            val span = (mx - mn).takeIf { it > 0f } ?: 1f
            val left = w * 0.45f; val top = h * 0.4f; val bottom = h - 2f
            val path = Path(); val area = Path()
            pts.forEachIndexed { i, p ->
                val x = left + (w - left) * i / (pts.size - 1)
                val y = bottom - 4f * s - (p - mn) / span * (bottom - top - 8f * s)
                if (i == 0) { path.moveTo(x, y); area.moveTo(x, bottom); area.lineTo(x, y) } else { path.lineTo(x, y); area.lineTo(x, y) }
            }
            area.lineTo(w, bottom); area.close()
            fp.shader = LinearGradient(0f, top, 0f, bottom, Style.alpha(skin.accent, 50), Style.alpha(skin.accent, 0), Shader.TileMode.CLAMP)
            c.drawPath(area, fp)
            lp.strokeWidth = 2f * s; lp.color = Style.alpha(skin.accent, 230)
            c.drawPath(path, lp)
        }
        val pad = 16f * s
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        tp.textSize = 12f * s; tp.color = skin.textDim
        c.drawText(label, pad, h / 2f - 8f * s, tp)
        tp.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        tp.textSize = 28f * s; tp.color = skin.text
        c.drawText(value, pad, h / 2f + 22f * s, tp)
        val vw = tp.measureText(value)
        tp.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        tp.textSize = 13f * s; tp.color = skin.textDim
        c.drawText(" $unit", pad + vw, h / 2f + 22f * s, tp)
    }
}

/** Silueta del auto vista desde arriba, con borde de neón. */
class TopCarView(ctx: Context, private val skin: LauncherTheme) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setLayerType(LAYER_TYPE_SOFTWARE, null) }

    override fun onDraw(c: Canvas) {
        val k = width / 96f
        c.save(); c.scale(k, k)
        val body = Path().apply {
            moveTo(28f, 10f); quadTo(48f, 2f, 68f, 10f); quadTo(80f, 16f, 80f, 40f); lineTo(80f, 160f)
            quadTo(80f, 188f, 68f, 192f); quadTo(48f, 198f, 28f, 192f); quadTo(16f, 188f, 16f, 160f); lineTo(16f, 40f)
            quadTo(16f, 16f, 28f, 10f); close()
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = 3f; p.color = Style.alpha(skin.accent, 150)
        p.maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
        c.drawPath(body, p)
        p.maskFilter = null
        p.style = Paint.Style.FILL; p.color = Style.mix(skin.card, Color.BLACK, 0.2f)
        c.drawPath(body, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = skin.accent
        c.drawPath(body, p)
        p.style = Paint.Style.FILL; p.color = Color.argb(18, 255, 255, 255)
        c.drawPath(Path().apply { moveTo(24f, 58f); quadTo(48f, 48f, 72f, 58f); lineTo(68f, 86f); quadTo(48f, 80f, 28f, 86f); close() }, p)
        c.drawPath(Path().apply { moveTo(28f, 132f); quadTo(48f, 138f, 68f, 132f); lineTo(70f, 150f); quadTo(48f, 158f, 26f, 150f); close() }, p)
        p.color = Color.rgb(44, 52, 64)
        for ((x, y) in listOf(4f to 36f, 82f to 36f, 4f to 134f, 82f to 134f)) c.drawRoundRect(RectF(x, y, x + 10f, y + 30f), 4f, 4f, p)
        c.restore()
    }
}
