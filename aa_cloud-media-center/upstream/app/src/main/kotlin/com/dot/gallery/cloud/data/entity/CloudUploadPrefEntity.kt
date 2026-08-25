/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.data.entity

import androidx.room.Entity
import androidx.room.Index
import com.dot.gallery.cloud.core.ProviderType

/**
 * Per-account backup album selection.
 *
 * Backup is scoped to a specific cloud account ([serverConfigId]) so that each
 * configured cloud independently chooses which local albums it backs up. The
 * same album can therefore be selected for one account and not another.
 * [providerType] is denormalised from the owning server config for cheap
 * grouping/labelling in the UI and worker.
 */
@Entity(
    tableName = "cloud_upload_pref",
    primaryKeys = ["serverConfigId", "albumId"],
    indices = [Index(value = ["serverConfigId"])]
)
data class CloudUploadPrefEntity(
    val serverConfigId: Long,
    val albumId: Long,
    val providerType: ProviderType,
    val albumLabel: String = "",
    val uploadEnabled: Boolean = false,
    val deleteLocalAfterUpload: Boolean = false
)
