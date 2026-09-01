package com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments

import android.graphics.Bitmap
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.Adjustment
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.TileBehavior
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rotate

data class Rotate90CW(
    val angle: Float
) : Adjustment {

    override fun apply(bitmap: Bitmap): Bitmap {
        return bitmap.rotate(angle)
    }

    override val tileBehavior: TileBehavior get() = TileBehavior.Geometry

}