package com.diegonmarcos.mediacenter.feature_node.presentation.edit.utils

import androidx.annotation.Keep
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.Adjustment
import com.diegonmarcos.mediacenter.feature_node.domain.model.editor.VariableFilterTypes

@Keep
fun List<Adjustment>.isApplied(variableFilterTypes: VariableFilterTypes): Boolean {
    return any { it.name.equals(variableFilterTypes.name, ignoreCase = true) }
}