package com.diegonmarcos.mediacenter.feature_node.presentation.common.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.Constants.Animation.enterAnimation
import com.diegonmarcos.mediacenter.core.Constants.Animation.exitAnimation
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadataState

@Composable
fun MetadataCollectionStatus(
    modifier: Modifier = Modifier,
    state: State<MediaMetadataState>,
) {
    AnimatedVisibility(
        modifier = modifier,
        visible = state.value.isLoading && state.value.isLoadingProgress > 1,
        enter = enterAnimation,
        exit = exitAnimation
    ) {
        ListItem(
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 8.dp
            ),
            headlineContent = {
                Text(stringResource(R.string.collecting_metadata))
            },
            supportingContent = {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    progress = {
                        state.value.isLoadingProgress.toFloat() / 100f
                    },
                )
            },
        )
    }
}