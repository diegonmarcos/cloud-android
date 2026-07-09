package com.diegonmarcos.superapp.updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
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

    /** Enqueue (or refresh) the periodic update worker. Idempotent. Respects
     *  the runtime Auto-update toggle (AutoUpdatePrefs.enabled) — OFF cancels. */
    fun start(context: Context) {
        if (!BuildConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)) {
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
     * Abort an in-flight update the user asked to cancel (the overlay's Cancel
     * button). Cancels the one-shot self-check, any companion installs, and the
     * fleet check, then flips the progress state to Cancelled so the overlay
     * dismisses. WorkManager cancellation makes the worker coroutine inactive;
     * the download loop bails on the next isStopped check.
     */
    fun cancelNow(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(ONE_SHOT_NAME)
        wm.cancelAllWorkByTag("companion-install") // no-op if none tagged
        wm.cancelUniqueWork("superapp-constellation-now")
        UpdateProgress.update(UpdateProgress.State.Cancelled)
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

    /**
     * Download a companion APK from a direct [apkUrl] (e.g. a GitHub release
     * asset) and install it for [packageName] via PackageInstaller. Used by
     * the launcher when a tile points at a companion app (Cloud-Comms /
     * Cloud-IDE hub) that isn't installed yet. URL + package come from
     * build.json::ui.external_apps — never hardcoded here. Keyed per package
     * so taps on different companion tiles don't clobber each other; KEEP
     * means a re-tap while a download is in flight is a no-op.
     */
    fun installApk(context: Context, apkUrl: String, packageName: String, label: String) {
        val data = Data.Builder()
            .putString(ApkInstallWorker.KEY_URL, apkUrl)
            .putString(ApkInstallWorker.KEY_PKG, packageName)
            .putString(ApkInstallWorker.KEY_LABEL, label)
            .build()
        val request = OneTimeWorkRequestBuilder<ApkInstallWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "companion-install-$packageName", ExistingWorkPolicy.KEEP, request,
        )
    }
}
