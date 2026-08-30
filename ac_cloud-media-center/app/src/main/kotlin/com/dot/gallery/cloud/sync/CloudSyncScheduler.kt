/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.sync

import androidx.work.WorkManager
import com.dot.gallery.cloud.data.dao.CloudServerConfigDao
import com.dot.gallery.feature_node.presentation.util.printDebug
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CloudSyncScheduler @Inject constructor(
    private val workManager: WorkManager,
    private val configDao: CloudServerConfigDao
) {

    suspend fun scheduleIfNeeded() {
        val configs = configDao.getAll().first()
        val syncConfigs = configs.filter { it.isActive && it.syncEnabled }

        if (syncConfigs.isEmpty()) {
            printDebug("CloudSyncScheduler: No sync-enabled configs, canceling workers")
            CloudSyncWorker.cancel(workManager)
            CloudUploadWorker.cancel(workManager)
            return
        }

        val wifiOnly = syncConfigs.all { it.wifiOnly }
        // The upload worker may run on cellular if any account enabled cellular photos/videos
        // (or explicitly disabled wifiOnly); the worker itself then gates per-account/per-type.
        val uploadAllowMetered = syncConfigs.any {
            !it.wifiOnly || it.cellularPhotos || it.cellularVideos
        }
        printDebug("CloudSyncScheduler: Scheduling sync + upload (wifiOnly=$wifiOnly, uploadAllowMetered=$uploadAllowMetered)")
        CloudSyncWorker.schedule(workManager, wifiOnly = wifiOnly)
        CloudUploadWorker.schedule(workManager, wifiOnly = !uploadAllowMetered)
    }

    fun cancel() {
        CloudSyncWorker.cancel(workManager)
        CloudUploadWorker.cancel(workManager)
    }
}
