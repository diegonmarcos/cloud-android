package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.battery.PowerStateReceiver
import com.diegonmarcos.superapp.battery.BatterySessionWorker
import com.diegonmarcos.superapp.battery.BatterySessionStats

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.work.Configuration as WorkManagerConfiguration
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.diegonmarcos.superapp.notificationcenter.KdeStatusService
import com.diegonmarcos.superapp.translate.LocalTranslateEngineClient
import com.diegonmarcos.superapp.translate.TranslateEngines
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
class App : Application(), WorkManagerConfiguration.Provider {
    /**
     * On-demand WorkManager initialization. The vendored HeliBoard
     * (libs:keyboard) merge drops androidx.startup's auto-init
     * `WorkManagerInitializer`, so the default provider never initializes
     * WorkManager — every `WorkManager.getInstance(...)` then throws
     * "WorkManager is not initialized properly". That crashed the launcher at
     * MainActivity → Updater.start, and silently broke
     * BatterySessionWorker.schedule (swallowed by its runCatching).
     *
     * Implementing Configuration.Provider + removing the default initializer in
     * the manifest is the canonical on-demand setup: the FIRST getInstance()
     * call lazily initializes WorkManager with this config. Repairs both the
     * updater and the battery worker, deterministically, regardless of the
     * manifest-merge outcome.
     */
    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        // Force night mode BEFORE super so AppCompatDelegate picks it up
        // on the very first inflation.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate()
        // Register in-process ML Kit engine so the keyboard's translate bar
        // (TranslateBarView / Translator) works in-process via LocalTranslateEngineClient.
        TranslateEngines.client = LocalTranslateEngineClient(this)
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
        // Persistent "Cloud SA - KDE" status notification, live-updated. Owned
        // by a foreground service (parity with "Cloud SA - Quick Actions") so it
        // survives "clear all" / process death instead of being a droppable
        // plain ongoing notify.
        runCatching { KdeStatusService.start(this) }
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
        // Constellation AppStore — periodic fleet check across every
        // constellation APK (Configs → Constellation). Notifies when siblings
        // have GHCR updates; install stays user-initiated. Idempotent (KEEP).
        runCatching { com.diegonmarcos.superapp.configs.ConstellationWorker.start(this) }
        // HeliBoard (libs:keyboard) is vendored WITHOUT its own Application —
        // our .App wins the manifest merge (tools:replace android:name), so the
        // keyboard's app-level init never ran. That left Settings /
        // SubtypeSettings.prefs null → Configs→Keyboard (SettingsActivity) AND
        // LatinIME crashed with "parameter prefs is null". Replicate HeliBoard
        // App.onCreate's synchronous init here so both work.
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
