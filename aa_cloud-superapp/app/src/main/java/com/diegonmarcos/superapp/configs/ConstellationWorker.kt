package com.diegonmarcos.superapp.configs

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.updater.Fleet
// AUTO_UPDATE_* knobs are baked into the libs:updater BuildConfig (shared AU
// knobs), NOT the app BuildConfig — reference them explicitly.
import com.diegonmarcos.superapp.updater.BuildConfig as AuConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Periodic constellation-fleet check. Unlike self-update (Updater), this scans
 * EVERY constellation app's GHCR image and, when any have updates available,
 * posts a single tap-to-open notification → Constellation AppStore page.
 * Install stays user-initiated (P1) — background-installing N foreign APKs is
 * intentionally not silent. Data-driven interval from the shared AU knobs.
 */
class ConstellationWorker(appCtx: Context, params: WorkerParameters) :
    CoroutineWorker(appCtx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!AuConfig.AUTO_UPDATE_ENABLED) return@withContext Result.success()
        try {
            val apps = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            val updatable = apps.count { !it.blocked && Fleet.status(applicationContext, it) is Fleet.State.UpdateAvailable }
            Log.i(TAG, "fleet check: $updatable/${apps.size} have updates")
            if (updatable > 0) notifyUpdates(applicationContext, updatable)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "fleet check failed: ${t.message}")
            Result.retry()
        }
    }

    private fun notifyUpdates(ctx: Context, n: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Constellation", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra("open_action", "constellation")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, flags)
        val notif = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("$n constellation update${if (n == 1) "" else "s"} available")
            .setContentText("Tap to open the Constellation AppStore")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        private const val TAG = "Fleet/Worker"
        private const val WORK_NAME = "superapp-constellation-check"
        private const val CHANNEL = "constellation"
        private const val NOTIF_ID = 0xC10E

        /** Schedule the periodic fleet check. Idempotent. Call from App.onCreate. */
        fun start(context: Context) {
            if (!AuConfig.AUTO_UPDATE_ENABLED) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME); return
            }
            val constraints = Constraints.Builder().apply {
                if (AuConfig.AU_REQUIRE_UNMETERED) setRequiredNetworkType(NetworkType.UNMETERED)
                else setRequiredNetworkType(NetworkType.CONNECTED)
                if (AuConfig.AU_REQUIRE_CHARGING) setRequiresCharging(true)
            }.build()
            val request = PeriodicWorkRequestBuilder<ConstellationWorker>(
                AuConfig.AUTO_UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
