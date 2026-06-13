package com.diegonmarcos.superapp.maps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Foreground service that drives the GPS tracker. Lifecycle:
 *   • startService(Intent) — sets up the persistent notification,
 *     reads tuning prefs, subscribes the FusedLocationProvider
 *     callback. Idempotent: re-entry just refreshes the callback.
 *   • Each fix lands in [onLocationResult]: write to SQLite, update
 *     [MapsTrackerPrefs.lastFix*], drive the stop-detector.
 *   • stopService → tears down the callback + foreground notification.
 *
 * Battery: FusedLocationProvider does the heavy lifting (sensor
 * fusion + Doze-respect). We just feed it a `LocationRequest` built
 * from [MapsTrackingPrefs.intervalMovingMs]. The "tighten interval
 * while stopped" optimisation is handled by the provider itself
 * (Priority + minUpdateInterval) — we don't poll twice.
 *
 * Stop detection: simple ring buffer of the last N fixes; if their
 * centroid stays within `stopsRadiusM` for at least `stopsDwellMin`
 * minutes, emit one Stop row and pause the ring until the user moves
 * out of the radius. Cheap on-device; never blocks the main thread.
 */
class LocationTrackerService : Service() {

    private val TAG = "LocationTrackerService"

    private lateinit var trackerPrefs: MapsTrackerPrefs
    private lateinit var trackingPrefs: MapsTrackingPrefs
    private lateinit var db: MapsDb

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var callback: LocationCallback? = null

    // ── Stop-detector state — see detectStop() ─────────────────────
    private val recentBuf = ArrayDeque<MapsDb.PointRow>()
    private var activeStop: MapsDb.StopRow? = null
    /** Row id of the currently-active Stop in MapsDb. Captured from
     *  insertStop's return value on the MOVING→STOPPED transition so
     *  the matching STOPPED→MOVING patches ended_at on the same row
     *  without re-querying. -1 means no active stop. */
    private var activeStopId: Long = -1L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        trackerPrefs  = MapsTrackerPrefs(this)
        trackingPrefs = MapsTrackingPrefs(this)
        db = MapsDb.get(this)
        ensureChannel()
        rehydrateDetectorState()
    }

    /** Repopulate the in-memory detector state from on-disk DB + prefs
     *  so a service revival (after Doze / OOM / low-memory kill) does
     *  NOT pay the full cold warm-up penalty before the first Stop can
     *  emit. Two restorations:
     *
     *  1) Ring buffer pre-fill — pulls the most recent points from the
     *     DB up to bufSize, filters to fixes within the last 2× dwell
     *     window (older points are from past sessions, not current
     *     dwelling) AND accuracy ≤ 100m (same gate `detectStop` uses on
     *     fresh fixes). Result: if the user was already stationary
     *     when the service died and is still stationary on revival,
     *     the very NEXT fix can clinch the Stop instead of waiting
     *     bufSize × intervalMs for the buffer to refill.
     *
     *  2) Active-stop restore — if `trackerPrefs.activeStopId` points
     *     at a Stop row that still has `ended_at = NULL`, restore it
     *     to the in-memory `activeStop` so the next centroid-exit
     *     correctly patches ended_at on that row instead of leaving
     *     it dangling forever and starting a fresh Stop next door.
     *     If the stored id is stale (row missing, already ended), the
     *     pref is wiped so we don't keep retrying it.
     */
    private fun rehydrateDetectorState() {
        val intervalMs = trackingPrefs.intervalMovingMs.coerceAtLeast(1_000)
        val dwellMs    = trackingPrefs.stopsDwellMin * 60_000L
        val bufSize    = maxOf(3, (dwellMs / intervalMs).toInt() + 1)
        val now        = System.currentTimeMillis()
        val cutoff     = now - dwellMs * 2L
        // recentPoints returns oldest-first; just append in order.
        val historic = db.recentPoints(bufSize * 2)
            .filter { it.ts >= cutoff && (it.accuracy ?: Float.MAX_VALUE) <= 100f }
        val seed = if (historic.size > bufSize) historic.takeLast(bufSize) else historic
        recentBuf.clear()
        for (p in seed) recentBuf.addLast(p)
        Log.i(TAG, "Detector buffer rehydrated: ${recentBuf.size}/$bufSize from DB")

        val savedId = trackerPrefs.activeStopId
        if (savedId > 0) {
            val row = db.getStop(savedId)
            if (row != null && row.endedAt == null) {
                activeStopId = savedId
                activeStop = MapsDb.StopRow(
                    startedAt = row.startedAt,
                    endedAt   = row.endedAt,
                    lat       = row.lat,
                    lon       = row.lon,
                )
                Log.i(TAG, "Active stop restored id=$savedId")
            } else {
                trackerPrefs.activeStopId = -1L
                Log.i(TAG, "Stale active_stop_id=$savedId cleared (row missing or already ended)")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!MapsPermissions.canTrackerRun(this)) {
            Log.w(TAG, "Tracker started without required permissions — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!trackerPrefs.enabled) {
            Log.i(TAG, "Tracker enabled=false — stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("Tracking — waiting for GPS fix…"))
        subscribeUpdates()
        // START_STICKY so Android revives the service after a kill /
        // OOM; onStartCommand re-checks permissions + enabled flag.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        callback?.let { fused.removeLocationUpdates(it) }
        callback = null
    }

    private fun subscribeUpdates() {
        // Drop prior callback (idempotent re-subscribe).
        callback?.let { fused.removeLocationUpdates(it) }
        // setMinUpdateIntervalMillis is the FLOOR (fastest delivery
        // rate), NOT the slow-mode interval. Passing intervalStoppedMs
        // there (5 min) capped the provider to 5-min cadence even
        // while moving — bufSize never filled within the dwell window,
        // Stops never emitted. Correct usage:
        //   - intervalMillis      = desired cadence (30s default)
        //   - minUpdateIntervalMs = same, so provider doesn't deliver
        //     faster than what we want (its default would be base/2)
        // Dynamic moving/stopped switching needs activity-recognition
        // re-subscribing on transitions; tracked separately. For now
        // the tracker runs at intervalMovingMs constantly.
        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            trackingPrefs.intervalMovingMs.toLong(),
        )
            .setMinUpdateIntervalMillis(trackingPrefs.intervalMovingMs.toLong())
            .setMinUpdateDistanceMeters(0f)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (loc in result.locations) ingest(loc)
            }
        }
        callback = cb
        // Manifest-declared permissions exist; runtime checked above
        // in onStartCommand → safe to suppress lint.
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
        }
    }

    /** Persist one GPS fix + update tracker prefs + run stop-detect. */
    private fun ingest(loc: Location) {
        val row = MapsDb.PointRow(
            ts        = loc.time,
            lat       = loc.latitude,
            lon       = loc.longitude,
            accuracy  = if (loc.hasAccuracy()) loc.accuracy else null,
            speed     = if (loc.hasSpeed()) loc.speed else null,
            bearing   = if (loc.hasBearing()) loc.bearing else null,
            altitude  = if (loc.hasAltitude()) loc.altitude else null,
        )
        db.insertPoint(row)
        trackerPrefs.lastFixLat = row.lat
        trackerPrefs.lastFixLon = row.lon
        trackerPrefs.lastFixTs  = row.ts
        detectStop(row)
        // Refresh the persistent notification's subtitle so the user
        // sees the live point count + accuracy as proof of life.
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(
            "Points: ${db.pointCount()} · acc: ${row.accuracy?.toInt() ?: "?"}m"
        ))
    }

    /** Ring-buffer Stop detector. Holds the last [bufSize] points;
     *  if the centroid of those points sits within `stopsRadiusM` and
     *  the buffer spans at least `stopsDwellMin` minutes, emit a Stop
     *  row and remember it as `activeStop`. The Stop's end-time is
     *  patched in when the user leaves the radius.
     *
     *  Telemetry: every tick writes a snapshot of (bufFill, bufSize,
     *  spanMin, p90DistM, radiusEffM, avgAccM) + a decision string to
     *  [MapsTrackerPrefs] so the MyTrips dashboard can show WHY a Stop
     *  has / hasn't emitted instead of treating the detector as a
     *  black box. */
    private fun detectStop(row: MapsDb.PointRow) {
        // Ring-buffer size derived from the user-tuned dwell + interval
        // so the buffer ALWAYS spans at least `stopsDwellMin` minutes of
        // fixes. ceil(dwellMs / intervalMs) + 1 (+1 fix of margin to
        // not race the exact boundary). Floored at 3 so a degenerate
        // <30s dwell still gets a meaningful centroid.
        val intervalMs = trackingPrefs.intervalMovingMs.coerceAtLeast(1_000)
        val dwellMs    = trackingPrefs.stopsDwellMin * 60_000L
        val bufSize    = maxOf(3, (dwellMs / intervalMs).toInt() + 1)

        fun writeTelemetry(decision: String, p90: Double, radEff: Double, span: Double, avgA: Double) {
            trackerPrefs.lastDecision     = decision
            trackerPrefs.lastDecisionTs   = row.ts
            trackerPrefs.lastBufFill      = recentBuf.size
            trackerPrefs.lastBufSize      = bufSize
            trackerPrefs.lastSpanMin      = span.toFloat()
            trackerPrefs.lastP90DistM     = p90.toFloat()
            trackerPrefs.lastRadiusEffM   = radEff.toFloat()
            trackerPrefs.lastAvgAccM      = avgA.toFloat()
        }

        // Skip clearly-garbage fixes (accuracy > 100m or null). They'd
        // dominate the centroid + maxDist math and push every dwell
        // window over the radius even when the user is stationary.
        // The point is still INSERTED in DB (caller already did) so the
        // map shows it; we just don't let it pollute Stop detection.
        if ((row.accuracy ?: Float.MAX_VALUE) > 100f) {
            writeTelemetry("skipped_bad_acc", 0.0, 0.0, 0.0, row.accuracy?.toDouble() ?: 0.0)
            return
        }

        recentBuf.addLast(row)
        while (recentBuf.size > bufSize) recentBuf.removeFirst()

        // Centroid + 90th-percentile distance from centroid.
        // p90 (not max) so a single noisy outlier doesn't blow the
        // check — common under satellite-geometry drift at 5-min
        // windows. radius_eff = stops_radius_m + avgAcc so a tight
        // 100m centroid radius isn't rejected by ±40m reported noise.
        val cLat = recentBuf.map { it.lat }.average()
        val cLon = recentBuf.map { it.lon }.average()
        val dists = recentBuf.map { haversine(it.lat, it.lon, cLat, cLon) }.sorted()
        val p90Idx = ((dists.size - 1) * 0.9).toInt()
        val p90Dist = if (dists.isEmpty()) 0.0 else dists[p90Idx]
        val avgAcc  = recentBuf.mapNotNull { it.accuracy?.toDouble() }
            .let { if (it.isEmpty()) 0.0 else it.average() }
        val spanMin = if (recentBuf.size < 2) 0.0
            else (recentBuf.last().ts - recentBuf.first().ts) / 60_000.0
        val radius  = trackingPrefs.stopsRadiusM.toDouble() + avgAcc
        val dwell   = trackingPrefs.stopsDwellMin.toDouble()

        if (recentBuf.size < bufSize) {
            writeTelemetry("buf_filling", p90Dist, radius, spanMin, avgAcc)
            return
        }

        val inside = p90Dist < radius
        val longEnough = spanMin >= dwell

        if (inside && longEnough && activeStop == null) {
            // Transition: MOVING → STOPPED. Emit one Stop row anchored
            // at the centroid + persist activeStopId so a service kill
            // mid-stop can still find the row to patch ended_at on
            // revival (see rehydrateDetectorState).
            val stop = MapsDb.StopRow(
                startedAt = recentBuf.first().ts,
                endedAt   = null,
                lat       = cLat,
                lon       = cLon,
                accuracy  = recentBuf.mapNotNull { it.accuracy }.average().toFloat(),
            )
            activeStopId = db.insertStop(stop)
            activeStop = stop
            trackerPrefs.activeStopId = activeStopId
            writeTelemetry("emit_stop", p90Dist, radius, spanMin, avgAcc)
            // Fire-and-forget reverse-geocode of the new Stop. The
            // enricher self-throttles (1 req/sec) and bails if a
            // worker is already running, so re-entry from rapid
            // Stop emissions is safe.
            StopsEnricher.kickAsync(applicationContext)
        } else if (!inside && activeStop != null) {
            // Transition: STOPPED → MOVING. Patch ended_at on the
            // active Stop row so downstream readers (Daily longest-
            // dwell algorithm + future dwell-time math) see real
            // bounds instead of treating the stop as still-running
            // forever.
            if (activeStopId > 0) db.updateStopEndedAt(activeStopId, row.ts)
            activeStop = null
            activeStopId = -1L
            trackerPrefs.activeStopId = -1L
            writeTelemetry("exit_stop", p90Dist, radius, spanMin, avgAcc)
        } else if (activeStop != null) {
            writeTelemetry("active_stop_held", p90Dist, radius, spanMin, avgAcc)
        } else if (!inside) {
            writeTelemetry("dist_too_far", p90Dist, radius, spanMin, avgAcc)
        } else {
            writeTelemetry("span_too_short", p90Dist, radius, spanMin, avgAcc)
        }
    }

    /** Great-circle distance in metres (Haversine). Cheap; no
     *  geospatial lib needed for the ~50-200m radii we work with. */
    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2).let { it * it } +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2).let { it * it }
        return r * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    }

    // ── Notification plumbing ──────────────────────────────────────
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_ID,
            "Location tracker",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification while GPS tracking is active."
            setShowBadge(false)
        })
    }

    private fun buildNotification(subtitle: String): Notification {
        val openMain = PendingIntent.getActivity(
            this, 0,
            (packageManager.getLaunchIntentForPackage(packageName) ?: Intent()).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_maps_notif)
            .setContentTitle("Cloud Maps tracker")
            .setContentText(subtitle)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openMain)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setGroup("nc_maps") // own group → not auto-bundled with the other NC notifications
            .build()
            // Persistent in the notification centre: FLAG_NO_CLEAR blocks
            // swipe-to-dismiss + "clear all" while tracking is active.
            .apply { flags = flags or Notification.FLAG_NO_CLEAR or Notification.FLAG_ONGOING_EVENT }
    }

    companion object {
        private const val CHANNEL_ID = "maps_tracker"
        private const val NOTIF_ID   = 0xA1

        /** Convenience starter — toggles [MapsTrackerPrefs.enabled],
         *  fires startForegroundService so the OS doesn't kill the
         *  start call on Android 8+, AND enqueues the periodic
         *  [TrackerWatchdog]. The watchdog is the survivability layer
         *  for Samsung-class aggressive task killers + memory-pressure
         *  kills that bypass Battery Optimization whitelist. */
        fun startTracking(ctx: android.content.Context) {
            MapsTrackerPrefs(ctx).enabled = true
            val intent = Intent(ctx, LocationTrackerService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
            TrackerWatchdog.schedule(ctx)
        }
        /** Convenience stopper — flips enabled=false (so a service
         *  revival by START_STICKY immediately self-stops), explicitly
         *  stops the running instance, and cancels the watchdog so a
         *  deliberate Stop doesn't keep the worker firing every 15
         *  min into a disabled service. */
        fun stopTracking(ctx: android.content.Context) {
            MapsTrackerPrefs(ctx).enabled = false
            ctx.stopService(Intent(ctx, LocationTrackerService::class.java))
            TrackerWatchdog.cancel(ctx)
        }
    }
}
