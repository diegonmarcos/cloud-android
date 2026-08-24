package com.diegonmarcos.superapp.devtools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM test for the credential parse (no Android deps).
 *
 * This is the one piece of the fleet auth that fails quietly in both directions:
 * too strict and every client gets a 401 that looks like a broken token, too
 * loose and a malformed header authenticates. The token comparison itself is
 * `MessageDigest.isEqual`, which needs no test of ours.
 */
class AppDebugServerAuthTest {

    @Test
    fun bearerOf_acceptsTheFormsRealClientsSend() {
        // curl -H "Authorization: Bearer abc"
        assertEquals("abc", AppDebugServer.bearerOf("Authorization: Bearer abc"))
        // HTTP header names are case-insensitive; some clients lowercase them.
        assertEquals("abc", AppDebugServer.bearerOf("authorization: bearer abc"))
        // Extra whitespace either side of the value.
        assertEquals("abc", AppDebugServer.bearerOf("Authorization:   Bearer   abc  "))
    }

    @Test
    fun bearerOf_rejectsAnythingElse() {
        assertNull(AppDebugServer.bearerOf("Accept: */*"))
        assertNull(AppDebugServer.bearerOf("Authorization: Basic dXNlcjpwdw=="))
        // Present but empty must be null, not "", or an app whose fleet token
        // failed to load would authenticate every empty-credential request.
        assertNull(AppDebugServer.bearerOf("Authorization: Bearer"))
        assertNull(AppDebugServer.bearerOf("Authorization: Bearer   "))
    }
}
