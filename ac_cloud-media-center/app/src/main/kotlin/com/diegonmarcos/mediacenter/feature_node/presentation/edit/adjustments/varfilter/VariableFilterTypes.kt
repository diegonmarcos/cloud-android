package com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter

import androidx.annotation.Keep
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brightness4
import androidx.compose.material.icons.outlined.Brightness5
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.CropDin
import androidx.compose.material.icons.outlined.Details
import androidx.compose.material.icons.outlined.FilterDrama
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.InvertColors
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Rotate90DegreesCcw
import androidx.compose.material.icons.outlined.Texture
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Tonality
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.ui.graphics.vector.ImageVector
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.VariableFilter
import kotlinx.serialization.Serializable

@Keep
@Serializable
enum class VariableFilterTypes {
    // Legacy
    Brightness, Contrast, Saturation, Rotate,
    // Lighting
    Tone, BlackPoint, WhitePoint, Highlights, Shadows, Vignette,
    // Colour
    Warmth, Tint, SkinTone, BlueTone, Hue, BlackWhite,
    // Effects
    Posterize, Edges, Borders,
    // Actions
    Pop, Sharpen, Denoise;

    fun createFilter(value: Float): VariableFilter =
        when (this) {
            Brightness -> Brightness(value)
            Contrast -> Contrast(value)
            Saturation -> Saturation(value)
            Rotate -> Rotate(value)
            Tone -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Tone(value)
            BlackPoint -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.BlackPoint(value)
            WhitePoint -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.WhitePoint(value)
            Highlights -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Highlights(value)
            Shadows -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Shadows(value)
            Vignette -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Vignette(value)
            Warmth -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Warmth(value)
            Tint -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Tint(value)
            SkinTone -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.SkinTone(value)
            BlueTone -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.BlueTone(value)
            Hue -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Hue(value)
            BlackWhite -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.BlackWhite(value)
            Posterize -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Posterize(value)
            Edges -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Edges(value)
            Borders -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Borders(value)
            Pop -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Pop(value)
            Sharpen -> Sharpness(value)
            Denoise -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Denoise(value)
        }

    fun createDefaultFilter(): VariableFilter =
        when (this) {
            Brightness -> Brightness()
            Contrast -> Contrast()
            Saturation -> Saturation()
            Rotate -> Rotate()
            Tone -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Tone()
            BlackPoint -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.BlackPoint()
            WhitePoint -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.WhitePoint()
            Highlights -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Highlights()
            Shadows -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Shadows()
            Vignette -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Vignette()
            Warmth -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Warmth()
            Tint -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Tint()
            SkinTone -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.SkinTone()
            BlueTone -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.BlueTone()
            Hue -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Hue()
            BlackWhite -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.BlackWhite()
            Posterize -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Posterize()
            Edges -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Edges()
            Borders -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Borders()
            Pop -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Pop()
            Sharpen -> Sharpness()
            Denoise -> com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments.varfilter.Denoise()
        }

    val icon: ImageVector get() =
        when (this) {
            Brightness -> Icons.Outlined.Brightness5
            Contrast -> Icons.Outlined.Contrast
            Saturation -> Icons.Outlined.WaterDrop
            Rotate -> Icons.Outlined.Rotate90DegreesCcw
            Tone -> Icons.Outlined.Tonality
            BlackPoint -> Icons.Outlined.RadioButtonUnchecked
            WhitePoint -> Icons.Outlined.Circle
            Highlights -> Icons.Outlined.Layers
            Shadows -> Icons.Outlined.FilterDrama
            Vignette -> Icons.Outlined.Vignette
            Warmth -> Icons.Outlined.Thermostat
            Tint -> Icons.Outlined.Palette
            SkinTone -> Icons.Outlined.InvertColors
            BlueTone -> Icons.Outlined.Waves
            Hue -> Icons.Outlined.Gradient
            BlackWhite -> Icons.Outlined.FilterBAndW
            Posterize -> Icons.Outlined.Texture
            Edges -> Icons.Outlined.GridOn
            Borders -> Icons.Outlined.CropDin
            Pop -> Icons.Outlined.Contrast
            Sharpen -> Icons.Outlined.Details
            Denoise -> Icons.Outlined.Brightness4
        }
}
