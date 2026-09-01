/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.mediaview.components.video

import android.net.Uri
import com.diegonmarcos.mediacenter.feature_node.domain.model.SubtitleTrack

data class VideoControllerState(
    val subtitleTracks: List<SubtitleTrack>,
    val onSelectSubtitle: (SubtitleTrack) -> Unit,
    val onDisableSubtitles: () -> Unit,
    val onAddExternalSubtitle: (Uri) -> Unit,
    val onRemoveSubtitle: (SubtitleTrack) -> Unit,
)
