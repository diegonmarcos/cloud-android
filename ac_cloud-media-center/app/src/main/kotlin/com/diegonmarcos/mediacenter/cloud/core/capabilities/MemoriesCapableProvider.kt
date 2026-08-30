/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core.capabilities

import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.cloud.core.MemoryInfo
import com.diegonmarcos.mediacenter.core.Resource
import kotlinx.coroutines.flow.Flow

interface MemoriesCapableProvider : MediaCapabilityProvider {
    fun getMemories(): Flow<Resource<List<MemoryInfo>>>
}
