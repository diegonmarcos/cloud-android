package com.diegonmarcos.superapp.media

/** The three top-level tabs of the panel (row 1). EMOJI is owned by the
 *  keyboard's own emoji pager; STICKER + GIF are owned by [MediaPanelView]. */
enum class MediaType { EMOJI, STICKER, GIF }

/** One entry in the second-row tab strip (row 2). For GIF these are the
 *  data-driven categories from build.json; for STICKER they are the installed
 *  pack names. [query] is what the GIF provider searches for ("" = trending). */
data class MediaCategory(val id: String, val label: String, val query: String = id)

/**
 * A pickable item. [previewUrl] is a small thumbnail shown in the grid;
 * [sourceUrl] is the full asset that gets downloaded + committed to the app.
 * Both may be remote (http/https) or local (content://). [mime] is the MIME
 * type committed to the target field (image/gif, image/webp, …).
 */
data class MediaItem(
    val previewUrl: String,
    val sourceUrl: String,
    val mime: String,
    val description: String = ""
)
