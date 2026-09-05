package com.diegonmarcos.superapp.appstore

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.updater.Advisory
import com.diegonmarcos.superapp.updater.AutoUpdatePrefs
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
        if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(applicationContext))
            return@withContext Result.success()
        // The metered decision the UNMETERED constraint used to make, made here
        // instead (same place UpdateWorker makes it). Deliberately a different
        // question: WorkManager's constraint asks the system default network,
        // which on a permanently-VPN'd phone reports metered even over Wi-Fi;
        // this asks the ACTIVE network, which inherits NOT_METERED from the
        // VPN's underlying transport. Next pass retries, so this only defers.
        if (AuConfig.AU_REQUIRE_UNMETERED && isMetered(applicationContext)) {
            Log.i(TAG, "auto-update: skipped, metered network and require_unmetered is set")
            return@withContext Result.success()
        }
        try {
            val fleet = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            // Auto-update ON ⇒ fully unattended: install everything the fleet
            // needs, APPS AND LIBS, drawing nothing on screen. Fleet.autoPass
            // owns the mode (Mode.AUTO — updates for apps, updates AND missing
            // for libs, which is why the 36 lib entries were invisible before)
            // and owns the session cap, so this worker and UpdateWorker can no
            // longer disagree about either depending on who won the race.
            // Fleet.status catches its own per-app errors so one bad image
            // can't throw here (the old 'forever looping' bug).
            val pass = Fleet.autoPass(applicationContext, fleet, owner = TAG)
            // SILENT IS NOT QUIET. pass.reason always says what happened and,
            // when nothing happened, which of the three reasons it was:
            // nothing to do / no session slots / no privileged channel. The
            // old line was "acted on 0 app(s)" for all three, which reads
            // identically to the feature being broken.
            Log.i(TAG, "auto-update: ${pass.reason}")
            // The pass outcome is also the primary STRANDED-DEVICE signal, so
            // it is recorded, not only logged. A pass that considered work and
            // acted on none of it with no privileged channel is not a bad night
            // on the network — it is a device that can no longer take the fix
            // that would let it update. Advisory turns that into a banner the
            // user sees on next open and a direct-install button that works
            // without any privileged channel at all.
            Advisory.recordPass(applicationContext, pass)
            // Staleness is independent of the pass: a device whose worker never
            // runs never fails, so it would never raise a failure-based
            // advisory. Our own versionCode is a wall-clock timestamp, so "how
            // old am I" costs nothing beyond the status we just computed.
            Advisory.recordSelfAge(
                applicationContext,
                updateAvailable = fleet.any {
                    it.pkg == applicationContext.packageName &&
                        Fleet.status(applicationContext, it) is Fleet.State.UpdateAvailable
                },
            )
            if (pass.acted > 0)
                notify(applicationContext, NOTIF_ID,
                    "${pass.acted} constellation update${if (pass.acted == 1) "" else "s"} installed",
                    "Tap to open the Constellation AppStore")
            // "Cannot install unattended" has to be visible to the USER, not
            // just to logcat — otherwise a phone with no privileged channel is
            // indistinguishable from a phone with nothing to update.
            if (!pass.silent && pass.considered > 0)
                notify(applicationContext, NOTIF_ID_NO_CHANNEL,
                    "Auto-update needs confirmation",
                    "${pass.considered} update(s) waiting. No privileged install " +
                    "channel, so each one asks first.")
            else
                cancel(applicationContext, NOTIF_ID_NO_CHANNEL)
        } catch (t: Throwable) {
            Log.w(TAG, "fleet auto-update failed: ${t.message}")
        }
        Result.success()
    }

    /** Metered per the ACTIVE network. Unknown network ⇒ not metered, so a
     *  transient null can only defer, never permanently suppress, the pass. */
    private fun isMetered(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun cancel(ctx: Context, id: Int) {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }

    /** One tap-to-open-Constellation notification. [id] separates the "work
     *  happened" one from the "work cannot happen unattended" one so neither
     *  can overwrite the other. */
    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Constellation", NotificationManager.IMPORTANCE_DEFAULT))
        // Deep-link via the launcher's shortcut_action grammar (MainActivity
        // .handleShortcutIntent → onTileClicked → dispatchHomeAction) so the tap
        // opens the Constellation page, not just Home. The old custom extra was
        // read by nothing → fell through to Home.
        // Target and routing come from the HOST: a library cannot name the
        // app's Activity. Null target = a notification with no tap action,
        // which is better than not notifying at all.
        val target = AppStoreHost.launchActivity
        val pi = if (target == null) null else {
            val open = Intent(ctx, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            AppStoreHost.launchExtras.forEach { (k, v) -> open.putExtra(k, v) }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
            PendingIntent.getActivity(ctx, id, open, flags)
        }
        val notif = Notification.Builder(ctx, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(AppStoreHost.notificationIcon)
            .setColor(0xFF0A0A0A.toInt())
            .apply { if (pi != null) setContentIntent(pi) }
            .setAutoCancel(true)
            .build()
        nm.notify(id, notif)
    }

    companion object {
        private const val TAG = "Fleet/Worker"
        private const val WORK_NAME = "superapp-constellation-check"
        private const val CHANNEL = "constellation"
        private const val NOTIF_ID = 0xC10E
        private const val NOTIF_ID_NO_CHANNEL = 0xC10F

        /** Schedule the periodic fleet check. Idempotent. Call from App.onCreate. */
        fun start(context: Context) {
            // Battery-hungry: a periodic GHCR network check. Gated by the
            // "Constellation update check" toggle (Configs → Launcher → Battery
            // Hunger Ones) in addition to the auto-update master switch. Off →
            // cancel the periodic work (manual checks still work).
            // The host owns its settings screen, so it supplies the gate
            // rather than the store reaching into the app's prefs class.
            // Default is "allowed", so a host that sets nothing behaves as
            // before rather than silently never checking.
            if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)
                || !AppStoreHost.periodicCheckAllowed(context)) {
                // Cancel the one-shot too. start() is what the Auto-update
                // toggle calls to reconcile, and a kick already sitting on its
                // 30s delay would otherwise still fire after the user turned
                // auto-update off (doWork re-checks and bails, but leaving
                // queued work behind makes the toggle look like it did nothing).
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME-now")
                return
            }
            // Always CONNECTED (not UNMETERED) — the same fix Updater.start got.
            // An UNMETERED constraint is evaluated by WorkManager against the
            // system's default network, and a device permanently on a VPN
            // routinely has no NET_CAPABILITY_NOT_METERED there, so the work sat
            // ENQUEUED with a constraint that was never going to be met. Run on
            // metered too and decide in doWork, where we can look at the ACTIVE
            // network instead.
            val constraints = Constraints.Builder().apply {
                setRequiredNetworkType(NetworkType.CONNECTED)
                if (AuConfig.AU_REQUIRE_CHARGING) setRequiresCharging(true)
            }.build()
            val request = PeriodicWorkRequestBuilder<ConstellationWorker>(
                AuConfig.AUTO_UPDATE_INTERVAL_HOURS, TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            // UPDATE (not KEEP): KEEP pins the FIRST interval/constraints the
            // device ever saw, so shipping a corrected build.json never reaches
            // an already-installed phone — including the constraint fix above.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request,
            )
            // The periodic worker's first run is a full interval out, so on a
            // fresh install/launch auto-update wouldn't fire for hours ("still
            // not being triggered"). Kick a one-shot ~30s after launch so the
            // fleet is checked promptly, then hourly-ish via the periodic.
            checkNow(context)
        }

        /** One-shot fleet check shortly after launch. Same gating as [start]. */
        fun checkNow(context: Context) {
            if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)) return
            val constraints = Constraints.Builder().apply {
                setRequiredNetworkType(NetworkType.CONNECTED)
                if (AuConfig.AU_REQUIRE_CHARGING) setRequiresCharging(true)
            }.build()
            val req = OneTimeWorkRequestBuilder<ConstellationWorker>()
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            // REPLACE (not KEEP): this kick is what a toggle-ON tap re-arms with.
            // KEEP kept whatever was already sitting there — which, with the old
            // UNMETERED constraint, was a permanently blocked instance — so every
            // tap and every launch was a silent no-op. That is why the toggle
            // looked dead. REPLACE drops the stale one and enqueues this one.
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now", ExistingWorkPolicy.REPLACE, req,
            )
        }
    }
}
