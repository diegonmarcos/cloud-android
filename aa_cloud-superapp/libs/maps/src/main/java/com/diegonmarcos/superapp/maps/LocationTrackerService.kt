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
        val req = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            trackingPrefs.intervalMovingMs.toLong(),
        )
            .setMinUpdateIntervalMillis(trackingPrefs.intervalStoppedMs.toLong())
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
     *  patched in when the user leaves the radius. */
    private fun detectStop(row: MapsDb.PointRow) {
        // Ring-buffer size derived from the user-tuned dwell + interval
        // so the buffer ALWAYS spans at least `stopsDwellMin` minutes of
        // fixes. Hardcoded 8 was wrong: at the default 30s cadence × 8
        // = 4 min window, the 5-min dwell threshold (also default) was
        // mathematically never satisfied → Stops never emitted even
        // though the user was clearly dwelling (Points: 20, Stops: 0).
        // New formula: ceil(dwellMs / intervalMs) + 1 (+1 fix of margin
        // to not race the exact boundary). Floored at 3 so a degenerate
        // <30s dwell still gets a meaningful centroid.
        val intervalMs = trackingPrefs.intervalMovingMs.coerceAtLeast(1_000)
        val dwellMs    = trackingPrefs.stopsDwellMin * 60_000L
        val bufSize    = maxOf(3, (dwellMs / intervalMs).toInt() + 1)
        recentBuf.addLast(row)
        while (recentBuf.size > bufSize) recentBuf.removeFirst()
        if (recentBuf.size < bufSize) return

        // Centroid + max distance from centroid.
        val cLat = recentBuf.map { it.lat }.average()
        val cLon = recentBuf.map { it.lon }.average()
        val maxDist = recentBuf.maxOfOrNull { haversine(it.lat, it.lon, cLat, cLon) } ?: 0.0
        val spanMin = (recentBuf.last().ts - recentBuf.first().ts) / 60_000.0
        val radius = trackingPrefs.stopsRadiusM.toDouble()
        val dwell  = trackingPrefs.stopsDwellMin.toDouble()

        if (maxDist < radius && spanMin >= dwell && activeStop == null) {
            // Transition: MOVING → STOPPED. Emit one Stop row anchored
            // at the centroid.
            val stop = MapsDb.StopRow(
                startedAt = recentBuf.first().ts,
                endedAt   = null,
                lat       = cLat,
                lon       = cLon,
                accuracy  = recentBuf.mapNotNull { it.accuracy }.average().toFloat(),
            )
            activeStopId = db.insertStop(stop)
            activeStop = stop
            // Fire-and-forget reverse-geocode of the new Stop. The
            // enricher self-throttles (1 req/sec) and bails if a
            // worker is already running, so re-entry from rapid
            // Stop emissions is safe.
            StopsEnricher.kickAsync(applicationContext)
        } else if (maxDist >= radius && activeStop != null) {
            // Transition: STOPPED → MOVING. Patch ended_at on the
            // active Stop row so downstream readers (Daily longest-
            // dwell algorithm + future dwell-time math) see real
            // bounds instead of treating the stop as still-running
            // forever.
            if (activeStopId > 0) db.updateStopEndedAt(activeStopId, row.ts)
            activeStop = null
            activeStopId = -1L
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
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "maps_tracker"
        private const val NOTIF_ID   = 0xA1

        /** Convenience starter — toggles [MapsTrackerPrefs.enabled]
         *  and fires startForegroundService so the OS doesn't kill
         *  the start call on Android 8+. */
        fun startTracking(ctx: android.content.Context) {
            MapsTrackerPrefs(ctx).enabled = true
            val intent = Intent(ctx, LocationTrackerService::class.java)
            ContextCompat.startForegroundService(ctx, intent)
        }
        /** Convenience stopper — flips enabled=false (so a service
         *  revival by START_STICKY immediately self-stops) and
         *  explicitly stops the running instance. */
        fun stopTracking(ctx: android.content.Context) {
            MapsTrackerPrefs(ctx).enabled = false
            ctx.stopService(Intent(ctx, LocationTrackerService::class.java))
        }
    }
}
