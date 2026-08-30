package com.diegonmarcos.mediacenter.feature_node.presentation.mediaview.components.actionbuttons

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.LocalMediaHandler
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberTrashEnabled
import com.diegonmarcos.mediacenter.core.util.SdkCompat
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.Vault
import com.diegonmarcos.mediacenter.feature_node.domain.util.isEncrypted
import com.diegonmarcos.mediacenter.feature_node.presentation.trashed.components.TrashDialog
import com.diegonmarcos.mediacenter.feature_node.presentation.trashed.components.TrashDialogAction
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberActivityResult
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberAppBottomSheetState
import kotlinx.coroutines.launch

@Composable
fun <T : Media> TrashButton(
    media: T,
    followTheme: Boolean = false,
    enabled: Boolean,
    deleteMedia: ((Vault, T, () -> Unit) -> Unit)?,
    currentVault: Vault?,
    onTrashConfirmed: () -> Unit = {}
) {
    val handler = LocalMediaHandler.current
    var shouldMoveToTrash by rememberSaveable { mutableStateOf(true) }
    val state = rememberAppBottomSheetState()
    val scope = rememberCoroutineScope()
    val trashEnabled = rememberTrashEnabled()
    val trashEnabledRes = remember(trashEnabled, media) {
        if (trashEnabled.value && !media.isEncrypted && SdkCompat.supportsTrash) R.string.trash else R.string.trash_delete
    }
    val result = rememberActivityResult(
        onResultCanceled = {
            scope.launch {
                state.hide()
                shouldMoveToTrash = true
            }
        },
        onResultOk = onTrashConfirmed
    )
    MediaViewButton(
        currentMedia = media,
        imageVector = Icons.Outlined.DeleteOutline,
        followTheme = followTheme,
        title = stringResource(id = trashEnabledRes),
        onItemLongClick = {
            shouldMoveToTrash = false
            scope.launch {
                state.show()
            }
        },
        onItemClick = {
            shouldMoveToTrash = true
            scope.launch {
                state.show()
            }
        },
        enabled = enabled
    )

    TrashDialog(
        appBottomSheetState = state,
        data = listOf(media),
        action = if (deleteMedia != null && currentVault != null) {
            TrashDialogAction.DELETE
        } else if (shouldMoveToTrash) {
            TrashDialogAction.TRASH
        } else {
            TrashDialogAction.DELETE
        }
    ) {
        if (deleteMedia != null && currentVault != null) {
            it.forEach { media ->
                deleteMedia(currentVault, media) {}
            }
            onTrashConfirmed()
        } else {
            if (shouldMoveToTrash && SdkCompat.supportsTrash) {
                handler.trashMedia(result, it, true)
            } else {
                handler.deleteMedia(result, it)
            }
            // On API 29, content is deleted directly without launching an
            // IntentSender, so onResultOk never fires. Trigger the callback
            // here so the viewer still advances immediately.
            if (!SdkCompat.supportsMediaStoreRequests) {
                onTrashConfirmed()
            }
        }
    }
}