package com.diegonmarcos.superapp.updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Public API for the updater module. Call [start] once from the launcher
 * Activity (e.g. MainActivity.onCreate). Everything else is data-driven by
 * build.json::release.auto_update — no caller-side config.
 */
object Updater {
    private const val WORK_NAME = "superapp-auto-update"
    private const val ONE_SHOT_NAME = "superapp-update-now"

    /** Enqueue (or refresh) the periodic update worker. Idempotent. */
    fun start(context: Context) {
        if (!BuildConfig.AUTO_UPDATE_ENABLED) {
            cancel(context)
            return
        }
        val constraints = Constraints.Builder().apply {
            if (BuildConfig.AU_REQUIRE_UNMETERED) {
                setRequiredNetworkType(NetworkType.UNMETERED)
            } else {
                setRequiredNetworkType(NetworkType.CONNECTED)
            }
            if (BuildConfig.AU_REQUIRE_CHARGING) {
                setRequiresCharging(true)
            }
        }.build()

        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            BuildConfig.AUTO_UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
        ).setConstraints(constraints).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * One-shot manual check. The "Check for updates" button calls this so the
     * user can install a new APK immediately instead of waiting for the next
     * periodic tick. REPLACE policy means rapid taps cancel the previous run.
     * Same UpdateWorker class — same flow — same install prompt at the end.
     */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request,
        )
    }
}
