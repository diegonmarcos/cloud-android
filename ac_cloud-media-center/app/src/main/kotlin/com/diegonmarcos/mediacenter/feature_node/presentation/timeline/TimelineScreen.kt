/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.timeline

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope

import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.GridPinchZoomLayout
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.rememberGridPinchZoomState
import com.diegonmarcos.mediacenter.core.Constants.Animation.enterAnimation
import com.diegonmarcos.mediacenter.core.Constants.Animation.exitAnimation
import com.diegonmarcos.mediacenter.core.Constants.cellsList
import com.diegonmarcos.mediacenter.core.LocalEventHandler
import com.diegonmarcos.mediacenter.core.LocalMediaDistributor
import com.diegonmarcos.mediacenter.core.LocalMediaSelector
import com.diegonmarcos.mediacenter.core.ScrollToTopHandler
import com.diegonmarcos.mediacenter.core.animateOrJumpToTop
import com.diegonmarcos.mediacenter.BuildConfig
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.Settings
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberAutoHideSearchBar
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberGridSize
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberLastSeenVersion
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberMosaicGridSize
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberShowFilterButton
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberTimelineGroupByDate
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberTimelineGroupMethod
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberTimelineLayoutType
import com.diegonmarcos.mediacenter.core.navigate
import com.diegonmarcos.mediacenter.core.presentation.components.EmptyMedia
import com.diegonmarcos.mediacenter.core.presentation.components.SelectionSheet
import com.diegonmarcos.mediacenter.core.toggleNavigationBar
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadataState
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaTypeFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.isHeaderKey
import com.diegonmarcos.mediacenter.feature_node.domain.model.isIgnoredKey
import com.diegonmarcos.mediacenter.feature_node.domain.util.isFavorite
import com.diegonmarcos.mediacenter.feature_node.domain.util.isImage
import com.diegonmarcos.mediacenter.feature_node.domain.util.isVideo
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.MediaGridView
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.MosaicMediaGrid
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.MosaicPinchZoomLayout
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.StickyHeaderGrid
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.TimelineScroller
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.rememberMosaicMonthSegments
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.rememberMosaicPinchZoomState
import com.diegonmarcos.mediacenter.feature_node.presentation.common.components.rememberStickyHeaderItem
import com.diegonmarcos.mediacenter.feature_node.presentation.help.components.WhatsNewHeroCard
import com.diegonmarcos.mediacenter.feature_node.presentation.mediaview.rememberedDerivedState
import com.diegonmarcos.mediacenter.feature_node.presentation.search.MainSearchBar
import com.diegonmarcos.mediacenter.feature_node.presentation.storycards.StoryCardsViewModel
import com.diegonmarcos.mediacenter.feature_node.presentation.storycards.components.StoryCardsRow
import com.diegonmarcos.mediacenter.feature_node.presentation.timeline.components.TimelineFilterSheet
import com.diegonmarcos.mediacenter.feature_node.presentation.timeline.components.TimelineNavActions
import com.diegonmarcos.mediacenter.feature_node.presentation.util.LocalHazeState
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberAppBottomSheetState
import com.diegonmarcos.mediacenter.feature_node.presentation.util.rememberBottomBarInset
import com.diegonmarcos.mediacenter.feature_node.presentation.util.roundSpToPx
import com.diegonmarcos.mediacenter.feature_node.presentation.util.selectedMedia
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class
)
@Composable
fun TimelineScreen(
    paddingValues: PaddingValues,
    isScrolling: MutableState<Boolean>,
    mediaState: State<MediaState<Media.UriMedia>>,
    metadataState: State<MediaMetadataState>,
    sharedTransitionScope: SharedTransitionScope,
    animatedContentScope: AnimatedContentScope,
) {
    val eventHandler = LocalEventHandler.current
    val distributor = LocalMediaDistributor.current
    val isRefreshing by distributor.isRefreshing.collectAsStateWithLifecycle()
    val refreshScope = rememberCoroutineScope()

    // Filter state
    var timelineFilter by remember { mutableStateOf(TimelineFilter()) }
    val filterSheetState = rememberAppBottomSheetState()

    val albumsState = distributor.albumsFlow.collectAsStateWithLifecycle()
    val availableAlbums by rememberedDerivedState(albumsState.value) {
        albumsState.value.albums.sortedBy { it.label }
    }

    val availableYears by rememberedDerivedState(mediaState.value) {
        val cal = java.util.Calendar.getInstance()
        mediaState.value.media.mapTo(mutableSetOf()) { media ->
            cal.timeInMillis = media.definedTimestamp * 1000L
            cal.get(java.util.Calendar.YEAR)
        }.sortedDescending()
    }

    val filteredMediaState: State<MediaState<Media.UriMedia>> = remember(mediaState, timelineFilter) {
        derivedStateOf {
            val state = mediaState.value
            if (!timelineFilter.isActive) return@derivedStateOf state

            val filtered = state.media.filter { media ->
                val typeMatch = when (timelineFilter.mediaType) {
                    MediaTypeFilter.ALL -> true
                    MediaTypeFilter.PHOTOS -> media.isImage
                    MediaTypeFilter.VIDEOS -> media.isVideo
                }
                val favMatch = if (timelineFilter.favoritesOnly) media.isFavorite else true
                val yearMatch = if (timelineFilter.selectedYears.isNotEmpty()) {
                    val cal = java.util.Calendar.getInstance()
                    cal.timeInMillis = media.definedTimestamp * 1000L
                    cal.get(java.util.Calendar.YEAR) in timelineFilter.selectedYears
                } else true
                val albumMatch = if (timelineFilter.selectedAlbumIds.isNotEmpty()) {
                    media.albumID in timelineFilter.selectedAlbumIds
                } else true
                typeMatch && favMatch && yearMatch && albumMatch
            }
            val filteredIds = filtered.mapTo(HashSet(filtered.size)) { it.id }
            state.copy(
                media = filtered,
                pagerMedia = state.pagerMedia.filter { it.id in filteredIds },
                mappedMedia = state.mappedMedia.filter { item ->
                    when (item) {
                        is com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem.MediaViewItem -> item.media.id in filteredIds
                        is com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem.Header -> item.data.any { it in filteredIds }
                    }
                },
                mappedMediaWithMonthly = state.mappedMediaWithMonthly.filter { item ->
                    when (item) {
                        is com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem.MediaViewItem -> item.media.id in filteredIds
                        is com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem.Header -> item.data.any { it in filteredIds }
                    }
                },
                mappedMediaWithYearly = state.mappedMediaWithYearly.filter { item ->
                    when (item) {
                        is com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem.MediaViewItem -> item.media.id in filteredIds
                        is com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem.Header -> item.data.any { it in filteredIds }
                    }
                },
                headers = state.headers.filter { header -> header.data.any { it in filteredIds } }
            )
        }
    }
    var lastSeenVersion by rememberLastSeenVersion()
    val showWhatsNew = remember(lastSeenVersion) { lastSeenVersion != BuildConfig.VERSION_NAME }

    // Story Cards
    val storyCardsViewModel = hiltViewModel<StoryCardsViewModel>()
    val storyCards by storyCardsViewModel.allCards.collectAsStateWithLifecycle()

    val hasStoryCards = storyCards?.isNotEmpty() == true
    val aboveGridContent: @Composable (() -> Unit)? = if (showWhatsNew || hasStoryCards) {
        {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (showWhatsNew) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        WhatsNewHeroCard(
                            versionName = BuildConfig.VERSION_NAME,
                            onClick = {
                                lastSeenVersion = BuildConfig.VERSION_NAME
                                eventHandler.navigate(Screen.WhatsNewScreen())
                            },
                            onDismiss = {
                                lastSeenVersion = BuildConfig.VERSION_NAME
                            }
                        )
                    }
                }
                if (hasStoryCards) {
                    StoryCardsRow(
                        cards = storyCards.orEmpty(),
                        onCardClick = { _, card ->
                            eventHandler.navigate(Screen.StoryViewerScreen.cardId(card.id))
                        },
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                        contentPadding = PaddingValues(horizontal = 32.dp)
                    )
                }
            }
        }
    } else null
    val selector = LocalMediaSelector.current
    val selectionState = selector.isSelectionActive.collectAsStateWithLifecycle()
    val selectedMedia = selector.selectedMedia.collectAsStateWithLifecycle()

    LaunchedEffect(selectionState.value) {
        eventHandler.toggleNavigationBar(!selectionState.value)
    }

    Box(
        modifier = Modifier
            .padding(
                start = paddingValues.calculateStartPadding(LocalLayoutDirection.current),
                end = paddingValues.calculateEndPadding(LocalLayoutDirection.current)
            )
    ) {
        Scaffold(
            topBar = {
                val showFilterButton by rememberShowFilterButton()
                MainSearchBar(
                    isScrolling = isScrolling,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    menuItems = { TimelineNavActions() },
                    searchBarTrailingIcon = if (showFilterButton) {
                        {
                            Box {
                                IconButton(
                                    onClick = {
                                        refreshScope.launch { filterSheetState.show() }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.FilterList,
                                        contentDescription = stringResource(R.string.filter),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (timelineFilter.isActive) {
                                    Badge(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(4.dp),
                                        containerColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                    } else null,
                )
            }
        ) { it ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { refreshScope.launch { distributor.invalidate() } },
            ) {
                val bottomBarInset = rememberBottomBarInset(paddingValues)
                TimelineMediaContent(
                    mediaState = filteredMediaState,
                    metadataState = metadataState,
                    scaffoldPadding = it,
                    bottomPadding = bottomBarInset + 128.dp,
                    screenTopPadding = paddingValues.calculateTopPadding(),
                    showSearchBar = true,
                    aboveGridContent = aboveGridContent,
                    isScrolling = isScrolling,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedContentScope = animatedContentScope,
                    onMediaClick = {
                        eventHandler.navigate(Screen.MediaViewScreen.idAndAlbum(it.id, -1L))
                    },
                    emptyContent = { EmptyMedia() },
                    scrollToTopRoute = Screen.TimelineScreen.route,
                )
            }
        }
        val selectedMediaList by selectedMedia(
            media = filteredMediaState.value.media,
            selectedSet = selectedMedia
        )
        SelectionSheet(
            modifier = Modifier.align(Alignment.BottomEnd),
            allMedia = filteredMediaState.value,
            selectedMedia = selectedMediaList
        )
    }

    TimelineFilterSheet(
        sheetState = filterSheetState,
        currentFilter = timelineFilter,
        availableYears = availableYears,
        availableAlbums = availableAlbums,
        onApply = { newFilter -> timelineFilter = newFilter }
    )
}