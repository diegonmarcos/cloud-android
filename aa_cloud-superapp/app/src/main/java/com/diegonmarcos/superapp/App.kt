package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.CrashLogger
import com.diegonmarcos.superapp.system.AppProcessUptime
import com.diegonmarcos.superapp.battery.PowerStateReceiver
import com.diegonmarcos.superapp.battery.BatterySessionWorker
import com.diegonmarcos.superapp.battery.BatterySessionStats

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.diegonmarcos.superapp.core.NotificationStore
import com.diegonmarcos.superapp.devcontrol.DevControlServer
import com.diegonmarcos.superapp.notificationcenter.KdeStatusService
import com.google.android.material.color.DynamicColors
import helium314.keyboard.latin.utils.prefs as heliboardPrefs

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
        // HeliBoard (libs:keyboard) is vendored WITHOUT its own Application —
        // our .App wins the manifest merge (tools:replace android:name), so the
        // keyboard's app-level init never ran. That left Settings /
        // SubtypeSettings.prefs null → Configs→Keyboard (SettingsActivity) AND
        // LatinIME crashed with "parameter prefs is null". Replicate HeliBoard
        // App.onCreate's synchronous init here so both work.
        runCatching { initVendoredKeyboard() }
        Trace.i("App", "Application.onCreate done — pid=${android.os.Process.myPid()}")
    }

    /** Mirrors helium314.keyboard.latin.App.onCreate's synchronous init (the
     *  vendored HeliBoard App class is never instantiated, since our manifest
     *  android:name wins). These are the initializers Settings / SubtypeSettings
     *  / LatinIME read at runtime. Each wrapped so one failure can't abort
     *  SuperApp startup. Keep in sync with libs/keyboard App.kt on resyncs. */
    private fun initVendoredKeyboard() {
        runCatching { helium314.keyboard.latin.define.DebugFlags.init(this) }
        runCatching { helium314.keyboard.latin.utils.FoldableUtils.init(this) }
        runCatching { helium314.keyboard.latin.settings.Settings.init(this) }
        runCatching { helium314.keyboard.latin.utils.SubtypeSettings.init(this) }
        runCatching { helium314.keyboard.latin.RichInputMethodManager.init(this) }
        runCatching { helium314.keyboard.latin.settings.Defaults.initDynamicDefaults(this) }
        runCatching { enableDefaultKeyboardLanguages() }
    }

    /** First-run only: enable the keyboard subtypes listed in
     *  build.json::keyboard_dicts.default_languages (baked CSV) — English,
     *  German, Spanish, Portuguese-BR. HeliBoard otherwise enables only the
     *  system locale. Skipped once the user has any enabled subtype, so we
     *  never clobber their later choices. Tries the exact BCP-47 tag, then
     *  falls back to the language only. */
    private fun enableDefaultKeyboardLanguages() {
        val prefs = heliboardPrefs()
        val key = helium314.keyboard.latin.settings.Settings.PREF_ENABLED_SUBTYPES
        if (!prefs.getString(key, "").isNullOrEmpty()) return  // user already chose
        val langs = BuildConfig.KEYBOARD_DEFAULT_LANGS
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        for (tag in langs) {
            val locale = java.util.Locale.forLanguageTag(tag)
            val subtypes = helium314.keyboard.latin.utils.SubtypeSettings
                .getResourceSubtypesForLocale(locale)
                .ifEmpty {
                    helium314.keyboard.latin.utils.SubtypeSettings
                        .getResourceSubtypesForLocale(java.util.Locale.forLanguageTag(locale.language))
                }
            subtypes.firstOrNull()?.let {
                helium314.keyboard.latin.utils.SubtypeSettings.addEnabledSubtype(prefs, it)
            }
        }
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
