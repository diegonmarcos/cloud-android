package com.diegonmarcos.cloudkeyboard

import android.app.Application
import androidx.work.Configuration as WorkManagerConfiguration
import com.diegonmarcos.superapp.media.MediaRuntime
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
        // Crash takeout — persist uncaught-exception stacks to a local file
        // (the app's OWN external dir: Android/data/com.diegonmarcos.cloudkeyboard/
        // files/crash-<ts>.txt). No READ_LOGS/storage permission needed and it's
        // visible in any file manager, so a crash can be pulled without adb/logcat.
        // Chains to the previous handler so the system crash dialog still shows.
        val prevCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, ex ->
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                java.io.PrintWriter(java.io.File(dir, "crash-${System.currentTimeMillis()}.txt")).use { pw ->
                    pw.println("thread=${t.name}  time=${java.util.Date()}")
                    ex.printStackTrace(pw)
                }
            }
            prevCrashHandler?.uncaughtException(t, ex)
        }

        runCatching { helium314.keyboard.latin.define.DebugFlags.init(this) }
        runCatching { helium314.keyboard.latin.utils.FoldableUtils.init(this) }
        runCatching { helium314.keyboard.latin.settings.Settings.init(this) }
        runCatching { helium314.keyboard.latin.utils.SubtypeSettings.init(this) }
        runCatching { helium314.keyboard.latin.RichInputMethodManager.init(this) }
        runCatching { helium314.keyboard.latin.settings.Defaults.initDynamicDefaults(this) }
        runCatching { helium314.keyboard.latin.utils.upgradeToolbarPrefs(heliboardPrefs()) }
        runCatching { MediaRuntime.configure(BuildConfig.MEDIA_CONFIG_B64, null, BuildConfig.GIPHY_API_KEY) }
    }
}
