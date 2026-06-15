package com.diegonmarcos.superapp.system
import com.diegonmarcos.superapp.App

import android.os.SystemClock

/**
 * Sticky anchor for "when did this process start". Captured exactly once
 * at App.onCreate so the About / Battery & Usage section can report a
 * stable process-uptime without paying for it on every read.
 *
 * SystemClock.elapsedRealtime is the right base — it ticks even while
 * the device is in deep sleep, so the uptime survives doze without
 * jumping backward.
 */
object AppProcessUptime {
    var startedAtElapsed: Long = SystemClock.elapsedRealtime()
        private set

    /** Called once from App.onCreate. Subsequent calls are no-ops. */
    fun initOnce() {
        if (!initialised) {
            startedAtElapsed = SystemClock.elapsedRealtime()
            initialised = true
        }
    }

    private var initialised = false
}
