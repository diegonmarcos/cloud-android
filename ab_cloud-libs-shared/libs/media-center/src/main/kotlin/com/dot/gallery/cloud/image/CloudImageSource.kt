/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.image

import android.content.Context
import com.dot.gallery.cloud.core.CloudTrace
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.resolveRemote
import com.dot.gallery.cloud.offline.CloudMediaCache
import com.github.panpf.zoomimage.subsampling.ImageSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Source
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/**
 * [ImageSource] for cloud media that serves the **original** image to ZoomImage's subsampling
 * pipeline from a locally-cached file.
 *
 * Why file-backed instead of streaming from the network: ZoomImage (1.6.0-alpha01) sniffs the
 * image header by calling `RegionDecoder.Factory.accept()` -> `openSource()` on the MAIN thread
 * (only `getImageInfo()`/`prepare()`/region decoding run on the IO dispatcher). A network read
 * there throws `NetworkOnMainThreadException`, and doing the download synchronously would block
 * the UI. So the original is downloaded once, off the main thread, via [create]; every
 * [openSource] call is then a cheap local file read that is safe on any thread and gives
 * `BitmapRegionDecoder` the seekable source it needs for tiling.
 */
class CloudImageSource private constructor(
    private val providerType: ProviderType,
    private val remoteId: String,
    private val configId: Long,
    private val localFile: File,
) : ImageSource {

    override val key: String = keyOf(providerType, remoteId, configId)

    override fun openSource(): Source = localFile.source()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as CloudImageSource
        return providerType == other.providerType && remoteId == other.remoteId &&
            configId == other.configId
    }

    override fun hashCode(): Int {
        var result = providerType.hashCode()
        result = 31 * result + remoteId.hashCode()
        result = 31 * result + configId.hashCode()
        return result
    }

    override fun toString(): String = "CloudImageSource(cloud://${providerType.name}/$remoteId)"

    companion object {
        private fun keyOf(providerType: ProviderType, remoteId: String, configId: Long): String =
            "cloud://${providerType.name}/$remoteId?size=original" +
                (if (configId > 0L) "&cfg=$configId" else "")

        /**
         * Ensure the original is cached locally (downloading it off the main thread if needed) and
         * return a file-backed source. Call from a coroutine before handing the source to ZoomImage.
         */
        suspend fun create(
            context: Context,
            providerType: ProviderType,
            remoteId: String,
            configId: Long = -1L,
        ): CloudImageSource = withContext(Dispatchers.IO) {
            val file = cacheFileFor(context, providerType, remoteId, configId)
            if (!file.exists() || file.length() == 0L) {
                downloadOriginal(providerType, remoteId, configId, file)
            }
            CloudImageSource(providerType, remoteId, configId, file)
        }

        private fun cacheFileFor(
            context: Context,
            providerType: ProviderType,
            remoteId: String,
            configId: Long,
        ): File {
            val dir = File(context.cacheDir, "cloud_zoom_originals").apply { mkdirs() }
            val ext = remoteId.substringAfterLast('.', "").takeIf { it.length in 1..5 } ?: "img"
            return File(dir, "${keyOf(providerType, remoteId, configId).hashCode()}.$ext")
        }

        private fun downloadOriginal(
            providerType: ProviderType,
            remoteId: String,
            configId: Long,
            target: File,
        ) {
            val registry = CloudFetcherRegistryHolder.registry
                ?: throw IllegalStateException("ProviderRegistry not available")
            val provider = registry.resolveRemote(providerType, configId)
                ?: throw IllegalStateException("No remote provider for $providerType")

            val url = provider.getOriginalUrl(remoteId)
            if (url.isBlank()) throw IllegalStateException("No original URL for $remoteId")

            val requestBuilder = Request.Builder().url(url).get()
            provider.getAuthHeaders().forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            requestBuilder.addHeader(
                CloudMediaCache.HEADER_KEY,
                CloudMediaCache.keyFor(providerType, configId, remoteId, "original")
            )

            val client = CloudFetcherRegistryHolder.okHttpClient ?: OkHttpClient()
            CloudTrace.d("ZoomSource[$providerType] original '$remoteId' -> GET $url")
            val response = CloudTrace.time("ZoomSource[$providerType] original '$remoteId' download") {
                client.newCall(requestBuilder.build()).execute()
            }
            response.use {
                if (!it.isSuccessful) {
                    CloudTrace.w("ZoomSource[$providerType] original '$remoteId' -> HTTP ${it.code}")
                    throw Exception("HTTP ${it.code}: ${it.message}")
                }
                val body = it.body ?: throw Exception("Empty response body")
                // Stream to a temp file then rename, so a partial/failed download never leaves a
                // truncated file that a later open would treat as complete.
                val tmp = File(target.path + ".tmp")
                body.source().use { src ->
                    tmp.sink().buffer().use { sink -> sink.writeAll(src) }
                }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }
        }
    }
}
