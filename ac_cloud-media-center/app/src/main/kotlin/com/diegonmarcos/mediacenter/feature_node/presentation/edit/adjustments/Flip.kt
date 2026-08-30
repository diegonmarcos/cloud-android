package com.diegonmarcos.mediacenter.feature_node.presentation.edit.adjustments

import android.graphics.Bitmap
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.Adjustment
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.TileBehavior
import com.diegonmarcos.mediacenter.feature_node.presentation.util.flipHorizontally
import com.diegonmarcos.mediacenter.feature_node.presentation.util.flipVertically

data class Flip(
    val horizontal: Boolean,
) : Adjustment {

    override fun apply(bitmap: Bitmap): Bitmap {
        return if (horizontal) bitmap.flipHorizontally() else bitmap.flipVertically()
    }

    override val tileBehavior: TileBehavior get() = TileBehavior.Geometry

}