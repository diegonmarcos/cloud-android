package com.diegonmarcos.ide.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager job: check → download → install. Scheduled by Updater.start() at
 * build.json::release.auto_update.interval_hours. Mirrors ea_cloud-superapp's
 * UpdateWorker.
 */
class UpdateWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        if (!com.diegonmarcos.ide.BuildConfig.AUTO_UPDATE_ENABLED) return@withContext Result.success()
        try {
            val update = UpdateChecker(applicationContext).check()
                ?: return@withContext Result.success()
            Log.i("Ide/Update/Worker", "downloaded ${update.assetTitle} (${update.remoteSize} bytes)")
            UpdateInstaller(applicationContext).install(update.downloadedTo)
            Result.success()
        } catch (t: Throwable) {
            Log.w("Ide/Update/Worker", "check failed: ${t.message}", t)
            Result.retry()
        }
    }
}
