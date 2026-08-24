package com.diegonmarcos.superapp.updater

import android.os.Build

/**
 * Resolves which GHCR tag the in-app updater should pull for THIS device's
 * ABI, so an x86_64 Waydroid install updates from `latest-x86_64` while an
 * arm64 phone updates from `latest`.
 *
 * The map is baked into BuildConfig.AUTO_UPDATE_TAG_MAP from
 * build.json::release.variants[].supported_abis → update_tag (format
 * "abi=tag;abi=tag;…"). We walk [Build.SUPPORTED_ABIS] in order and return
 * the first tag whose ABI is in the map — native ABIs precede libhoudini /
 * libndk-translated ones in SUPPORTED_ABIS, so a translated arm64 entry on an
 * x86_64 device never wins over the real x86_64 entry. Falls back to
 * AUTO_UPDATE_TAG (arm64 `latest`) when nothing matches.
 */
object AbiUpdateTag {

    /** Parse the baked "abi=tag;…" string into an ordered list of pairs. */
    fun parseMap(raw: String): List<Pair<String, String>> =
        raw.split(';')
            .mapNotNull { entry ->
                val kv = entry.split('=', limit = 2)
                if (kv.size == 2 && kv[0].isNotBlank() && kv[1].isNotBlank()) kv[0] to kv[1] else null
            }

    /** Resolve the tag for the given device ABIs against the given map. */
    fun resolve(deviceAbis: Array<String>, map: List<Pair<String, String>>, fallback: String): String {
        for (abi in deviceAbis) {
            map.firstOrNull { it.first == abi }?.let { return it.second }
        }
        return fallback
    }

    /** Live resolution for the running device using baked BuildConfig. */
    fun current(): String = resolve(
        Build.SUPPORTED_ABIS,
        parseMap(BuildConfig.AUTO_UPDATE_TAG_MAP),
        BuildConfig.AUTO_UPDATE_TAG,
    )
}
