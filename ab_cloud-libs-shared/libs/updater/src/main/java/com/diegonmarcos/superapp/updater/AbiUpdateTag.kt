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

    /**
     * Same rule, applied to any per-ABI lookup table rather than the baked tag
     * string — used by [Fleet.App.abiReleaseUrl] to pick a GitHub Release asset.
     *
     * This exists so there is exactly ONE ABI selection rule in the updater.
     * The GHCR path was already ABI-aware through [current], but the Release
     * path used a single flat asset name (always the arm64 one), so an x86_64
     * device that fell back from GHCR to the release download silently fetched
     * an arm64 APK and failed with INSTALL_FAILED_NO_MATCHING_ABIS — or 404ed.
     * One ABI dimension was handled and the other was not.
     *
     * Map iteration order is irrelevant: the device ABI list is the outer loop,
     * exactly as in [resolve], so native ABIs still beat translated ones.
     */
    fun resolveFrom(deviceAbis: Array<String>, map: Map<String, String>, fallback: String): String {
        for (abi in deviceAbis) {
            map[abi]?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return fallback
    }

    /** Live per-ABI lookup for the running device. */
    fun currentFrom(map: Map<String, String>, fallback: String): String =
        resolveFrom(Build.SUPPORTED_ABIS, map, fallback)

    /** Live resolution for the running device using baked BuildConfig. */
    fun current(): String = resolve(
        Build.SUPPORTED_ABIS,
        parseMap(BuildConfig.AUTO_UPDATE_TAG_MAP),
        BuildConfig.AUTO_UPDATE_TAG,
    )
}
