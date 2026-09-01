package com.diegonmarcos.mediacenter.feature_node.presentation.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.presentation.components.SetupButton
import com.diegonmarcos.mediacenter.core.presentation.components.SetupWizard
import com.diegonmarcos.mediacenter.feature_node.domain.model.Vault
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberAppBottomSheetState
import com.diegonmarcos.mediacenter.feature_node.presentation.vault.components.VaultPasswordSetupSheet
import com.diegonmarcos.mediacenter.feature_node.presentation.vault.utils.VaultPasswordManager
import com.diegonmarcos.mediacenter.ui.core.Icons
import com.diegonmarcos.mediacenter.ui.core.icons.Encrypted
import kotlinx.coroutines.launch

@Composable
fun VaultPasswordSetupScreen(
    vault: Vault?,
    onSkip: () -> Unit,
    onComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val passwordSetupSheetState = rememberAppBottomSheetState()

    SetupWizard(
        icon = Icons.Encrypted,
        title = stringResource(R.string.vault_set_password),
        subtitle = stringResource(R.string.vault_setup_password_prompt),
        bottomBar = {
            SetupButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                text = stringResource(R.string.vault_skip_password)
            )
            SetupButton(
                onClick = {
                    scope.launch { passwordSetupSheetState.show() }
                },
                modifier = Modifier.weight(1f),
                applyHorizontalPadding = false,
                applyBottomPadding = false,
                applyInsets = false,
                text = stringResource(R.string.vault_set_password)
            )
        },
        content = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = stringResource(R.string.vault_custom_password_summary),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    )

    VaultPasswordSetupSheet(
        state = passwordSetupSheetState,
        onSecretSet = { type, secret ->
            if (vault != null) {
                scope.launch {
                    VaultPasswordManager.setPassword(context, vault.uuid, secret, type)
                    onComplete()
                }
            }
        }
    )
}
