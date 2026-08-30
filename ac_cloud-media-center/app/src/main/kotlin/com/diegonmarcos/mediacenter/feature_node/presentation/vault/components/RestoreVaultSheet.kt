package com.diegonmarcos.mediacenter.feature_node.presentation.vault.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.feature_node.presentation.util.AppBottomSheetState

@Composable
fun RestoreVaultSheet(
    state: AppBottomSheetState,
    onConfirm: () -> Unit
) {
    ConfirmationSheet(
        state = state,
        title = stringResource(R.string.restore_vault_title),
        summary = stringResource(R.string.restore_vault_summary),
        onConfirm = onConfirm
    )
}