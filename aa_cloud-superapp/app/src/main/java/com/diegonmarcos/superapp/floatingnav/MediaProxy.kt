package com.diegonmarcos.superapp.floatingnav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import com.diegonmarcos.superapp.PhoneNotificationListenerService
import com.diegonmarcos.superapp.R

/**
 * "Cloud SuperApp - Notification Center Media Session" — a rich media-control
 * notification that mirrors + drives whatever is actually playing on the
 * device. It reads the active MediaSession via MediaSessionManager (needs the
 * app's NotificationListener access) and forwards Prev / Play-Pause / Next to
 * that session's transport controls. Album art + title/artist are the real
 * metadata; MediaStyle is linked to the live session token for the full
 * media-player rendering. When nothing is playing, the notification is removed.
 */
class MediaProxy(private val ctx: Context) {

    private var lastKey: String? = null

    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The active controller — the playing one if any, else the first. Throws
     *  SecurityException without notification access → caught → null. */
    fun activeController(): MediaController? = runCatching {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val comp = ComponentName(ctx, PhoneNotificationListenerService::class.java)
        val list = msm.getActiveSessions(comp)
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }.getOrNull()

    /** Forward a `media:*` action to the active session's transport. */
    fun transport(target: String) {
        val c = activeController() ?: return
        when (target) {
            "media:playpause" ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            "media:next" -> c.transportControls.skipToNext()
            "media:prev" -> c.transportControls.skipToPrevious()
        }
        lastKey = null // force a re-render on the next refresh
    }

    /** (Re)post the media notification for the current session, or cancel it. */
    fun refresh() {
        val c = activeController()
        if (c == null) { nm().cancel(NOTIF_MEDIA); lastKey = null; return }
        ensureChannel()
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: "Now playing"
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        val key = "$title|$artist|$playing"
        if (key == lastKey) return // no change → don't re-post (avoid flicker)
        lastKey = key
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val b = NotificationCompat.Builder(ctx, MEDIA_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText("Cloud SuperApp - Notification Center")
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (art != null) b.setLargeIcon(art)
        b.addAction(android.R.drawable.ic_media_previous, "Prev", pi("media:prev"))
        b.addAction(
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) "Pause" else "Play", pi("media:playpause"),
        )
        b.addAction(android.R.drawable.ic_media_next, "Next", pi("media:next"))
        b.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setShowActionsInCompactView(0, 1, 2)
                .setMediaSession(MediaSessionCompat.Token.fromToken(c.sessionToken)),
        )
        runCatching { nm().notify(NOTIF_MEDIA, b.build()) }
    }

    fun cancel() { runCatching { nm().cancel(NOTIF_MEDIA) }; lastKey = null }

    /** Media transport keeps the shade OPEN (unlike the Main quick actions), so
     *  it's a plain service PendingIntent, not the close-shade trampoline. */
    private fun pi(target: String): PendingIntent = PendingIntent.getService(
        ctx, target.hashCode(),
        Intent(ctx, FloatingNavService::class.java)
            .setAction(FloatingNavService.ACTION_NAV).putExtra(FloatingNavService.EXTRA_TARGET, target),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm().getNotificationChannel(MEDIA_CHANNEL_ID) == null) {
                nm().createNotificationChannel(NotificationChannel(
                    MEDIA_CHANNEL_ID, "Media controls", NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Cloud SuperApp media controls for the active session."; setShowBadge(false) })
            }
        }
    }

    companion object {
        const val NOTIF_MEDIA = 0xF3
        private const val MEDIA_CHANNEL_ID = "floating_nav_media"
    }
}
