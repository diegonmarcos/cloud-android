/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.core.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

/**
 * Abstraction for rendering media/album thumbnails.
 *
 * The default implementation uses GlideImage. Override via CompositionLocalProvider
 * to render colored placeholders in preview contexts (e.g. Help & Tips previews).
 *
 * [model] is the image source — typically a [Uri] from [Media.getUri()] or [Album.uri].
 * [signature] is an optional cache-invalidation key (e.g. the media/album object itself).
 */
interface MediaImageRenderer {
    @Composable
    fun RenderImage(
        modifier: Modifier,
        model: Any?,
        contentScale: ContentScale,
        contentDescription: String?,
        signature: Any? = null
    )
}

val LocalMediaImageRenderer = staticCompositionLocalOf<MediaImageRenderer> {
    error("No MediaImageRenderer provided! Ensure it is set up via SetupMediaProviders or CompositionLocalProvider.")
}
