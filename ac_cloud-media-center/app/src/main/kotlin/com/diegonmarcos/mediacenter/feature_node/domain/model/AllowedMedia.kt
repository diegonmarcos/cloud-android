/**
 * Extracted from presentation/picker/PickerViewModel.kt. It is a query
 * predicate MediaRepository takes, so a domain type that happened to live in a
 * ViewModel file — which meant the repository interface could not be compiled
 * without the presentation layer.
 */
package com.diegonmarcos.mediacenter.feature_node.domain.model

enum class AllowedMedia {
    PHOTOS, VIDEOS, BOTH;

    override fun toString(): String {
        return when (this) {
            PHOTOS -> "image%"
            VIDEOS -> "video%"
            BOTH -> "%/%"
        }
    }

    fun toStringAny(): String {
        return when (this) {
            PHOTOS -> "image/*"
            VIDEOS -> "video/*"
            BOTH -> "*/*"
        }
    }
}
