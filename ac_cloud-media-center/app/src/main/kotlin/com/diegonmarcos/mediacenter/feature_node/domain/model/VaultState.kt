package com.diegonmarcos.mediacenter.feature_node.domain.model

import androidx.compose.runtime.Stable

@Stable
data class VaultState(
    val vaults: List<Vault> = emptyList(),
    val isLoading: Boolean = true
)