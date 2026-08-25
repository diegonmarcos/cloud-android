/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.cloud.core.CloudAlbum
import com.dot.gallery.cloud.core.CloudServerConfig
import com.dot.gallery.cloud.core.CloudStorageInfo
import com.dot.gallery.cloud.core.ConnectionState
import com.dot.gallery.cloud.core.CredentialEncryptor
import com.dot.gallery.cloud.core.Disconnectable
import com.dot.gallery.cloud.core.ProviderCapability
import com.dot.gallery.cloud.core.ProviderRegistry
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.capabilities.RemoteMediaProvider
import com.dot.gallery.cloud.data.dao.CloudAlbumSyncDao
import com.dot.gallery.cloud.data.dao.CloudMediaDao
import com.dot.gallery.cloud.data.dao.CloudServerConfigDao
import com.dot.gallery.cloud.data.dao.CloudUploadPrefDao
import com.dot.gallery.cloud.data.entity.CloudAlbumSyncEntity
import com.dot.gallery.cloud.data.entity.CloudServerConfigEntity
import com.dot.gallery.cloud.data.entity.CloudUploadPrefEntity
import com.dot.gallery.cloud.data.repository.CloudRepository
import com.dot.gallery.cloud.di.CloudProviderInitializer
import com.dot.gallery.cloud.network.ServerUrlResolver
import com.dot.gallery.core.Resource
import com.dot.gallery.feature_node.domain.model.Album
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import com.dot.gallery.feature_node.domain.util.MediaOrder
import com.dot.gallery.feature_node.domain.util.OrderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CloudAccountUiState(
    val configs: List<CloudServerConfigEntity> = emptyList(),
    val connectionStates: Map<Long, ConnectionState> = emptyMap(),
    val assetCounts: Map<Long, Int> = emptyMap()
)

data class AddServerUiState(
    val providerType: ProviderType = ProviderType.IMMICH,
    val serverUrl: String = "",
    val apiKey: String = "",
    val username: String = "",
    val password: String = "",
    val displayName: String = "",
    val syncEnabled: Boolean = false,
    val wifiOnly: Boolean = true,
    // Networking (auto local/external URL switching)
    val autoUrlSwitch: Boolean = false,
    val localWifiSsid: String = "",
    val localServerUrl: String = "",
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val testSuccess: Boolean = false,
    val isSaving: Boolean = false,
    val savedConfigId: Long? = null,
    val error: String? = null,
    // Final sync stage: which local folders to back up and which remote albums to pull.
    val selectedLocalAlbumIds: Set<Long> = emptySet(),
    val remoteAlbums: List<CloudAlbum> = emptyList(),
    val selectedRemoteAlbumIds: Set<String> = emptySet(),
    val isLoadingRemoteAlbums: Boolean = false,
    val remoteAlbumsLoaded: Boolean = false,
    val remoteAlbumsError: String? = null
)

@HiltViewModel
class CloudAccountsViewModel @Inject constructor(
    private val configDao: CloudServerConfigDao,
    private val cloudMediaDao: CloudMediaDao,
    private val registry: ProviderRegistry,
    private val providerInitializer: CloudProviderInitializer,
    private val credentialEncryptor: CredentialEncryptor,
    private val urlResolver: ServerUrlResolver,
    private val uploadPrefDao: CloudUploadPrefDao,
    private val albumSyncDao: CloudAlbumSyncDao,
    private val mediaRepository: MediaRepository,
    private val cloudRepository: CloudRepository
) : ViewModel() {

    val accountState: StateFlow<List<CloudServerConfigEntity>> = configDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** On-device albums (folders) offered as local backup sources, excluding cloud albums. */
    private val _localAlbums = MutableStateFlow<List<Album>>(emptyList())
    val localAlbums: StateFlow<List<Album>> = _localAlbums.asStateFlow()

    private fun loadLocalAlbums() {
        viewModelScope.launch {
            mediaRepository.getAlbums(MediaOrder.Label(OrderType.Ascending)).collect { resource ->
                _localAlbums.value = (resource.data ?: emptyList()).filter { it.uri.scheme != "cloud" }
            }
        }
    }

    private val _addServerState = MutableStateFlow(AddServerUiState())
    val addServerState: StateFlow<AddServerUiState> = _addServerState.asStateFlow()

    /** Capabilities advertised by the registered provider for [providerType], if any. */
    fun capabilitiesOf(providerType: ProviderType): Set<ProviderCapability> =
        registry.get(providerType)?.capabilities ?: emptySet()

    fun initAddServer(providerType: ProviderType) {
        _addServerState.value = AddServerUiState(providerType = providerType)
        loadLocalAlbums()
    }

    fun toggleLocalAlbum(albumId: Long) {
        val current = _addServerState.value.selectedLocalAlbumIds
        _addServerState.value = _addServerState.value.copy(
            selectedLocalAlbumIds = if (albumId in current) current - albumId else current + albumId
        )
    }

    fun toggleRemoteAlbum(remoteId: String) {
        val current = _addServerState.value.selectedRemoteAlbumIds
        _addServerState.value = _addServerState.value.copy(
            selectedRemoteAlbumIds = if (remoteId in current) current - remoteId else current + remoteId
        )
    }

    /**
     * Fetches the provider's remote albums using the credentials entered so far so the user can
     * pick which ones to pull down. Configures + authenticates the provider on the fly (the
     * config is not persisted until [saveServer]). All albums are selected by default.
     */
    fun loadRemoteAlbums(force: Boolean = false) {
        val state = _addServerState.value
        if (state.isLoadingRemoteAlbums) return
        if (state.remoteAlbumsLoaded && !force) return
        _addServerState.value = state.copy(isLoadingRemoteAlbums = true, remoteAlbumsError = null)
        viewModelScope.launch {
            try {
                val provider = providerInitializer.createTransientProvider(state.providerType) as? RemoteMediaProvider
                if (provider == null) {
                    _addServerState.value = _addServerState.value.copy(
                        isLoadingRemoteAlbums = false,
                        remoteAlbumsLoaded = true,
                        remoteAlbumsError = "Provider not available"
                    )
                    return@launch
                }
                val config = urlResolver.resolve(buildConfig(state))
                provider.configure(config)
                provider.authenticate(config)
                var albums: List<CloudAlbum> = emptyList()
                provider.getRemoteAlbums().collect { resource ->
                    if (resource is Resource.Success) albums = resource.data ?: emptyList()
                    else if (resource is Resource.Error) {
                        _addServerState.value = _addServerState.value.copy(
                            remoteAlbumsError = resource.message
                        )
                    }
                }
                _addServerState.value = _addServerState.value.copy(
                    isLoadingRemoteAlbums = false,
                    remoteAlbumsLoaded = true,
                    remoteAlbums = albums,
                    // Default: pull everything unless the user deselects.
                    selectedRemoteAlbumIds = albums.map { it.remoteId }.toSet()
                )
            } catch (e: Exception) {
                _addServerState.value = _addServerState.value.copy(
                    isLoadingRemoteAlbums = false,
                    remoteAlbumsLoaded = true,
                    remoteAlbumsError = e.message ?: "Failed to load albums"
                )
            }
        }
    }

    fun initEditServer(configId: Long) {
        viewModelScope.launch {
            configDao.getById(configId)?.let { entity ->
                _addServerState.value = AddServerUiState(
                    providerType = entity.providerType,
                    serverUrl = entity.serverUrl,
                    apiKey = entity.apiKey?.let { credentialEncryptor.decrypt(it) } ?: "",
                    username = entity.username ?: "",
                    password = entity.encryptedPassword?.let { credentialEncryptor.decrypt(it) } ?: "",
                    displayName = entity.displayName,
                    syncEnabled = entity.syncEnabled,
                    wifiOnly = entity.wifiOnly,
                    autoUrlSwitch = entity.autoUrlSwitch,
                    localWifiSsid = entity.localWifiSsid,
                    localServerUrl = entity.localServerUrl,
                    savedConfigId = entity.id
                )
            }
        }
    }

    fun updateServerUrl(url: String) {
        _addServerState.value = _addServerState.value.copy(serverUrl = url, testResult = null)
    }

    fun updateApiKey(key: String) {
        _addServerState.value = _addServerState.value.copy(apiKey = key, testResult = null)
    }

    fun updateUsername(username: String) {
        _addServerState.value = _addServerState.value.copy(username = username, testResult = null)
    }

    fun updatePassword(password: String) {
        _addServerState.value = _addServerState.value.copy(password = password, testResult = null)
    }

    fun updateDisplayName(name: String) {
        _addServerState.value = _addServerState.value.copy(displayName = name)
    }

    fun updateSyncEnabled(enabled: Boolean) {
        _addServerState.value = _addServerState.value.copy(syncEnabled = enabled)
    }

    fun updateWifiOnly(wifiOnly: Boolean) {
        _addServerState.value = _addServerState.value.copy(wifiOnly = wifiOnly)
    }

    fun updateAutoUrlSwitch(enabled: Boolean) {
        _addServerState.value = _addServerState.value.copy(autoUrlSwitch = enabled)
    }

    fun updateLocalWifiSsid(ssid: String) {
        _addServerState.value = _addServerState.value.copy(localWifiSsid = ssid)
    }

    fun updateLocalServerUrl(url: String) {
        _addServerState.value = _addServerState.value.copy(localServerUrl = url)
    }

    fun testConnection() {
        val state = _addServerState.value
        if (state.serverUrl.isBlank()) {
            _addServerState.value = state.copy(testResult = "Server URL is required", testSuccess = false)
            return
        }
        _addServerState.value = state.copy(isTesting = true, testResult = null)
        viewModelScope.launch {
            try {
                val config = buildConfig(state)
                val provider = providerInitializer.createTransientProvider(state.providerType) as? RemoteMediaProvider
                if (provider != null) {
                    // Ensure provider is configured with the test URL before testing
                    provider.configure(config)
                    val result = provider.testConnection(config)
                    result.fold(
                        onSuccess = { info ->
                            _addServerState.value = _addServerState.value.copy(
                                isTesting = false,
                                testResult = info.serverName,
                                testSuccess = true
                            )
                        },
                        onFailure = { e ->
                            _addServerState.value = _addServerState.value.copy(
                                isTesting = false,
                                testResult = e.message ?: "Connection failed",
                                testSuccess = false
                            )
                        }
                    )
                } else {
                    _addServerState.value = _addServerState.value.copy(
                        isTesting = false,
                        testResult = "Provider not available. Is it enabled in build?",
                        testSuccess = false
                    )
                }
            } catch (e: Exception) {
                _addServerState.value = _addServerState.value.copy(
                    isTesting = false,
                    testResult = e.message ?: "Unknown error",
                    testSuccess = false
                )
            }
        }
    }

    private val _syncCompleted = MutableStateFlow(false)
    val syncCompleted: StateFlow<Boolean> = _syncCompleted.asStateFlow()

    fun saveServer() {
        val state = _addServerState.value
        _addServerState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            try {
                val encryptedApiKey = state.apiKey.ifBlank { null }?.let {
                    credentialEncryptor.encrypt(it)
                }
                val encryptedPassword = state.password.ifBlank { null }?.let {
                    credentialEncryptor.encrypt(it)
                }
                val entity = CloudServerConfigEntity(
                    id = state.savedConfigId ?: 0L,
                    providerType = state.providerType,
                    serverUrl = state.serverUrl.trimEnd('/'),
                    apiKey = encryptedApiKey,
                    username = state.username.ifBlank { null },
                    encryptedPassword = encryptedPassword,
                    displayName = state.displayName.ifBlank {
                        "${state.providerType.displayName} Server"
                    },
                    isActive = true,
                    syncEnabled = state.syncEnabled ||
                        state.selectedLocalAlbumIds.isNotEmpty() ||
                        state.selectedRemoteAlbumIds.isNotEmpty(),
                    wifiOnly = state.wifiOnly,
                    autoUrlSwitch = state.autoUrlSwitch,
                    localWifiSsid = state.localWifiSsid.trim(),
                    localServerUrl = state.localServerUrl.trim().trimEnd('/')
                )
                val id = configDao.insert(entity)

                // Persist the final sync-stage selections: local folders to back up and
                // remote albums to pull. Both reuse existing per-album preference tables.
                persistSyncSelections(state, id)

                // Mint, configure, authenticate and register the provider instance for this
                // account so it is usable immediately (per-account, no app restart needed).
                providerInitializer.registerAccount(id)

                _addServerState.value = _addServerState.value.copy(
                    isSaving = false,
                    savedConfigId = id
                )

                // Trigger initial sync (media + albums) after successful save
                triggerSync(id)

                _syncCompleted.value = true
            } catch (e: Exception) {
                _addServerState.value = _addServerState.value.copy(
                    isSaving = false,
                    error = e.message ?: "Save failed"
                )
            }
        }
    }

    fun deleteServer(configId: Long) {
        viewModelScope.launch {
            val entity = configDao.getById(configId) ?: return@launch
            cloudMediaDao.deleteByServerConfig(configId)
            uploadPrefDao.deleteByConfig(configId)
            albumSyncDao.deleteByServer(configId)
            configDao.deleteById(configId)
            val provider = registry.getByConfigId(configId)
            if (provider is Disconnectable) {
                provider.disconnect()
            }
            registry.unregister(configId)
            // If this was the last account of its type, mark the type disconnected so the
            // media distributor drops its cloud albums. (Cached media rows were already
            // removed above via cloudMediaDao.deleteByServerConfig, which the timeline
            // observes reactively.)
            if (registry.getAllForType(entity.providerType).isEmpty()) {
                cloudRepository.disconnect(entity.providerType)
            }
        }
    }

    fun getConnectionState(configId: Long): ConnectionState {
        val provider = registry.getByConfigId(configId) as? RemoteMediaProvider
        return provider?.connectionState?.value ?: ConnectionState.DISCONNECTED
    }

    fun updateConfigById(configId: Long, transform: CloudServerConfigEntity.() -> CloudServerConfigEntity) {
        viewModelScope.launch {
            val entity = configDao.getById(configId) ?: return@launch
            val updated = entity.transform()
            configDao.update(updated)
        }
    }

    suspend fun getAssetCount(providerType: ProviderType): Int {
        return cloudMediaDao.countByProvider(providerType)
    }

    // All keyed by account id (configId) so several accounts of the same provider type
    // each show their own storage, version, count and sync progress.
    private val _storageInfo = MutableStateFlow<Map<Long, CloudStorageInfo>>(emptyMap())
    val storageInfo: StateFlow<Map<Long, CloudStorageInfo>> = _storageInfo.asStateFlow()

    private val _assetCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val assetCounts: StateFlow<Map<Long, Int>> = _assetCounts.asStateFlow()

    private val _serverVersions = MutableStateFlow<Map<Long, String>>(emptyMap())
    val serverVersions: StateFlow<Map<Long, String>> = _serverVersions.asStateFlow()

    fun loadStorageInfo() {
        viewModelScope.launch {
            configDao.getAll().first().forEach { config ->
                val provider = registry.getByConfigId(config.id) as? RemoteMediaProvider ?: return@forEach
                try {
                    provider.getStorageInfo().onSuccess { info ->
                        _storageInfo.value = _storageInfo.value + (config.id to info)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    fun loadAssetCounts() {
        viewModelScope.launch {
            configDao.getAll().first().forEach { config ->
                _assetCounts.value = _assetCounts.value + (config.id to cloudMediaDao.countByConfig(config.id))
            }
        }
    }

    fun loadServerVersions() {
        viewModelScope.launch {
            configDao.getAll().first().forEach { config ->
                val provider = registry.getByConfigId(config.id) as? RemoteMediaProvider ?: return@forEach
                try {
                    provider.getServerVersion().onSuccess { version ->
                        _serverVersions.value = _serverVersions.value + (config.id to version)
                    }
                } catch (_: Exception) { }
            }
        }
    }

    private val _syncProgress = MutableStateFlow<Map<Long, SyncProgress>>(emptyMap())
    val syncProgress: StateFlow<Map<Long, SyncProgress>> = _syncProgress.asStateFlow()

    data class SyncProgress(
        val isSyncing: Boolean = false,
        val mediaCount: Int = 0,
        val albumCount: Int = 0,
        val message: String = ""
    )

    fun triggerSync(configId: Long) {
        viewModelScope.launch {
            val provider = registry.getByConfigId(configId) as? RemoteMediaProvider ?: return@launch
            _syncProgress.value = _syncProgress.value + (configId to SyncProgress(
                isSyncing = true, message = "Fetching media..."
            ))
            try {
                var mediaCount = 0
                provider.getRemoteAssets(0, 500).collect { resource ->
                    if (resource is com.dot.gallery.core.Resource.Success) {
                        mediaCount = resource.data?.size ?: 0
                        _syncProgress.value = _syncProgress.value + (configId to SyncProgress(
                            isSyncing = true, mediaCount = mediaCount, message = "Synced $mediaCount media items..."
                        ))
                    }
                }
                _syncProgress.value = _syncProgress.value + (configId to SyncProgress(
                    isSyncing = true, mediaCount = mediaCount, message = "Fetching albums..."
                ))
                var albumCount = 0
                provider.getRemoteAlbums().collect { resource ->
                    if (resource is com.dot.gallery.core.Resource.Success) {
                        albumCount = resource.data?.size ?: 0
                    }
                }
                _syncProgress.value = _syncProgress.value + (configId to SyncProgress(
                    isSyncing = false, mediaCount = mediaCount, albumCount = albumCount,
                    message = "Done: $mediaCount media, $albumCount albums"
                ))
            } catch (e: Exception) {
                _syncProgress.value = _syncProgress.value + (configId to SyncProgress(
                    isSyncing = false, message = "Sync failed: ${e.message}"
                ))
            }
        }
    }

    /** Writes the chosen local-folder upload prefs and remote-album sync flags for [configId]. */
    private suspend fun persistSyncSelections(state: AddServerUiState, configId: Long) {
        val albums = _localAlbums.value
        state.selectedLocalAlbumIds.forEach { albumId ->
            val label = albums.firstOrNull { it.id == albumId }?.label ?: ""
            uploadPrefDao.upsert(
                CloudUploadPrefEntity(
                    serverConfigId = configId,
                    albumId = albumId,
                    providerType = state.providerType,
                    albumLabel = label,
                    uploadEnabled = true
                )
            )
        }
        state.remoteAlbums.forEach { album ->
            albumSyncDao.upsert(
                CloudAlbumSyncEntity(
                    albumRemoteId = album.remoteId,
                    providerType = state.providerType,
                    serverConfigId = configId,
                    albumName = album.name,
                    syncEnabled = album.remoteId in state.selectedRemoteAlbumIds
                )
            )
        }
    }

    private fun buildConfig(state: AddServerUiState) = CloudServerConfig(
        id = state.savedConfigId ?: 0L,
        providerType = state.providerType,
        serverUrl = state.serverUrl.trimEnd('/'),
        apiKey = state.apiKey.ifBlank { null },
        username = state.username.ifBlank { null },
        password = state.password.ifBlank { null },
        displayName = state.displayName,
        syncEnabled = state.syncEnabled,
        wifiOnly = state.wifiOnly,
        autoUrlSwitch = state.autoUrlSwitch,
        localWifiSsid = state.localWifiSsid.trim(),
        localServerUrl = state.localServerUrl.trim().trimEnd('/')
    )
}
