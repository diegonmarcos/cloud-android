package com.diegonmarcos.mediacenter.feature_node.domain.model.editor

import android.os.Parcelable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.ui.core.icons.InkHighlighter
import com.diegonmarcos.mediacenter.ui.core.icons.InkMarker
import com.diegonmarcos.mediacenter.ui.core.icons.Ink_Eraser
import com.diegonmarcos.mediacenter.ui.core.icons.Stylus
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable
import com.diegonmarcos.mediacenter.ui.core.Icons as DotIcons

@Serializable
@Parcelize
enum class MarkupItems : Parcelable {
    Stylus,
    Highlighter,
    Marker,
    Blur,
    Mosaic,
    Text,
    Eraser,
    Pan;

    @get:Composable
    val translatedName get() = when (this) {
        Stylus -> stringResource(R.string.type_stylus)
        Highlighter -> stringResource(R.string.type_highlighter)
        Marker -> stringResource(R.string.type_marker)
        Blur -> stringResource(R.string.type_blur)
        Mosaic -> stringResource(R.string.type_mosaic)
        Text -> stringResource(R.string.type_text)
        Eraser -> stringResource(R.string.type_erase)
        Pan -> stringResource(R.string.type_pan)
    }

    @IgnoredOnParcel
    val icon: ImageVector
        get() = when (this) {
            Stylus -> DotIcons.Stylus
            Highlighter -> DotIcons.InkHighlighter
            Marker -> DotIcons.InkMarker
            Blur -> Icons.Outlined.BlurOn
            Mosaic -> Icons.Outlined.GridOn
            Text -> Icons.Outlined.TextFields
            Eraser -> DotIcons.Ink_Eraser
            Pan -> Icons.Outlined.PanTool
        }
}