/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.feature_node.domain.model

/**
 * Tile size variants for the mosaic layout.
 *
 * TILE_2x2: 2 columns, aspect 1:1   → height = 2w. Companion: QuadTile (span 2)
 * TILE_2x1: 2 columns, aspect 2:1   → height = w.  Companion: 2 singles (span 1 each)
 * TILE_3x2: 3 columns, aspect 3:2   → height = 2w. Companion: PairTile (span 1)
 */
enum class BigTileSize(val colSpan: Int, val aspectRatio: Float) {
    TILE_2x2(2, 1f),
    TILE_2x1(2, 2f),
    TILE_3x2(3, 1.5f),
}

/**
 * Dynamically computed mosaic tile pattern for a given column count.
 *
 * Each pattern defines how a "block" fills exactly [totalColumns] columns:
 *  - A big tile taking [bigTileSpan] columns
 *  - A companion type filling the remaining columns
 *
 * Heights match because aspect ratios are coordinated:
 *  - QUAD: big aspect = bigSpan/quadSpan, quad container is 1:1 (2×2 grid)
 *  - PAIR: big aspect = bigSpan/2, pair is 0.5 (two stacked squares)
 *  - SINGLES: big aspect = bigSpan (one row tall), singles are 1:1
 */
data class MosaicTilePattern(
    val bigTileSpan: Int,
    val bigTileAspectRatio: Float,
    val companionType: CompanionType,
    val itemsConsumed: Int,
) {
    enum class CompanionType { QUAD, PAIR, SINGLES }
}

fun mosaicPatternsForColumns(columns: Int): List<MosaicTilePattern> {
    val patterns = mutableListOf<MosaicTilePattern>()

    // QUAD pattern: big(N-2) + quad(2). Needs columns >= 4, consumes 5 items.
    // big aspect = (N-2) / 2
    if (columns >= 4) {
        val bigSpan = columns - 2
        val bigAspect = bigSpan.toFloat() / 2f
        if (bigAspect <= 2.5f) {
            patterns.add(
                MosaicTilePattern(
                    bigTileSpan = bigSpan,
                    bigTileAspectRatio = bigAspect,
                    companionType = MosaicTilePattern.CompanionType.QUAD,
                    itemsConsumed = 5
                )
            )
        }
    }

    // PAIR pattern: big(N-1) + pair(1). Needs columns >= 3, consumes 3 items.
    // big aspect = (N-1) / 2
    if (columns >= 3) {
        val bigSpan = columns - 1
        val bigAspect = bigSpan.toFloat() / 2f
        if (bigAspect <= 2.5f) {
            patterns.add(
                MosaicTilePattern(
                    bigTileSpan = bigSpan,
                    bigTileAspectRatio = bigAspect,
                    companionType = MosaicTilePattern.CompanionType.PAIR,
                    itemsConsumed = 3
                )
            )
        }
    }

    // SINGLES pattern: big(2) + (N-2) singles. Needs columns >= 3, consumes 1+(N-2)=N-1 items.
    // big aspect = 2.0 (2 cols wide, 1 row)
    if (columns >= 3) {
        patterns.add(
            MosaicTilePattern(
                bigTileSpan = 2,
                bigTileAspectRatio = 2f,
                companionType = MosaicTilePattern.CompanionType.SINGLES,
                itemsConsumed = columns - 1
            )
        )
    }

    return patterns
}

sealed class MosaicDisplayItem<T : Media> {
    abstract val key: String

    data class HeaderItem<T : Media>(val header: MediaItem.Header<T>) : MosaicDisplayItem<T>() {
        override val key = header.key
    }

    data class BigTileItem<T : Media>(
        val mediaItem: MediaItem.MediaViewItem<T>,
        val tileSize: BigTileSize,
        val dynamicSpan: Int = tileSize.colSpan,
        val dynamicAspectRatio: Float = tileSize.aspectRatio,
    ) : MosaicDisplayItem<T>() {
        override val key = "mosaic_big_${dynamicSpan}_${dynamicAspectRatio}_${mediaItem.key}"
    }

    data class QuadTileItem<T : Media>(val mediaItems: List<MediaItem.MediaViewItem<T>>) : MosaicDisplayItem<T>() {
        override val key = "mosaic_quad_${mediaItems.first().key}"
    }

    data class PairTileItem<T : Media>(val mediaItems: List<MediaItem.MediaViewItem<T>>) : MosaicDisplayItem<T>() {
        override val key = "mosaic_pair_${mediaItems.first().key}"
    }

    data class SingleItem<T : Media>(val mediaItem: MediaItem.MediaViewItem<T>) : MosaicDisplayItem<T>() {
        override val key = mediaItem.key
    }
}
