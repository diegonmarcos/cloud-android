package com.diegonmarcos.cloudkeyboard

import android.app.Application
import androidx.work.Configuration as WorkManagerConfiguration
import helium314.keyboard.latin.utils.prefs as heliboardPrefs

/**
 * Application entry point for Cloud Keyboard.
 *
 * HeliBoard (libs:keyboard) ships android:name="helium314.keyboard.latin.App" in its
 * manifest; our manifest wins via tools:replace="android:name". Since HeliBoard's own
 * App.onCreate never fires, we replicate its synchronous keyboard init here so
 * LatinIME / Settings don't crash with "parameter prefs is null".
 */
class App : Application(), WorkManagerConfiguration.Provider {

    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Mirror helium314.keyboard.latin.App.onCreate's synchronous init.
        runCatching { helium314.keyboard.latin.define.DebugFlags.init(this) }
        runCatching { helium314.keyboard.latin.utils.FoldableUtils.init(this) }
        runCatching { helium314.keyboard.latin.settings.Settings.init(this) }
        runCatching { helium314.keyboard.latin.utils.SubtypeSettings.init(this) }
        runCatching { helium314.keyboard.latin.RichInputMethodManager.init(this) }
        runCatching { helium314.keyboard.latin.settings.Defaults.initDynamicDefaults(this) }
        runCatching { helium314.keyboard.latin.utils.upgradeToolbarPrefs(heliboardPrefs()) }
    }
}
