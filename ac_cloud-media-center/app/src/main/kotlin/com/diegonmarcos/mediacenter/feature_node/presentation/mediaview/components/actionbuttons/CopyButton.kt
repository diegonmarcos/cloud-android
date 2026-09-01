package com.diegonmarcos.mediacenter.feature_node.presentation.mediaview.components.actionbuttons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CopyAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.feature_node.domain.model.AlbumState
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.presentation.exif.CopyMediaSheet
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberAppBottomSheetState
import kotlinx.coroutines.launch

@Composable
fun <T : Media> CopyButton(
    media: T,
    albumsState: State<AlbumState>,
    enabled: Boolean,
    followTheme: Boolean = false
) {
    val copySheetState = rememberAppBottomSheetState()
    val scope = rememberCoroutineScope()
    MediaViewButton(
        currentMedia = media,
        imageVector = Icons.Outlined.CopyAll,
        followTheme = followTheme,
        title = stringResource(R.string.copy),
        enabled = enabled
    ) {
        scope.launch {
            copySheetState.show()
        }
    }

    CopyMediaSheet(
        sheetState = copySheetState,
        mediaList = listOf(media),
        albumsState = albumsState,
        onFinish = { }
    )
}