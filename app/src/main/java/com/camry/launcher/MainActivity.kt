package com.camry.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import kotlin.math.min
import org.osmdroid.config.Configuration as OsmConfig
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.io.File
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var skin: LauncherTheme
    private var s = 1f // escala: el diseño está hecho a 1024 x 600

    private lateinit var road: RoadView
    private lateinit var carplayStatus: TextView
    private lateinit var musicTitle: TextView
    private lateinit var musicArtist: TextView
    private lateinit var musicArt: ImageView
    private lateinit var musicProgress: ProgressBar
    private lateinit var musicPos: TextView
    private lateinit var musicDur: TextView
    private lateinit var playBtn: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { ThemeManager.prefs(this) }

    private var locationManager: LocationManager? = null
    private var lastFix = 0L
    private var controller: MediaController? = null
    private var mediaManager: MediaSessionManager? = null
    private var mediaNeedsAccess = false
    private lateinit var mapPanel: MapPanel
    private var mapLabel: TextView? = null
    @Volatile private var online = false
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val tripViews = HashMap<String, Array<View>>()
    private var weatherText: TextView? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skin = ThemeManager.current(this)
        val dm = resources.displayMetrics
        s = min(maxOf(dm.widthPixels, dm.heightPixels) / 1024f, min(dm.widthPixels, dm.heightPixels) / 600f)
        setContentView(buildUi())
        Profiles.onChange = { if (visible && !isFinishing) recreate() }
        askPermissions()
        registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        // El encendido lo vigila CarService (sigue vivo aunque Android cierre esta pantalla)
        CarService.start(this)
        Obd.start(this)
        Obd.listen(obdListener)
        setupMedia()
        watchNetwork()
        handler.post(ticker)
        // Primer arranque del estéreo (no al cambiar de tema)
        if (!greetedThisRun) {
            greetedThisRun = true
            val up = SystemClock.elapsedRealtime()
            EventLog.add(this, "Launcher iniciado (estéreo encendido hace ${up / 1000}s)")
            if (prefs.getBoolean("startup_anim", true)) playStartup()
            // el saludo de arranque lo hace CarService
        }
    }

    override fun onResume() {
        super.onResume()
        ThemeManager.current(this).let { if (it.id != skin.id || it.accent != skin.accent) { recreate(); return } }
        if (::mapPanel.isInitialized) mapPanel.map?.onResume()
        if (lastFix == 0L || SystemClock.elapsedRealtime() - lastFix > 10_000L) startLocation(force = locationManager != null)
        refreshIphoneStatus()
        if (mediaNeedsAccess) setupMedia()
    }

    override fun onPause() {
        if (::mapPanel.isInitialized) mapPanel.map?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (Profiles.onChange != null) Profiles.onChange = null
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        Obd.unlisten(obdListener)
        runCatching { netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } }
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { locationManager?.unregisterGnssStatusCallback(gnssCallback) }
        runCatching { mediaManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(mediaCallback)
        super.onDestroy()
    }

    @Deprecated("Launcher: el botón atrás no hace nada aquí")
    override fun onBackPressed() { if (page != 0) goPage(0) /* si no, es la pantalla de inicio */ }

    // ---------------------------------------------------------------- interfaz

    private fun px(v: Float): Int = (v * s).roundToInt()

    private fun label(str: String, size: Float, color: Int, medium: Boolean = false): TextView =
        TextView(this).apply {
            text = str
            setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
            setTextColor(color)
            typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            includeFontPadding = false
        }

    private fun icon(res: Int, size: Float, color: Int): ImageView = ImageView(this).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(px(size), px(size))
    }

    private fun rounded(color: Int, radius: Float, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius * s
        setColor(color)
        if (stroke) setStroke(1, if (skin.isLight) Color.argb(22, 0, 0, 0) else Color.argb(16, 255, 255, 255))
    }

    private fun pressable(color: Int, radius: Float) = RippleDrawable(
        ColorStateList.valueOf(if (skin.isLight) Color.argb(40, 0, 0, 0) else Color.argb(50, 255, 255, 255)),
        rounded(color, radius), rounded(Color.WHITE, radius)
    )

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Style.glass(skin, 24f * s)
        setPadding(px(20f), px(18f), px(20f), px(18f))
    }

    /** Botón principal con degradado del acento y brillo de color. */
    private fun accentButton(radius: Float): Drawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), Style.accentFill(skin, radius * s), null
    )

    /** Botón secundario de vidrio. */
    private fun glassButton(radius: Float): Drawable = RippleDrawable(
        ColorStateList.valueOf(if (skin.isLight) Color.argb(40, 0, 0, 0) else Color.argb(40, 255, 255, 255)),
        GradientDrawable().apply {
            cornerRadius = radius * s
            setColor(if (skin.isLight) Color.argb(14, 0, 0, 0) else Color.argb(14, 255, 255, 255))
            setStroke(1, if (skin.isLight) Color.argb(22, 0, 0, 0) else Color.argb(20, 255, 255, 255))
        }, null
    )

    private fun overline(str: String) = label(str, 11f, skin.textDim).apply { letterSpacing = 0.16f }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun gap(v: Float) = View(this).apply { layoutParams = lp(px(v), px(v)) }
    private fun spacer() = View(this).apply { layoutParams = lp(0, 0, 1f) }

    private lateinit var homePage: View
    private lateinit var panel: VehiclePanel

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.background = AmbientDrawable(skin)
        skin.backgroundImage?.let { path ->
            decode(path)?.let { bmp ->
                root.addView(ImageView(this).apply {
                    setImageBitmap(bmp)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(MATCH, MATCH))
            }
        }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16f), px(16f), px(16f), px(14f))
        }
        homePage = column
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        column.addView(row, lp(MATCH, 0, 1f))
        column.addView(gap(14f))
        column.addView(buildDock(), LinearLayout.LayoutParams(MATCH, px(74f)).apply { setMargins(px(40f), 0, px(40f), 0) })

        row.addView(buildCommuteCard(), lp(px(270f), MATCH))
        row.addView(gap(14f))
        val center = FrameLayout(this)
        road = RoadView(this, skin).apply {
            setOnLongClickListener { showOptions(); true }
        }
        center.addView(road, FrameLayout.LayoutParams(MATCH, MATCH))
        // pestaña para abrir el panel del vehículo
        val tab = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(11f * s, 11f * s, 0f, 0f, 0f, 0f, 11f * s, 11f * s)
                setColor(Color.argb(15, 255, 255, 255))
                setStroke(1, Color.argb(20, 255, 255, 255))
            }
            contentDescription = "Abrir panel del vehículo"
            setOnClickListener { openPanel() }
        }
        tab.addView(icon(R.drawable.ic_back, 14f, skin.textDim), FrameLayout.LayoutParams(px(14f), px(14f), Gravity.CENTER))
        center.addView(tab, FrameLayout.LayoutParams(px(22f), px(84f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = -px(8f) })
        // pestaña para abrir el mapa completo (lado contrario)
        val mapTab = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(0f, 0f, 11f * s, 11f * s, 11f * s, 11f * s, 0f, 0f)
                setColor(Color.argb(15, 255, 255, 255))
                setStroke(1, Color.argb(20, 255, 255, 255))
            }
            contentDescription = "Abrir mapa"
            setOnClickListener { goPage(-1) }
        }
        mapTab.addView(icon(R.drawable.ic_back, 14f, skin.textDim).apply { rotation = 180f }, FrameLayout.LayoutParams(px(14f), px(14f), Gravity.CENTER))
        center.addView(mapTab, FrameLayout.LayoutParams(px(22f), px(84f), Gravity.START or Gravity.CENTER_VERTICAL).apply { leftMargin = -px(8f) })
        row.addView(center, lp(0, MATCH, 1f))
        row.addView(gap(14f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(buildClockCard(), lp(MATCH, WRAP))
        right.addView(gap(14f))
        right.addView(buildMusicCard(), lp(MATCH, 0, 1f))
        row.addView(right, lp(px(270f), MATCH))

        // panel del vehículo, fuera de la pantalla a la derecha
        panel = VehiclePanel(this, skin, s) { closePanel() }
        val pv = panel.view
        root.addView(pv, FrameLayout.LayoutParams(MATCH, MATCH))
        pv.visibility = View.INVISIBLE
        // mapa completo, fuera de la pantalla a la izquierda
        mapPanel = MapPanel(this, skin, s, startPoint(), object : MapPanel.Actions {
            override fun back() = goPage(0)
            override fun openMaps() = this@MainActivity.openMaps()
            override fun openCarPlay() = this@MainActivity.openCarPlay()
            override fun askDestination() = askText("¿A dónde vamos?", "Lugar o dirección", "") { if (it.isNotBlank()) toast(CarActions.navigate(this@MainActivity, it)) }
            override fun assistant() = openAssistant()
            override fun navigate(dest: String) { toast(CarActions.navigate(this@MainActivity, dest)) }
            override fun places() = showPlaces()
        })
        val mp = mapPanel.view
        root.addView(mp, FrameLayout.LayoutParams(MATCH, MATCH))
        mp.visibility = View.INVISIBLE
        root.post { pv.translationX = root.width.toFloat(); mp.translationX = -root.width.toFloat() }
        return root
    }

    // ---------------------------------------------------------------- panel deslizable

    private val obdListener: (Obd.Snapshot) -> Unit = { snap -> handler.post { panel.update(snap); road.invalidate() } }

    /** Página visible: -1 = mapa, 0 = inicio, 1 = Mi Camry. */
    private var page = 0
    private val panelOpen get() = page != 0

    fun openPanel() = goPage(1)
    fun closePanel() = goPage(0)

    fun goPage(to: Int) {
        if (to == page) return
        val w = (homePage.parent as View).width.toFloat()
        val old = page
        page = to
        fun viewOf(p: Int) = when (p) { -1 -> mapPanel.view; 1 -> panel.view; else -> null }
        // la página que se va
        viewOf(old)?.let { v -> v.animate().translationX(if (old < 0) -w else w).setDuration(240).withEndAction { if (page != old) v.visibility = View.INVISIBLE }.start() }
        // la que llega
        val v = viewOf(to)
        if (v != null) {
            if (old == 0) v.translationX = if (to < 0) -w else w
            v.visibility = View.VISIBLE
            v.animate().translationX(0f).setDuration(260).start()
            homePage.animate().translationX(if (to < 0) w * 0.25f else -w * 0.25f).alpha(0.3f).setDuration(260).start()
        } else {
            homePage.animate().translationX(0f).alpha(1f).setDuration(240).start()
        }
        if (to == 1) panel.update(Obd.snap)
        if (to == -1) { mapPanel.map?.onResume(); refreshCommute() }
    }

    private var flingStartX = 0f

    private val swipe by lazy {
        android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (kotlin.math.abs(vx) < 1200f || kotlin.math.abs(vx) < kotlin.math.abs(vy) * 1.5f) return false
                // en el mapa, arrastrar mueve el mapa: solo se regresa deslizando desde el borde derecho
                if (page == -1 && flingStartX < (homePage.parent as View).width - 90f * s) return false
                if (vx < 0 && page < 1) { goPage(page + 1); return true }
                if (vx > 0 && page > -1) { goPage(page - 1); return true }
                return false
            }
        })
    }

    private var lastTouchAt = 0L

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) flingStartX = ev.x
        runCatching { swipe.onTouchEvent(ev) }
        return super.dispatchTouchEvent(ev)
    }

    /** Elegir el adaptador OBD2 entre los dispositivos Bluetooth emparejados. */
    fun chooseObdAdapter() {
        val devs = Obd.bonded()
        if (devs.isEmpty()) {
            ObdPairing(this) { dialog() }.start()
            return
        }
        val current = Obd.address(this)
        val names = devs.map { (it.name ?: "Sin nombre") + if (it.address == current) "  ✓" else "" }.toTypedArray()
        dialog().setTitle("Adaptador OBD2")
            .setItems(names) { _, which ->
                Obd.setAddress(this, devs[which].address)
                toast("Conectando a ${devs[which].name}…")
            }
            .setNeutralButton("Buscar otro") { _, _ -> ObdPairing(this) { dialog() }.start() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r * s)
        }
    }

    /** Tiempo a Casa y al Trabajo (reemplaza al mapa; el mapa completo está deslizando a la derecha). */
    private fun buildCommuteCard(): View {
        val c = card()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(View(this).apply { background = rounded(Color.rgb(52, 211, 153), 4f) }, lp(px(8f), px(8f)))
        header.addView(gap(8f))
        mapLabel = overline("TRAYECTOS")
        header.addView(mapLabel, lp(0, WRAP, 1f))
        header.addView(label("Mapa ›", 12f, skin.textDim).apply { setOnClickListener { goPage(-1) } })
        c.addView(header)
        c.addView(gap(12f))
        for ((key, name, iconRes, color) in listOf(
            arrayOf("home", "Casa", R.drawable.ic_home, skin.accent),
            arrayOf("work", "Trabajo", R.drawable.ic_work, Color.rgb(138, 92, 255))
        ).map { Quad(it[0] as String, it[1] as String, it[2] as Int, it[3] as Int) }) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(12f), px(12f), px(12f), px(12f))
                background = glassButton(18f)
                setOnClickListener { tripTap(key) }
                setOnLongClickListener { editPlace(key); true }
            }
            val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val sq = FrameLayout(this).apply { background = rounded(Style.alpha(color, 50), 12f) }
            sq.addView(icon(iconRes, 20f, Style.lighten(color, 0.3f)), FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
            top.addView(sq, lp(px(40f), px(40f)))
            top.addView(gap(10f))
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(label(name, 16f, skin.text, medium = true))
            texts.addView(gap(3f))
            val sub = label("", 12f, skin.textDim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            texts.addView(sub)
            top.addView(texts, lp(0, WRAP, 1f))
            val mins = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
            val big = label("—", 30f, skin.text).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
            mins.addView(big)
            mins.addView(label("min", 11f, skin.textDim))
            top.addView(mins)
            b.addView(top)
            b.addView(gap(10f))
            val segs = LinearLayout(this)
            val segViews = Array<View>(10) { View(this) }
            segViews.forEachIndexed { i, v ->
                if (i > 0) segs.addView(gap(3f))
                segs.addView(v, lp(0, px(5f), 1f))
            }
            b.addView(segs, lp(MATCH, px(5f)))
            tripViews[key] = arrayOf<View>(sub, big, *segViews)
            c.addView(b, lp(MATCH, WRAP))
            c.addView(gap(10f))
        }
        val more = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(14f), 0, px(12f), 0)
            background = glassButton(14f)
            setOnClickListener { showPlaces() }
        }
        more.addView(icon(R.drawable.ic_pin, 18f, skin.textDim))
        more.addView(gap(10f))
        placesLabel = label("", 14f, skin.text)
        more.addView(placesLabel, lp(0, WRAP, 1f))
        more.addView(label("›", 18f, skin.textDim))
        c.addView(more, lp(MATCH, px(44f)))
        updatePlacesLabel()
        c.addView(spacer())
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val maps = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = accentButton(13f)
            setOnClickListener { openMaps() }
            setOnLongClickListener { pickApp("slot_maps"); true }
        }
        Style.glow(maps, skin.accent, 8f * s)
        maps.addView(icon(R.drawable.ic_nav, 20f, Color.WHITE))
        maps.addView(gap(8f))
        maps.addView(label("Abrir Maps", 16f, Color.WHITE, medium = true))
        buttons.addView(maps, lp(0, px(50f), 1f))
        buttons.addView(gap(8f))
        val cp = FrameLayout(this).apply {
            background = glassButton(13f)
            contentDescription = "Abrir CarPlay"
            setOnClickListener { openCarPlay() }
            setOnLongClickListener { pickApp("slot_carplay"); true }
        }
        cp.addView(icon(R.drawable.ic_carplay, 22f, skin.textDim), FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
        carplayStatus = label("CarPlay", 13f, skin.textDim)
        buttons.addView(cp, lp(px(50f), px(50f)))
        c.addView(buttons, lp(MATCH, WRAP))
        showTrips()
        return c
    }

    private var placesLabel: TextView? = null

    private fun updatePlacesLabel() {
        val n = Places.all(this).size
        placesLabel?.text = if (n == 0) "Agregar más lugares" else "Otros lugares ($n)"
    }

    private fun showPlaces() {
        Places.chooser(this, { dialog() }, { px(it) }, { dest -> toast(CarActions.navigate(this, dest)) }) {
            updatePlacesLabel()
            if (::mapPanel.isInitialized) mapPanel.rebuildChips()
        }
    }

    private data class Quad(val a: String, val b: String, val c: Int, val d: Int)

    private fun tripTap(key: String) {
        if (Commute.address(this, key).isBlank() && Commute.coords(this, key) == null) { editPlace(key); return }
        toast(CarActions.navigate(this, if (key == "home") "casa" else "trabajo"))
    }

    private fun editPlace(key: String) {
        val name = if (key == "home") "Casa" else "Trabajo"
        dialog().setTitle(name)
            .setItems(arrayOf("Estoy aquí: usar mi ubicación actual", "Escribir la dirección")) { _, w ->
                if (w == 0) {
                    val la = CarState.lat; val lo = CarState.lon
                    if (la == null || lo == null) { toast("Todavía no tengo tu ubicación del GPS"); return@setItems }
                    Commute.setCoords(this, key, la, lo)
                    prefs.edit().putString(if (key == "home") "addr_home" else "addr_work", "%.6f,%.6f".format(java.util.Locale.US, la, lo)).apply()
                    toast("$name guardado aquí")
                    refreshCommute(force = true)
                } else {
                    askText("Dirección de ${name.lowercase()}", "Calle, número, colonia, ciudad", Commute.address(this, key)) { t ->
                        Commute.setAddress(this, key, t) { ok -> runOnUiThread { toast(if (ok) "$name guardado" else "No encontré esa dirección; se usará el texto en Maps"); refreshCommute(force = true) } }
                    }
                }
            }.setNegativeButton("Cancelar", null).show()
    }

    /** Pinta los tiempos que haya en caché. */
    private fun showTrips() {
        for (key in listOf("home", "work")) {
            val v = tripViews[key] ?: continue
            val sub = v[0] as TextView; val big = v[1] as TextView
            val eta = Commute.cached(key)
            val has = Commute.address(this, key).isNotBlank() || Commute.coords(this, key) != null
            big.text = eta?.minutes?.toString() ?: "—"
            sub.text = when {
                !has -> "Mantén presionado para guardar"
                eta == null -> if (online) "Calculando…" else "Sin internet"
                else -> "%.1f km · ".format(java.util.Locale.US, eta.km) + when {
                    eta.rush >= 1.4f -> "hora pico (estimado)"
                    eta.rush > 1.05f -> "tráfico moderado (estimado)"
                    else -> "fluido"
                }
            }
            val lit = when { eta == null -> 0; eta.rush >= 1.4f -> 8; eta.rush > 1.05f -> 6; else -> 3 }
            val col = when { eta == null -> skin.textDim; eta.rush >= 1.4f -> Color.rgb(255, 122, 89); eta.rush > 1.05f -> Color.rgb(245, 185, 66); else -> Color.rgb(52, 211, 153) }
            for (i in 0 until 10) v[2 + i].background = rounded(if (i < lit) col else if (skin.isLight) Color.argb(20, 0, 0, 0) else Color.argb(20, 255, 255, 255), 2f)
            if (::mapPanel.isInitialized) mapPanel.setEta(key, eta?.minutes)
        }
    }

    private var lastCommuteAt = 0L
    @Volatile private var commuteBusy = false

    private fun refreshCommute(force: Boolean = false) {
        val la = CarState.lat ?: return; val lo = CarState.lon ?: return
        val now = SystemClock.elapsedRealtime()
        if (commuteBusy || !online || (!force && now - lastCommuteAt < 5 * 60_000L)) { showTrips(); return }
        lastCommuteAt = now
        commuteBusy = true
        Thread {
            for (k in listOf("home", "work")) runCatching { Commute.eta(this, k, la, lo) }
            commuteBusy = false
            runOnUiThread { showTrips() }
        }.start()
    }

    /** Al recuperar internet, el mapa vuelve a pedir los cuadros que no pudo descargar. */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        online = runCatching { cm.activeNetwork != null }.getOrDefault(false)
        updateMapLabel()
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                online = true
                handler.post {
                    EventLog.add(this@MainActivity, "Internet disponible")
                    updateMapLabel()
                    refreshMap()
                }
            }
            override fun onLost(network: android.net.Network) {
                online = runCatching { cm.activeNetwork != null }.getOrDefault(false)
                handler.post { updateMapLabel() }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    /** Prueba si el estéreo puede descargar un cuadro del mapa. */
    private fun tileTest(): String = runCatching {
        val c = java.net.URL("https://tile.openstreetmap.org/3/1/3.png").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", MAP_USER_AGENT)
        val code = c.responseCode
        c.disconnect()
        if (code == 200) "descarga OK" else "el servidor respondió $code"
    }.getOrElse { "no se pudo conectar (${it.javaClass.simpleName}: ${it.message})" }

    private fun updateMapLabel() {
        mapLabel?.text = if (online) "TRAYECTOS" else "TRAYECTOS · SIN INTERNET"
    }

    private fun refreshMap() {
        if (::mapPanel.isInitialized) mapPanel.refreshTiles()
        refreshCommute()
        refreshWeather()
    }

    private fun startPoint(): GeoPoint {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val last = runCatching {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }.getOrNull()
            if (last != null) return GeoPoint(last.latitude, last.longitude)
        }
        return GeoPoint(25.6866, -100.3161) // Monterrey
    }

    private fun updateMap(l: Location, kmh: Float) {
        if (::mapPanel.isInitialized) mapPanel.onLocation(l, kmh, road.heading)
        if (lastCommuteAt == 0L) refreshCommute()
        if (weatherAt == 0L) refreshWeather()
    }

    private fun buildClockCard(): View {
        val c = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 54f * s)
            setTextColor(skin.text)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            letterSpacing = -0.02f
            includeFontPadding = false
        })
        texts.addView(gap(6f))
        texts.addView(TextClock(this).apply {
            format24Hour = "EEEE d MMM"
            format12Hour = "EEEE d MMM"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 14f * s)
            setTextColor(skin.textDim)
            includeFontPadding = false
        })
        texts.addView(gap(8f))
        weatherText = label("", 14f, skin.text)
        texts.addView(weatherText)
        c.addView(texts, lp(0, WRAP, 1f))

        val btns = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val micBtn = FrameLayout(this).apply {
            background = accentButton(22f)
            contentDescription = "Asistente de voz"
            setOnClickListener { openAssistant() }
        }
        Style.glow(micBtn, skin.accent, 8f * s)
        micBtn.addView(icon(R.drawable.ic_mic, 20f, Color.WHITE), FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
        btns.addView(micBtn, lp(px(44f), px(44f)))
        btns.addView(gap(8f))
        val themeBtn = FrameLayout(this).apply {
            background = glassButton(22f)
            contentDescription = "Temas y opciones"
            setOnClickListener { showOptions() }
        }
        themeBtn.addView(icon(R.drawable.ic_settings, 20f, skin.text), FrameLayout.LayoutParams(px(20f), px(20f), Gravity.CENTER))
        btns.addView(themeBtn, lp(px(44f), px(44f)))
        c.addView(btns)
        return c
    }

    private fun buildMusicCard(): View {
        val c = card()
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onMusicCardTap() }
        }
        musicArt = ImageView(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(skin.accent, Color.rgb(138, 92, 255))).apply { cornerRadius = 18f * s }
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_music)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            outlineProvider = roundOutline(18f)
            clipToOutline = true
        }
        Style.glow(musicArt, Color.rgb(90, 92, 255), 10f * s)
        top.addView(musicArt, lp(px(76f), px(76f)))
        top.addView(gap(14f))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        musicTitle = label("Sin reproducción", 18f, skin.text, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        musicArtist = label("Toca para abrir música", 14f, skin.textDim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        t.addView(overline("REPRODUCIENDO"))
        t.addView(gap(5f))
        t.addView(musicTitle)
        t.addView(gap(3f))
        t.addView(musicArtist)
        top.addView(t, lp(0, WRAP, 1f))
        c.addView(top)
        c.addView(gap(14f))

        musicProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(Style.lighten(skin.accent, 0.15f))
            progressBackgroundTintList = ColorStateList.valueOf(if (skin.isLight) Color.argb(30, 0, 0, 0) else Color.argb(26, 255, 255, 255))
        }
        c.addView(musicProgress, lp(MATCH, px(4f)))
        val times = LinearLayout(this)
        musicPos = label("0:00", 12f, skin.textDim)
        musicDur = label("0:00", 12f, skin.textDim)
        times.addView(musicPos)
        times.addView(spacer())
        times.addView(musicDur)
        c.addView(times.apply { setPadding(0, px(6f), 0, 0) })
        c.addView(spacer())

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(roundButton(R.drawable.ic_prev, 52f, false, "Anterior") { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        controls.addView(spacer())
        val play = roundButton(R.drawable.ic_play, 70f, true, "Reproducir o pausar") { togglePlay() }
        playBtn = play.getChildAt(0) as ImageView
        controls.addView(play)
        controls.addView(spacer())
        controls.addView(roundButton(R.drawable.ic_next, 52f, false, "Siguiente") { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) })
        c.addView(controls)
        return c
    }

    private fun roundButton(res: Int, size: Float, primary: Boolean, desc: String, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this).apply {
            background = if (primary) accentButton(size / 2f) else glassButton(size / 2f)
            contentDescription = desc
            setOnClickListener { onClick() }
            layoutParams = lp(px(size), px(size))
        }
        if (primary) Style.glow(f, skin.accent, 10f * s)
        f.addView(icon(res, if (primary) 28f else 22f, if (primary) Color.WHITE else skin.text),
            FrameLayout.LayoutParams(px(if (primary) 28f else 22f), px(if (primary) 28f else 22f), Gravity.CENTER))
        return f
    }

    // ---------------------------------------------------------------- barra de apps

    private data class Slot(val key: String, val name: String, val icon: Int, val action: () -> Unit)

    private fun buildDock(): View {
        val dock = LinearLayout(this).apply {
            background = Style.glass(skin, 26f * s, strong = true)
            setPadding(px(10f), 0, px(10f), 0)
            gravity = Gravity.CENTER_VERTICAL
        }
        val slots = listOf(
            Slot("slot_maps", "Mapas", R.drawable.ic_pin) { openMaps() },
            Slot("slot_carplay", "CarPlay", R.drawable.ic_carplay) { openCarPlay() },
            Slot("slot_music", "Música", R.drawable.ic_music) { openMusicApp() },
            Slot("slot_phone", "Teléfono", R.drawable.ic_call) {
                openByLabel("Teléfono", Intent(Intent.ACTION_DIAL), "bluetooth", "telefono", "phone", "llamada")
            },
            Slot("slot_radio", "Radio", R.drawable.ic_radio) { openByLabel("Radio", null, "radio") },
            Slot("slot_camera", "Cámara", R.drawable.ic_camera) { openByLabel("Cámara", null, "camara", "camera", "avm", "360") },
            Slot("slot_vehicle", "Mi Camry", R.drawable.ic_car) { openPanel() },
            Slot("slot_apps", "Apps", R.drawable.ic_apps) { startActivity(Intent(this, AppsActivity::class.java)) }
        )
        for (slot in slots) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pressable(Color.TRANSPARENT, 16f)
                setOnClickListener { if (!launchSaved(slot.key)) slot.action() }
                if (slot.key != "slot_apps" && slot.key != "slot_vehicle") setOnLongClickListener { pickApp(slot.key); true }
            }
            val hi = slot.key == "slot_vehicle"
            val fg = if (hi) skin.text else RoadView.mix(skin.textDim, skin.text, 0.45f)
            val sq = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 13f * s
                    setColor(if (hi) Style.alpha(skin.accent, 56) else if (skin.isLight) Color.argb(12, 0, 0, 0) else Color.argb(13, 255, 255, 255))
                }
            }
            if (hi) Style.glow(sq, skin.accent, 6f * s)
            sq.addView(icon(slot.icon, 22f, fg), FrameLayout.LayoutParams(px(22f), px(22f), Gravity.CENTER))
            b.addView(sq, lp(px(40f), px(40f)))
            b.addView(gap(4f))
            b.addView(label(slot.name, 11f, fg).apply { letterSpacing = 0.04f })
            dock.addView(b, lp(0, MATCH, 1f))
        }
        return dock
    }

    /** Si el usuario eligió otra app para este botón (pulsación larga), la abre. */
    private fun launchSaved(key: String): Boolean {
        val saved = prefs.getString(key, null) ?: return false
        val cn = ComponentName.unflattenFromString(saved) ?: return false
        return AppUtils.launch(this, cn)
    }

    private fun pickApp(key: String) {
        val apps = AppUtils.launchables(this)
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Elige la app para este botón")
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                prefs.edit().putString(key, apps[which].component.flattenToString()).apply()
                toast("Listo: ${apps[which].label}")
            }
            .setNeutralButton("Restablecer") { _, _ -> prefs.edit().remove(key).apply() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------------------------------------------------------- abrir apps

    private fun openCarPlay() {
        if (launchSaved("slot_carplay")) return
        val cn = AppUtils.findByLabel(this, "tlink", "carplay", "zlink", "carlink")
        if (cn == null || !AppUtils.launch(this, cn)) {
            toast("No encontré TLINK5. Mantén presionado el botón para elegir la app.")
        }
    }

    private fun openMaps() {
        if (launchSaved("slot_maps")) return
        if (AppUtils.launchPackage(this, "com.google.android.apps.maps")) return
        if (AppUtils.launchPackage(this, "com.waze")) return
        if (!AppUtils.start(this, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))) toast("No hay app de mapas instalada")
    }

    private fun openMusicApp() {
        controller?.packageName?.let { if (AppUtils.launchPackage(this, it)) return }
        val i = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
        if (!AppUtils.start(this, i)) openByLabel("Música", null, "musica", "music", "bluetooth")
    }

    private fun openByLabel(name: String, fallback: Intent?, vararg keys: String) {
        val cn = AppUtils.findByLabel(this, *keys)
        if (cn != null && AppUtils.launch(this, cn)) return
        if (fallback != null && AppUtils.start(this, fallback)) return
        toast("No encontré la app de $name. Mantén presionado el botón para elegirla.")
    }

    // ---------------------------------------------------------------- temas

    private fun dialog() = AlertDialog.Builder(
        this,
        if (skin.isLight) android.R.style.Theme_DeviceDefault_Light_Dialog_Alert else android.R.style.Theme_DeviceDefault_Dialog_Alert
    )

    private fun showOptions() {
        val auto = prefs.getBoolean("auto_carplay", true)
        val greet = Greeter.isOn(this)
        val items = arrayOf(
            "Cambiar tema",
            "Saludo de voz al encender: " + if (greet) "Sí" else "No",
            "Cambiar el texto del saludo",
            "Probar el saludo",
            "Abrir CarPlay al conectar el iPhone: " + if (auto) "Sí" else "No",
            "Diagnóstico (voz, mapa y encendido)",
            "Agregar audios de saludo",
            "Audios de saludo guardados (" + Greeter.audioFiles().size + ")",
            "Asistente: llave de la API" + if (Assistant.apiKey(this).isBlank()) " (falta)" else " (lista)",
            "Dirección de casa",
            "Dirección de trabajo",
            "Asistente: modelo (" + (if (Assistant.model(this) == Assistant.MODEL_FAST) "rápido" else "más listo") + ")",
            "Al encender, volver a esta pantalla: " + if (prefs.getBoolean("wake_home", true)) "Sí" else "No",
            "Al encender, reanudar la música: " + if (prefs.getBoolean("wake_music", true)) "Sí" else "No",
            "Permiso para volver a esta pantalla" + if (canGoHomeFromBackground()) " (listo)" else " (falta)",
            "OBD2: buscar / elegir adaptador" + (Obd.address(this)?.let { " (elegido)" } ?: ""),
            "Al encender, activar el audio Bluetooth: " + if (prefs.getBoolean("wake_bt_audio", true)) "Sí" else "No",
            "Ajustes del estéreo",
            "Perfiles (Jahaz / Diana) y luces",
            "Animación de inicio: " + if (prefs.getBoolean("startup_anim", true)) "Sí" else "No",
            "Ver la animación de inicio"
        )
        dialog().setTitle("Opciones")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showThemes()
                    1 -> { Greeter.setOn(this, !greet); toast(if (!greet) "Saludo activado" else "Saludo desactivado") }
                    2 -> editGreeting()
                    3 -> Greeter.speak(this) { msg -> runOnUiThread { toast(msg) } }
                    4 -> {
                        prefs.edit().putBoolean("auto_carplay", !auto).apply()
                        toast(if (!auto) "CarPlay se abrirá al conectar el iPhone" else "Apertura automática desactivada")
                    }
                    5 -> Thread {
                        val tiles = tileTest()
                        Greeter.diagnose(this) { info ->
                        runOnUiThread {
                            dialog().setTitle("Diagnóstico").setMessage(info + "\n\nRED: " + (if (online) "con internet" else "SIN internet") + "\n" + gpsInfo() + "\n" + ObdPairing.status() + "\nMAPA: " + tiles + "\nPERFIL: " + (Profiles.active(this)?.name ?: "ninguno") + " · teléfonos en Bluetooth de Android: " + Profiles.connectedPhones().joinToString { d -> runCatching { d.name }.getOrNull() ?: d.address }.ifEmpty { "ninguno" } + "\nLUCES: " + (Lights.name(this) ?: "sin elegir") + " · " + Lights.lastResult + "\n\nBLUETOOTH DEL ESTÉREO:\n" + StereoBt.report(this) + "\n" + "\nSERVICIOS DE MÚSICA: " + BtAudio.browserServices(this).joinToString { it.packageName }.ifEmpty { "ninguno" } + "\n\nEVENTOS (más reciente arriba):\n" + EventLog.text(this))
                                .setPositiveButton("OK", null)
                                .setNeutralButton("Ajustes de voz") { _, _ ->
                                    AppUtils.start(this, Intent("com.android.settings.TTS_SETTINGS"))
                                }
                                .show()
                        }
                        }
                    }.start()
                    6 -> pickGreetingAudios()
                    7 -> showGreetingAudios()
                    8 -> askText("Llave de la API de Claude", "sk-ant-…", Assistant.apiKey(this), secret = true) { Assistant.setApiKey(this, it); toast("Llave guardada") }
                    9 -> editPlace("home")
                    10 -> editPlace("work")
                    11 -> {
                        val fast = Assistant.model(this) == Assistant.MODEL_FAST
                        Assistant.setModel(this, if (fast) Assistant.MODEL_SMART else Assistant.MODEL_FAST)
                        toast(if (fast) "Modelo más listo (Sonnet): un poco más lento y caro" else "Modelo rápido (Haiku)")
                    }
                    12 -> togglePref("wake_home", "Al encender volverá a esta pantalla", "Al encender se queda la última app")
                    13 -> togglePref("wake_music", "Al encender se reanuda la música", "Al encender la música queda en pausa")
                    14 -> askOverlayPermission()
                    15 -> chooseObdAdapter()
                    16 -> togglePref("wake_bt_audio", "Al encender se abrirá un momento la música Bluetooth", "Ya no se abrirá la música Bluetooth al encender")
                    17 -> openByLabel("Ajustes", Intent(Settings.ACTION_SETTINGS), "ajustes del coche", "configuracion del coche", "carsetting", "car setting")
                    18 -> startActivity(Intent(this, ProfilesActivity::class.java))
                    19 -> togglePref("startup_anim", "La animación saldrá al encender", "Sin animación al encender")
                    20 -> playStartup()
                }
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showThemes() {
        val all = ThemeManager.loadAll(this)
        val current = all.indexOfFirst { it.id == skin.id }.coerceAtLeast(0)
        var picked = current
        dialog().setTitle("Temas")
            .setSingleChoiceItems(all.map { it.name }.toTypedArray(), current) { _, which -> picked = which }
            .setPositiveButton("Aplicar") { _, _ ->
                ThemeManager.select(this, all[picked].id)
                recreate()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun askText(title: String, hint: String, current: String, secret: Boolean = false, save: (String) -> Unit) {
        val input = android.widget.EditText(this).apply {
            setText(current)
            this.hint = hint
            isSingleLine = true
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(px(24f), px(8f), px(24f), 0); addView(input) }
        dialog().setTitle(title).setView(box)
            .setPositiveButton("Guardar") { _, _ -> save(input.text.toString().trim()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAssistant() {
        startActivity(Intent(this, AssistantActivity::class.java))
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOICE_ASSIST || keyCode == KeyEvent.KEYCODE_ASSIST || keyCode == KeyEvent.KEYCODE_SEARCH) {
            openAssistant()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun editGreeting() {
        val input = android.widget.EditText(this).apply {
            setText(Greeter.text(this@MainActivity))
            hint = "Una frase por línea"
            minLines = 3
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply { setPadding(px(24f), px(8f), px(24f), 0); addView(input) }
        dialog().setTitle("Frases del saludo")
            .setMessage("Una por línea; sale una al azar.\n• {saludo} = Buenos días / Buenas tardes / Buenas noches\n• {nombre} = quien maneja (perfil activo)\n• Empieza con \"mañana:\", \"tarde:\" o \"noche:\" para que salga solo a esa hora.")
            .setView(box)
            .setPositiveButton("Guardar") { _, _ ->
                val t = input.text.toString().trim()
                Greeter.setText(this, t.ifEmpty { Greeter.DEFAULT_TEXT })
                Greeter.speak(this) { msg -> runOnUiThread { toast(msg) } }
            }
            .setNeutralButton("Original") { _, _ -> Greeter.setText(this, Greeter.DEFAULT_TEXT) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    /** Carpeta destino del próximo "Agregar audios": null = cualquier hora. */
    private var pendingSlot: Greeter.Slot? = null

    private fun pickGreetingAudios() {
        val names = arrayOf("Cualquier hora") + Greeter.Slot.values().map {
            it.label + when (it) {
                Greeter.Slot.MANANA -> " (5:00 a 11:59)"
                Greeter.Slot.TARDE -> " (12:00 a 18:59)"
                Greeter.Slot.NOCHE -> " (19:00 a 4:59)"
            }
        }
        dialog().setTitle("¿Para qué horario son estos audios?")
            .setItems(names) { _, which ->
                pendingSlot = if (which == 0) null else Greeter.Slot.values()[which - 1]
                openAudioPicker()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun openAudioPicker() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_AUDIO)
        } catch (e: Exception) {
            toast("Copia tus audios a CamryLauncher/saludos (o a sus carpetas manana, tarde y noche)")
        }
    }

    @Deprecated("API clásica de resultados")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_AUDIO || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        val dir = (pendingSlot?.let { File(Greeter.greetingsDir(), it.folder) } ?: Greeter.greetingsDir()).apply { mkdirs() }
        var ok = 0
        for (u in uris) {
            runCatching {
                var name = "saludo_${System.currentTimeMillis()}.mp3"
                contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { n -> name = n.replace(Regex("[^A-Za-z0-9._ -]"), "_") }
                }
                val out = File(dir, name)
                contentResolver.openInputStream(u)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ok++
            }
        }
        val where = pendingSlot?.label?.lowercase()?.let { "para la $it" } ?: "para cualquier hora"
        toast(if (ok > 0) "Listo: $ok audio(s) $where. Saldrá uno al azar." else "No pude copiar los audios")
    }

    private fun showGreetingAudios() {
        val files = Greeter.audioFiles()
        if (files.isEmpty()) {
            toast("No hay audios. Usa \"Agregar audios de saludo\" o cópialos a CamryLauncher/saludos")
            return
        }
        dialog().setTitle("Toca uno para escucharlo")
            .setItems(files.map { "[" + Greeter.slotLabel(it) + "]  " + it.name }.toTypedArray()) { _, which -> playPreview(files[which]) }
            .setNeutralButton("Borrar uno") { _, _ ->
                dialog().setTitle("¿Cuál borro?")
                    .setItems(files.map { "[" + Greeter.slotLabel(it) + "]  " + it.name }.toTypedArray()) { _, w ->
                        toast(if (files[w].delete()) "Borrado: ${files[w].name}" else "No se pudo borrar")
                    }
                    .setNegativeButton("Cancelar", null).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private var preview: android.media.MediaPlayer? = null

    private fun playPreview(f: File) {
        runCatching { preview?.release() }
        preview = runCatching {
            android.media.MediaPlayer().apply {
                setDataSource(f.absolutePath)
                setOnCompletionListener { it.release(); if (preview === it) preview = null }
                prepare()
                start()
            }
        }.getOrNull()
        if (preview == null) toast("No pude reproducir ${f.name}")
    }

    // ---------------------------------------------------------------- saludo al encender
    //
    // Cada estéreo avisa el encendido de forma distinta, así que se escuchan varias señales:
    //  - la pantalla se prende tras estar apagada más de 1 minuto
    //  - el estéreo despierta de una suspensión (el reloj real avanza más que el tiempo activo)
    //  - el launcher vuelve a primer plano tras más de 1 minuto oculto
    //  - el iPhone se reconecta por Bluetooth (pasa cada vez que arrancas)
    //  - el estéreo acaba de arrancar desde cero
    // Solo se saluda una vez cada 10 minutos.

    private var screenOffAt = 0L
    private var stoppedAt = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = SystemClock.elapsedRealtime()
                    EventLog.add(context, "Pantalla apagada")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val away = SystemClock.elapsedRealtime() - screenOffAt
                    EventLog.add(context, "Pantalla encendida (apagada ${away / 1000}s)")
                    if (screenOffAt != 0L && away > 60_000L) onCarStarted("pantalla encendida")
                }
                android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val dev: android.bluetooth.BluetoothDevice? = intent.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE)
                    val name = runCatching { dev?.name }.getOrNull() ?: "dispositivo"
                    EventLog.add(context, "Bluetooth conectado: $name")
                    greetSoon("bluetooth $name", 5000L)
                    resumeMusicSoon(12_000L)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        val away = if (stoppedAt == 0L) 0L else SystemClock.elapsedRealtime() - stoppedAt
        if (stoppedAt != 0L) EventLog.add(this, "Launcher visible otra vez (${away / 1000}s oculto)")
        if (stoppedAt != 0L && away > 60_000L) {
            WakeLogic.carStarted(this, "launcher visible", goHome = false)
            if (prefs.getBoolean("startup_anim", true)) playStartup()
        }
        stoppedAt = 0L
    }

    override fun onStop() {
        visible = false
        stoppedAt = SystemClock.elapsedRealtime()
        if (!isChangingConfigurations) WakeLogic.onLauncherCovered(this, lastTouchAt)
        super.onStop()
    }

    private var lastStartHandled = 0L

    /** Se acaba de encender el auto: volver al inicio, saludar y reanudar la música. */
    private fun onCarStarted(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastStartHandled != 0L && now - lastStartHandled < 30_000L) return
        lastStartHandled = now
        if (prefs.getBoolean("wake_home", true)) goHome(reason)
        greetSoon(reason, 6000L)
        resumeMusicSoon(12_000L)
    }

    private fun goHome(reason: String) {
        if (stoppedAt == 0L) return // ya estamos en pantalla
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val ok = runCatching { startActivity(i); true }.getOrDefault(false)
        EventLog.add(this, "Volver al inicio ($reason): " + if (ok) "pedido" else "falló")
        handler.postDelayed({
            if (stoppedAt != 0L) EventLog.add(this, "No pude volver al inicio: activa el permiso en Opciones")
        }, 2500L)
    }

    private fun resumeMusicSoon(delayMs: Long) {
        if (!prefs.getBoolean("wake_music", true)) return
        handler.postDelayed({
            val mc = controller
            if (mc?.playbackState?.state == PlaybackState.STATE_PLAYING) return@postDelayed
            if (mc != null) mc.transportControls.play() else mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            EventLog.add(this, "Música reanudada" + (mc?.packageName?.let { " ($it)" } ?: " (tecla play)"))
        }, delayMs)
    }

    private fun togglePref(key: String, onMsg: String, offMsg: String) {
        val v = !prefs.getBoolean(key, true)
        prefs.edit().putBoolean(key, v).apply()
        toast(if (v) onMsg else offMsg)
    }

    /** Android solo deja traer el launcher al frente desde segundo plano con el permiso "mostrar sobre otras apps". */
    private fun canGoHomeFromBackground() = Settings.canDrawOverlays(this)

    private fun askOverlayPermission() {
        if (canGoHomeFromBackground()) { toast("El permiso ya está activo"); return }
        toast("Activa \"Camry Launcher\" en la lista para que vuelva al inicio al encender")
        AppUtils.start(this, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun greetSoon(reason: String, delayMs: Long) {
        if (!Greeter.isOn(this)) return
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_greet_at", 0L)
        if (last != 0L && now - last in 0..10 * 60_000L) {
            EventLog.add(this, "Saludo omitido ($reason): ya saludé hace ${(now - last) / 1000}s")
            return
        }
        prefs.edit().putLong("last_greet_at", now).apply()
        EventLog.add(this, "Saludo programado por: $reason")
        handler.postDelayed({
            Greeter.speak(this) { msg ->
                EventLog.add(this, "Saludo falló: $msg")
                runOnUiThread { toast(msg) }
            }
        }, delayMs)
    }

    private fun decode(path: String): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / (sample * 2) >= 1024 && o.outHeight / (sample * 2) >= 600) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    // ---------------------------------------------------------------- permisos y GPS

    private fun askPermissions() {
        val needed = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onPermissionsReady() else requestPermissions(needed.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onPermissionsReady()
    }

    private fun onPermissionsReady() {
        if (!canGoHomeFromBackground() && !prefs.getBoolean("asked_overlay", false)) {
            prefs.edit().putBoolean("asked_overlay", true).apply()
            dialog().setTitle("Volver al inicio al encender")
                .setMessage("Para que al encender el auto se muestre esta pantalla (y no la última app que dejaste abierta), Android necesita el permiso \"Mostrar sobre otras apps\".")
                .setPositiveButton("Activar") { _, _ -> askOverlayPermission() }
                .setNegativeButton("Ahora no", null)
                .show()
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            ThemeManager.ensureUserDir(this)
            runCatching {
                Greeter.greetingsDir().mkdirs()
                Greeter.Slot.values().forEach { File(Greeter.greetingsDir(), it.folder).mkdirs() }
            }
        }
        startLocation()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            // Las ubicaciones pasivas sin velocidad no deben poner el velocímetro en 0
            if (l.provider == LocationManager.PASSIVE_PROVIDER && !l.hasSpeed() && lastFix != 0L &&
                SystemClock.elapsedRealtime() - lastFix < 3000L) { updateMap(l, road.speedKmh); return }
            lastFix = SystemClock.elapsedRealtime()
            val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
            road.speedKmh = if (kmh < 2f) 0f else kmh
            CarState.speedKmh = road.speedKmh
            CarState.lat = l.latitude
            CarState.lon = l.longitude
            road.heading = if (l.hasBearing() && kmh >= 5f) "Rumbo " + compass(l.bearing) else "GPS listo"
            if (l.provider == LocationManager.GPS_PROVIDER) TripLog.gps(this@MainActivity, l)
            updateMap(l, kmh)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            road.heading = "GPS apagado"
        }

        @Deprecated("API antigua")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private var lastLocRestart = 0L
    private var satsUsed = 0
    private var satsSeen = 0

    private val gnssCallback = object : android.location.GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
            satsSeen = status.satelliteCount
            satsUsed = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    /**
     * Pide la ubicación al GPS. Con force=true se vuelve a registrar: después de que el estéreo
     * duerme y despierta, el GPS a veces deja de mandar datos a quien ya estaba escuchando.
     */
    private fun startLocation(force: Boolean = false) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            road.heading = "Sin permiso de ubicación"
            return
        }
        if (locationManager != null && !force) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { locationManager?.unregisterGnssStatusCallback(gnssCallback) }
        locationManager = lm
        lastLocRestart = SystemClock.elapsedRealtime()
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper()) }
            .onFailure { road.heading = "GPS no disponible" }
        // También aprovecha las ubicaciones que piden otras apps (Maps, CarPlay…)
        runCatching { lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper()) }
        runCatching { lm.registerGnssStatusCallback(gnssCallback, handler) }
        if (force) EventLog.add(this, "GPS reiniciado")
    }

    fun gpsInfo(): String {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val on = runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
        val age = if (lastFix == 0L) "nunca" else "hace ${(SystemClock.elapsedRealtime() - lastFix) / 1000}s"
        return "GPS: " + (if (on) "activado" else "APAGADO") + ", último dato $age, satélites $satsUsed/$satsSeen"
    }

    private fun compass(b: Float): String {
        val names = arrayOf("norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste")
        return names[(((b % 360f) + 360f + 22.5f) / 45f).toInt() % 8]
    }

    // ---------------------------------------------------------------- iPhone / CarPlay

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            @Suppress("DEPRECATION")
            val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            if (dev?.vendorId != APPLE_VENDOR_ID) return
            if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                carplayStatus.text = "iPhone listo"
                if (prefs.getBoolean("auto_carplay", true)) handler.postDelayed({
                    WakeLogic.selfLaunchAt = SystemClock.elapsedRealtime()
                    openCarPlay()
                }, 1500L)
            } else {
                carplayStatus.text = "CarPlay"
            }
        }
    }

    private fun refreshIphoneStatus() {
        val um = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val connected = runCatching { um.deviceList.values.any { it.vendorId == APPLE_VENDOR_ID } }.getOrDefault(false)
        carplayStatus.text = if (connected) "iPhone listo" else "CarPlay"
    }

    // ---------------------------------------------------------------- música

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bindController(pickController(list))
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = updateMusic()
        override fun onPlaybackStateChanged(state: PlaybackState?) = updateMusic()
        override fun onSessionDestroyed() = bindController(null)
    }

    private fun pickController(list: List<MediaController>?): MediaController? =
        list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()

    private fun setupMedia() {
        val mm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val cn = ComponentName(this, MediaListener::class.java)
        try {
            if (mediaManager == null) mm.addOnActiveSessionsChangedListener(sessionsListener, cn)
            mediaManager = mm
            bindController(pickController(mm.getActiveSessions(cn)))
            mediaNeedsAccess = false
        } catch (e: SecurityException) {
            mediaNeedsAccess = true
            musicTitle.text = "Activar música"
            musicArtist.text = "Toca para dar acceso"
        }
    }

    private fun bindController(mc: MediaController?) {
        controller?.unregisterCallback(mediaCallback)
        controller = mc
        mc?.registerCallback(mediaCallback, handler)
        updateMusic()
    }

    private fun updateMusic() {
        if (mediaNeedsAccess) return
        val mc = controller
        val md = mc?.metadata
        musicTitle.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "Sin reproducción"
        CarState.song = md?.let { m ->
            listOfNotNull(m.getString(MediaMetadata.METADATA_KEY_TITLE), m.getString(MediaMetadata.METADATA_KEY_ARTIST)).joinToString(" - ").ifBlank { null }
        }
        musicArtist.text = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            ?: if (mc == null) "Toca para abrir música" else appName(mc.packageName)
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) {
            musicArt.imageTintList = null
            musicArt.scaleType = ImageView.ScaleType.CENTER_CROP
            musicArt.setImageBitmap(art)
            val ac = artColor(art) ?: skin.accent
            Style.glow(musicArt, ac, 14f * s)
            musicProgress.progressTintList = ColorStateList.valueOf(ac)
        } else {
            musicArt.imageTintList = ColorStateList.valueOf(skin.textDim)
            musicArt.scaleType = ImageView.ScaleType.CENTER
            musicArt.setImageResource(R.drawable.ic_music)
            musicProgress.progressTintList = ColorStateList.valueOf(Style.lighten(skin.accent, 0.15f))
        }
        val playing = mc?.playbackState?.state == PlaybackState.STATE_PLAYING
        playBtn.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        updateProgress()
    }

    private fun updateProgress() {
        val mc = controller
        val dur = mc?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val st = mc?.playbackState
        var pos = st?.position ?: 0L
        if (st != null && st.state == PlaybackState.STATE_PLAYING) {
            pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
        }
        if (dur > 0) pos = pos.coerceIn(0L, dur)
        musicProgress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        musicPos.text = fmt(pos)
        musicDur.text = fmt(dur)
    }

    private fun fmt(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(t / 60, t % 60)
    }

    private fun appName(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun togglePlay() {
        val mc = controller
        if (mc == null) {
            mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            return
        }
        if (mc.playbackState?.state == PlaybackState.STATE_PLAYING) mc.transportControls.pause() else mc.transportControls.play()
    }

    private fun mediaKey(code: Int) {
        val mc = controller
        if (mc != null && code == KeyEvent.KEYCODE_MEDIA_NEXT) { mc.transportControls.skipToNext(); return }
        if (mc != null && code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { mc.transportControls.skipToPrevious(); return }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun onMusicCardTap() {
        if (mediaNeedsAccess) {
            toast("Activa \"Camry Launcher (música)\" en la lista")
            AppUtils.start(this, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } else {
            openMusicApp()
        }
    }

    // ---------------------------------------------------------------- utilidades

    private var lastTickElapsed = 0L
    private var tickCount = 0
    private var lastTickUptime = 0L

    private val ticker = object : Runnable {
        override fun run() {
            // Al quitar la llave el TS18 se "duerme": el reloj real avanza pero el tiempo activo no.
            // Si al volver hubo más de un minuto dormido, es que se acaba de encender el auto.
            val e = SystemClock.elapsedRealtime()
            val u = SystemClock.uptimeMillis()
            val slept = (e - lastTickElapsed) - (u - lastTickUptime)
            // (la detección de encendido la hace CarService)
            lastTickElapsed = e
            lastTickUptime = u

            if (lastFix != 0L && SystemClock.elapsedRealtime() - lastFix > 5000L) {
                road.speedKmh = 0f
                road.heading = "Buscando GPS"
            }
            // Sin datos del GPS por 15 s: volver a registrarse (cada 20 s como máximo)
            val nowE = SystemClock.elapsedRealtime()
            if (visible && (lastFix == 0L || nowE - lastFix > 15_000L) && nowE - lastLocRestart > 20_000L) startLocation(force = true)
            // Revisar internet de vez en cuando (al despertar el aviso podía quedarse viejo)
            if (tickCount % 5 == 0) {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val now = runCatching { cm.activeNetwork != null }.getOrDefault(online)
                if (now != online) { online = now; updateMapLabel(); if (now) refreshMap() }
            }
            updateProgress()
            tickCount++
            if (tickCount % 60 == 0) refreshCommute()
            if (tickCount % 1800 == 0 && tickCount > 0) refreshWeather()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------------------------------------------------------------- animación de arranque

    private var startup: StartupView? = null

    private fun playStartup() {
        if (startup != null) return
        val root = window.decorView as? ViewGroup ?: return
        val v = StartupView(this, skin.accent) { startup = null }
        startup = v
        root.addView(v, ViewGroup.LayoutParams(MATCH, MATCH))
        v.play()
    }

    // ---------------------------------------------------------------- clima (Open-Meteo, sin llave)

    private var weatherAt = 0L

    private fun refreshWeather() {
        val la = CarState.lat ?: 25.6866; val lo = CarState.lon ?: -100.3161
        if (!online) return
        weatherAt = SystemClock.elapsedRealtime()
        Thread {
            val r = runCatching {
                val c = java.net.URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,weather_code&timezone=auto")
                    .openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                val o = org.json.JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("current")
                c.disconnect()
                o.getDouble("temperature_2m").roundToInt() to o.getInt("weather_code")
            }.getOrNull() ?: return@Thread
            val desc = when (r.second) {
                0 -> "Despejado"; 1, 2 -> "Medio nublado"; 3 -> "Nublado"; 45, 48 -> "Neblina"
                in 51..57 -> "Llovizna"; in 61..67, in 80..82 -> "Lluvia"; in 71..77 -> "Nieve"; in 95..99 -> "Tormenta"
                else -> ""
            }
            runOnUiThread { weatherText?.text = "${r.first}°" + if (desc.isNotEmpty()) " · $desc" else "" }
        }.start()
    }

    // ---------------------------------------------------------------- color de la portada

    /** Color más vivo de la portada del disco, para el brillo de la tarjeta de música. */
    private fun artColor(b: Bitmap): Int? = runCatching {
        val sm = Bitmap.createScaledBitmap(b, 12, 12, true)
        var best: Int? = null; var bestScore = 0f
        val hsv = FloatArray(3)
        for (x in 0 until 12) for (y in 0 until 12) {
            val c = sm.getPixel(x, y)
            Color.colorToHSV(c, hsv)
            val score = hsv[1] * (if (hsv[2] in 0.25f..0.95f) hsv[2] else 0f)
            if (score > bestScore) { bestScore = score; best = c }
        }
        if (bestScore < 0.15f) null else best?.let { c -> Color.colorToHSV(c, hsv); hsv[1] = hsv[1].coerceAtLeast(0.55f); hsv[2] = hsv[2].coerceAtLeast(0.75f); Color.HSVToColor(hsv) }
    }.getOrNull()

    companion object {
        private const val APPLE_VENDOR_ID = 0x05AC
        private var greetedThisRun = false
        private const val MAP_USER_AGENT = "CamryLauncher/1.0 (launcher personal para estereo de auto)"
        @Volatile var visible = false
        private const val REQ_AUDIO = 7
    }
}
