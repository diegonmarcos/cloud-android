package com.diegonmarcos.ide

import android.os.SystemClock

/**
 * Sticky anchor for "when did this process start", captured once at App.onCreate
 * so the About popup reports a stable process-uptime. Mirrors ea_cloud-superapp's
 * AppProcessUptime. elapsedRealtime ticks through Doze so uptime never jumps back.
 */
object AppProcessUptime {
    var startedAtElapsed: Long = SystemClock.elapsedRealtime()
        private set

    private var initialised = false

    fun initOnce() {
        if (!initialised) {
            startedAtElapsed = SystemClock.elapsedRealtime()
            initialised = true
        }
    }
}
