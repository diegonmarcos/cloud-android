package com.diegonmarcos.cloudnav.maps

import android.content.Context

/**
 * Persistence for the tracker's on/off state, last-known position
 * hints, and detector telemetry. Separate from [MapsTrackingPrefs]
 * (which holds calibration knobs) and [MapsProviderPrefs] (which
 * holds provider selections) so each concern owns its own
 * SharedPreferences file.
 *
 * Detector telemetry block — written by every [LocationTrackerService]
 * tick — lets the MyTrips dashboard (and future
 * `/api/tracker/state`) surface WHY a Stop has / hasn't emitted
 * without treating the detector as a black box.
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

    /** Currently-active Stop row id (set on MOVING→STOPPED transition,
     *  cleared on STOPPED→MOVING). Persisting it means a service kill
     *  mid-stop can still patch ended_at correctly on revival instead
     *  of forgetting which row to update. -1 = no active stop. */
    var activeStopId: Long
        get() = sp.getLong(K_ACTIVE_STOP_ID, -1L)
        set(v) { sp.edit().putLong(K_ACTIVE_STOP_ID, v).apply() }

    /** Decision taken on the last detect-stop tick. One of:
     *    emit_stop          MOVING→STOPPED, Stop row written
     *    exit_stop          STOPPED→MOVING, ended_at patched
     *    active_stop_held   already in a Stop, centroid still inside
     *    buf_filling        ring buffer hasn't reached bufSize yet
     *    span_too_short     buffer full but spans < dwellMin
     *    dist_too_far       buffer + span ok but p90 > radius_eff
     *    skipped_bad_acc    fix had accuracy > 100m, not buffered
     */
    var lastDecision: String
        get() = sp.getString(K_DECISION, "") ?: ""
        set(v) { sp.edit().putString(K_DECISION, v).apply() }
    var lastDecisionTs: Long
        get() = sp.getLong(K_DECISION_TS, 0L)
        set(v) { sp.edit().putLong(K_DECISION_TS, v).apply() }

    /** Snapshot of the detector's state on the last decision.
     *  bufFill/bufSize  — ring fullness
     *  spanMin           — minutes between buffer.first.ts and last.ts
     *  p90DistM          — 90th-percentile distance from centroid
     *  radiusEffM        — stops_radius_m + avgAcc (the accept gate)
     *  avgAccM           — mean reported accuracy in the buffer
     *  Together they explain every possible decision string above. */
    var lastBufFill: Int
        get() = sp.getInt(K_BUF_FILL, 0)
        set(v) { sp.edit().putInt(K_BUF_FILL, v).apply() }
    var lastBufSize: Int
        get() = sp.getInt(K_BUF_SIZE, 0)
        set(v) { sp.edit().putInt(K_BUF_SIZE, v).apply() }
    var lastSpanMin: Float
        get() = sp.getFloat(K_SPAN_MIN, 0f)
        set(v) { sp.edit().putFloat(K_SPAN_MIN, v).apply() }
    var lastP90DistM: Float
        get() = sp.getFloat(K_P90, 0f)
        set(v) { sp.edit().putFloat(K_P90, v).apply() }
    var lastRadiusEffM: Float
        get() = sp.getFloat(K_RAD_EFF, 0f)
        set(v) { sp.edit().putFloat(K_RAD_EFF, v).apply() }
    var lastAvgAccM: Float
        get() = sp.getFloat(K_AVG_ACC, 0f)
        set(v) { sp.edit().putFloat(K_AVG_ACC, v).apply() }

    companion object {
        private const val K_ENABLED        = "enabled"
        private const val K_LAST_LAT       = "last_lat"
        private const val K_LAST_LON       = "last_lon"
        private const val K_LAST_TS        = "last_ts"
        private const val K_ACTIVE_STOP_ID = "active_stop_id"
        private const val K_DECISION       = "decision"
        private const val K_DECISION_TS    = "decision_ts"
        private const val K_BUF_FILL       = "buf_fill"
        private const val K_BUF_SIZE       = "buf_size"
        private const val K_SPAN_MIN       = "span_min"
        private const val K_P90            = "p90"
        private const val K_RAD_EFF        = "rad_eff"
        private const val K_AVG_ACC        = "avg_acc"
    }
}
