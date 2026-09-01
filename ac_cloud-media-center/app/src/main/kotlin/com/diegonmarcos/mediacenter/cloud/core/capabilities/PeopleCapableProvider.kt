/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.cloud.core.capabilities

import com.diegonmarcos.mediacenter.cloud.core.DetectedFace
import com.diegonmarcos.mediacenter.cloud.core.MediaCapabilityProvider
import com.diegonmarcos.mediacenter.cloud.core.PersonInfo
import com.diegonmarcos.mediacenter.core.Resource
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import kotlinx.coroutines.flow.Flow

interface PeopleCapableProvider : MediaCapabilityProvider {
    fun getPeople(): Flow<Resource<List<PersonInfo>>>
    fun getPersonMedia(personId: String): Flow<Resource<List<Media>>>
    fun getPersonThumbnailUrl(personId: String): String?

    suspend fun updatePersonName(personId: String, name: String): Result<Unit>
    suspend fun updatePersonBirthDate(personId: String, birthDate: String): Result<Unit>

    /**
     * Run face detection on a local media item.
     * Returns null if this provider does not support local face detection.
     */
    suspend fun detectFaces(mediaId: Long): List<DetectedFace>? = null
}
