package com.camry.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Servicio que se queda vivo aunque Android cierre la pantalla del launcher.
 * Detecta cuando enciendes el auto y entonces: vuelve al inicio, saluda y reanuda la música.
 */
class CarService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var screenOffAt = 0L
    private var lastElapsed = 0L
    private var lastUptime = 0L
    private var ticks = 0

    private fun markAlive() = ThemeManager.prefs(this).edit().putLong("alive_at", System.currentTimeMillis()).apply()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOffAt = SystemClock.elapsedRealtime()
                    EventLog.add(context, "Pantalla apagada")
                }
                Intent.ACTION_SCREEN_ON -> {
                    val away = if (screenOffAt == 0L) 0L else SystemClock.elapsedRealtime() - screenOffAt
                    EventLog.add(context, "Pantalla encendida (apagada ${away / 1000}s)")
                    if (away > 60_000L) WakeLogic.carStarted(context, "pantalla encendida", goHome = true)
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    @Suppress("DEPRECATION")
                    val dev: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    val name = runCatching { dev?.name }.getOrNull() ?: "dispositivo"
                    EventLog.add(context, "Bluetooth conectado: $name (${dev?.address})")
                    dev?.let { d -> Profiles.match(context, d.address, name)?.let { Profiles.activate(context, it, "se conectó $name") } }
                    // El adaptador OBD2 también es Bluetooth: ese no cuenta como encendido ni trae audio
                    if (!AppUtils.normalize(name).contains("obd")) {
                        WakeLogic.carStarted(context, "bluetooth $name", goHome = true)
                        WakeLogic.activateBtAudio(context, "se conectó $name")
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> EventLog.add(context, "Bluetooth desconectado")
            }
        }
    }

    /** Si el reloj real avanzó mucho más que el tiempo activo, el estéreo estuvo dormido. */
    private val ticker = object : Runnable {
        override fun run() {
            val e = SystemClock.elapsedRealtime()
            val u = SystemClock.uptimeMillis()
            if (lastElapsed != 0L) {
                val slept = (e - lastElapsed) - (u - lastUptime)
                val gap = e - lastElapsed
                if (slept > 60_000L) {
                    EventLog.add(this@CarService, "Desperté de suspensión (${slept / 1000}s dormido)")
                    WakeLogic.carStarted(this@CarService, "despertar", goHome = true)
                } else if (gap > 90_000L) {
                    // El reloj se detuvo sin "dormir" (algunos estéreos congelan todo al quitar la llave)
                    EventLog.add(this@CarService, "Volví después de ${gap / 1000}s sin actividad")
                    WakeLogic.carStarted(this@CarService, "reanudación", goHome = true)
                }
            }
            lastElapsed = e
            lastUptime = u
            if (++ticks % 3 == 0) markAlive()
            handler.postDelayed(this, 3000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        startForeground(1, buildNotification())
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        })
        val up = SystemClock.elapsedRealtime()
        // Tu estéreo cierra todas las apps al quitar la llave: si llevábamos más de 1 minuto
        // sin estar vivos, este arranque ES el encendido del auto.
        val lastAlive = ThemeManager.prefs(this).getLong("alive_at", 0L)
        val off = if (lastAlive == 0L) -1L else (System.currentTimeMillis() - lastAlive) / 1000
        EventLog.add(this, "Servicio iniciado (inactivo ${off}s, estéreo encendido hace ${up / 1000}s)")
        markAlive()
        when {
            up < 5 * 60_000L -> WakeLogic.carStarted(this, "arranque del estéreo", goHome = true, greetDelay = 12_000L)
            off > 60 -> WakeLogic.carStarted(this, "encendido (estuve cerrado ${off}s)", goHome = true, greetDelay = 7000L)
        }
        handler.post(ticker)
        Obd.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        EventLog.add(this, "Servicio detenido")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Camry Launcher", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle("Camry Launcher activo")
            .setContentText("Listo para saludarte al encender")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "camry_service"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, CarService::class.java)) }
                .onFailure { EventLog.add(ctx, "No pude iniciar el servicio: ${it.message}") }
        }
    }
}

/** Arranca el servicio cuando el estéreo se reinicia por completo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EventLog.add(context, "Arranque del sistema recibido (${intent.action})")
        CarService.start(context)
    }
}

/** Lo que pasa al encender el auto, compartido por el servicio y la pantalla. */
object WakeLogic {
    private val handler = Handler(Looper.getMainLooper())
    private var lastHandled = 0L

    private var lastBtActivation = 0L

    /**
     * El estéreo solo manda el audio Bluetooth a las bocinas cuando se abre su app de música:
     * la abrimos un momento y luego regresamos al launcher. También se usa cuando el iPhone
     * se conecta por Bluetooth después de encender.
     */
    fun activateBtAudio(ctx: Context, reason: String) {
        val app = ctx.applicationContext
        if (!ThemeManager.prefs(app).getBoolean("wake_bt_audio", true)) return
        val now = SystemClock.elapsedRealtime()
        if (lastBtActivation != 0L && now - lastBtActivation < 20_000L) return
        lastBtActivation = now
        handler.postDelayed({
            // Primero en segundo plano (sin abrir pantallas); si no funciona, el método anterior.
            BtAudio.activate(app) { ok, how ->
                EventLog.add(app, "Audio Bluetooth ($reason) en segundo plano: " + (if (ok) "OK, " else "no, ") + how)
                if (!ok && ThemeManager.prefs(app).getBoolean("bt_open_fallback", true)) {
                    selfLaunchAt = SystemClock.elapsedRealtime()
                    val what = CarActions.openBluetoothMusic(app)
                    EventLog.add(app, "Audio Bluetooth: abrí $what un momento")
                    goHome(app, "tras activar Bluetooth", firstDelay = 2600L)
                }
            }
        }, 1500L)
    }

    // Ventana después de encender en la que, si una app del estéreo (p. ej. Bluetooth)
    // se abre sola encima del launcher, lo regresamos al frente.
    @Volatile var startWindowUntil = 0L
    @Volatile var selfLaunchAt = 0L
    private var reasserts = 0

    /** Lo llama MainActivity cuando algo la tapa. */
    fun onLauncherCovered(ctx: Context, lastTouchAt: Long) {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (now > startWindowUntil) return
        if (now - lastTouchAt < 3000L) return          // lo abriste tú desde el launcher
        if (now - selfLaunchAt < 4000L) return         // lo abrimos nosotros (audio Bluetooth)
        if (!ThemeManager.prefs(app).getBoolean("wake_home", true)) return
        if (reasserts >= 3) return
        reasserts++
        handler.postDelayed({
            if (!MainActivity.visible && Ui.ownScreens == 0) {
                val ok = runCatching {
                    app.startActivity(Intent(app, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); true
                }.getOrDefault(false)
                EventLog.add(app, "Una app se abrió sola encima; volví al inicio: " + if (ok) "pedido" else "falló")
            }
        }, 1500L)
    }

    fun carStarted(ctx: Context, reason: String, goHome: Boolean, greetDelay: Long = 6000L) {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        startWindowUntil = now + 45_000L
        reasserts = 0
        if (lastHandled != 0L && now - lastHandled < 30_000L) return
        lastHandled = now
        val prefs = ThemeManager.prefs(app)
        val btAudio = prefs.getBoolean("wake_bt_audio", true)
        if (btAudio) {
            activateBtAudio(app, reason)
        } else if (goHome && prefs.getBoolean("wake_home", true)) {
            goHome(app, reason)
        }
        // ¿Quién viene manejando? Se revisa qué teléfono se conecta en el primer minuto
        var profileFound = false
        for (d in longArrayOf(1500L, 4000L, 8000L, 15_000L, 25_000L, 40_000L, 60_000L, 90_000L)) handler.postDelayed({
            if (!profileFound) Thread { runCatching { Profiles.detect(app, "encendido", force = true)?.let { profileFound = true } } }.start()
        }, d)
        // si nadie se detectó, igual se ponen las luces del último perfil
        handler.postDelayed({
            if (!profileFound) Profiles.active(app)?.let { p -> Lights.address(app)?.let { Lights.applyProfile(app, p) } }
        }, 20_000L)
        greet(app, reason, greetDelay)
        if (prefs.getBoolean("wake_music", true)) handler.postDelayed({ resumeMusic(app) }, greetDelay + 6000L)
    }

    /**
     * Pone el launcher al frente. El estéreo abre su app de Bluetooth unos segundos después
     * de encender, así que se vuelve a intentar durante los primeros ~20 segundos.
     */
    private fun goHome(ctx: Context, reason: String, firstDelay: Long = 500L) {
        if (!Settings.canDrawOverlays(ctx)) {
            EventLog.add(ctx, "No puedo volver al inicio: falta el permiso \"Mostrar sobre otras apps\"")
        }
        var tries = 0
        for (delay in longArrayOf(firstDelay, firstDelay + 2500L, firstDelay + 5500L, firstDelay + 9500L, firstDelay + 14_500L, firstDelay + 20_500L)) {
            handler.postDelayed({
                if (!MainActivity.visible && Ui.ownScreens == 0) {
                    tries++
                    val ok = runCatching {
                        ctx.startActivity(
                            Intent(ctx, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        ); true
                    }.getOrDefault(false)
                    EventLog.add(ctx, "Volver al inicio ($reason) intento $tries: " + if (ok) "pedido" else "falló")
                }
            }, delay)
        }
    }

    private fun greet(ctx: Context, reason: String, delay: Long) {
        if (!Greeter.isOn(ctx)) return
        val prefs = ThemeManager.prefs(ctx)
        val now = System.currentTimeMillis()
        val last = prefs.getLong("last_greet_at", 0L)
        if (last != 0L && now - last in 0..10 * 60_000L) {
            EventLog.add(ctx, "Saludo omitido ($reason): ya saludé hace ${(now - last) / 1000}s")
            return
        }
        prefs.edit().putLong("last_greet_at", now).apply()
        EventLog.add(ctx, "Saludo programado por: $reason")
        handler.postDelayed({ Greeter.speak(ctx) { msg -> EventLog.add(ctx, "Saludo falló: $msg") } }, delay)
        // recordatorio de mantenimiento, después del saludo
        handler.postDelayed({ TripLog.dueText(ctx)?.let { Greeter.say(ctx, it) } }, delay + 9000L)
    }

    private fun resumeMusic(ctx: Context) {
        val controllers = runCatching {
            (ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager)
                .getActiveSessions(ComponentName(ctx, MediaListener::class.java))
        }.getOrNull().orEmpty()
        if (controllers.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }) return
        val mc = controllers.firstOrNull()
        if (mc != null) {
            mc.transportControls.play()
            EventLog.add(ctx, "Música reanudada (${mc.packageName})")
        } else {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY))
            EventLog.add(ctx, "Música: tecla play enviada")
        }
    }
}
