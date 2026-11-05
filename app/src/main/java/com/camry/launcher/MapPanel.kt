package com.camry.launcher

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.roundToInt

/** Mapa a pantalla completa (deslizar a la derecha desde el inicio), fundido con la interfaz. */
class MapPanel(
    private val act: Activity,
    private val skin: LauncherTheme,
    private val s: Float,
    private val start: GeoPoint,
    private val actions: Actions
) {
    interface Actions {
        fun back()
        fun openMaps()
        fun openCarPlay()
        fun askDestination()
        fun assistant()
        fun navigate(dest: String)
        fun places()
    }

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

    private val glassBg get() = if (skin.isLight) Color.argb(225, 250, 251, 253) else Color.argb(225, 20, 27, 39)
    private fun glass(r: Float) = GradientDrawable().apply {
        cornerRadius = r * s
        setColor(glassBg)
        setStroke(1, if (skin.isLight) Color.argb(25, 0, 0, 0) else Color.argb(24, 255, 255, 255))
    }
    private fun icon(res: Int, size: Float, color: Int) = ImageView(act).apply {
        setImageResource(res); imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(px(size), px(size))
    }

    var map: MapView? = null
        private set
    private var marker: Marker? = null
    private var follow = true
    private lateinit var speedText: TextView
    private lateinit var compassText: TextView
    private lateinit var streetText: TextView
    private lateinit var cityText: TextView
    private val chipTimes = HashMap<String, TextView>()
    private var lastReverseAt = 0L
    private var lastReverse: Location? = null

    val view: View by lazy { build() }

    private fun build(): View {
        val root = FrameLayout(act).apply { setBackgroundColor(skin.background); isClickable = true }

        OsmConfig.getInstance().load(act, act.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        OsmConfig.getInstance().userAgentValue = Commute.UA
        OsmConfig.getInstance().osmdroidBasePath = File(act.cacheDir, "osmdroid")
        OsmConfig.getInstance().osmdroidTileCache = File(act.cacheDir, "osmdroid/tiles")
        val mv = MapView(act)
        mv.setTileSource(XYTileSource("OSM-Camry", 0, 19, 256, ".png", arrayOf("https://tile.openstreetmap.org/")))
        mv.setMultiTouchControls(true)
        mv.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mv.isTilesScaledToDpi = true
        mv.overlayManager.tilesOverlay.setLoadingBackgroundColor(skin.background)
        mv.overlayManager.tilesOverlay.setLoadingLineColor(skin.background)
        if (!skin.isLight) mv.overlayManager.tilesOverlay.setColorFilter(darkMapFilter(skin.accent))
        mv.controller.setZoom(16.0)
        mv.controller.setCenter(start)
        // si arrastras el mapa deja de seguirte hasta que toques "centrar"
        mv.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_MOVE) follow = false; false }
        val mk = Marker(mv).apply {
            icon = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(skin.accent); setStroke(px(4f), Color.WHITE); setSize(px(28f), px(28f))
            }
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            position = start
            setOnMarkerClickListener { _, _ -> true }
        }
        mv.overlays.add(mk)
        map = mv; marker = mk
        root.addView(mv, FrameLayout.LayoutParams(MATCH, MATCH))

        // fundido con la interfaz: degradados en los cuatro bordes
        val bg = skin.background
        fun fade(o: GradientDrawable.Orientation, strong: Int) = View(act).apply {
            background = GradientDrawable(o, intArrayOf(Style.alpha(bg, strong), Style.alpha(bg, 0)))
        }
        root.addView(fade(GradientDrawable.Orientation.TOP_BOTTOM, 220), FrameLayout.LayoutParams(MATCH, px(150f), Gravity.TOP))
        root.addView(fade(GradientDrawable.Orientation.BOTTOM_TOP, 240), FrameLayout.LayoutParams(MATCH, px(170f), Gravity.BOTTOM))
        root.addView(fade(GradientDrawable.Orientation.LEFT_RIGHT, 170), FrameLayout.LayoutParams(px(90f), MATCH, Gravity.START))
        root.addView(fade(GradientDrawable.Orientation.RIGHT_LEFT, 170), FrameLayout.LayoutParams(px(90f), MATCH, Gravity.END))

        // ---- búsqueda y accesos rápidos
        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val search = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20f), 0, px(7f), 0)
            background = glass(27f)
            setOnClickListener { actions.askDestination() }
        }
        Style.glow(search, Color.BLACK, 10f * s)
        search.addView(icon(R.drawable.ic_pin, 20f, skin.textDim))
        search.addView(gap(12f))
        search.addView(text("¿A dónde vamos?", 17f, skin.textDim), lp(0, WRAP, 1f))
        val mic = FrameLayout(act).apply {
            background = Style.accentFill(skin, 20f * s)
            contentDescription = "Decir el destino"
            setOnClickListener { actions.assistant() }
        }
        Style.glow(mic, skin.accent, 6f * s)
        mic.addView(icon(R.drawable.ic_mic, 18f, Color.WHITE), FrameLayout.LayoutParams(px(18f), px(18f), Gravity.CENTER))
        search.addView(mic, lp(px(40f), px(40f)))
        top.addView(search, lp(px(420f), px(54f)))
        top.addView(gap(10f))
        chipsRow = LinearLayout(act)
        val scroll = android.widget.HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(chipsRow) }
        top.addView(scroll, lp(px(800f), WRAP))
        rebuildChips()
        root.addView(top, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(px(20f), px(18f), 0, 0) })

        // ---- velocidad y brújula
        val right = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val sp = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(glassBg); setStroke(px(2f), skin.accent) }
        }
        Style.glow(sp, skin.accent, 8f * s)
        speedText = text("0", 34f, skin.text, light = true)
        sp.addView(speedText)
        sp.addView(text("KM/H", 10f, skin.textDim).apply { letterSpacing = 0.2f })
        right.addView(sp, lp(px(92f), px(92f)))
        right.addView(gap(10f))
        compassText = text("N", 12f, Color.rgb(255, 90, 95), medium = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(glassBg) }
        }
        right.addView(compassText, lp(px(48f), px(48f)))
        root.addView(right, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply { setMargins(0, px(18f), px(36f), 0) })

        // ---- zoom y centrar
        val zoom = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun zbtn(label: String, desc: String, onClick: () -> Unit) = TextView(act).apply {
            text = label; gravity = Gravity.CENTER; contentDescription = desc
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 24f * s); setTextColor(skin.text)
            background = glass(14f)
            setOnClickListener { onClick() }
        }
        zoom.addView(zbtn("+", "Acercar") { mv.controller.zoomIn() }, lp(px(50f), px(50f)))
        zoom.addView(gap(8f))
        zoom.addView(zbtn("−", "Alejar") { mv.controller.zoomOut() }, lp(px(50f), px(50f)))
        zoom.addView(gap(8f))
        val center = FrameLayout(act).apply {
            background = glass(14f); contentDescription = "Centrar en mi ubicación"
            setOnClickListener { follow = true; marker?.position?.let { mv.controller.animateTo(it) } }
        }
        center.addView(icon(R.drawable.ic_nav, 20f, skin.accent), FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
        zoom.addView(center, lp(px(50f), px(50f)))
        root.addView(zoom, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL).apply { setMargins(0, px(40f), px(36f), 0) })

        // ---- barra inferior
        val bar = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22f), 0, px(12f), 0)
            background = glass(26f)
        }
        Style.glow(bar, Color.BLACK, 12f * s)
        val where = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        where.addView(text("ESTÁS EN", 11f, skin.textDim).apply { letterSpacing = 0.16f })
        where.addView(gap(3f))
        streetText = text("Buscando tu ubicación…", 19f, skin.text, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        where.addView(streetText)
        where.addView(gap(3f))
        cityText = text("", 13f, skin.textDim).apply { maxLines = 1 }
        where.addView(cityText)
        bar.addView(where, lp(0, WRAP, 1f))
        val nav = LinearLayout(act).apply {
            gravity = Gravity.CENTER; setPadding(px(20f), 0, px(20f), 0)
            background = Style.accentFill(skin, 16f * s)
            setOnClickListener { actions.openMaps() }
        }
        Style.glow(nav, skin.accent, 8f * s)
        nav.addView(icon(R.drawable.ic_nav, 18f, Color.WHITE))
        nav.addView(gap(8f))
        nav.addView(text("Navegar con Maps", 16f, Color.WHITE, medium = true))
        bar.addView(nav, lp(WRAP, px(52f)))
        bar.addView(gap(10f))
        val cp = LinearLayout(act).apply {
            gravity = Gravity.CENTER; setPadding(px(16f), 0, px(16f), 0)
            background = GradientDrawable().apply { cornerRadius = 16f * s; setColor(Color.argb(16, 255, 255, 255)); setStroke(1, Color.argb(22, 255, 255, 255)) }
            setOnClickListener { actions.openCarPlay() }
        }
        cp.addView(icon(R.drawable.ic_carplay, 18f, skin.textDim))
        cp.addView(gap(8f))
        cp.addView(text("CarPlay", 15f, skin.textDim))
        bar.addView(cp, lp(WRAP, px(52f)))
        root.addView(bar, FrameLayout.LayoutParams(MATCH, px(78f), Gravity.BOTTOM).apply { setMargins(px(20f), 0, px(36f), px(18f)) })
        root.addView(text("© colaboradores de OpenStreetMap", 10f, skin.textDim), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(px(26f), 0, 0, px(102f)) })

        // ---- pestaña para volver al inicio
        val tab = FrameLayout(act).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(11f * s, 11f * s, 0f, 0f, 0f, 0f, 11f * s, 11f * s)
                setColor(Color.argb(30, 255, 255, 255)); setStroke(1, Color.argb(26, 255, 255, 255))
            }
            contentDescription = "Volver al inicio"
            setOnClickListener { actions.back() }
        }
        tab.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back); imageTintList = ColorStateList.valueOf(skin.textDim)
        }, FrameLayout.LayoutParams(px(14f), px(14f), Gravity.CENTER))
        root.addView(tab, FrameLayout.LayoutParams(px(22f), px(84f), Gravity.END or Gravity.CENTER_VERTICAL))
        return root
    }

    fun onLocation(l: Location, kmh: Float, heading: String) {
        if (map == null) return
        val gp = GeoPoint(l.latitude, l.longitude)
        marker?.position = gp
        speedText.text = kmh.roundToInt().toString()
        if (l.hasBearing() && kmh >= 5f) compassText.text = arrayOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")[(((l.bearing % 360f) + 382.5f) / 45f).toInt() % 8]
        if (follow) map?.controller?.animateTo(gp)
        map?.invalidate()
        // calle actual, como máximo cada 45 s y si te moviste 150 m
        val now = System.currentTimeMillis()
        val prev = lastReverse
        if (now - lastReverseAt > 45_000L && (prev == null || prev.distanceTo(l) > 150f)) {
            lastReverseAt = now; lastReverse = l
            Thread {
                val r = Commute.reverse(l.latitude, l.longitude)
                act.runOnUiThread {
                    if (r != null) { streetText.text = r.first; cityText.text = r.second + if (heading.startsWith("Rumbo")) " · $heading" else "" }
                    else if (streetText.text.startsWith("Buscando")) streetText.text = "%.4f, %.4f".format(l.latitude, l.longitude)
                }
            }.start()
        }
    }

    private lateinit var chipsRow: LinearLayout

    private fun chip(label: String, color: Int, timeKey: String?, onClick: () -> Unit, onLong: (() -> Unit)? = null) {
        val c = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14f), 0, px(14f), 0)
            background = glass(19f)
            setOnClickListener { onClick() }
            onLong?.let { l -> setOnLongClickListener { l(); true } }
        }
        c.addView(View(act).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) } }, lp(px(9f), px(9f)))
        c.addView(gap(8f))
        c.addView(text(label, 14f, skin.text))
        if (timeKey != null) {
            c.addView(gap(8f))
            val t = text(Commute.cached(timeKey)?.let { "${it.minutes} min" } ?: "", 14f, skin.textDim)
            chipTimes[timeKey] = t
            c.addView(t)
        }
        chipsRow.addView(c, lp(WRAP, px(38f)))
        chipsRow.addView(gap(8f))
    }

    /** Casa, Trabajo, tus lugares guardados, Gasolinera y "+ Lugar". */
    fun rebuildChips() {
        if (!::chipsRow.isInitialized) return
        chipsRow.removeAllViews()
        chipTimes.clear()
        chip("Casa", skin.accent, "home", { actions.navigate("casa") })
        chip("Trabajo", Color.rgb(138, 92, 255), "work", { actions.navigate("trabajo") })
        val cols = intArrayOf(Color.rgb(52, 211, 153), Color.rgb(33, 212, 253), Color.rgb(255, 79, 163), Color.rgb(255, 122, 89))
        Places.all(act).forEachIndexed { i, p -> chip(p.name, cols[i % cols.size], null, { actions.navigate(p.name) }, { actions.places() }) }
        chip("Gasolinera", Color.rgb(245, 185, 66), null, { actions.navigate("gasolinera") })
        chip("＋ Lugar", skin.textDim, null, { actions.places() })
    }

    fun setEta(key: String, minutes: Int?) { chipTimes[key]?.text = minutes?.let { "$it min" } ?: "" }

    fun refreshTiles() {
        val mv = map ?: return
        runCatching { mv.tileProvider.clearTileCache() }
        mv.invalidate()
    }

    companion object {
        /** Convierte el mapa claro de OpenStreetMap en uno oscuro con un toque del color del tema. */
        fun darkMapFilter(accent: Int): android.graphics.ColorMatrixColorFilter {
            val m = android.graphics.ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f
            ))
            m.postConcat(android.graphics.ColorMatrix().apply { setSaturation(0.15f) })
            val r = Color.red(accent) / 255f; val g = Color.green(accent) / 255f; val b = Color.blue(accent) / 255f
            m.postConcat(android.graphics.ColorMatrix(floatArrayOf(
                0.62f, 0f, 0f, 0f, 6f + 14f * r, 0f, 0.66f, 0f, 0f, 8f + 14f * g, 0f, 0f, 0.74f, 0f, 12f + 18f * b, 0f, 0f, 0f, 1f, 0f
            )))
            return android.graphics.ColorMatrixColorFilter(m)
        }
    }
}
