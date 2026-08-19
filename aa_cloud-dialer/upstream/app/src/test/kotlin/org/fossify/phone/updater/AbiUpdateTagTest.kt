package org.fossify.phone.updater

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cloud Dialer: tester for the self-contained updater's ABI→GHCR-tag resolution
 * (patch 0005). Pure JVM — no Android/BuildConfig — so it runs under `gradle test`.
 */
class AbiUpdateTagTest {

    @Test
    fun parseMap_parsesWellFormedEntries() {
        val map = AbiUpdateTag.parseMap("x86_64=latest-x86_64;arm64-v8a=latest")
        assertEquals(listOf("x86_64" to "latest-x86_64", "arm64-v8a" to "latest"), map)
    }

    @Test
    fun parseMap_dropsMalformedOrEmptyEntries() {
        val map = AbiUpdateTag.parseMap(";x86_64=;=latest;arm64-v8a=latest;junk")
        assertEquals(listOf("arm64-v8a" to "latest"), map)
    }

    @Test
    fun resolve_firstNativeAbiWins_translatedArmNeverBeatsX86() {
        // x86_64 device that also lists a translated arm64: native x86_64 must win.
        val map = AbiUpdateTag.parseMap("arm64-v8a=latest;x86_64=latest-x86_64")
        val tag = AbiUpdateTag.resolve(arrayOf("x86_64", "arm64-v8a"), map, "latest")
        assertEquals("latest-x86_64", tag)
    }

    @Test
    fun resolve_fallsBackWhenNoAbiMatches() {
        val map = AbiUpdateTag.parseMap("x86_64=latest-x86_64")
        val tag = AbiUpdateTag.resolve(arrayOf("arm64-v8a"), map, "latest")
        assertEquals("latest", tag)
    }
}
