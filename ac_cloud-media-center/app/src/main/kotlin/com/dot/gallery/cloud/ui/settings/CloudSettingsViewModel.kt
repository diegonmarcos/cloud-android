/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bumptech.glide.Glide
import com.dot.gallery.cloud.core.CloudRuntimeSettings
import com.dot.gallery.cloud.data.dao.CloudServerConfigDao
import com.dot.gallery.cloud.data.entity.CloudServerConfigEntity
import com.dot.gallery.cloud.di.CloudProviderInitializer
import com.dot.gallery.cloud.network.ServerUrlResolver
import com.dot.gallery.cloud.offline.CloudMediaCache
import com.github.panpf.sketch.sketch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class CloudSettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val configDao: CloudServerConfigDao,
    private val urlResolver: ServerUrlResolver,
    private val providerInitializer: CloudProviderInitializer,
    private val cloudMediaCache: CloudMediaCache
) : ViewModel() {

    private val _cacheClearing = MutableStateFlow(false)
    val cacheClearing: StateFlow<Boolean> = _cacheClearing.asStateFlow()

    private val _config = MutableStateFlow<CloudServerConfigEntity?>(null)
    val config: StateFlow<CloudServerConfigEntity?> = _config.asStateFlow()

    init {
        loadActiveConfig()
    }

    private fun loadActiveConfig() {
        viewModelScope.launch {
            val configs = configDao.getAll().first()
            val active = configs.firstOrNull { it.isActive }
            _config.value = active
            CloudRuntimeSettings.apply(active?.toCloudServerConfig())
        }
    }

    /** The server URL currently in effect for the active config given the current network. */
    fun effectiveUrl(): String? =
        _config.value?.toCloudServerConfig()?.let { urlResolver.effectiveUrl(it) }

    /** Whether the active config's local URL is currently active (on the configured local network). */
    fun isLocalActive(): Boolean {
        val cfg = _config.value?.toCloudServerConfig() ?: return false
        return cfg.autoUrlSwitch && cfg.localServerUrl.isNotBlank() &&
                urlResolver.isOnConfiguredLocalNetwork(cfg)
    }

    fun updateConfig(transform: CloudServerConfigEntity.() -> CloudServerConfigEntity) {
        val current = _config.value ?: return
        val updated = current.transform()
        _config.value = updated
        CloudRuntimeSettings.apply(updated.toCloudServerConfig())
        viewModelScope.launch {
            configDao.update(updated)
            // Re-apply the effective URL to the live provider so toggling automatic URL
            // switching (or editing the local URL/SSID) updates the connection immediately.
            // Fire-and-forget on the initializer's app-lifetime scope: the re-authentication
            // must NOT be cancelled if the user leaves this screen (which clears viewModelScope),
            // otherwise the provider is left half-switched. No-op when the URL is unchanged.
            providerInitializer.reconfigureAccountAsync(updated.id)
        }
    }

    /**
     * Clears all cloud image caches: the two-tier offline cache (auto tier only — pinned
     * offline content is preserved), the shared Sketch memory/disk caches, Glide's disk cache,
     * and the zoom-original scratch dir. Returns to idle when done so the UI can re-enable the row.
     */
    fun clearImageCache() {
        if (_cacheClearing.value) return
        _cacheClearing.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { cloudMediaCache.clearAuto() }
                runCatching { context.sketch.memoryCache.clear() }
                runCatching { context.sketch.resultCache.clear() }
                runCatching { context.sketch.downloadCache.clear() }
                runCatching { Glide.get(context).clearDiskCache() }
                runCatching { File(context.cacheDir, "cloud_zoom_originals").deleteRecursively() }
                runCatching { File(context.cacheDir, "cloud_http_cache").deleteRecursively() }
            }
            runCatching { Glide.get(context).clearMemory() }
            _cacheClearing.value = false
        }
    }
}
