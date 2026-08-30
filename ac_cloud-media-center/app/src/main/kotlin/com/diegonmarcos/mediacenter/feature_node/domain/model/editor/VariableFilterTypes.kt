/**
 * The adjustment identifiers, extracted from
 * presentation/edit/adjustments/varfilter. EditorDestination.AdjustDetail
 * carries one, so the enum is domain — but its createFilter() returns
 * presentation VariableFilter types, which is why only the CONSTANTS move and
 * createFilter stays behind as an extension beside the filters it builds.
 */
package com.diegonmarcos.mediacenter.feature_node.domain.model.editor

import androidx.annotation.Keep
import kotlinx.serialization.Serializable

// @Keep and @Serializable came with the enum: EditorDestination.AdjustDetail
// is serialized for navigation, so losing @Serializable would break the nav
// argument silently at runtime rather than at compile time.
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
    Pop, Sharpen, Denoise
}
