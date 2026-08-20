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
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.MainActivity
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
        try {
            val apps = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
            // Auto-update ON ⇒ actually INSTALL available updates for the whole
            // fleet (Mode.UPDATES = don't auto-install apps the user never chose).
            // Silent when 'install unknown apps' is granted; otherwise each
            // install posts a tap-to-confirm notification (PackageInstallerReceiver).
            // Fleet.status catches its own per-app errors so one bad image can't
            // throw here (the old 'forever looping' bug); always return success.
            // Capped: this pass runs unattended, so every install it starts
            // leaves a tap-to-install notification holding a PackageInstaller
            // session until the user answers it. Whatever it does not take is
            // picked up by the next pass. build.json::release.auto_update
            // .max_installs_per_pass drives the number.
            val acted = Fleet.installAll(
                applicationContext, apps, Fleet.Mode.UPDATES,
                limit = AuConfig.AU_MAX_PER_PASS,
            )
            Log.i(TAG, "auto-update: acted on $acted app(s)")
            if (acted > 0) notifyUpdates(applicationContext, acted)
        } catch (t: Throwable) {
            Log.w(TAG, "fleet auto-update failed: ${t.message}")
        }
        Result.success()
    }

    private fun notifyUpdates(ctx: Context, n: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Constellation", NotificationManager.IMPORTANCE_DEFAULT))
        // Deep-link via the launcher's shortcut_action grammar (MainActivity
        // .handleShortcutIntent → onTileClicked → dispatchHomeAction) so the tap
        // opens the Constellation page, not just Home. The old custom extra was
        // read by nothing → fell through to Home.
        val open = Intent(ctx, MainActivity::class.java)
            .putExtra("shortcut_action", "action:constellation")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getActivity(ctx, NOTIF_ID, open, flags)
        val notif = Notification.Builder(ctx, CHANNEL)
            .setContentTitle("$n constellation update${if (n == 1) "" else "s"} available")
            .setContentText("Tap to open the Constellation AppStore")
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xFF0A0A0A.toInt())
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
            // Battery-hungry: a periodic GHCR network check. Gated by the
            // "Constellation update check" toggle (Configs → Launcher → Battery
            // Hunger Ones) in addition to the auto-update master switch. Off →
            // cancel the periodic work (manual checks still work).
            if (!AuConfig.AUTO_UPDATE_ENABLED || !AutoUpdatePrefs.enabled(context)
                || !com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(context).toggle("fleet_check")) {
                // Cancel the one-shot too. start() is what the Auto-update
                // toggle calls to reconcile, and a kick already sitting on its
                // 30s delay would otherwise still fire after the user turned
                // auto-update off (doWork re-checks and bails, but leaving
                // queued work behind makes the toggle look like it did nothing).
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork("$WORK_NAME-now")
                return
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
                if (AuConfig.AU_REQUIRE_UNMETERED) setRequiredNetworkType(NetworkType.UNMETERED)
                else setRequiredNetworkType(NetworkType.CONNECTED)
                if (AuConfig.AU_REQUIRE_CHARGING) setRequiresCharging(true)
            }.build()
            val req = OneTimeWorkRequestBuilder<ConstellationWorker>()
                .setConstraints(constraints)
                .setInitialDelay(30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$WORK_NAME-now", ExistingWorkPolicy.KEEP, req,
            )
        }
    }
}
