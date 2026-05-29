package com.diegonmarcos.superapp

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Application entry point — runs BEFORE any Activity. Wires:
 *  • Trace + CrashLogger (capture inflation/onCreate failures)
 *  • Material 3 DynamicColors — on Android 12+ the theme adapts to the
 *    system wallpaper palette. Older devices fall back to the brand
 *    palette declared in colors.xml + themes.xml.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Trace.install(this)
        CrashLogger.install(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
        Trace.i("App", "Application.onCreate done — pid=${android.os.Process.myPid()}")
    }
}
