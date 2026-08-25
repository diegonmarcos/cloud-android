/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.presentation.collection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dot.gallery.feature_node.domain.model.Collection
import com.dot.gallery.feature_node.domain.model.CollectionWithCount
import com.dot.gallery.feature_node.domain.repository.MediaRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val repository: MediaRepository
) : ViewModel() {

    val collectionsWithCount: StateFlow<List<CollectionWithCount>> =
        repository.getCollectionsWithCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun createCollection(name: String, onCreated: ((Long) -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.insertCollection(
                Collection(label = name)
            )
            onCreated?.invoke(id)
        }
    }

    fun renameCollection(collectionId: Long, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateCollectionLabel(collectionId, newName)
        }
    }

    fun deleteCollection(collectionId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCollection(collectionId)
        }
    }

    fun togglePin(collectionId: Long, isPinned: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.toggleCollectionPinned(collectionId, isPinned)
        }
    }

    fun updateCover(collectionId: Long, mediaId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateCollectionCover(collectionId, mediaId)
        }
    }

    fun addMediaToCollection(collectionId: Long, mediaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addMediaToCollection(collectionId, mediaId)
        }
    }

    fun addMediaListToCollection(collectionId: Long, mediaIds: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.addMediaListToCollection(collectionId, mediaIds)
        }
    }

    fun removeMediaFromCollection(collectionId: Long, mediaId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.removeMediaFromCollection(collectionId, mediaId)
        }
    }

    fun createCollectionAndAddMedia(
        name: String,
        mediaIds: List<Long>,
        onCreated: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = repository.insertCollection(
                Collection(label = name)
            )
            repository.addMediaListToCollection(id, mediaIds)
            onCreated?.invoke(id)
        }
    }
}
