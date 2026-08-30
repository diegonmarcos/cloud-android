/**
 * The adjustment identifiers, extracted from
 * presentation/edit/adjustments/varfilter. EditorDestination.AdjustDetail
 * carries one, so the enum is domain — but its createFilter() returns
 * presentation VariableFilter types, which is why only the CONSTANTS move and
 * createFilter stays behind as an extension beside the filters it builds.
 */
package com.diegonmarcos.mediacenter.feature_node.domain.model.editor

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
