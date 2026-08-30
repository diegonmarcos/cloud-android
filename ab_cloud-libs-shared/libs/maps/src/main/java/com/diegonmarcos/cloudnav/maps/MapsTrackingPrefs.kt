package com.diegonmarcos.cloudnav.maps

import android.content.Context

/**
 * Tracker calibration prefs — the user-tuneable knobs the foreground
 * GPS service reads each run. First-run defaults come from the
 * build.json-baked BuildConfig fields (`UI_MAPS_INTERVAL_*`,
 * `UI_MAPS_STOPS_*`); user changes via Configs → Maps → Calibration
 * sliders override these.
 *
 * Why these knobs matter:
 *   • `intervalMovingMs` — how often the GPS callback fires while
 *     speed > [movingThresholdMps]. Tighter = more accurate track, more
 *     battery.
 *   • `intervalStoppedMs` — same but while you're at rest. Loosens to
 *     reduce battery cost when nothing's happening.
 *   • `stopsRadiusM` + `stopsDwellMin` — the Stop detector: stay within
 *     stopsRadiusM metres for at least stopsDwellMin minutes = a Stop
 *     event, which triggers ONE reverse-geocode + POI lookup (the
 *     "batching trick"). Bigger radius / shorter dwell = more Stops
 *     detected, more API calls.
 */
class MapsTrackingPrefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("maps_tracking", Context.MODE_PRIVATE)

    var intervalMovingMs: Int
        get() = sp.getInt(K_MOVING_MS, BuildConfig.UI_MAPS_INTERVAL_MOVING_MS)
        set(v) { sp.edit().putInt(K_MOVING_MS, v).apply() }

    var intervalStoppedMs: Int
        get() = sp.getInt(K_STOPPED_MS, BuildConfig.UI_MAPS_INTERVAL_STOPPED_MS)
        set(v) { sp.edit().putInt(K_STOPPED_MS, v).apply() }

    var movingThresholdMps: Float
        get() = sp.getFloat(K_MOVING_MPS, BuildConfig.UI_MAPS_MOVING_THRESHOLD_MPS.toFloat())
        set(v) { sp.edit().putFloat(K_MOVING_MPS, v).apply() }

    var stopsRadiusM: Int
        get() = sp.getInt(K_RADIUS_M, BuildConfig.UI_MAPS_STOPS_RADIUS_M)
        set(v) { sp.edit().putInt(K_RADIUS_M, v).apply() }

    var stopsDwellMin: Int
        get() = sp.getInt(K_DWELL_MIN, BuildConfig.UI_MAPS_STOPS_DWELL_MIN)
        set(v) { sp.edit().putInt(K_DWELL_MIN, v).apply() }

    companion object {
        private const val K_MOVING_MS  = "interval_moving_ms"
        private const val K_STOPPED_MS = "interval_stopped_ms"
        private const val K_MOVING_MPS = "moving_threshold_mps"
        private const val K_RADIUS_M   = "stops_radius_m"
        private const val K_DWELL_MIN  = "stops_dwell_min"
    }
}
