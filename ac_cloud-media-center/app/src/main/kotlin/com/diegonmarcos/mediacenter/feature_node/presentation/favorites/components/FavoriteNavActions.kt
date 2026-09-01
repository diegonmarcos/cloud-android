/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.favorites.components

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.Constants.Animation.enterAnimation
import com.diegonmarcos.mediacenter.core.Constants.Animation.exitAnimation
import com.diegonmarcos.mediacenter.core.LocalMediaHandler
import com.diegonmarcos.mediacenter.core.LocalMediaSelector
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.presentation.util.selectedMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun <T: Media> FavoriteNavActions(
    mediaState: State<MediaState<T>>,
    result: ActivityResultLauncher<IntentSenderRequest>
) {
    val scope = rememberCoroutineScope { Dispatchers.IO }
    val handler = LocalMediaHandler.current
    val selector = LocalMediaSelector.current
    val selectedMedia = selector.selectedMedia.collectAsStateWithLifecycle()
    val selectionState = selector.isSelectionActive.collectAsStateWithLifecycle()
    val selectedMediaList = mediaState.value.media.selectedMedia(selectedSet = selectedMedia)
    val removeAllTitle = stringResource(R.string.remove_all)
    val removeSelectedTitle = stringResource(R.string.remove_selected)
    val title = if (selectionState.value) removeSelectedTitle else removeAllTitle
    AnimatedVisibility(
        visible = mediaState.value.media.isNotEmpty(),
        enter = enterAnimation,
        exit = exitAnimation
    ) {
        TextButton(
            onClick = {
                scope.launch {
                    handler.toggleFavorite(result, selectedMediaList.ifEmpty { mediaState.value.media }, false)
                }
            }
        ) {
            Text(
                text = title,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

