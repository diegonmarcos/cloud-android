/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core.capabilities

import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.cloud.core.OcrResult
import com.diegonmarcos.mediacenter.core.Resource
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import kotlinx.coroutines.flow.Flow

interface OcrCapableProvider : MediaCapabilityProvider {
    suspend fun extractText(mediaId: Long): OcrResult?
    fun searchByText(query: String): Flow<Resource<List<Media>>>
}
