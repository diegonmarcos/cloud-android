/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.nextcloud

import com.diegonmarcos.mediacenter.cloud.core.CloudStorageInfo
import com.diegonmarcos.mediacenter.cloud.core.ProviderType
import com.diegonmarcos.mediacenter.cloud.webdav.OcsWebDavDialect
import com.diegonmarcos.mediacenter.cloud.webdav.WebDavFeatureKey
import com.diegonmarcos.mediacenter.cloud.webdav.WebDavSession

/**
 * Nextcloud server dialect. Extends the shared OCS behavior with Nextcloud's
 * native favorites (`oc:favorite`) and storage quota reporting.
 */
class NextcloudDialect : OcsWebDavDialect() {
    override val providerType = ProviderType.NEXTCLOUD
    override val displayName = "Nextcloud"
    override val productName = "Nextcloud"

    override val features: Set<WebDavFeatureKey> = super.features + setOf(
        WebDavFeatureKey.FAVORITES,
        WebDavFeatureKey.QUOTA
    )

    override suspend fun toggleFavorite(
        session: WebDavSession,
        remotePath: String,
        favorite: Boolean
    ): Result<Unit> = try {
        session.webDavClient.setFavorite(remotePath, favorite)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun storageInfo(session: WebDavSession): Result<CloudStorageInfo> =
        ocsStorageInfo(session)
}
