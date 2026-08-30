package com.diegonmarcos.mediacenter.feature_node.domain.util

import com.diegonmarcos.mediacenter.feature_node.domain.model.UIEvent
import kotlinx.coroutines.flow.Flow

interface EventHandler {

    val updaterFlow: Flow<UIEvent>
    var navigateAction: (String) -> Unit
    var toggleNavigationBarAction: (Boolean) -> Unit
    var navigateUpAction: () -> Unit
    var setFollowThemeAction: (Boolean) -> Unit

    fun pushEvent(event: UIEvent)

}