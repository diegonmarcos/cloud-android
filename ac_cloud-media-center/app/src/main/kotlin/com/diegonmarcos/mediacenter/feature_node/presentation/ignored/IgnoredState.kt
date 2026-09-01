package com.diegonmarcos.mediacenter.feature_node.presentation.ignored

import android.os.Parcelable
import androidx.compose.runtime.Immutable
import com.diegonmarcos.mediacenter.feature_node.domain.model.IgnoredAlbum
import kotlinx.parcelize.Parcelize

@Immutable
@Parcelize
data class IgnoredState(
    val albums: List<IgnoredAlbum> = emptyList()
): Parcelable
