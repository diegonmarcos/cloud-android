package com.diegonmarcos.cloudnav.maps

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic watchdog that re-spawns [LocationTrackerService] when
 * Android (or Samsung's aggressive "Put apps to sleep" / "Deep sleep"
 * killer) takes the foreground service down — even with Battery
 * Optimization whitelisted.
 *
 * Why this exists: Battery Optimization controls Doze + standard
 * background restrictions. It does NOT cover Samsung's "Background
 * usage limits" (Sleeping apps / Deep-sleeping apps) which kills
 * foreground services independently. It also doesn't survive memory
 * pressure / low-memory killer or app updates. The rehydrate path in
 * LocationTrackerService.onCreate makes a revival smooth, but
 * something has to actually trigger the revival.
 *
 * The Android-canonical answer is a [PeriodicWorkRequest] under
 * WorkManager: WorkManager wraps JobScheduler, which the OS keeps
 * scheduled across process death AND across reboots. The minimum
 * periodic interval is 15 minutes — worst-case detection of a dead
 * tracker is therefore 15 min, which is acceptable given the
 * alternative (service stays dead overnight, hours of GPS data lost).
 *
 * The watchdog only does work when the user actually wants tracking
 * on (MapsTrackerPrefs.enabled = true). On the disabled path it just
 * exits, leaving WorkManager to schedule the next tick at minimal
 * cost.
 */
class TrackerWatchdog(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val prefs = MapsTrackerPrefs(ctx)
        if (!prefs.enabled) {
            // User stopped tracking — nothing to revive. The Result is
            // success so WorkManager keeps the periodic schedule alive
            // for the next time they enable it (cheaper than re-enqueue).
            return Result.success()
        }
        if (!MapsPermissions.canTrackerRun(ctx)) {
            // Permission was revoked. Service couldn't run anyway;
            // skip the start so we don't log spurious stopSelf cycles.
            return Result.success()
        }
        val now = System.currentTimeMillis()
        val lastFixTs = prefs.lastFixTs
        // Threshold = 4x the moving-interval. At default 30s cadence
        // that's 2 min of silence — well past any real GPS hiccup but
        // tight enough that the next 15-min tick catches a true death.
        val intervalMs = MapsTrackingPrefs(ctx).intervalMovingMs.coerceAtLeast(1_000).toLong()
        val staleAfter = intervalMs * 4
        val stale = lastFixTs <= 0L || (now - lastFixTs) > staleAfter
        if (stale) {
            Log.w("TrackerWatchdog", "Tracker enabled but stale (last fix ${now - lastFixTs} ms ago) — restarting service")
            LocationTrackerService.startTracking(ctx)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "tracker-watchdog"

        /** Enqueue the periodic watchdog. Idempotent (KEEP policy) —
         *  re-calling won't disturb the existing schedule. Cadence =
         *  15 min (Android's minimum for periodic work). No network
         *  constraint so the watchdog runs in airplane mode. */
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<TrackerWatchdog>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        /** Cancel the periodic schedule. Called by
         *  LocationTrackerService.stopTracking so a deliberate Stop
         *  doesn't keep the worker firing into a disabled service.
         *  WorkManager handles in-flight workers gracefully — a
         *  currently-running doWork finishes, then no further ticks. */
        fun cancel(ctx: Context) {
            WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
        }
    }
}
