package com.diegonmarcos.superapp

import android.app.Application

/**
 * Application entry point — runs BEFORE any Activity. Used solely to wire up
 * the verbose Trace logger and the global CrashLogger so we have a chance to
 * capture failures that happen during MainActivity inflation/onCreate.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Trace.install(this)
        CrashLogger.install(this)
        Trace.i("App", "Application.onCreate done — pid=${android.os.Process.myPid()}")
    }
}
