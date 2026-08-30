/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core.capabilities

import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media

interface SmartSearchCapableProvider : MediaCapabilityProvider {
    suspend fun smartSearch(query: String): Result<List<Media>>
}
