package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.superapp.updater.AbiUpdateTag
import com.diegonmarcos.superapp.updater.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the ABI-aware updater tag resolution — the logic behind
 * the "x86 update failed with the URL" fix.
 *
 * Proves the chain build.json::release.variants[].supported_abis/update_tag →
 * libs/updater BuildConfig.AUTO_UPDATE_TAG_MAP → AbiUpdateTag.resolve, and the
 * critical ordering rule: on an x86_64 Waydroid whose Build.SUPPORTED_ABIS may
 * ALSO list a libhoudini-translated arm64-v8a, the NATIVE x86_64 entry (which
 * comes first) must win so the device pulls `latest-x86_64`, not `latest`.
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class AbiUpdateTagTest {

    private val map = AbiUpdateTag.parseMap(BuildConfig.AUTO_UPDATE_TAG_MAP)

    @Test fun bakedMapCoversBothAbis() {
        val m = map.toMap()
        assertEquals("latest", m["arm64-v8a"])
        assertEquals("latest-x86_64", m["x86_64"])
        assertEquals("latest-x86_64", m["x86"])
    }

    @Test fun x86DeviceResolvesToX86Tag() {
        // Native x86_64 precedes a translated arm64-v8a in SUPPORTED_ABIS.
        val tag = AbiUpdateTag.resolve(
            arrayOf("x86_64", "x86", "arm64-v8a", "armeabi-v7a"), map, "latest")
        assertEquals("latest-x86_64", tag)
    }

    @Test fun arm64DeviceResolvesToLatest() {
        val tag = AbiUpdateTag.resolve(arrayOf("arm64-v8a", "armeabi-v7a"), map, "latest")
        assertEquals("latest", tag)
    }

    @Test fun unknownAbiFallsBack() {
        val tag = AbiUpdateTag.resolve(arrayOf("riscv64"), map, "latest")
        assertEquals("latest", tag)
    }

    @Test fun malformedEntriesIgnored() {
        val parsed = AbiUpdateTag.parseMap("x86_64=latest-x86_64;;bogus;=tag;abi=")
        assertEquals(1, parsed.size)
        assertEquals("x86_64" to "latest-x86_64", parsed[0])
    }
}
