package com.diegonmarcos.superapp.updater

import com.diegonmarcos.superapp.updater.install.UpdateInstaller
import com.diegonmarcos.superapp.updater.source.UpdateChecker
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
        val force = inputData.getBoolean(KEY_FORCE, false)
        // The toggle governs UNATTENDED updates, not the user asking directly.
        // This check used to run BEFORE `force` was read, so turning auto-update
        // off also silently killed "Check for updates": the worker returned
        // success without touching the network and the UI showed nothing.
        if (!force && (!BuildConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(applicationContext)))
            return@withContext Result.success()
        UpdateProgress.beginDownload() // disarm any stale cancel from a prior run
        try {
            // ── The fleet FIRST, then this app.
            // Installing our own APK tears this process (and therefore this
            // worker) down, so anything after the self-update never runs. The
            // fleet pass used to be nowhere at all: Fleet.installAll had a
            // single caller, the manual Update-All button, so "auto-update on"
            // updated the superapp forever and never once touched the other
            // apps. Unattended only — a forced "check for updates" is the user
            // asking about THIS app.
            if (!force) updateFleet()

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

    /**
     * Unattended constellation pass: update the fleet apps that already have a
     * newer release, never install missing ones. MISSING is deliberately out of
     * scope here — a first install cannot be silent (we are not yet the
     * installer of record for that package, so the OS shows its dialog no
     * matter what we ask for), and an unattended job must not throw dialogs at
     * someone who is not looking at the phone. Missing apps stay a job for the
     * Update-All button, which runs with the user watching.
     *
     * Failures are per-app inside [Fleet.installAll]; a fleet problem must not
     * cost this app its own update, which is the whole reason for the catch.
     */
    private fun updateFleet() {
        val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        if (fleet.isEmpty()) return
        runCatching {
            // CAP IT. This pass is unattended, and installAll's own contract
            // says so: every install it starts can leave a tap-to-confirm
            // notification holding a PackageInstaller session until the user
            // answers, and Android refuses new sessions past 50 per UID. This
            // call passed no limit at all, so it defaulted to Int.MAX_VALUE —
            // a whole 50-entry fleet from one unattended wake-up.
            //
            // It also made the fleet pass non-deterministic once the batch
            // became single-flight: ConstellationWorker runs the SAME pass
            // capped at AU_MAX_PER_PASS, so whichever worker won the race
            // decided whether 3 apps or all of them installed. Same cap, same
            // behaviour, whoever gets there first.
            val n = Fleet.installAll(
                applicationContext, fleet, Fleet.Mode.UPDATES,
                limit = BuildConfig.AU_MAX_PER_PASS,
            )
            if (n > 0) Log.i("Updater/Worker", "fleet auto-update: acted on $n app(s)")
        }.onFailure { Log.w("Updater/Worker", "fleet auto-update failed: ${it.message}", it) }
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
