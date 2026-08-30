/**
 * Parsed EXIF metadata, extracted from presentation/exif/MetadataViewViewModel.kt.
 *
 * These are plain data classes the metadata PARSER produces, not view state, and
 * core/sandbox/IsolatedMetadataParser needs them. Declaring them in a ViewModel
 * file made that one import the single edge that dragged the entire UI layer
 * into the cloud engine's dependency closure: parser -> ViewModel -> Settings
 * (1371 lines, 16 Compose imports) -> Screen, MapAppearance, FilterComponent,
 * LibraryShortcut, SlideshowPlaylist.
 */
package com.diegonmarcos.mediacenter.core.metadata

data class MetadataDirectory(
    val name: String,
    val tags: List<MetadataTag>
)

data class MetadataTag(
    val name: String,
    val description: String
)
