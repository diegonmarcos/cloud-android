package com.diegonmarcos.mediacenter.feature_node.presentation.actions

import androidx.lifecycle.ViewModel
import com.diegonmarcos.mediacenter.core.MediaDistributor
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ActionsViewModel @Inject constructor(
    private val distributor: MediaDistributor
): ViewModel() {

}