/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ThumbnailSize
import com.dot.gallery.cloud.core.capabilities.RemoteMediaProvider
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.data.dao.CloudOfflinePinDao
import com.dot.gallery.cloud.data.entity.CloudMediaEntity
import com.dot.gallery.cloud.image.CloudFetcherRegistryHolder
import com.dot.gallery.cloud.offline.CloudMediaCache
import com.dot.gallery.feature_node.presentation.util.printDebug
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import okhttp3.Request

/**
 * Downloads every cached asset of each "Available offline" account into the pinned cache tier
 * ([CloudMediaCache] pinned dir), so the grid (THUMBNAIL) and viewer (PREVIEW) render with no
 * network. Originals are intentionally NOT pinned in v1 to bound storage/bandwidth.
 *
 * Reports progress as `{done, total, configId}` via [setProgress].
 */
@HiltWorker
class CloudOfflineDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val registry: ProviderRegistry,
    private val pinDao: CloudOfflinePinDao,
    private val cloudMediaDao: CloudMediaDao,
    private val cache: CloudMediaCache
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pins = pinDao.getAllAsync()
        if (pins.isEmpty()) return Result.success()

        val client = CloudFetcherRegistryHolder.okHttpClient ?: return Result.retry()

        // Collect the work set up-front for an accurate total.
        val targets = pins.mapNotNull { pin ->
            val provider = registry.getByConfigId(pin.serverConfigId) as? RemoteMediaProvider ?: return@mapNotNull null
            val assets = cloudMediaDao.getByServerConfig(pin.serverConfigId).first()
            provider to assets
        }
        val total = targets.sumOf { it.second.size } * 2 // thumbnail + preview per asset
        if (total == 0) return Result.success()

        var done = 0
        for ((provider, assets) in targets) {
            val authHeaders = provider.getAuthHeaders()
            for (asset in assets) {
                for (size in SIZES) {
                    val key = cache.keyFor(asset.providerType, asset.serverConfigId, asset.remoteId, size.label)
                    if (!cache.isPinned(key)) {
                        downloadInto(provider, asset, size, key, authHeaders, client)
                    }
                    done++
                }
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
        }
        printDebug("CloudOfflineDownloadWorker: pinned $done/$total variants")
        return Result.success()
    }

    private suspend fun downloadInto(
        provider: RemoteMediaProvider,
        asset: CloudMediaEntity,
        size: Variant,
        key: String,
        authHeaders: Map<String, String>,
        client: okhttp3.OkHttpClient
    ) {
        runCatching {
            val url = provider.getThumbnailUrl(asset.remoteId, size.thumb, asset.fileId)
            // No server preview URL (e.g. a video on a path-based store like WebDAV that has
            // no preview endpoint). Decode a poster frame locally from the original stream and
            // pin that instead of building an invalid HTTP request that throws "no URL scheme".
            if (!url.startsWith("http", ignoreCase = true)) {
                val frame = provider.getVideoThumbnailBytes(asset.remoteId, size.thumb)
                if (frame != null && frame.isNotEmpty()) cache.storePinned(key, frame, "image/jpeg")
                return
            }
            val builder = Request.Builder().url(url)
            authHeaders.forEach { (k, v) -> builder.addHeader(k, v) }
            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body
                    val contentType = responseBody.contentType()?.toString()
                    val bytes = responseBody.bytes()
                    if (bytes.isNotEmpty()) cache.storePinned(key, bytes, contentType)
                }
            }
        }.onFailure { printDebug("CloudOfflineDownloadWorker: failed ${asset.remoteId} ${size.label}: ${it.message}") }
    }

    private data class Variant(val label: String, val thumb: ThumbnailSize)

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val WORK_NAME = "cloud_offline_download"

        private val SIZES = listOf(
            Variant("thumbnail", ThumbnailSize.THUMBNAIL),
            Variant("preview", ThumbnailSize.PREVIEW)
        )

        fun triggerNow(workManager: WorkManager, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<CloudOfflineDownloadWorker>()
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
