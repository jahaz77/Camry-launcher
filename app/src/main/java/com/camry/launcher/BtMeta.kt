package com.camry.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock

/**
 * Canción del Bluetooth del estéreo leída de los "avisos" (broadcasts) que mandan las apps de Bluetooth de fábrica.
 * Muchas no usan el método normal de Android (MediaSession) y solo avisan con nombres propios de cada marca
 * (módulos Goodocom, Microntek, Autochips, FYT…). Se escuchan todos los conocidos y se guarda lo último.
 */
object BtMeta {

    data class Track(
        val title: String?, val artist: String?, val album: String?,
        val playing: Boolean?, val durationMs: Long, val positionMs: Long,
        val source: String, val at: Long
    ) {
        /** Posición estimada ahora mismo (si va sonando, avanza sola). */
        fun positionNow(): Long =
            if (positionMs < 0) -1 else if (playing == true) positionMs + (SystemClock.elapsedRealtime() - at) else positionMs
    }

    /** Avisos conocidos de reproductores y de apps de Bluetooth de estéreos chinos. */
    val ACTIONS = listOf(
        // Bluetooth estándar de Android (AVRCP) y variantes
        "android.bluetooth.avrcp-controller.profile.action.TRACK_EVENT",
        "com.android.bluetooth.a2dpsink.action.METADATA_CHANGED",
        "com.android.music.bluetooth.metachanged",
        // módulos Bluetooth de estéreos (Goodocom es muy común en Topway)
        "com.goodocom.gocsdk.music.info", "com.goodocom.gocsdk.action.MUSIC_INFO",
        "com.goodocom.gocsdk.music.state", "com.goodocom.gocsdk.action.MUSIC_STATE",
        "com.ts.bt.music.info", "com.ts.bt.music.state",
        "com.tw.bt.music.info", "com.tw.bt.music.id3", "com.tw.music.action.meta",
        "com.microntek.music.report", "com.microntek.bt.report",
        "com.syu.bt.music.id3", "com.syu.ms.bt.id3", "com.fyt.bt.music.id3",
        "com.autochips.bluetooth.MUSIC_INFO", "com.autochips.bt.music.id3",
        "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility.action.ACTION_MEDIA_DATA_UPDATE",
        "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility.action.ACTION_PLAYBACK_DATA_UPDATE",
        "com.nwd.action.ACTION_BT_MUSIC_ID3",
        "com.clw.intent.MEDIA_INFO", "com.clw.intent.MEDIA_PLAY_STATE",
        // reproductores comunes
        "com.android.music.metachanged", "com.android.music.playstatechanged",
        "com.spotify.music.metadatachanged", "com.spotify.music.playbackstatechanged"
    )

    @Volatile var current: Track? = null
        private set
    @Volatile var onChange: (() -> Unit)? = null

    /** Para Diagnóstico: últimos avisos recibidos. */
    private val log = ArrayDeque<String>()
    @Volatile var count = 0
        private set

    fun logText(): String = synchronized(log) { if (log.isEmpty()) "ningún aviso de música recibido" else log.joinToString("\n") }

    private fun addLog(s: String) = synchronized(log) {
        log.addFirst(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + s)
        while (log.size > 8) log.removeLast()
    }

    /** Dura 10 min si está en pausa / sin estado; mientras suena no caduca. */
    fun fresh(): Track? = current?.takeIf { it.playing == true || SystemClock.elapsedRealtime() - it.at < 10 * 60_000L }

    fun register(ctx: Context, extra: List<String> = emptyList()): BroadcastReceiver {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { runCatching { handle(c, i) } }
        }
        val f = IntentFilter().apply { (ACTIONS + extra).distinct().forEach { addAction(it) } }
        ctx.registerReceiver(r, f)
        return r
    }

    private fun str(x: Bundle, vararg keys: String): String? {
        for (k in keys) {
            val v = runCatching { x.get(k) }.getOrNull() ?: continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null" && !s.equals("unknown", true)) return s
        }
        return null
    }

    private fun num(x: Bundle, vararg keys: String): Long {
        for (k in keys) {
            val v = runCatching { x.get(k) }.getOrNull() ?: continue
            val n = when (v) { is Number -> v.toLong(); is String -> v.trim().toLongOrNull(); else -> null } ?: continue
            if (n >= 0) return n
        }
        return -1
    }

    private fun bool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> when (v.toInt()) { 1, 3 -> true; 0, 2 -> false; else -> null }
        is String -> when (v.lowercase()) { "true", "1", "play", "playing", "start" -> true; "false", "0", "pause", "paused", "stop", "stopped" -> false; else -> null }
        else -> null
    }

    private fun handle(ctx: Context, i: Intent) {
        val x = i.extras ?: return
        val action = i.action ?: return
        count++
        val src = if (action.contains("bt", true) || action.contains("bluetooth", true) || action.contains("avrcp", true) || action.contains("a2dp", true)) "Bluetooth" else "Música"
        var title: String? = null; var artist: String? = null; var album: String? = null
        var playing: Boolean? = null; var dur = -1L; var pos = -1L

        // Bluetooth estándar: trae MediaMetadata / PlaybackState
        val md = runCatching { x.get("android.bluetooth.avrcp-controller.profile.extra.METADATA") }.getOrNull() as? MediaMetadata
        if (md != null) {
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 } ?: -1
        }
        val ps = runCatching { x.get("android.bluetooth.avrcp-controller.profile.extra.PLAYBACK") }.getOrNull() as? PlaybackState
        if (ps != null) {
            playing = when (ps.state) { PlaybackState.STATE_PLAYING, PlaybackState.STATE_BUFFERING -> true; PlaybackState.STATE_PAUSED, PlaybackState.STATE_STOPPED -> false; else -> null }
            pos = ps.position
        }

        // Autochips (nombres largos)
        val ac = "com.autochips.bluetooth.avrcpct.AvrcpCtPlayerUtility.extra."
        title = title ?: str(x, ac + "MEDIA_TITLE")
        artist = artist ?: str(x, ac + "MEDIA_ARTIST")
        album = album ?: str(x, ac + "MEIDA_ALBUM", ac + "MEDIA_ALBUM")
        if (dur < 0) dur = num(x, ac + "MEDIA_LENGTH")
        if (pos < 0) pos = num(x, ac + "MEDIA_POSITION")
        if (playing == null) playing = bool(runCatching { x.get(ac + "PLAYBACK_STATUS") }.getOrNull())

        // nombres genéricos que usan casi todas las marcas
        title = title ?: str(x, "track", "title", "song", "name", "id3_title", "music_title", "songName", "song_name", "TITLE", "Title")
        artist = artist ?: str(x, "artist", "singer", "id3_artist", "music_artist", "artistName", "ARTIST", "Artist")
        album = album ?: str(x, "album", "id3_album", "music_album", "ALBUM", "Album")
        if (playing == null) for (k in listOf("playing", "isPlaying", "playstate", "play_state", "state", "status", "isplaying")) {
            if (x.containsKey(k)) { playing = bool(x.get(k)); if (playing != null) break }
        }
        if (dur < 0) dur = num(x, "duration", "length", "total_time", "totalTime", "DURATION")
        if (pos < 0) pos = num(x, "position", "playbackPosition", "current_time", "curTime", "POSITION")
        if (dur in 1..35_999) dur *= 1000                       // venía en segundos
        if (pos in 1..35_999 && dur >= 36_000 && pos * 1000 <= dur) pos *= 1000

        addLog(action.substringAfterLast("com.") + ": " + (title ?: "—") + (artist?.let { " · $it" } ?: "") +
            (playing?.let { if (it) " ▶" else " ⏸" } ?: "") + "  [" + x.keySet().take(6).joinToString(",") + "]")

        if (title == null && artist == null && playing == null) return
        val prev = current
        // un aviso solo de estado conserva la canción anterior
        current = Track(
            title ?: prev?.title, artist ?: (if (title == null) prev?.artist else null), album ?: (if (title == null) prev?.album else null),
            playing ?: prev?.playing, if (dur > 0) dur else if (title == null) prev?.durationMs ?: -1 else -1,
            if (pos >= 0) pos else -1, src, SystemClock.elapsedRealtime()
        )
        EventLog.takeIf { prev?.title != current?.title }?.add(ctx, "Música por aviso: ${current?.title ?: "?"} (${action.substringAfterLast('.')})")
        android.os.Handler(android.os.Looper.getMainLooper()).post { onChange?.invoke() }
    }
}
