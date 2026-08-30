/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui.offline

import android.text.format.Formatter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.core.Position
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.core.presentation.components.SetupButton
import com.dot.gallery.feature_node.presentation.settings.components.BaseSettingsScreen
import androidx.compose.ui.platform.LocalContext

@Composable
fun OfflineModeScreen(
    viewModel: OfflineModeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val items: SnapshotStateList<SettingsEntity> = remember(state) {
        buildList {
            // === Offline mode ===
            add(SettingsEntity.Header(title = "Offline mode"))
            add(
                SettingsEntity.SwitchPreference(
                    icon = Icons.Outlined.CloudOff,
                    title = "Force offline mode",
                    summary = if (!state.connected) "No network — already offline"
                    else "Serve only cached media; don't use the network",
                    isChecked = state.forceOffline,
                    onCheck = { viewModel.setForceOffline(it) },
                    screenPosition = Position.Alone
                )
            )

            // === Cache-on-view ===
            add(SettingsEntity.Header(title = "Automatic cache"))
            add(
                SettingsEntity.SwitchPreference(
                    icon = Icons.Outlined.Sync,
                    title = "Cache while browsing",
                    summary = "Keep recently viewed cloud media for offline use",
                    isChecked = state.cacheOnView,
                    onCheck = { viewModel.setCacheOnView(it) },
                    screenPosition = Position.Top
                )
            )
            add(
                SettingsEntity.SwitchPreference(
                    icon = Icons.Outlined.Wifi,
                    title = "Cache on Wi-Fi only",
                    summary = "Don't write to the cache on metered connections",
                    isChecked = state.cacheWifiOnly,
                    onCheck = { viewModel.setCacheWifiOnly(it) },
                    screenPosition = Position.Middle
                )
            )
            add(
                SettingsEntity.Preference(
                    icon = Icons.Outlined.SdStorage,
                    title = "Cache size limit",
                    summary = "Oldest cached items are removed past this limit",
                    rightText = "${state.budgetMb} MB",
                    onClick = { showBudgetDialog = true },
                    screenPosition = Position.Bottom
                )
            )

            // === Per-account offline ===
            if (state.accounts.isNotEmpty()) {
                add(SettingsEntity.Header(title = "Available offline"))
                state.accounts.forEachIndexed { index, account ->
                    add(
                        SettingsEntity.SwitchPreference(
                            icon = Icons.Outlined.CloudDownload,
                            title = account.label,
                            summary = if (account.pinned) "Downloaded for offline access" else "Tap to download for offline",
                            isChecked = account.pinned,
                            onCheck = { viewModel.setAccountPinned(account.configId, account.label, it) },
                            screenPosition = positionFor(index, state.accounts.size)
                        )
                    )
                }

                // === Per-account cached-data management ===
                add(SettingsEntity.Header(title = "Cached data by account"))
                state.accounts.forEachIndexed { index, account ->
                    add(
                        SettingsEntity.Preference(
                            icon = Icons.Outlined.Storage,
                            title = account.label,
                            summary = "${account.providerType.displayName} • tap to manage albums",
                            rightText = Formatter.formatShortFileSize(context, account.cacheBytes),
                            onClick = { viewModel.openAccountCache(account.configId, account.label) },
                            screenPosition = positionFor(index, state.accounts.size)
                        )
                    )
                }
            }
        }.toMutableStateList()
    }

    val accountSheet by viewModel.accountSheet.collectAsStateWithLifecycle()

    BaseSettingsScreen(
        title = "Offline & Cache",
        topContent = {
            StorageUsageCard(
                autoBytes = state.autoCacheBytes,
                pinnedBytes = state.pinnedBytes,
                budgetMb = state.budgetMb,
                downloading = state.downloading,
                downloadDone = state.downloadDone,
                downloadTotal = state.downloadTotal,
                formatBytes = { Formatter.formatShortFileSize(context, it) }
            )
        },
        settingsList = items,
        bottomContent = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SetupButton(
                    text = if (state.downloading) "Downloading…" else "Download pinned now",
                    enabled = !state.downloading && state.accounts.any { it.pinned },
                    applyHorizontalPadding = false,
                    applyBottomPadding = false,
                    applyInsets = false,
                    onClick = { viewModel.downloadNow() }
                )
                SetupButton(
                    text = "Clear cached data",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    applyHorizontalPadding = false,
                    applyBottomPadding = false,
                    applyInsets = false,
                    onClick = { showClearDialog = true }
                )
            }
        }
    )

    if (showBudgetDialog) {
        BudgetDialog(
            currentMb = state.budgetMb,
            onDismiss = { showBudgetDialog = false },
            onSelect = {
                showBudgetDialog = false
                viewModel.setBudgetMb(it)
            }
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear cached data?") },
            text = { Text("This removes browsed (auto) cache and downloaded offline copies. Your accounts and timeline are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearAllCache()
                }) { Text("Clear all", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    viewModel.clearAutoCache()
                }) { Text("Auto cache only") }
            }
        )
    }

    accountSheet?.let { sheet ->
        AccountCacheSheet(
            state = sheet,
            formatBytes = { Formatter.formatShortFileSize(context, it) },
            onClearAccount = { viewModel.clearAccountCache(sheet.configId) },
            onClearAlbum = { remoteId -> viewModel.clearAlbumCache(sheet.configId, remoteId) },
            onDismiss = { viewModel.closeAccountCache() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountCacheSheet(
    state: AccountCacheSheetState,
    formatBytes: (Long) -> String,
    onClearAccount: () -> Unit,
    onClearAlbum: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = state.label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${formatBytes(state.totalBytes)} cached",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            SetupButton(
                text = "Clear all cache for this account",
                enabled = state.totalBytes > 0L,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                onClick = onClearAccount
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "By album",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            when {
                state.error != null -> {
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                state.loading && state.albums.isEmpty() -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Loading albums…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                state.albums.isEmpty() -> {
                    Text(
                        text = "No albums found for this account.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        state.albums.forEach { album ->
                            AlbumCacheRow(
                                album = album,
                                formatBytes = formatBytes,
                                onClear = { onClearAlbum(album.remoteId) }
                            )
                        }
                        if (state.loading) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumCacheRow(
    album: AlbumCacheEntry,
    formatBytes: (Long) -> String,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatBytes(album.cacheBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        when {
            album.clearing -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp
            )

            album.cacheBytes > 0L -> TextButton(onClick = onClear) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }

            else -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun StorageUsageCard(
    autoBytes: Long,
    pinnedBytes: Long,
    budgetMb: Int,
    downloading: Boolean,
    downloadDone: Int,
    downloadTotal: Int,
    formatBytes: (Long) -> String
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = formatBytes(autoBytes + pinnedBytes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "cached for offline use",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val budgetBytes = budgetMb.toLong() * 1024L * 1024L
            // Bar is scaled to the larger of the auto budget and what's actually
            // stored, so pinned media that exceeds the auto budget stays visible.
            val trackTotal = maxOf(budgetBytes, autoBytes + pinnedBytes, 1L)
            val freeBytes = (budgetBytes - autoBytes).coerceAtLeast(0L)
            val autoColor = MaterialTheme.colorScheme.primary
            val pinnedColor = MaterialTheme.colorScheme.tertiary
            val freeColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

            Spacer(Modifier.height(14.dp))
            SegmentedStorageBar(
                segments = listOf(autoColor to autoBytes, pinnedColor to pinnedBytes),
                total = trackTotal,
                trackColor = freeColor,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                StorageLegendItem(color = autoColor, label = "Auto", value = formatBytes(autoBytes))
                StorageLegendItem(color = pinnedColor, label = "Pinned", value = formatBytes(pinnedBytes))
                StorageLegendItem(color = freeColor, label = "Free", value = formatBytes(freeBytes))
            }
            if (downloading) {
                val p = if (downloadTotal > 0) downloadDone.toFloat() / downloadTotal else 0f
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
                Text(
                    "Downloading offline copies $downloadDone / $downloadTotal",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * A thick, rounded, multi-segment storage bar. Each segment is drawn proportional
 * to its byte count over [total]; the remainder shows as the faint [trackColor].
 */
@Composable
private fun SegmentedStorageBar(
    segments: List<Pair<Color, Long>>,
    total: Long,
    trackColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clip(RoundedCornerShape(7.dp))) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)

        val safeTotal = total.coerceAtLeast(1L).toFloat()
        var x = 0f
        segments.forEach { (color, bytes) ->
            if (bytes <= 0L) return@forEach
            val w = size.width * (bytes.toFloat() / safeTotal)
            drawRoundRect(
                color = color,
                topLeft = Offset(x, 0f),
                size = Size(w, size.height),
                cornerRadius = radius
            )
            x += w
        }
    }
}

@Composable
private fun StorageLegendItem(color: Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun BudgetDialog(currentMb: Int, onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    val options = listOf(256, 512, 1024, 2048, 5120)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cache size limit") },
        text = {
            Column {
                options.forEach { mb ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = mb == currentMb, onClick = { onSelect(mb) })
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = mb == currentMb, onClick = { onSelect(mb) })
                        Text(
                            text = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

private fun positionFor(index: Int, size: Int): Position = when {
    size == 1 -> Position.Alone
    index == 0 -> Position.Top
    index == size - 1 -> Position.Bottom
    else -> Position.Middle
}
