package com.diegonmarcos.ide

import android.app.Application
import com.diegonmarcos.ide.update.Updater

/** Hub application object. Kept minimal — the broker surfaces (provider +
 *  service) are component-scoped, not tied to a long-lived Application. Starts
 *  the periodic in-app updater (idempotent; data-driven from
 *  build.json::release.auto_update). */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Updater.start(this)
    }
}
