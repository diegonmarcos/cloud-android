/**
 * rememberLocationData lived in the domain model file beside LocationData,
 * which made a domain model depend on Compose and on presentation.util's
 * geocoder helpers. The data class is domain; resolving an address for the UI
 * is not. Split so the domain half can compile without a UI toolkit.
 */
package com.diegonmarcos.mediacenter.feature_node.presentation.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.diegonmarcos.mediacenter.feature_node.domain.model.LocationData
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.diegonmarcos.mediacenter.core.util.formattedAddress

@Composable
fun rememberLocationData(
    exifMetadata: MediaMetadata?
): LocationData? {
    val geocoder = rememberGeocoder()
    var locationName by remember { mutableStateOf(exifMetadata?.formattedCords) }
    LaunchedEffect(geocoder, exifMetadata) {
        withContext(Dispatchers.IO) {
            if (exifMetadata?.gpsLongitude != null && exifMetadata.gpsLatitude != null) {
                geocoder?.getLocation(
                    exifMetadata.gpsLatitude,
                    exifMetadata.gpsLongitude
                ) { address ->
                    address?.let {
                        val addressName = it.formattedAddress
                        if (addressName.isNotEmpty()) {
                            locationName = addressName
                        }
                    }
                }
            }
        }
    }
    return remember(exifMetadata, locationName) {
        exifMetadata?.let {
            if (it.gpsLatitude == null || it.gpsLongitude == null) return@let null
            LocationData(
                latitude = it.gpsLatitude,
                longitude = it.gpsLongitude,
                location = locationName ?: "Unknown"
            )
        }
    }
}