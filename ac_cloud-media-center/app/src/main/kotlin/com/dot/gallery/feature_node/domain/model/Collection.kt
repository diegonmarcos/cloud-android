/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.domain.model

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "collections")
@Immutable
@Serializable
data class Collection(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val label: String,
    val coverMediaId: Long? = null,
    val isPinned: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Represents a collection with its associated media count and thumbnail.
 * Used for displaying collections in the UI.
 */
data class CollectionWithCount(
    val collection: Collection,
    val mediaCount: Int,
    val thumbnailMediaId: Long?,
    val totalSize: Long = 0
)
