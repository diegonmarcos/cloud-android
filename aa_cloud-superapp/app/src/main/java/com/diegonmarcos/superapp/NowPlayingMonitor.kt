package com.diegonmarcos.superapp

import android.content.ComponentName
import android.content.Context
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
    /** Invoked whenever the "is anything playing" answer flips. */
    private val onPlayingChanged: (playing: Boolean) -> Unit,
) {
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
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            rebindCallbacks(controllers ?: emptyList())
            evaluateAndDispatch()
        }

    private var tracked: List<MediaController> = emptyList()
    private var lastReported: Boolean = false

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
        val anyPlaying = tracked.any { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (anyPlaying != lastReported) {
            lastReported = anyPlaying
            onPlayingChanged(anyPlaying)
        }
    }
}
