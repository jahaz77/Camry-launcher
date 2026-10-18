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
    private var map: MapView? = null
    private var mapLabel: TextView? = null
    @Volatile private var online = false
    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private var myMarker: Marker? = null

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skin = ThemeManager.current(this)
        val dm = resources.displayMetrics
        s = min(maxOf(dm.widthPixels, dm.heightPixels) / 1024f, min(dm.widthPixels, dm.heightPixels) / 600f)
        setContentView(buildUi())
        askPermissions()
        registerReceiver(usbReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        })
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON).apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)
        })
        setupMedia()
        watchNetwork()
        handler.post(ticker)
        // Primer arranque del estéreo (no al cambiar de tema)
        if (!greetedThisRun) {
            greetedThisRun = true
            val up = SystemClock.elapsedRealtime()
            EventLog.add(this, "Launcher iniciado (estéreo encendido hace ${up / 1000}s)")
            if (up < 5 * 60_000L) { greetSoon("arranque del estéreo", 12_000L); resumeMusicSoon(18_000L) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (ThemeManager.current(this).id != skin.id) { recreate(); return }
        map?.onResume()
        refreshIphoneStatus()
        if (mediaNeedsAccess) setupMedia()
    }

    override fun onPause() {
        map?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(usbReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { netCallback?.let { (getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager).unregisterNetworkCallback(it) } }
        runCatching { locationManager?.removeUpdates(locationListener) }
        runCatching { mediaManager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(mediaCallback)
        super.onDestroy()
    }

    @Deprecated("Launcher: el botón atrás no hace nada aquí")
    override fun onBackPressed() { /* es la pantalla de inicio */ }

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
        background = rounded(skin.card, 20f, stroke = true)
        setPadding(px(20f), px(18f), px(20f), px(18f))
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private fun gap(v: Float) = View(this).apply { layoutParams = lp(px(v), px(v)) }
    private fun spacer() = View(this).apply { layoutParams = lp(0, 0, 1f) }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(skin.background)
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
            setPadding(px(16f), px(16f), px(16f), px(16f))
        }
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        column.addView(row, lp(MATCH, 0, 1f))
        column.addView(gap(12f))
        column.addView(buildDock(), lp(MATCH, px(76f)))

        row.addView(buildMapCard(), lp(px(280f), MATCH))
        row.addView(gap(12f))
        road = RoadView(this, skin).apply {
            outlineProvider = roundOutline(20f)
            clipToOutline = true
            setOnLongClickListener { showOptions(); true }
        }
        row.addView(road, lp(0, MATCH, 1f))
        row.addView(gap(12f))

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        right.addView(buildClockCard(), lp(MATCH, WRAP))
        right.addView(gap(12f))
        right.addView(buildMusicCard(), lp(MATCH, 0, 1f))
        row.addView(right, lp(px(280f), MATCH))
        return root
    }

    private fun roundOutline(r: Float) = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setRoundRect(0, 0, view.width, view.height, r * s)
        }
    }

    /** Mapa de la ciudad (OpenStreetMap + CARTO, sin llave ni costo) con tu ubicación. */
    private fun buildMapCard(): View {
        val frame = FrameLayout(this).apply {
            background = rounded(skin.card, 20f, stroke = true)
            outlineProvider = roundOutline(20f)
            clipToOutline = true
        }

        OsmConfig.getInstance().load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        OsmConfig.getInstance().userAgentValue = packageName
        OsmConfig.getInstance().osmdroidBasePath = File(cacheDir, "osmdroid")
        OsmConfig.getInstance().osmdroidTileCache = File(cacheDir, "osmdroid/tiles")

        val style = if (skin.isLight) "light_all" else "dark_all"
        val source = XYTileSource(
            "Carto-$style", 0, 19, 256, ".png",
            arrayOf(
                "https://a.basemaps.cartocdn.com/$style/",
                "https://b.basemaps.cartocdn.com/$style/",
                "https://c.basemaps.cartocdn.com/$style/",
                "https://d.basemaps.cartocdn.com/$style/"
            )
        )
        val start = startPoint()
        val mv = MapView(this)
        mv.setTileSource(source)
        mv.setMultiTouchControls(false)
        mv.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mv.isTilesScaledToDpi = true
        mv.overlayManager.tilesOverlay.setLoadingBackgroundColor(skin.card)
        mv.overlayManager.tilesOverlay.setLoadingLineColor(skin.card)
        mv.controller.setZoom(16.0)
        mv.controller.setCenter(start)

        val dot = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(skin.accent)
            setStroke(px(3f), Color.WHITE)
            setSize(px(22f), px(22f))
        }
        val mk = Marker(mv)
        mk.icon = dot
        mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        mk.position = start
        mk.setOnMarkerClickListener { _, _ -> true }
        mv.overlays.add(mk)
        map = mv
        myMarker = mk
        frame.addView(mv, FrameLayout.LayoutParams(MATCH, MATCH))

        // Tocar el mapa abre Maps (el mapa no se arrastra para no distraer al manejar)
        frame.addView(View(this).apply { setOnClickListener { openMaps() } }, FrameLayout.LayoutParams(MATCH, MATCH))

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16f), px(14f), px(16f), px(22f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(RoadView.alpha(skin.card, 235), RoadView.alpha(skin.card, 0))
            )
        }
        header.addView(View(this).apply { background = rounded(Color.rgb(52, 211, 153), 4f) }, lp(px(8f), px(8f)))
        header.addView(gap(8f))
        mapLabel = label("MAPA", 13f, skin.textDim).apply { letterSpacing = 0.08f }
        header.addView(mapLabel)
        frame.addView(header, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12f), px(28f), px(12f), px(12f))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(RoadView.alpha(skin.card, 0), RoadView.alpha(skin.card, 240))
            )
        }
        bar.addView(label("© OpenStreetMap · © CARTO", 10f, skin.textDim).apply { gravity = Gravity.END }, lp(MATCH, WRAP))
        bar.addView(gap(6f))
        val buttons = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val maps = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = pressable(skin.accent, 14f)
            setOnClickListener { openMaps() }
            setOnLongClickListener { pickApp("slot_maps"); true }
        }
        maps.addView(icon(R.drawable.ic_nav, 20f, Color.WHITE))
        maps.addView(gap(8f))
        maps.addView(label("Abrir Maps", 16f, Color.WHITE, medium = true))
        buttons.addView(maps, lp(0, px(52f), 1f))
        buttons.addView(gap(8f))
        // CarPlay discreto, por si quieres navegar desde el iPhone
        val cp = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            background = pressable(skin.button, 14f)
            setPadding(px(12f), 0, px(12f), 0)
            contentDescription = "Abrir CarPlay"
            setOnClickListener { openCarPlay() }
            setOnLongClickListener { pickApp("slot_carplay"); true }
        }
        cp.addView(icon(R.drawable.ic_carplay, 18f, skin.textDim))
        cp.addView(gap(6f))
        carplayStatus = label("CarPlay", 13f, skin.textDim)
        cp.addView(carplayStatus)
        buttons.addView(cp, lp(WRAP, px(52f)))
        bar.addView(buttons, lp(MATCH, WRAP))
        frame.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        return frame
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
        val c = java.net.URL("https://a.basemaps.cartocdn.com/dark_all/3/1/3.png").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        c.setRequestProperty("User-Agent", packageName)
        val code = c.responseCode
        c.disconnect()
        if (code == 200) "descarga OK" else "el servidor respondió $code"
    }.getOrElse { "no se pudo conectar (${it.javaClass.simpleName}: ${it.message})" }

    private fun updateMapLabel() {
        mapLabel?.text = if (online) "MAPA" else "MAPA · SIN INTERNET"
    }

    private fun refreshMap() {
        val mv = map ?: return
        runCatching { mv.tileProvider.clearTileCache() }
        mv.invalidate()
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

    private var lastZoom = 16.0

    private fun updateMap(l: Location, kmh: Float) {
        val mv = map ?: return
        val gp = GeoPoint(l.latitude, l.longitude)
        myMarker?.position = gp
        val z = when {
            kmh > 80f -> 14.5
            kmh > 40f -> 15.5
            else -> 16.5
        }
        if (z != lastZoom) { lastZoom = z; mv.controller.zoomTo(z) }
        mv.controller.animateTo(gp)
        mv.invalidate()
    }

    private fun buildClockCard(): View {
        val c = card().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_PX, 40f * s)
            setTextColor(skin.text)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
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
        c.addView(texts, lp(0, WRAP, 1f))

        val micBtn = FrameLayout(this).apply {
            background = pressable(skin.accent, 16f)
            contentDescription = "Asistente de voz"
            setOnClickListener { openAssistant() }
        }
        micBtn.addView(icon(R.drawable.ic_mic, 26f, Color.WHITE), FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER))
        c.addView(micBtn, lp(px(50f), px(50f)))
        c.addView(gap(8f))
        val themeBtn = FrameLayout(this).apply {
            background = pressable(skin.button, 16f)
            contentDescription = "Temas y opciones"
            setOnClickListener { showOptions() }
        }
        themeBtn.addView(icon(R.drawable.ic_palette, 26f, skin.text), FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER))
        c.addView(themeBtn, lp(px(50f), px(50f)))
        return c
    }

    private fun buildMusicCard(): View {
        val c = card()
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener { onMusicCardTap() }
        }
        musicArt = ImageView(this).apply {
            background = rounded(skin.button, 14f)
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_music)
            imageTintList = ColorStateList.valueOf(skin.textDim)
            clipToOutline = true
        }
        top.addView(musicArt, lp(px(72f), px(72f)))
        top.addView(gap(14f))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        musicTitle = label("Sin reproducción", 18f, skin.text, medium = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        musicArtist = label("Toca para abrir música", 14f, skin.textDim).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        t.addView(musicTitle)
        t.addView(gap(4f))
        t.addView(musicArtist)
        top.addView(t, lp(0, WRAP, 1f))
        c.addView(top)
        c.addView(gap(14f))

        musicProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(skin.accent)
            progressBackgroundTintList = ColorStateList.valueOf(RoadView.mix(skin.card, Color.WHITE, 0.12f))
        }
        c.addView(musicProgress, lp(MATCH, px(6f)))
        val times = LinearLayout(this)
        musicPos = label("0:00", 12f, skin.textDim)
        musicDur = label("0:00", 12f, skin.textDim)
        times.addView(musicPos)
        times.addView(spacer())
        times.addView(musicDur)
        c.addView(times.apply { setPadding(0, px(6f), 0, 0) })
        c.addView(spacer())

        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        controls.addView(roundButton(R.drawable.ic_prev, 56f, Color.TRANSPARENT, skin.text, "Anterior") { mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) })
        controls.addView(spacer())
        val play = roundButton(R.drawable.ic_play, 68f, skin.text, skin.card, "Reproducir o pausar") { togglePlay() }
        playBtn = play.getChildAt(0) as ImageView
        controls.addView(play)
        controls.addView(spacer())
        controls.addView(roundButton(R.drawable.ic_next, 56f, Color.TRANSPARENT, skin.text, "Siguiente") { mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) })
        c.addView(controls)
        return c
    }

    private fun roundButton(res: Int, size: Float, bg: Int, fg: Int, desc: String, onClick: () -> Unit): FrameLayout {
        val f = FrameLayout(this).apply {
            background = pressable(bg, size / 2f)
            contentDescription = desc
            setOnClickListener { onClick() }
            layoutParams = lp(px(size), px(size))
        }
        f.addView(icon(res, 26f, fg), FrameLayout.LayoutParams(px(26f), px(26f), Gravity.CENTER))
        return f
    }

    // ---------------------------------------------------------------- barra de apps

    private data class Slot(val key: String, val name: String, val icon: Int, val action: () -> Unit)

    private fun buildDock(): View {
        val dock = LinearLayout(this).apply {
            background = rounded(skin.card, 20f, stroke = true)
            setPadding(px(8f), 0, px(8f), 0)
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
            Slot("slot_settings", "Ajustes", R.drawable.ic_settings) {
                openByLabel("Ajustes", Intent(Settings.ACTION_SETTINGS), "ajustes del coche", "configuracion del coche", "carsetting", "car setting")
            },
            Slot("slot_apps", "Apps", R.drawable.ic_apps) { startActivity(Intent(this, AppsActivity::class.java)) }
        )
        for (slot in slots) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = pressable(Color.TRANSPARENT, 16f)
                setOnClickListener { if (!launchSaved(slot.key)) slot.action() }
                if (slot.key != "slot_apps") setOnLongClickListener { pickApp(slot.key); true }
            }
            b.addView(icon(slot.icon, 26f, RoadView.mix(skin.textDim, skin.text, 0.6f)))
            b.addView(gap(5f))
            b.addView(label(slot.name, 13f, RoadView.mix(skin.textDim, skin.text, 0.6f)))
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
            "Asistente: dirección de casa",
            "Asistente: dirección de trabajo",
            "Asistente: modelo (" + (if (Assistant.model(this) == Assistant.MODEL_FAST) "rápido" else "más listo") + ")",
            "Al encender, volver a esta pantalla: " + if (prefs.getBoolean("wake_home", true)) "Sí" else "No",
            "Al encender, reanudar la música: " + if (prefs.getBoolean("wake_music", true)) "Sí" else "No",
            "Permiso para volver a esta pantalla" + if (canGoHomeFromBackground()) " (listo)" else " (falta)"
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
                            dialog().setTitle("Diagnóstico").setMessage(info + "\n\nRED: " + (if (online) "con internet" else "SIN internet") + "\nMAPA: " + tiles + "\n\nEVENTOS (más reciente arriba):\n" + EventLog.text(this))
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
                    9 -> askText("Dirección de casa", "Calle, número, colonia, ciudad", CarActions.home(this)) { prefs.edit().putString("addr_home", it).apply(); toast("Casa guardada") }
                    10 -> askText("Dirección de trabajo", "Calle, número, colonia, ciudad", CarActions.work(this)) { prefs.edit().putString("addr_work", it).apply(); toast("Trabajo guardado") }
                    11 -> {
                        val fast = Assistant.model(this) == Assistant.MODEL_FAST
                        Assistant.setModel(this, if (fast) Assistant.MODEL_SMART else Assistant.MODEL_FAST)
                        toast(if (fast) "Modelo más listo (Sonnet): un poco más lento y caro" else "Modelo rápido (Haiku)")
                    }
                    12 -> togglePref("wake_home", "Al encender volverá a esta pantalla", "Al encender se queda la última app")
                    13 -> togglePref("wake_music", "Al encender se reanuda la música", "Al encender la música queda en pausa")
                    14 -> askOverlayPermission()
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
        dialog().setTitle("Frases del saludo (una por línea, sale una al azar)")
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

    private fun pickGreetingAudios() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("audio/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_AUDIO)
        } catch (e: Exception) {
            toast("Copia tus audios a la carpeta CamryLauncher/saludos con el administrador de archivos")
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
        val dir = Greeter.greetingsDir().apply { mkdirs() }
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
        toast(if (ok > 0) "Listo: $ok audio(s) agregados. Saldrá uno al azar." else "No pude copiar los audios")
    }

    private fun showGreetingAudios() {
        val files = Greeter.audioFiles()
        if (files.isEmpty()) {
            toast("No hay audios. Usa \"Agregar audios de saludo\" o cópialos a CamryLauncher/saludos")
            return
        }
        dialog().setTitle("Toca uno para escucharlo")
            .setItems(files.map { it.name }.toTypedArray()) { _, which -> playPreview(files[which]) }
            .setNeutralButton("Borrar uno") { _, _ ->
                dialog().setTitle("¿Cuál borro?")
                    .setItems(files.map { it.name }.toTypedArray()) { _, w ->
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
        val away = if (stoppedAt == 0L) 0L else SystemClock.elapsedRealtime() - stoppedAt
        if (stoppedAt != 0L) EventLog.add(this, "Launcher visible otra vez (${away / 1000}s oculto)")
        if (stoppedAt != 0L && away > 60_000L) greetSoon("launcher visible", 6000L)
        stoppedAt = 0L
    }

    override fun onStop() {
        stoppedAt = SystemClock.elapsedRealtime()
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
            runCatching { Greeter.greetingsDir().mkdirs() }
        }
        startLocation()
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            lastFix = SystemClock.elapsedRealtime()
            val kmh = if (l.hasSpeed()) l.speed * 3.6f else 0f
            road.speedKmh = if (kmh < 2f) 0f else kmh
            CarState.speedKmh = road.speedKmh
            CarState.lat = l.latitude
            CarState.lon = l.longitude
            road.heading = if (l.hasBearing() && kmh >= 5f) "Rumbo " + compass(l.bearing) else "GPS listo"
            updateMap(l, kmh)
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            road.heading = "GPS apagado"
        }

        @Deprecated("API antigua")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            road.heading = "Sin permiso de ubicación"
            return
        }
        if (locationManager != null) return
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, locationListener, Looper.getMainLooper()) }
            .onFailure { road.heading = "GPS no disponible" }
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
                if (prefs.getBoolean("auto_carplay", true)) handler.postDelayed({ openCarPlay() }, 1500L)
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
        } else {
            musicArt.imageTintList = ColorStateList.valueOf(skin.textDim)
            musicArt.scaleType = ImageView.ScaleType.CENTER
            musicArt.setImageResource(R.drawable.ic_music)
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
            if (lastTickElapsed != 0L && slept > 60_000L) {
                EventLog.add(this@MainActivity, "Desperté de suspensión (${slept / 1000}s dormido)")
                onCarStarted("despertar")
            }
            lastTickElapsed = e
            lastTickUptime = u

            if (lastFix != 0L && SystemClock.elapsedRealtime() - lastFix > 5000L) {
                road.speedKmh = 0f
                road.heading = "Buscando GPS"
            }
            updateProgress()
            tickCount++
            if (tickCount % 20 == 0 && online) map?.invalidate()
            handler.postDelayed(this, 1000L)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val APPLE_VENDOR_ID = 0x05AC
        private var greetedThisRun = false
        private const val REQ_AUDIO = 7
    }
}
