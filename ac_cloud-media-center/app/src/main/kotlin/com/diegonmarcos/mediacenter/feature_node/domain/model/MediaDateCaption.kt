package com.diegonmarcos.mediacenter.feature_node.domain.model

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.mediacenter.R
import com.diegonmarcos.mediacenter.core.Settings.Misc.rememberExifDateFormat
import com.diegonmarcos.mediacenter.core.util.getDate

@Stable
data class MediaDateCaption(
    val date: String,
    val deviceInfo: String? = null,
    val description: String
)

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
