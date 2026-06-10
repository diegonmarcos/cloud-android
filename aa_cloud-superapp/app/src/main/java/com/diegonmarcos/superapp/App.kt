package com.diegonmarcos.superapp

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.google.android.material.color.DynamicColors

/**
 * Application entry point — runs BEFORE any Activity. Wires:
 *  • Trace + CrashLogger (capture inflation/onCreate failures)
 *  • Material 3 DynamicColors — on Android 12+ the theme adapts to the
 *    system wallpaper palette. Older devices fall back to the brand
 *    palette declared in colors.xml + themes.xml.
 *  • Force night-mode — the app's whole visual identity is dark
 *    purple→black gradient, and the system long-press tooltip pill
 *    picks `tooltip_frame_dark` (black bg, white text) instead of
 *    `tooltip_frame_light` (white bg) when night mode is on. Same
 *    knob fixes the pill colour without us re-implementing it.
 */
class App : Application() {
    override fun onCreate() {
        // Force night mode BEFORE super so AppCompatDelegate picks it up
        // on the very first inflation.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
        // Capture process-start time before anything else so About →
        // Battery & Usage can report the real uptime.
        AppProcessUptime.initOnce()
        // DevControlServer FIRST so even if anything downstream
        // crashes I can still curl /logcat / /trace / /crashes from
        // this device's shell to debug.
        runCatching { DevControlServer.start(this) }
        runCatching { Trace.install(this) }
        runCatching { CrashLogger.install(this) }
        runCatching { DynamicColors.applyToActivitiesIfAvailable(this) }
        runCatching { detectVersionBump() }
        // Schedule the periodic battery-session tick (15 min cadence).
        // Idempotent — KEEP policy ensures re-scheduling on every cold
        // start is a no-op. Without this the discharge anchor only
        // updates when the user OPENS a battery surface, so the rate
        // appears to "start computing just now" hours after an actual
        // unplug. With it, the worker runs even when the SuperApp is
        // backgrounded / process-killed and the transition-detection
        // path in BatterySessionStats.read catches plug/unplug events
        // at ≤15 min granularity even when PowerStateReceiver is
        // suppressed by Samsung Sleeping Apps.
        runCatching { BatterySessionWorker.schedule(this) }
        Trace.i("App", "Application.onCreate done — pid=${android.os.Process.myPid()}")
    }

    /** Updater producer for NotificationStore. Compares the current
     *  BuildConfig.VERSION_CODE against the value last recorded in a
     *  private SharedPreferences. First launch after a code bump pushes
     *  an "Updated to vc:N" entry; first-ever launch records the
     *  baseline silently (nothing to update from). */
    private fun detectVersionBump() {
        val sp = getSharedPreferences("updater_marker", android.content.Context.MODE_PRIVATE)
        val lastVc = sp.getInt("last_vc", -1)
        val curVc  = BuildConfig.VERSION_CODE
        if (lastVc in 1 until curVc) {
            NotificationStore.push(
                ctx      = this,
                source   = "Updater",
                title    = "Updated to vc:$curVc",
                body     = "From vc:$lastVc · sha:${BuildConfig.GIT_SHORT_SHA} · ${BuildConfig.BUILD_TIMESTAMP}",
                severity = NotificationStore.Sev.INFO,
            )
        }
        if (lastVc != curVc) sp.edit().putInt("last_vc", curVc).apply()
    }
}
