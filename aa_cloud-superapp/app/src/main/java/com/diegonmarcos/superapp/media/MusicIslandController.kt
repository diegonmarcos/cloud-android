package com.diegonmarcos.superapp.media

import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.diegonmarcos.superapp.R

/**
 * Drives the music-playing mini-island above the Dynamic Island, extracted from
 * MainActivity. Owns the [NowPlayingMonitor] + [MusicControlsPopup]; the Activity
 * just calls [resume]/[pause] from its lifecycle.
 *
 * The island is permanently visible — a plain black pill when nothing plays,
 * lighting up (icon + marquee title + wave) when a media session is PLAYING.
 * Tap → a Samsung-style controls popup anchored under the island.
 */
class MusicIslandController(private val activity: AppCompatActivity) {

    // Lazy: allocate the MediaSessionManager binder + listener only once the
    // activity is actually visible (first resume).
    private var monitor: NowPlayingMonitor? = null
    private var popup: MusicControlsPopup? = null

    fun resume() {
        val island = activity.findViewById<FrameLayout>(R.id.music_playing_island)
        val icon = activity.findViewById<ImageView>(R.id.music_playing_icon)
        val title = activity.findViewById<TextView>(R.id.music_playing_title)
        val wave = activity.findViewById<View>(R.id.music_playing_wave)

        island?.visibility = View.VISIBLE
        if (monitor == null) {
            // Default idle look — black pill, contents hidden — until the monitor
            // dispatches a playing session.
            icon?.visibility = View.GONE
            title?.visibility = View.GONE
            wave?.visibility = View.GONE
        }
        if (island != null && monitor == null) {
            monitor = NowPlayingMonitor(activity) { playingPackage, songTitle, artist ->
                if (playingPackage != null) {
                    icon?.visibility = View.VISIBLE
                    title?.visibility = View.VISIBLE
                    wave?.visibility = View.VISIBLE
                    runCatching {
                        icon?.setImageDrawable(activity.packageManager.getApplicationIcon(playingPackage))
                    }.onFailure { icon?.setImageDrawable(null) }
                    val song = songTitle ?: runCatching {
                        activity.packageManager.getApplicationLabel(
                            activity.packageManager.getApplicationInfo(playingPackage, 0)).toString()
                    }.getOrDefault("Playing")
                    title?.text = if (!artist.isNullOrBlank()) "$artist — $song" else song
                    // Restart the marquee scroll from the start of the new string.
                    title?.isSelected = false
                    title?.isSelected = true
                } else {
                    icon?.setImageDrawable(null); icon?.visibility = View.GONE
                    title?.text = ""; title?.visibility = View.GONE
                    wave?.visibility = View.GONE
                }
            }
            island.setOnClickListener {
                val pkg = monitor?.currentPackage
                val ctrl = monitor?.currentController
                if (popup == null) popup = MusicControlsPopup(activity)
                popup?.show(island, ctrl, pkg)
            }
        }
        monitor?.start()
    }

    fun pause() {
        monitor?.stop()
        popup?.dismiss()
    }
}
