/**
 * Address formatting, split out of feature_node.presentation.util.Geolocation.
 * It is a plain android.location.Address extension with no Compose in it, but
 * living beside @Composable rememberGeocoder meant domain/model/MediaMetadata
 * could not compile without the presentation layer.
 */
package com.diegonmarcos.mediacenter.core.util

import android.location.Address
import androidx.core.text.isDigitsOnly

val Address.formattedAddress: String get() {
    var address = ""
    if (!featureName.isNullOrBlank() && !featureName.isDigitsOnly()) address += featureName
    else if (!subLocality.isNullOrBlank()) address += subLocality
    if (!locality.isNullOrBlank()) {
        address += if (address.isEmpty()) locality
        else ", $locality"
    }
    if (!countryName.isNullOrBlank()) {
        address += if (address.isEmpty()) countryName
        else ", $countryName"
    }

    return address
}

val Address.locationTag: String get() =
    if (!featureName.isNullOrBlank() && !featureName.isDigitsOnly()) featureName
    else if (!subLocality.isNullOrBlank()) subLocality
    else locality
