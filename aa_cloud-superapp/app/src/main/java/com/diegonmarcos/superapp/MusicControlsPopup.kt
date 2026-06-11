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
        if (controller == null || playingPackage == null) return

        val view = LayoutInflater.from(ctx).inflate(
            R.layout.popup_music_controls, null, false)

        // App icon (left) — load the playing app's launcher icon
        // + wire it to open the app on tap. Same launch path as
        // tapping the island itself before this popup existed.
        val iconView = view.findViewById<ImageView>(R.id.music_popup_icon)
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

        // Title marquee needs isSelected=true to actually scroll.
        view.findViewById<TextView>(R.id.music_popup_title).isSelected = true

        // PopupWindow setup — outsideTouchable so the user can tap
        // outside to dismiss; focusable=true so back-press dismisses.
        val pw = PopupWindow(
            view,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        pw.isOutsideTouchable = true
        pw.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        // Drop-shadow elevation for the floating card feel.
        pw.elevation = 16f * ctx.resources.displayMetrics.density
        pw.setOnDismissListener { detachController() }
        popup = pw

        attachedController = controller
        controller.registerCallback(controllerCallback, Handler(Looper.getMainLooper()))

        refreshMetadata(controller)
        refreshState(controller)
        startTicking(view, controller)

        // Horizontally SCREEN-centered (not anchor-centered). The
        // mini-island anchor isn't at screen center, so a plain
        // Gravity.CENTER_HORIZONTAL biases the popup toward the
        // anchor's side. Compute xOffset so the popup's left edge
        // lands at (screenWidth − popupWidth) / 2 regardless of
        // where the anchor sits — same fix CalendarAgendaPopup uses.
        // popupWidth = the layout's fixed 280dp root width.
        val d = ctx.resources.displayMetrics.density
        val anchorLoc = IntArray(2); anchor.getLocationOnScreen(anchorLoc)
        val screenW = ctx.resources.displayMetrics.widthPixels
        val popupW = (280 * d).toInt()
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
        // Album art — prefer ALBUM_ART, fall back to ART, then
        // DISPLAY_ICON. Leave the app icon visible when the app
        // publishes no art (already set at show() time).
        val art = md?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }
        if (art != null) {
            view.findViewById<ImageView>(R.id.music_popup_icon).setImageBitmap(art)
        }
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
                handler.postDelayed(this, 500L)
            }
        }
        handler.post(r)
        tickHandler = handler
        tickRunnable = r
    }

    private fun formatTime(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "%d:%02d".format(m, s)
    }
}
