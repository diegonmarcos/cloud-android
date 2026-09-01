package com.diegonmarcos.superapp

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the Configs/About → Permissions "Default phone app" +
 * "Spam filter (call screening)" Special-access rows.
 *
 * Proves the data path that has no other guard:
 *   build.json::ui.permissions.roles[]  →  app/build.gradle
 *   UI_PERMISSIONS_ROLES_B64  →  BuildConfig  →  DevControlFragment
 *   .parsePermissionRoles() / .specialAccessRole().
 *
 * The single-holder roles are held by the Cloud-Comms phone fork, never by
 * this launcher — so the only invariant we can assert at build time is that
 * the declared roles use the literal android.app.role.* constants and name a
 * Cloud-Comms / Fossify expected holder (a typo in the role string or a
 * dropped holder would otherwise dead-row at runtime with no signal).
 *
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class PermissionRolesTest {

    private fun roles(): JSONArray {
        val raw = String(Base64.decode(BuildConfig.UI_PERMISSIONS_ROLES_B64, Base64.DEFAULT))
        return JSONArray(raw)
    }

    @Test fun bothRolesDeclared() {
        val byRole = (0 until roles().length())
            .map { roles().getJSONObject(it) }
            .associateBy { it.getString("role") }
        assertTrue(
            "Default phone app must be declared as android.app.role.DIALER",
            byRole.containsKey("android.app.role.DIALER"),
        )
        assertTrue(
            "Spam filter must be declared as android.app.role.CALL_SCREENING",
            byRole.containsKey("android.app.role.CALL_SCREENING"),
        )
    }

    @Test fun everyRoleHasLabelAndCloudCommsHolder() {
        val arr = roles()
        assertTrue("expected at least the Phone + Spam roles", arr.length() >= 2)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            assertTrue("role[$i] must have a non-blank label", o.getString("label").isNotBlank())
            assertTrue("role[$i] must use an android.app.role.* constant",
                o.getString("role").startsWith("android.app.role."))
            val holders = o.getJSONArray("expected_holders")
            val list = (0 until holders.length()).map { holders.getString(it) }
            assertTrue(
                "role[$i] (${o.getString("role")}) must name the Cloud-Comms dialer fork as an expected holder",
                list.any { it.startsWith("com.diegonmarcos.comms") },
            )
        }
    }
}
