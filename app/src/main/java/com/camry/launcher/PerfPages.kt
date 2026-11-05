package com.camry.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/** Pestañas "Rendimiento" y "Mantenimiento" del panel Mi Camry. */
class PerfPages(private val act: Activity, private val skin: LauncherTheme, private val s: Float) {

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
    private fun card() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        background = Style.glass(skin, 22f * s)
        setPadding(px(20f), px(18f), px(20f), px(16f))
    }
    private fun dialog() = AlertDialog.Builder(act, if (skin.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private val green = Color.rgb(52, 211, 153)
    private val amber = Color.rgb(245, 185, 66)
    private val red = Color.rgb(255, 90, 95)
    private val orange = Color.rgb(255, 122, 89)

    private fun money(v: Double) = "$" + String.format(Locale.US, "%,.0f", v)

    // ================================================================== rendimiento

    private lateinit var kmplText: TextView
    private lateinit var trendText: TextView
    private lateinit var costKmText: TextView
    private lateinit var spentText: TextView
    private lateinit var spentSub: TextView
    private lateinit var fuelText: TextView
    private lateinit var bars: BarsView
    private lateinit var tripList: LinearLayout

    val rendimiento: View by lazy {
        val row = LinearLayout(act)
        val c = card()
        val h = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        h.addView(overline("RENDIMIENTO · ÚLTIMOS 14 DÍAS"), lp(0, WRAP, 1f))
        trendText = text("", 13f, green)
        h.addView(trendText)
        c.addView(h)
        c.addView(gap(14f))
        // Fila de números: medidas fijas para que el estéreo no las recorte
        val nums = LinearLayout(act).apply { gravity = Gravity.BOTTOM; isBaselineAligned = false }
        fun col(value: TextView, sub: String, w: Float): Pair<LinearLayout, TextView> {
            val l = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; isBaselineAligned = false }
            value.setSingleLine(true)
            l.addView(value, lp(WRAP, WRAP))
            l.addView(gap(5f))
            val st = text(sub, 13f, skin.textDim).apply { setSingleLine(true) }
            l.addView(st, lp(WRAP, WRAP))
            nums.addView(l, lp(px(w), WRAP))
            return l to st
        }
        kmplText = text("—", 56f, skin.text, light = true)
        col(kmplText, "km/L promedio", 170f)
        costKmText = text("—", 26f, skin.text)
        col(costKmText, "por km", 120f)
        spentText = text("—", 26f, skin.text)
        spentSub = col(spentText, "gastado", 220f).second
        c.addView(nums, lp(MATCH, WRAP))
        c.addView(gap(12f))
        bars = BarsView(act, skin, s)
        c.addView(bars, lp(MATCH, 0, 1f))
        c.addView(gap(10f))
        val foot = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        foot.addView(text("Con OBD2 se mide la gasolina real; sin él solo los km", 12f, skin.textDim), lp(0, WRAP, 1f))
        fuelText = text("", 13f, skin.text).apply {
            setPadding(px(12f), px(7f), px(12f), px(7f))
            background = GradientDrawable().apply { cornerRadius = 12f * s; setColor(Color.argb(16, 255, 255, 255)) }
            setOnClickListener { editFuel() }
        }
        foot.addView(fuelText)
        c.addView(foot)
        row.addView(c, lp(0, MATCH, 1.25f))
        row.addView(gap(14f))

        val t = card()
        t.addView(overline("VIAJES RECIENTES"))
        t.addView(gap(8f))
        tripList = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tripList, lp(MATCH, 0, 1f))
        row.addView(t, lp(0, MATCH, 1f))
        row
    }

    // ================================================================== mantenimiento

    private lateinit var odoText: TextView
    private lateinit var items: LinearLayout

    val mantenimiento: View by lazy {
        val row = LinearLayout(act)
        val c = card()
        val h = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        h.addView(overline("MANTENIMIENTO"), lp(0, WRAP, 1f))
        h.addView(text("Toca uno para marcarlo como hecho", 12f, skin.textDim))
        c.addView(h)
        c.addView(gap(10f))
        items = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        c.addView(items, lp(MATCH, 0, 1f))
        row.addView(c, lp(0, MATCH, 1.4f))
        row.addView(gap(14f))

        val r = card()
        r.addView(overline("KILOMETRAJE"))
        r.addView(gap(10f))
        odoText = text("—", 44f, skin.text, light = true)
        r.addView(odoText)
        r.addView(gap(6f))
        r.addView(text("Se actualiza solo con el GPS o el OBD2", 13f, skin.textDim))
        r.addView(gap(14f))
        r.addView(button("Corregir kilometraje") { editOdometer() }, lp(MATCH, px(48f)))
        r.addView(View(act), lp(MATCH, 0, 1f))
        r.addView(overline("AVISO POR VOZ"))
        r.addView(gap(8f))
        r.addView(text("Al encender te recuerda lo que ya toca (300 km antes).", 13f, skin.textDim))
        row.addView(r, lp(0, MATCH, 1f))
        row
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(act).apply {
        text = label; gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_PX, 15f * s); setTextColor(Color.WHITE)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        background = Style.accentFill(skin, 14f * s)
        setOnClickListener { onClick() }
    }

    // ================================================================== datos

    fun refresh() {
        if (::kmplText.isInitialized) refreshPerf()
        if (::odoText.isInitialized) refreshMant()
    }

    private fun refreshPerf() {
        val ctx: Context = act
        val sum = TripLog.summary(ctx)
        val price = TripLog.fuelPrice(ctx)
        kmplText.text = sum.kmpl?.let { String.format(Locale.US, "%.1f", it) } ?: "—"
        costKmText.text = sum.kmpl?.let { "$" + String.format(Locale.US, "%.2f", price / it) } ?: "—"
        val liters = sum.liters.takeIf { it > 0.1 } ?: sum.kmpl?.let { sum.km / it }
        spentText.text = liters?.let { money(it * price) } ?: "—"
        spentSub.text = liters?.let { "gastado (" + it.roundToInt() + " L · " + sum.km.roundToInt() + " km)" } ?: "${sum.km.roundToInt()} km recorridos"
        trendText.text = if (sum.kmpl != null && sum.prevKmpl != null) {
            val d = (sum.kmpl / sum.prevKmpl - 1) * 100
            trendText.setTextColor(if (d >= 0) green else orange)
            (if (d >= 0) "▲ " else "▼ ") + kotlin.math.abs(d).roundToInt() + "% vs. 2 semanas antes"
        } else ""
        fuelText.text = "${TripLog.fuelType(ctx)} · $" + String.format(Locale.US, "%.2f", price) + "/L"
        bars.set(TripLog.daily(ctx))

        tripList.removeAllViews()
        val trips = TripLog.recent(ctx).take(5)
        if (trips.isEmpty()) {
            tripList.addView(text("Aún no hay viajes. Se guardan solos mientras manejas.", 14f, skin.textDim).apply { setPadding(0, px(10f), 0, 0) })
            return
        }
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        for (t in trips) {
            val r = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(9f), 0, px(9f)) }
            val cal = Calendar.getInstance().apply { timeInMillis = t.start }
            val day = when (today - cal.get(Calendar.DAY_OF_YEAR)) {
                0 -> "Hoy"; 1 -> "Ayer"
                else -> SimpleDateFormat("EEE", Locale("es", "MX")).format(cal.time).replace(".", "").replaceFirstChar { it.uppercase() }
            }
            r.addView(text(day, 12f, skin.textDim).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { cornerRadius = 11f * s; setColor(Color.argb(14, 255, 255, 255)) }
            }, lp(px(40f), px(36f)))
            r.addView(gap(12f))
            val mid = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            val from = TripLog.placeName(ctx, t.sLat, t.sLon)
            val to = TripLog.placeName(ctx, t.eLat, t.eLon)
            val name = when {
                from != null && to != null && from != to -> "$from → $to"
                to != null -> "Hacia $to"
                from != null -> "Desde $from"
                else -> "Viaje de las " + SimpleDateFormat("HH:mm", Locale.US).format(cal.time)
            }
            mid.addView(text(name, 15f, skin.text, medium = true))
            mid.addView(gap(3f))
            mid.addView(text(String.format(Locale.US, "%.1f km · %d min · %d km/h prom.", t.km, t.minutes, t.avgSpeed), 12f, skin.textDim))
            r.addView(mid, lp(0, WRAP, 1f))
            val k = t.kmpl
            val col = when { k == null -> skin.textDim; k >= 11 -> green; k >= 9 -> skin.text; k >= 8 -> amber; else -> orange }
            val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            right.addView(text(k?.let { String.format(Locale.US, "%.1f", it) } ?: "—", 18f, col))
            right.addView(text("km/L", 11f, skin.textDim))
            r.addView(right)
            tripList.addView(r)
        }
    }

    private fun refreshMant() {
        val ctx: Context = act
        odoText.text = if (TripLog.hasOdometer(ctx)) String.format(Locale.US, "%,d km", TripLog.odometer(ctx)) else "Toca para capturar"
        odoText.setOnClickListener { editOdometer() }
        items.removeAllViews()
        for (it in TripLog.ITEMS) {
            val every = TripLog.every(ctx, it)
            val rem = TripLog.remaining(ctx, it)
            val frac = rem?.let { r -> 1f - r.toFloat() / every } ?: 0f
            val col = when { rem == null -> skin.textDim; rem <= 0 -> red; rem <= 1000 -> amber; else -> green }
            val label = when {
                !TripLog.hasOdometer(ctx) -> "Falta kilometraje"
                rem == null -> "Sin registrar"
                rem <= 0 -> "¡Ya toca!"
                else -> String.format(Locale.US, "en %,d km", rem)
            }
            val r = LinearLayout(act).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, px(12f), 0, px(12f))
                setOnClickListener { _ -> itemDialog(it) }
            }
            val names = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            names.addView(text(it.name, 16f, skin.text))
            names.addView(gap(3f))
            names.addView(text(String.format(Locale.US, "cada %,d km", every), 12f, skin.textDim))
            r.addView(names, lp(px(190f), WRAP))
            val track = FrameLayout(act).apply { background = GradientDrawable().apply { cornerRadius = 5f * s; setColor(Color.argb(18, 255, 255, 255)) } }
            val fill = View(act).apply { background = GradientDrawable().apply { cornerRadius = 5f * s; setColor(col) } }
            track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
            track.post { fill.layoutParams = FrameLayout.LayoutParams((track.width * frac.coerceIn(0f, 1f)).toInt(), MATCH) }
            r.addView(track, lp(0, px(10f), 1f))
            r.addView(gap(14f))
            r.addView(text(label, 14f, col).apply { gravity = Gravity.END }, lp(px(130f), WRAP))
            items.addView(r)
        }
    }

    // ================================================================== diálogos

    private fun askNumber(title: String, msg: String?, current: String, decimal: Boolean, save: (String) -> Unit) {
        val input = EditText(act).apply {
            setText(current); setSelection(text.length)
            inputType = InputType.TYPE_CLASS_NUMBER or if (decimal) InputType.TYPE_NUMBER_FLAG_DECIMAL else 0
        }
        val box = FrameLayout(act).apply { setPadding(px(24f), px(8f), px(24f), 0); addView(input) }
        dialog().setTitle(title).apply { msg?.let { setMessage(it) } }.setView(box)
            .setPositiveButton("Guardar") { _, _ -> save(input.text.toString().trim().replace(",", "")) }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun editOdometer() {
        askNumber("Kilometraje actual", "Lo que marca el tablero ahora.", if (TripLog.hasOdometer(act)) TripLog.odometer(act).toString() else "", false) { v ->
            v.toIntOrNull()?.let { TripLog.setOdometer(act, it); refreshMant() }
        }
    }

    private fun editFuel() {
        val types = arrayOf("Magna", "Premium", "Diésel")
        dialog().setTitle("¿Qué gasolina usas?")
            .setItems(types) { _, w ->
                askNumber("Precio por litro de ${types[w]}", null, String.format(Locale.US, "%.2f", TripLog.fuelPrice(act)), true) { v ->
                    v.toFloatOrNull()?.let { TripLog.setFuel(act, it, types[w]); refreshPerf() }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    private fun itemDialog(it: TripLog.Item) {
        if (!TripLog.hasOdometer(act)) { editOdometer(); return }
        dialog().setTitle(it.name)
            .setItems(arrayOf("Lo acabo de hacer", "Lo hice en otro kilometraje…", "Cambiar cada cuántos km")) { _, w ->
                when (w) {
                    0 -> { TripLog.markDone(act, it); refreshMant() }
                    1 -> askNumber("¿A qué kilometraje se hizo?", null, TripLog.odometer(act).toString(), false) { v ->
                        v.toIntOrNull()?.let { km -> TripLog.markDone(act, it, km); refreshMant() }
                    }
                    2 -> askNumber("Cada cuántos km", null, TripLog.every(act, it).toString(), false) { v ->
                        v.toIntOrNull()?.takeIf { k -> k > 0 }?.let { k -> TripLog.setEvery(act, it, k); refreshMant() }
                    }
                }
            }.setNegativeButton("Cerrar", null).show()
    }
}

/** Barras de km/L por día; azul = día normal, verde = día con buen rendimiento (carretera). */
class BarsView(ctx: Context, private val skin: LauncherTheme, private val s: Float) : View(ctx) {
    private var data: List<Pair<Calendar, Double?>> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val letters = arrayOf("D", "L", "M", "M", "J", "V", "S")

    fun set(d: List<Pair<Calendar, Double?>>) { data = d; invalidate() }

    override fun onDraw(c: Canvas) {
        if (data.isEmpty()) return
        val w = width.toFloat(); val h = height.toFloat()
        val labelH = 18f * s
        val chartH = h - labelH
        val n = data.size
        val gapW = 7f * s
        val bw = (w - gapW * (n - 1)) / n
        val max = (data.mapNotNull { it.second }.maxOrNull() ?: 12.0).coerceAtLeast(12.0) * 1.1
        val avg = data.mapNotNull { it.second }.average().takeIf { !it.isNaN() }
        p.color = if (skin.isLight) Color.argb(25, 0, 0, 0) else Color.argb(20, 255, 255, 255)
        c.drawRect(0f, chartH - 1f, w, chartH, p)
        data.forEachIndexed { i, (day, v) ->
            val x = i * (bw + gapW)
            val top: Float
            if (v == null) {
                top = chartH - 4f * s
                p.shader = null; p.color = if (skin.isLight) Color.argb(25, 0, 0, 0) else Color.argb(22, 255, 255, 255)
            } else {
                top = chartH - (v / max * chartH).toFloat()
                val good = avg != null && v >= avg * 1.15
                val c1 = if (good) Color.rgb(110, 231, 183) else Style.lighten(skin.accent, 0.3f)
                val c2 = if (good) Color.rgb(52, 211, 153) else skin.accent
                p.shader = LinearGradient(0f, top, 0f, chartH, c1, c2, Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(RectF(x, top, x + bw, chartH), 5f * s, 5f * s, p)
            p.shader = null
            tp.textSize = 10f * s; tp.color = skin.textDim
            c.drawText(letters[day.get(Calendar.DAY_OF_WEEK) - 1], x + bw / 2f, h - 3f * s, tp)
            if (v != null && bw > 28f * s) {
                tp.textSize = 10f * s; tp.color = skin.text
                c.drawText(String.format(Locale.US, "%.1f", v), x + bw / 2f, top - 4f * s, tp)
            }
        }
    }
}
