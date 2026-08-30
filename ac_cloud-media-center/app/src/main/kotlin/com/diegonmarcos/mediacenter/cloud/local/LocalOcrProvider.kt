/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.local

import com.diegonmarcos.mediacenter.cloud.core.OcrResult
import com.diegonmarcos.mediacenter.cloud.core.ProviderCapability
import com.diegonmarcos.mediacenter.cloud.core.ProviderType
import com.diegonmarcos.mediacenter.cloud.core.capabilities.OcrCapableProvider
import com.diegonmarcos.mediacenter.core.Resource
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local on-device OCR provider.
 * Uses ONNX Runtime for text detection and recognition in images.
 *
 * TODO: Implement in Phase 9+ with actual ONNX model integration.
 * This stub provides the interface contract for future implementation.
 */
@Singleton
class LocalOcrProvider @Inject constructor() : LocalCapabilityProvider(), OcrCapableProvider {

    override val providerType: ProviderType = ProviderType.LOCAL_OCR
    override val displayName: String = ProviderType.LOCAL_OCR.displayName
    override val capabilities: Set<ProviderCapability> = setOf(ProviderCapability.OCR)

    private var initialized = false

    override suspend fun initialize() {
        // TODO: Load ONNX text detection + recognition models
        initialized = true
    }

    override fun release() {
        initialized = false
    }

    override val isAvailable: Boolean
        get() = initialized

    override suspend fun extractText(mediaId: Long): OcrResult? {
        // TODO: Run OCR on media
        return null
    }

    override fun searchByText(query: String): Flow<Resource<List<Media>>> {
        // TODO: Search OCR index
        return flowOf(Resource.Success(emptyList()))
    }
}
