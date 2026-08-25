package com.diegonmarcos.ide.update

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
 * Public API for the hub updater. Call [start] once from App.onCreate.
 * Everything is data-driven by build.json::release.auto_update — no caller-side
 * config. Mirrors ea_cloud-superapp's Updater facade.
 */
object Updater {
    private const val WORK_NAME = "cloud-ide-auto-update"
    private const val ONE_SHOT_NAME = "cloud-ide-update-now"

    /** Enqueue (or refresh) the periodic update worker. Idempotent. */
    fun start(context: Context) {
        if (!com.diegonmarcos.ide.BuildConfig.AUTO_UPDATE_ENABLED) { cancel(context); return }
        val constraints = Constraints.Builder().apply {
            setRequiredNetworkType(if (com.diegonmarcos.ide.BuildConfig.AU_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED)
            if (com.diegonmarcos.ide.BuildConfig.AU_REQUIRE_CHARGING) setRequiresCharging(true)
        }.build()
        val request = PeriodicWorkRequestBuilder<UpdateWorker>(
            com.diegonmarcos.ide.BuildConfig.AUTO_UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** One-shot manual check — the Configs "Check for updates" button calls this
     *  so the user can install immediately instead of waiting for the next tick. */
    fun checkNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<UpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(ONE_SHOT_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
