package com.diegonmarcos.superapp

import android.content.Context

/**
 * Persistence for the tracker's on/off state and last-known position
 * hints. Separate from [MapsTrackingPrefs] (which holds calibration
 * knobs) and [MapsProviderPrefs] (which holds provider selections) so
 * each concern owns its own SharedPreferences file.
 */
class MapsTrackerPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_tracker_state", Context.MODE_PRIVATE)

    /** Master switch — does the user want the tracker running? The
     *  foreground service checks this on startCommand and refuses to
     *  start (or stops itself) when false. */
    var enabled: Boolean
        get() = sp.getBoolean(K_ENABLED, false)
        set(v) { sp.edit().putBoolean(K_ENABLED, v).apply() }

    /** Last successful GPS fix — surfaces in the control fragment so
     *  the user can sanity-check "is the tracker actually getting
     *  points?" without scrolling to the timeline. Lat/lon stored as
     *  doubles encoded via raw-bits to skirt SharedPreferences's
     *  lack of native double support. */
    var lastFixLat: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(K_LAST_LAT, 0L))
        set(v) { sp.edit().putLong(K_LAST_LAT, java.lang.Double.doubleToRawLongBits(v)).apply() }
    var lastFixLon: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(K_LAST_LON, 0L))
        set(v) { sp.edit().putLong(K_LAST_LON, java.lang.Double.doubleToRawLongBits(v)).apply() }
    var lastFixTs: Long
        get() = sp.getLong(K_LAST_TS, 0L)
        set(v) { sp.edit().putLong(K_LAST_TS, v).apply() }

    fun hasLastFix(): Boolean = lastFixTs > 0L

    companion object {
        private const val K_ENABLED = "enabled"
        private const val K_LAST_LAT = "last_lat"
        private const val K_LAST_LON = "last_lon"
        private const val K_LAST_TS  = "last_ts"
    }
}
