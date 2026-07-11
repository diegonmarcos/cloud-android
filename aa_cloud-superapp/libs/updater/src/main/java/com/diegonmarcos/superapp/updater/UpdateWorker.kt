package com.diegonmarcos.superapp.updater

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager job: check → (gate) → download → install. Scheduled by
 * Updater.start(). Runs at build.json::release.auto_update.interval_hours.
 *
 * Metered gate: when build.json::auto_update.require_unmetered_network is true,
 * an *automatic* run on a metered network (mobile data) does NOT silently
 * download — it publishes UpdateAvailable so the overlay asks the user. Auto
 * silent download only happens on unmetered (Wi-Fi). A run started with
 * KEY_FORCE=true (the "Update now" prompt button or the manual "Check for
 * updates" button) is explicit user consent and downloads on any network.
 */
class UpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!BuildConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(applicationContext))
            return@withContext Result.success()
        val force = inputData.getBoolean(KEY_FORCE, false)
        UpdateProgress.beginDownload() // disarm any stale cancel from a prior run
        try {
            val available = UpdateChecker(applicationContext).available()
                ?: return@withContext Result.success()
            // Ask (don't auto-download) on metered unless the user forced it.
            if (!force && BuildConfig.AU_REQUIRE_UNMETERED && isMetered(applicationContext)) {
                Log.i("Updater/Worker", "update available but on metered network — prompting instead of auto-downloading")
                UpdateProgress.update(UpdateProgress.State.UpdateAvailable(available.remoteSize))
                return@withContext Result.success()
            }
            // Poll BOTH: isStopped covers WorkManager cancels of the one-shot;
            // cancelRequested covers a Cancel hit during a PERIODIC run (whose
            // WORK_NAME cancelNow deliberately leaves scheduled).
            val apk = UpdateChecker(applicationContext).download(available) {
                isStopped || UpdateProgress.cancelRequested
            }
            Log.i("Updater/Worker", "downloaded ${available.assetTitle} (${available.remoteSize} bytes)")
            UpdateInstaller(applicationContext).install(apk)
            Result.success()
        } catch (c: java.util.concurrent.CancellationException) {
            // Cancel button: state is already Cancelled — leave it, unwind cleanly.
            Log.i("Updater/Worker", "update cancelled by user")
            Result.success()
        } catch (t: Throwable) {
            Log.w("Updater/Worker", "check failed: ${t.message}", t)
            Result.retry()
        }
    }

    /** True when the active network is metered (mobile data, or Wi-Fi the user
     *  marked metered). No active network → treat as metered (conservative:
     *  don't silently spend the user's data). */
    private fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return true
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        const val KEY_FORCE = "force"
    }
}
