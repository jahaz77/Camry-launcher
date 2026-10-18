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
                    EventLog.add(context, "Bluetooth conectado: $name")
                    WakeLogic.carStarted(context, "bluetooth $name", goHome = true)
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

    fun carStarted(ctx: Context, reason: String, goHome: Boolean, greetDelay: Long = 6000L) {
        val app = ctx.applicationContext
        val now = SystemClock.elapsedRealtime()
        if (lastHandled != 0L && now - lastHandled < 30_000L) return
        lastHandled = now
        val prefs = ThemeManager.prefs(app)
        if (goHome && prefs.getBoolean("wake_home", true)) goHome(app, reason)
        greet(app, reason, greetDelay)
        if (prefs.getBoolean("wake_music", true)) handler.postDelayed({ resumeMusic(app) }, greetDelay + 6000L)
    }

    /**
     * Pone el launcher al frente. El estéreo abre su app de Bluetooth unos segundos después
     * de encender, así que se vuelve a intentar durante los primeros ~20 segundos.
     */
    private fun goHome(ctx: Context, reason: String) {
        if (!Settings.canDrawOverlays(ctx)) {
            EventLog.add(ctx, "No puedo volver al inicio: falta el permiso \"Mostrar sobre otras apps\"")
        }
        var tries = 0
        for (delay in longArrayOf(500L, 3000L, 6000L, 10_000L, 15_000L, 21_000L)) {
            handler.postDelayed({
                if (!MainActivity.visible) {
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
