package org.fossify.phone

import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getSharedPrefs
import org.fossify.commons.helpers.BLOCK_UNKNOWN_NUMBERS
import org.fossify.commons.helpers.SIDELOADING_FALSE

/**
 * Cloud Dialer application class (extends commons' FossifyApp).
 *
 * Commons caches its anti-repackage verdict forever in
 * [org.fossify.commons.helpers.BaseConfig.appSideloadingStatus]; devices that ran
 * an earlier build may still hold SIDELOADING_TRUE and would keep showing the
 * "fake version of the app" dialog even after the check's call site is removed.
 * Pin the flag to FALSE on every start — this fork is intentionally repackaged
 * (applicationId com.diegonmarcos.comms.dialer), so the verdict is a false
 * positive by construction.
 */
class App : FossifyApp() {
    override fun onCreate() {
        super.onCreate()
        baseConfig.appSideloadingStatus = SIDELOADING_FALSE

        // First-run screening default = contacts_only, per the owner contract in
        // build.json::forks.dialer.call_screening.default_mode: unknown numbers
        // are silently rejected until the user picks another mode in Settings.
        // Seed-once (key-absence check) so the user's later choice always wins.
        if (!getSharedPrefs().contains(BLOCK_UNKNOWN_NUMBERS)) {
            baseConfig.blockUnknownNumbers = true
        }
    }
}
