package com.diegonmarcos.mediacenter.feature_node.presentation.util

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.diegonmarcos.mediacenter.core.DefaultEventHandler
import com.diegonmarcos.mediacenter.core.LocalEventHandler
import com.diegonmarcos.mediacenter.ui.theme.GalleryTheme

@Composable
fun PreviewHost(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(LocalEventHandler provides DefaultEventHandler()) {
        GalleryTheme(
            darkTheme = darkTheme,
            content = content
        )
    }
}