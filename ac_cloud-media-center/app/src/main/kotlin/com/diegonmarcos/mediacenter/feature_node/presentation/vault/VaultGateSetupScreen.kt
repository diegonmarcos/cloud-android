package com.diegonmarcos.mediacenter.feature_node.presentation.vault

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
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
import com.diegonmarcos.mediacenter.core.presentation.components.NavigationBackButton
import com.diegonmarcos.mediacenter.core.presentation.components.SetupButton
import com.diegonmarcos.mediacenter.core.presentation.components.SetupWizard
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberAppBottomSheetState
import com.diegonmarcos.mediacenter.feature_node.presentation.vault.components.VaultPasswordSetupSheet
import com.diegonmarcos.mediacenter.feature_node.presentation.vault.utils.GateMode
import com.diegonmarcos.mediacenter.feature_node.presentation.vault.utils.VaultPasswordManager
import com.diegonmarcos.mediacenter.ui.core.Icons
import com.diegonmarcos.mediacenter.ui.core.icons.Encrypted
import kotlinx.coroutines.launch

@Composable
fun VaultGateSetupScreen(
    onBack: (() -> Unit)? = null,
    onNone: () -> Unit,
    onDeviceSecurity: () -> Unit,
    onCustomComplete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customSetupSheetState = rememberAppBottomSheetState()

    Box(modifier = Modifier.fillMaxSize()) {
        SetupWizard(
        icon = Icons.Encrypted,
        title = stringResource(R.string.vault_gate_setup_title),
        subtitle = stringResource(R.string.vault_gate_setup_subtitle),
        bottomBar = {},
        content = {
            Text(
                text = stringResource(R.string.vault_gate_setup_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                SetupButton(
                    onClick = {
                        scope.launch {
                            VaultPasswordManager.setGateMode(context, GateMode.NONE)
                            onNone()
                        }
                    },
                    applyHorizontalPadding = false,
                    applyBottomPadding = false,
                    applyInsets = false,
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    text = stringResource(R.string.vault_gate_none)
                )
                SetupButton(
                    onClick = {
                        scope.launch {
                            VaultPasswordManager.setGateMode(context, GateMode.DEVICE)
                            onDeviceSecurity()
                        }
                    },
                    applyHorizontalPadding = false,
                    applyBottomPadding = false,
                    applyInsets = false,
                    text = stringResource(R.string.vault_gate_device)
                )
                SetupButton(
                    onClick = {
                        scope.launch { customSetupSheetState.show() }
                    },
                    applyHorizontalPadding = false,
                    applyBottomPadding = false,
                    applyInsets = false,
                    text = stringResource(R.string.vault_gate_custom)
                )
            }
        }
        )

        if (onBack != null) {
            NavigationBackButton(
                modifier = Modifier.statusBarsPadding(),
                forcedAction = onBack
            )
        }
    }

    VaultPasswordSetupSheet(
        state = customSetupSheetState,
        onSecretSet = { type, secret ->
            scope.launch {
                VaultPasswordManager.setGateMode(context, GateMode.CUSTOM)
                VaultPasswordManager.setPassword(
                    context,
                    VaultPasswordManager.GATE_UUID,
                    secret,
                    type
                )
                onCustomComplete()
            }
        }
    )
}
