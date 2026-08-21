package com.diegonmarcos.superapp.adbdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two things here that can silently break: the settings keys/values
 * (a typo makes the toggle a no-op that still reports success) and the
 * "partly re-armed" reading of [PackageVerifier.State].
 */
class PackageVerifierTest {

    @Test fun offCommandsWriteTheDecliningValues() {
        assertEquals(listOf(
            "settings put global package_verifier_user_consent -1",
            "settings put global package_verifier_enable 0",
            "settings put global verifier_verify_adb_installs 0",
        ), PackageVerifier.commands(scan = false))
    }

    @Test fun onCommandsRestoreTheStockDefaults() {
        assertEquals(listOf(
            "settings put global package_verifier_user_consent 1",
            "settings put global package_verifier_enable 1",
            "settings put global verifier_verify_adb_installs 1",
        ), PackageVerifier.commands(scan = true))
    }

    @Test fun scanningIsOffOnlyWhenAllThreeAreDown() {
        assertFalse(PackageVerifier.State(-1, 0, 0).on)
        // GMS re-arming any single value must read as ON, not as a stale green label.
        assertTrue(PackageVerifier.State(1, 0, 0).on)
        assertTrue(PackageVerifier.State(-1, 1, 0).on)
        assertTrue(PackageVerifier.State(-1, 0, 1).on)
        assertTrue(PackageVerifier.State(1, 1, 1).on)
    }
}
