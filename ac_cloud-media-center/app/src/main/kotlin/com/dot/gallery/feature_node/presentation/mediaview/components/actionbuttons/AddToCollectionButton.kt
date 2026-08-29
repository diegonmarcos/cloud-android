/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */
package com.dot.gallery.feature_node.presentation.mediaview.components.actionbuttons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.dot.gallery.R
import com.dot.gallery.feature_node.domain.model.AlbumState
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.presentation.collection.CollectionViewModel
import com.dot.gallery.feature_node.presentation.collection.components.AddToCollectionSheet

@Composable
fun <T : Media> AddToCollectionButton(
    media: T,
    albumsState: State<AlbumState>,
    enabled: Boolean,
    followTheme: Boolean = false
) {
    var showCollectionSheet by rememberSaveable { mutableStateOf(false) }
    val collectionViewModel = hiltViewModel<CollectionViewModel>()

    MediaViewButton(
        currentMedia = media,
        imageVector = Icons.Outlined.Collections,
        followTheme = followTheme,
        title = stringResource(R.string.add_to_collection),
        enabled = enabled
    ) {
        showCollectionSheet = true
    }

    AddToCollectionSheet(
        visible = showCollectionSheet,
        collections = albumsState.value.collections,
        onDismiss = { showCollectionSheet = false },
        onCollectionSelected = { collectionId ->
            collectionViewModel.addMediaToCollection(collectionId, media.id)
        },
        onCreateAndAdd = { name ->
            collectionViewModel.createCollectionAndAddMedia(
                name,
                listOf(media.id)
            )
        }
    )
}
