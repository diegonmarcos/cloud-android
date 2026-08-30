/**
 * rememberMediaDateCaption sat in the domain model file beside
 * MediaDateCaption, which made a domain model reach for core.Settings — 1371
 * lines with 16 Compose imports — plus stringResource and R. The data class is
 * domain; reading a user preference and a string resource to build one is not.
 * This was the LAST edge pulling the UI layer into the cloud engine's closure.
 */
package com.diegonmarcos.mediacenter.feature_node.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberExifDateFormat
import com.diegonmarcos.mediacenter.core.util.getDate
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaDateCaption
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadata

@Composable
fun rememberMediaDateCaption(
    exifMetadata: MediaMetadata?,
    media: Media
): MediaDateCaption {
    val deviceInfo = remember(exifMetadata, media) { exifMetadata?.lensDescription }
    val defaultDesc = stringResource(R.string.image_add_description)
    val description = remember(exifMetadata, media) { exifMetadata?.imageDescription ?: defaultDesc }
    val currentDateFormat by rememberExifDateFormat()
    return remember(media, exifMetadata, currentDateFormat) {
        MediaDateCaption(
            date = media.definedTimestamp.getDate(currentDateFormat),
            deviceInfo = deviceInfo,
            description = description
        )
    }
}
