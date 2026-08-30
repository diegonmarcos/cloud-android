/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core.capabilities

import com.diegonmarcos.mediacenter.cloud.core.CloudMapMarker
import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.core.Resource
import kotlinx.coroutines.flow.Flow

interface MapCapableProvider : MediaCapabilityProvider {
    fun getMapMarkers(): Flow<Resource<List<CloudMapMarker>>>
}
