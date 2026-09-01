/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core.capabilities

import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.cloud.core.SharedLinkInfo
import com.diegonmarcos.mediacenter.core.Resource
import kotlinx.coroutines.flow.Flow

interface ShareLinkCapableProvider : MediaCapabilityProvider {
    suspend fun createShareLink(
        assetIds: List<String>,
        expiresAt: Long? = null
    ): Result<String>

    fun getSharedLinks(): Flow<Resource<List<SharedLinkInfo>>>
    suspend fun deleteSharedLink(linkId: String): Result<Unit>
    suspend fun updateSharedLink(linkId: String, updates: Map<String, Any>): Result<Unit>
}
