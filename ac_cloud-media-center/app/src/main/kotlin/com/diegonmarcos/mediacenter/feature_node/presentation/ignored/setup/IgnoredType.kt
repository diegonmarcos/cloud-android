package com.diegonmarcos.mediacenter.feature_node.presentation.ignored.setup

import com.diegonmarcos.mediacenter.feature_node.domain.model.Album

sealed class IgnoredType {

    data class SINGLE(val selectedAlbum: Album?) : IgnoredType()

    data class MULTIPLE(val selectedAlbums: List<Album> = emptyList()) : IgnoredType()

    data class REGEX(val regex: String = "") : IgnoredType()
}