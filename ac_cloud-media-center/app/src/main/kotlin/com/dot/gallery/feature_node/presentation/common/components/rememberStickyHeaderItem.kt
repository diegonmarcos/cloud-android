package com.dot.gallery.feature_node.presentation.common.components

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.dot.gallery.R
import com.dot.gallery.feature_node.domain.model.Media
import com.dot.gallery.feature_node.domain.model.MediaItem
import com.dot.gallery.feature_node.domain.model.MediaState
import com.dot.gallery.feature_node.domain.model.isHeaderKey
import com.dot.gallery.feature_node.domain.model.isSmallHeaderKey
import com.dot.gallery.feature_node.presentation.mediaview.rememberedDerivedState

@Composable
fun <T : Media> rememberStickyHeaderItem(
    gridState: LazyGridState,
    mediaState: State<MediaState<T>>
): State<String?> {
    val stringToday = stringResource(id = R.string.header_today)
    val stringYesterday = stringResource(id = R.string.header_yesterday)

    var lastStickyHeaderItem by rememberSaveable { mutableStateOf<String?>(null) }

    /**
     * Remember last known header item
     */
    val stickyHeaderLastItem = rememberedDerivedState(gridState) {
        val visibleItems = gridState.layoutInfo.visibleItemsInfo
        val headers = mediaState.value.headers
        val firstItem = visibleItems.firstOrNull()
        val firstVisibleSmallHeaderKey = visibleItems.firstOrNull { it.key.isSmallHeaderKey }?.key?.toString()

        val item = firstVisibleSmallHeaderKey?.let { key -> headers.firstOrNull { it.key == key } }
        return@rememberedDerivedState if (item != null) {
            val newItem = item.text
                .replace("Today", stringToday)
                .replace("Yesterday", stringYesterday)
            val newIndex = (headers.indexOf(item) - 1).coerceAtLeast(0)
            val previousHeader = headers[newIndex].text
                .replace("Today", stringToday)
                .replace("Yesterday", stringYesterday)
            val newHeader = if (firstItem != null && !firstItem.key.isHeaderKey) {
                previousHeader
            } else {
                newItem
            }
            lastStickyHeaderItem = newHeader
            newHeader
        } else lastStickyHeaderItem
    }
    return stickyHeaderLastItem
}