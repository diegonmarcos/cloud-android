/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dot.gallery.R
import com.dot.gallery.core.Position
import com.dot.gallery.core.SettingsEntity
import com.dot.gallery.feature_node.presentation.settings.components.BaseSettingsScreen

@Composable
fun CloudAdvancedSettingsScreen() {
    val settingsVm = hiltViewModel<CloudSettingsViewModel>()
    val config by settingsVm.config.collectAsStateWithLifecycle()
    val cacheClearing by settingsVm.cacheClearing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val troubleshooting = config?.verboseLogging ?: false
    val readOnlyMode = config?.readOnlyMode ?: false

    val settingsList = remember(troubleshooting, readOnlyMode, cacheClearing) {
        buildList {
            // Troubleshooting section
            add(SettingsEntity.Header(title = context.getString(R.string.cloud_adv_troubleshooting)))
            add(
                SettingsEntity.SwitchPreference(
                    title = context.getString(R.string.cloud_adv_verbose),
                    summary = context.getString(R.string.cloud_adv_verbose_summary),
                    isChecked = troubleshooting,
                    onCheck = { settingsVm.updateConfig { copy(verboseLogging = it) } },
                    screenPosition = Position.Alone
                )
            )

            // Display section
            add(SettingsEntity.Header(title = context.getString(R.string.cloud_adv_display)))
            add(
                SettingsEntity.SwitchPreference(
                    title = context.getString(R.string.cloud_adv_readonly),
                    summary = context.getString(R.string.cloud_adv_readonly_summary),
                    isChecked = readOnlyMode,
                    onCheck = { settingsVm.updateConfig { copy(readOnlyMode = it) } },
                    screenPosition = Position.Alone
                )
            )

            // Cache section
            add(SettingsEntity.Header(title = context.getString(R.string.cloud_adv_cache)))
            add(
                SettingsEntity.Preference(
                    title = if (cacheClearing) context.getString(R.string.cloud_adv_clearing_cache)
                    else context.getString(R.string.cloud_adv_clear_cache),
                    enabled = !cacheClearing,
                    onClick = { settingsVm.clearImageCache() },
                    screenPosition = Position.Alone
                )
            )
        }.toMutableStateList()
    }

    BaseSettingsScreen(
        title = stringResource(R.string.cloud_advanced),
        settingsList = settingsList
    )
}
