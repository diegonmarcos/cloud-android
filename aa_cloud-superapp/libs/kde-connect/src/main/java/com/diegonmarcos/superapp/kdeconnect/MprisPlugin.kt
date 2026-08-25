package com.diegonmarcos.superapp.kdeconnect

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import org.json.JSONArray

/**
 * kdeconnect.mpris — let the desktop see + control the phone's media playback.
 * Reads active MediaControllers via [MediaSessionManager] (authorised by the
 * app's notification-listener), reports now-playing/player-list, and applies
 * transport / volume / seek commands. Original implementation of the documented
 * mpris packet contract.
 */
object MprisPlugin : KdePlugin {
    private const val LISTENER = "com.diegonmarcos.superapp.notificationcenter.PhoneNotificationListenerService"

    override val id = "mpris"
    override val incoming = setOf(NetworkPacket.TYPE_MPRIS_REQUEST, NetworkPacket.TYPE_MPRIS)
    override val outgoing = setOf(NetworkPacket.TYPE_MPRIS, NetworkPacket.TYPE_MPRIS_REQUEST)

    /** Sender (remote) builders — control the DESKTOP's player from the phone. */
    fun command(action: String, player: String? = null) =
        NetworkPacket.of(NetworkPacket.TYPE_MPRIS_REQUEST) {
            put("action", action); if (player != null) put("player", player)
        }
    fun setRemoteVolume(pct: Int, player: String? = null) =
        NetworkPacket.of(NetworkPacket.TYPE_MPRIS_REQUEST) {
            put("setVolume", pct.coerceIn(0, 100)); if (player != null) put("player", player)
        }
    fun requestPlayers() =
        NetworkPacket.of(NetworkPacket.TYPE_MPRIS_REQUEST) { put("requestPlayerList", true) }

    override fun onLinkReady(ctx: Context, link: KdeLink) {
        sendPlayerList(ctx, link)
        controllers(ctx).firstOrNull()?.let { sendNowPlaying(ctx, link, it) }
    }

    /** Last now-playing the DESKTOP reported (when we drive it as a remote). */
    @Volatile var remoteNowPlaying: String = ""
        private set
    @Volatile var remotePlayer: String = ""
        private set

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        // Inbound now-playing from the desktop (remote-control view) — cache it.
        if (packet.type == NetworkPacket.TYPE_MPRIS) {
            packet.getString("player").takeIf { it.isNotBlank() }?.let { remotePlayer = it }
            val np = packet.getString("nowPlaying")
            if (np.isNotBlank()) remoteNowPlaying = np
            return true
        }
        if (packet.getBoolean("requestPlayerList")) { sendPlayerList(ctx, link); return true }
        val c = controllerFor(ctx, packet.getString("player")) ?: controllers(ctx).firstOrNull() ?: return true
        when (packet.getString("action")) {
            "PlayPause" -> if (isPlaying(c)) c.transportControls.pause() else c.transportControls.play()
            "Play"      -> c.transportControls.play()
            "Pause"     -> c.transportControls.pause()
            "Stop"      -> c.transportControls.stop()
            "Next"      -> c.transportControls.skipToNext()
            "Previous"  -> c.transportControls.skipToPrevious()
        }
        if (packet.has("setVolume")) setVolume(c, packet.getInt("setVolume"))
        if (packet.has("Seek")) runCatching {          // KDE Seek = microseconds offset
            c.transportControls.seekTo(position(c) + packet.body.optLong("Seek", 0) / 1000)
        }
        if (packet.has("SetPosition")) runCatching {    // absolute, milliseconds
            c.transportControls.seekTo(packet.body.optLong("SetPosition", 0))
        }
        if (packet.getBoolean("requestNowPlaying") || packet.getBoolean("requestVolume")) {
            sendNowPlaying(ctx, link, c)
        }
        return true
    }

    private fun controllers(ctx: Context): List<MediaController> = runCatching {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        msm.getActiveSessions(ComponentName(ctx, LISTENER))
    }.getOrDefault(emptyList())

    private fun controllerFor(ctx: Context, name: String): MediaController? =
        if (name.isBlank()) null
        else controllers(ctx).firstOrNull { playerName(ctx, it) == name || it.packageName == name }

    private fun playerName(ctx: Context, c: MediaController): String = runCatching {
        val pm = ctx.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
    }.getOrDefault(c.packageName)

    private fun isPlaying(c: MediaController): Boolean =
        c.playbackState?.state == PlaybackState.STATE_PLAYING
    private fun position(c: MediaController): Long = c.playbackState?.position ?: 0L

    private fun setVolume(c: MediaController, pct: Int) {
        val pi = c.playbackInfo ?: return
        val max = pi.maxVolume.takeIf { it > 0 } ?: return
        runCatching { c.setVolumeTo((pct.coerceIn(0, 100) * max) / 100, 0) }
    }

    private fun sendPlayerList(ctx: Context, link: KdeLink) {
        link.send(NetworkPacket.of(NetworkPacket.TYPE_MPRIS) {
            put("playerList", JSONArray(controllers(ctx).map { playerName(ctx, it) }))
            put("supportAlbumArtPayload", false)
        })
    }

    private fun sendNowPlaying(ctx: Context, link: KdeLink, c: MediaController) {
        val md = c.metadata
        val title  = md?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val album  = md?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        val length = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        val pi = c.playbackInfo
        val vol = if (pi != null && pi.maxVolume > 0) pi.currentVolume * 100 / pi.maxVolume else 100
        link.send(NetworkPacket.of(NetworkPacket.TYPE_MPRIS) {
            put("player", playerName(ctx, c))
            put("title", title); put("artist", artist); put("album", album)
            put("nowPlaying", listOf(artist, title).filter { it.isNotBlank() }.joinToString(" - "))
            put("isPlaying", isPlaying(c))
            put("pos", position(c)); put("length", length)
            put("volume", vol)
            put("canPause", true); put("canPlay", true)
            put("canGoNext", true); put("canGoPrevious", true); put("canSeek", length > 0)
        })
    }
}
