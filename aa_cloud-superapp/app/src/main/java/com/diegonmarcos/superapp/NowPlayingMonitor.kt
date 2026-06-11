package com.diegonmarcos.superapp

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Polls + listens for an active media session in the
 * BATTERY_STATUS_FULL-equivalent of "music is playing right now"
 * sense (any media session whose PlaybackState is STATE_PLAYING).
 * Used by [MainActivity] to drive the equalizer-bars view above
 * the Dynamic Island.
 *
 * Permission model:
 *   [MediaSessionManager.getActiveSessions] requires the caller
 *   to be a NotificationListener (the same access the existing
 *   [PhoneNotificationListenerService] needs for the Phone
 *   Notifications panel). When that's not granted yet, the
 *   service throws SecurityException; we catch it, report empty
 *   state, and the music icon simply stays hidden. The user can
 *   enable notification access via the existing button in
 *   Configs/About → Permissions and music detection lights up
 *   automatically — no app restart needed.
 *
 * Threading:
 *   addOnActiveSessionsChangedListener delivers callbacks on the
 *   thread tied to the Handler we pass. We pass the main-looper
 *   Handler so the listener fires on the UI thread and can update
 *   the icon directly — no need for a postToMain bounce.
 */
class NowPlayingMonitor(
    private val ctx: Context,
    /** Invoked whenever the currently-playing package OR its
     *  metadata flips. `playingPackage` is null when nothing is
     *  currently playing — UI hides the island. `title` is the
     *  best-effort song title for the marquee: prefers TITLE,
     *  falls back to DISPLAY_TITLE, finally null when the app
     *  publishes neither (rare — most music apps publish at
     *  least one). Callers should defensively render
     *  null/blank as a generic label like the app name. */
    private val onPlayingChanged: (playingPackage: String?, title: String?) -> Unit,
) {
    /** Most recently observed playing-controller package name, or
     *  null when nothing is currently playing. Surfaced for the tap
     *  handler so it can short-circuit "no app to open" without
     *  having to re-query MediaSessionManager. */
    @Volatile var currentPackage: String? = null
        private set
    /** Most recently observed playing track title (TITLE or
     *  DISPLAY_TITLE metadata key), or null when nothing is
     *  playing or the app publishes no title. */
    @Volatile var currentTitle: String? = null
        private set
    private val mgr: MediaSessionManager? =
        ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val notifListenerComponent = ComponentName(
        ctx, PhoneNotificationListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Per-controller PlaybackState callback — fires whenever the
     *  app updates its playback state (play / pause / stop / seek).
     *  We share one instance across all currently-tracked
     *  controllers; re-registering it is harmless. */
    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            evaluateAndDispatch()
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            evaluateAndDispatch()
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebindCallbacks(controllers ?: emptyList())
            evaluateAndDispatch()
        }

    private var tracked: List<MediaController> = emptyList()
    private var lastReportedPkg: String? = null
    private var lastReportedTitle: String? = null

    /** The MediaController whose session is currently in
     *  STATE_PLAYING — surfaced so the controls popup can read
     *  metadata (TITLE, ARTIST, DURATION, ALBUM_ART) and send
     *  transport commands (play, pause, skipToPrevious,
     *  skipToNext) without having to re-walk the session list. */
    @Volatile var currentController: MediaController? = null
        private set

    fun start() {
        val m = mgr ?: return
        // Initial subscribe + initial evaluation. SecurityException
        // when the notification-listener service isn't enabled yet
        // — silently treat as "no sessions" so the icon stays hidden.
        runCatching {
            m.addOnActiveSessionsChangedListener(sessionsChangedListener, notifListenerComponent, mainHandler)
            val initial = m.getActiveSessions(notifListenerComponent) ?: emptyList()
            rebindCallbacks(initial)
            evaluateAndDispatch()
        }
    }

    fun stop() {
        val m = mgr ?: return
        runCatching { m.removeOnActiveSessionsChangedListener(sessionsChangedListener) }
        for (c in tracked) runCatching { c.unregisterCallback(controllerCallback) }
        tracked = emptyList()
    }

    /** Unsubscribe from the OLD controller list + subscribe to the
     *  NEW list. Idempotent — we don't deduplicate (the controller's
     *  own register/unregister handle the bookkeeping). */
    private fun rebindCallbacks(controllers: List<MediaController>) {
        for (c in tracked) runCatching { c.unregisterCallback(controllerCallback) }
        for (c in controllers) runCatching {
            c.registerCallback(controllerCallback, mainHandler)
        }
        tracked = controllers
    }

    private fun evaluateAndDispatch() {
        // Pick the FIRST controller in PLAYING state. Most users have
        // exactly one media app playing at a time; even when several
        // are listed (e.g. when one was paused but the system still
        // surfaces the session), the playing one wins. Ties are rare
        // enough that picking the first-listed is fine.
        val playing = tracked.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val playingPkg = playing?.packageName
        val title = playing?.metadata?.let { md ->
            md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }?.takeIf { it.isNotBlank() }
        currentController = playing
        currentPackage = playingPkg
        currentTitle = title
        if (playingPkg != lastReportedPkg || title != lastReportedTitle) {
            lastReportedPkg = playingPkg
            lastReportedTitle = title
            onPlayingChanged(playingPkg, title)
        }
    }
}
