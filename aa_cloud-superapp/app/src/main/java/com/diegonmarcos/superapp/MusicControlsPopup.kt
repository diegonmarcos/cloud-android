package com.diegonmarcos.superapp

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView

/**
 * Samsung One-UI-style now-playing controls popup, anchored under
 * the music mini-island. Three rows:
 *   1. App icon (left, 48dp — tap → open the playing app) + title
 *      stack (title + artist).
 *   2. SeekBar progress with elapsed / total time underneath.
 *   3. Centered transport buttons — prev | play/pause | next.
 *
 * Lifecycle — show() builds + presents; dismiss() tears down all
 * callbacks + the 1-second tick handler. Re-show is idempotent;
 * an existing popup is dismissed before a new one is built so the
 * caller doesn't have to track state.
 *
 * MediaController source — we DON'T re-walk MediaSessionManager;
 * the caller (MainActivity) passes the currently-playing
 * [MediaController] from [NowPlayingMonitor.currentController].
 * That keeps notification-listener access checks in one place and
 * means the popup never has to deal with permission errors.
 *
 * If the controller dies mid-popup (the app stops publishing the
 * session), playback callbacks fall silent and the SeekBar stops
 * advancing — fine for a transient UI; the user dismisses + the
 * island also flips GONE via [NowPlayingMonitor].
 */
class MusicControlsPopup(private val ctx: Context) {

    companion object {
        /** Fixed popup height in dp — the compact landscape box that
         *  ends just after the transport buttons. Applied as an
         *  EXPLICIT window height (see show()) so the album art can't
         *  inflate the popup down the screen. 167 ≈ 196 × 0.85 (the
         *  15% trim taken off the top/bottom padding). */
        private const val POPUP_HEIGHT_DP = 167f
    }

    private var popup: PopupWindow? = null
    private var tickHandler: Handler? = null
    private var tickRunnable: Runnable? = null
    private var attachedController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            refreshState(attachedController)
        }
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            refreshMetadata(attachedController)
        }
    }

    fun show(
        anchor: View,
        controller: MediaController?,
        playingPackage: String?,
    ) {
        dismiss()
        // NOTE: controller/playingPackage may be NULL — nothing is
        // playing. We STILL open the popup (user wants the tap to work
        // even when idle); it just renders an empty "Nothing playing"
        // card with inert controls. All controller-dependent wiring
        // below is guarded on a non-null controller.

        val view = LayoutInflater.from(ctx).inflate(
            R.layout.popup_music_controls, null, false)

        // App icon (left) — load the playing app's launcher icon
        // + wire it to open the app on tap. When idle there's no
        // package, so leave the icon empty and the tap inert.
        val iconView = view.findViewById<ImageView>(R.id.music_popup_icon)
        if (playingPackage != null) {
            runCatching {
                iconView.setImageDrawable(ctx.packageManager.getApplicationIcon(playingPackage))
            }
            iconView.setOnClickListener {
                runCatching {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(playingPackage)
                    if (intent != null) ctx.startActivity(intent)
                }
                dismiss()
            }
        } else {
            iconView.setImageDrawable(null)
        }

        if (controller != null) {
            // Transport buttons — send commands via TransportControls.
            // No-op safely when an action isn't supported by the
            // session (most apps support all three even if greyed out
            // visually; we don't bother checking session.flags).
            view.findViewById<ImageButton>(R.id.music_popup_prev).setOnClickListener {
                runCatching { controller.transportControls.skipToPrevious() }
            }
            view.findViewById<ImageButton>(R.id.music_popup_next).setOnClickListener {
                runCatching { controller.transportControls.skipToNext() }
            }
            view.findViewById<ImageButton>(R.id.music_popup_play_pause).setOnClickListener {
                val playing = controller.playbackState?.state == PlaybackState.STATE_PLAYING
                runCatching {
                    if (playing) controller.transportControls.pause()
                    else controller.transportControls.play()
                }
            }

            // SeekBar drag — only commit on user release so we don't
            // spam the controller while the thumb is moving. The 1Hz
            // tick refreshes the progress when the user isn't dragging.
            val seek = view.findViewById<SeekBar>(R.id.music_popup_progress)
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                private var userDragging = false
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar?) { userDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    userDragging = false
                    val durationMs = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    if (durationMs > 0L) {
                        val pos = (durationMs * (sb?.progress ?: 0) / seek.max).coerceAtLeast(0)
                        runCatching { controller.transportControls.seekTo(pos) }
                    }
                }
            })
        } else {
            // Idle placeholder — no track, inert progress bar.
            view.findViewById<TextView>(R.id.music_popup_title).text = "Nothing playing"
            view.findViewById<TextView>(R.id.music_popup_artist).text = ""
            view.findViewById<TextView>(R.id.music_popup_route).text = ""
            view.findViewById<TextView>(R.id.music_popup_volume).text = ""
            view.findViewById<TextView>(R.id.music_popup_time_elapsed).text = "0:00"
            view.findViewById<TextView>(R.id.music_popup_time_total).text = "0:00"
            view.findViewById<SeekBar>(R.id.music_popup_progress).apply {
                progress = 0
                isEnabled = false
            }
            view.findViewById<ImageView>(R.id.music_popup_art_bg).setImageDrawable(null)
        }

        // Title marquee needs isSelected=true to actually scroll.
        view.findViewById<TextView>(R.id.music_popup_title).isSelected = true

        // WIDE + SHORT landscape card (Samsung media-card shape):
        // horizontally spans nearly the full screen (left-icon to
        // right-icon — small side margins), vertically a compact fixed
        // box that ends right after the transport buttons.
        //
        // CRITICAL: the popup HEIGHT must be an EXPLICIT pixel value on
        // the PopupWindow itself — NOT WRAP_CONTENT. With WRAP_CONTENT
        // the window measures its root view with an AT_MOST(screen)
        // height spec, and the root card's layout_height="…dp" is NOT
        // enforced as a measure constraint for a window root. So the
        // full-bleed album ImageView (match_parent + a large bitmap +
        // centerCrop) measures to the bitmap's intrinsic height →
        // window wraps to ~screen height → the card ran all the way
        // down the screen. Passing an EXPLICIT window height caps
        // everything inside to that box; the album art then centerCrops
        // INTO it. Both dims derived from real screen metrics.
        val dm = ctx.resources.displayMetrics
        val screenW = dm.widthPixels
        val popupW = (screenW * 0.92f).toInt()
        val popupH = (POPUP_HEIGHT_DP * dm.density).toInt()

        // PopupWindow setup — outsideTouchable so the user can tap
        // outside to dismiss; focusable=true so back-press dismisses.
        val pw = PopupWindow(
            view,
            popupW,
            popupH,
            true,
        )
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Drop-shadow elevation for the floating card feel.
        pw.elevation = 16f * ctx.resources.displayMetrics.density
        pw.setOnDismissListener { detachController() }
        popup = pw

        attachedController = controller
        if (controller != null) {
            controller.registerCallback(controllerCallback, Handler(Looper.getMainLooper()))
            refreshMetadata(controller)
            refreshState(controller)
            startTicking(view, controller)
        }

        // Horizontally SCREEN-centered (not anchor-centered). The
        // mini-island anchor isn't at screen center, so a plain
        // Gravity.CENTER_HORIZONTAL biases the popup toward the
        // anchor's side. Compute xOffset so the popup's left edge
        // lands at (screenWidth − popupWidth) / 2 regardless of
        // where the anchor sits — same fix CalendarAgendaPopup uses.
        val anchorLoc = IntArray(2); anchor.getLocationOnScreen(anchorLoc)
        val xOffset = (screenW - popupW) / 2 - anchorLoc[0]
        pw.showAsDropDown(anchor, xOffset, 4, android.view.Gravity.START)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun detachController() {
        tickHandler?.removeCallbacks(tickRunnable ?: return)
        tickHandler = null
        tickRunnable = null
        attachedController?.let { c ->
            runCatching { c.unregisterCallback(controllerCallback) }
        }
        attachedController = null
    }

    private fun refreshMetadata(c: MediaController?) {
        val view = popup?.contentView ?: return
        val md = c?.metadata
        val title = md?.let {
            it.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        }?.takeIf { it.isNotBlank() } ?: ""
        val artist = md?.let {
            it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: it.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
        }?.takeIf { it.isNotBlank() } ?: ""
        view.findViewById<TextView>(R.id.music_popup_title).text = title
        view.findViewById<TextView>(R.id.music_popup_artist).text = artist
        // Album cover → full-bleed card BACKGROUND (centerCrop, dimmed
        // by the scrim). Prefer ALBUM_ART, fall back to ART, then
        // DISPLAY_ICON. When the session publishes no art the
        // background stays transparent and the card's black shows
        // through. The small left icon stays the APP icon (the
        // launch target), set at show() time — independent of the art.
        val art = md?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        view.findViewById<ImageView>(R.id.music_popup_art_bg)
            .setImageBitmap(art) // null clears it → black card background
    }

    private fun refreshState(c: MediaController?) {
        val view = popup?.contentView ?: return
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        view.findViewById<ImageButton>(R.id.music_popup_play_pause).setImageResource(
            if (playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private fun startTicking(root: View, c: MediaController) {
        val handler = Handler(Looper.getMainLooper())
        val seek = root.findViewById<SeekBar>(R.id.music_popup_progress)
        val elapsedView = root.findViewById<TextView>(R.id.music_popup_time_elapsed)
        val totalView = root.findViewById<TextView>(R.id.music_popup_time_total)
        val routeView = root.findViewById<TextView>(R.id.music_popup_route)
        val volumeView = root.findViewById<TextView>(R.id.music_popup_volume)
        val r = object : Runnable {
            override fun run() {
                val durationMs = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val state = c.playbackState
                val nowPos = if (state != null && state.state == PlaybackState.STATE_PLAYING) {
                    val elapsed = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
                    state.position + (elapsed * state.playbackSpeed).toLong()
                } else {
                    state?.position ?: 0L
                }
                if (durationMs > 0L) {
                    seek.progress = (nowPos.toFloat() / durationMs * seek.max).toInt().coerceIn(0, seek.max)
                    totalView.text = formatTime(durationMs)
                } else {
                    seek.progress = 0
                    totalView.text = "--:--"
                }
                elapsedView.text = formatTime(nowPos.coerceAtLeast(0L))
                applyRoute(routeView)
                volumeView.text = volumeLabel(c)
                handler.postDelayed(this, 500L)
            }
        }
        handler.post(r)
        tickHandler = handler
        tickRunnable = r
    }

    /** Set the route TextView's text + a MONOCHROME (white-tinted)
     *  leading icon — bluetooth / headphones / speaker. Replaces the
     *  previous colour emoji (🎧/🔊). Uses the deprecated-but-accurate
     *  "is audio going there NOW" booleans (isBluetoothA2dpOn /
     *  isWiredHeadsetOn — these reflect the ACTIVE route, unlike
     *  getDevices which lists all CONNECTED outputs), with a
     *  device-name lookup for the Bluetooth case so the label can read
     *  the actual headset name when available. */
    @Suppress("DEPRECATION")
    private fun applyRoute(routeView: TextView) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        val (iconRes, label) = when {
            am == null -> return
            am.isBluetoothA2dpOn -> {
                val name = runCatching {
                    am.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull {
                            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                                it.type == android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
                        }?.productName?.toString()
                }.getOrNull()?.takeIf { it.isNotBlank() }
                R.drawable.ic_route_bluetooth to (name ?: "Bluetooth")
            }
            am.isWiredHeadsetOn -> R.drawable.ic_route_headphones to "Headphones"
            else -> R.drawable.ic_route_speaker to "Phone speaker"
        }
        routeView.text = label
        routeView.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0)
        routeView.compoundDrawablePadding =
            (5 * ctx.resources.displayMetrics.density).toInt()
    }

    /** Session volume as a percent. Prefers the MediaController's own
     *  PlaybackInfo (accurate for both LOCAL and REMOTE/cast sessions);
     *  falls back to the system STREAM_MUSIC volume when the session
     *  doesn't publish PlaybackInfo. */
    private fun volumeLabel(c: MediaController): String {
        val info = c.playbackInfo
        val pct = if (info != null && info.maxVolume > 0) {
            (info.currentVolume * 100f / info.maxVolume).toInt()
        } else {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
            val max = am?.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
            val cur = am?.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) ?: 0
            if (max > 0) cur * 100 / max else return ""
        }
        return "Vol $pct%"
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "%d:%02d".format(m, s)
    }
}
