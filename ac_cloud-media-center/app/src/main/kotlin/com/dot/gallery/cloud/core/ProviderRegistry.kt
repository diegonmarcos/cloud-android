/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.core

import com.dot.gallery.cloud.core.capabilities.MapCapableProvider
import com.dot.gallery.cloud.core.capabilities.MemoriesCapableProvider
import com.dot.gallery.cloud.core.capabilities.OcrCapableProvider
import com.dot.gallery.cloud.core.capabilities.PeopleCapableProvider
import com.dot.gallery.cloud.core.capabilities.RemoteMediaProvider
import com.dot.gallery.cloud.core.capabilities.ShareLinkCapableProvider
import com.dot.gallery.cloud.core.capabilities.SmartSearchCapableProvider
import com.dot.gallery.cloud.core.capabilities.SyncCapableProvider
import com.dot.gallery.feature_node.presentation.util.printDebug
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProviderRegistry @Inject constructor() {

    // Keyed by cloud account id (CloudServerConfigEntity.id), NOT by ProviderType, so that
    // several accounts of the same provider type (e.g. two Immich servers) can be registered
    // simultaneously, each as its own isolated instance.
    @PublishedApi
    internal val _providers = mutableMapOf<Long, MediaCapabilityProvider>()

    fun register(configId: Long, provider: MediaCapabilityProvider) {
        _providers[configId] = provider
        printDebug("ProviderRegistry: Registered ${provider.providerType.displayName} " +
                "(account #$configId) with capabilities: ${provider.capabilities}")
    }

    fun unregister(configId: Long) {
        _providers.remove(configId)
    }

    fun getAll(): List<MediaCapabilityProvider> = _providers.values.toList()

    fun getAvailable(): List<MediaCapabilityProvider> =
        _providers.values.filter { it.isAvailable }

    /** Instance for a specific account. Preferred lookup once a config id is known. */
    fun getByConfigId(configId: Long): MediaCapabilityProvider? = _providers[configId]

    /** All registered instances of a given provider type (one per account). */
    fun getAllForType(type: ProviderType): List<MediaCapabilityProvider> =
        _providers.values.filter { it.providerType == type }

    /**
     * Backward-compatible lookup by type: returns the first registered instance of [type].
     * Call sites that must address a specific account should use [getByConfigId] instead.
     */
    fun get(type: ProviderType): MediaCapabilityProvider? =
        _providers.values.firstOrNull { it.providerType == type }

    inline fun <reified T : MediaCapabilityProvider> getByCapability(): List<T> =
        _providers.values.filterIsInstance<T>().filter { it.isAvailable }

    fun hasCapability(cap: ProviderCapability): Boolean =
        _providers.values.any { it.isAvailable && cap in it.capabilities }

    fun hasAnyProvider(): Boolean = _providers.isNotEmpty()

    fun hasConfiguredProvider(): Boolean = _providers.values.any { it.isAvailable }

    fun getRemoteProviders(): List<RemoteMediaProvider> = getByCapability()
    fun getPeopleProviders(): List<PeopleCapableProvider> = getByCapability()
    fun getOcrProviders(): List<OcrCapableProvider> = getByCapability()
    fun getSmartSearchProviders(): List<SmartSearchCapableProvider> = getByCapability()
    fun getMapProviders(): List<MapCapableProvider> = getByCapability()
    fun getShareLinkProviders(): List<ShareLinkCapableProvider> = getByCapability()
    fun getSyncProviders(): List<SyncCapableProvider> = getByCapability()
    fun getMemoriesProviders(): List<MemoriesCapableProvider> = getByCapability()
}
