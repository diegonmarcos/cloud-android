package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the companion external-apps feature
 * (build.json::ui.external_apps + `extapp:` tile targets).
 *
 * Proves, end to end, the data path that has no other guard:
 *   build.json  →  app/build.gradle EXTERNAL_APPS_B64  →  BuildConfig  →
 *   Sections.externalApps()  →  MainActivity.launchExternalApp resolution.
 *
 * The strong invariant (test #3) is the one that catches real regressions:
 * EVERY `extapp:<id>/<fork>` target declared anywhere in the section taxonomy
 * must resolve to a registered ExternalApp AND a known fork key — so a typo in
 * a tile target, or deleting an external_apps entry while a tile still points
 * at it, fails the build's instrumented suite instead of dead-tapping at
 * runtime.
 *
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class ExternalAppsTest {

    @Test fun cloudComms_registryResolves() {
        val app = Sections.externalApp("cloud-comms")
        assertNotNull("cloud-comms must be declared in ui.external_apps", app)
        app!!
        assertEquals("com.diegonmarcos.comms", app.hubPackage)
        assertEquals("com.diegonmarcos.comms", app.installPackage)
        assertTrue("install URL must be the Cloud-Comms-Hub.apk release asset",
            app.installApkUrl.endsWith("/Cloud-Comms-Hub.apk"))
        assertEquals("com.diegonmarcos.comms.mail", app.forks["mail"])
        assertEquals("com.diegonmarcos.comms.chat", app.forks["chat"])
        assertEquals("com.diegonmarcos.comms.matrix", app.forks["matrix"])
    }

    @Test fun cloudIde_registryResolves() {
        val app = Sections.externalApp("cloud-ide")
        assertNotNull("cloud-ide must be declared in ui.external_apps", app)
        app!!
        assertEquals("com.diegonmarcos.ide", app.hubPackage)
        assertEquals("com.diegonmarcos.ide", app.installPackage)
        assertTrue("install URL must be the Cloud-IDE-Hub.apk release asset",
            app.installApkUrl.endsWith("/Cloud-IDE-Hub.apk"))
        assertEquals("com.diegonmarcos.ide.files", app.forks["files"])
        assertEquals("com.diegonmarcos.ide.utils", app.forks["utils"])
        assertEquals("com.diegonmarcos.ide.editor", app.forks["editor"])
    }

    /** No dangling `extapp:` target anywhere in the section taxonomy. */
    @Test fun everyExtappTargetResolves() {
        // All tile targets across every aggregator surface: flat tiles_*,
        // and the themed tile_groups (Suite/Labs).
        val targets = Sections.all().flatMap { sec ->
            sec.tilesShared.map { it.target } +
                sec.tilesApps.map { it.target } +
                sec.tilesAdmin.map { it.target } +
                sec.tileGroups.flatMap { g -> g.tiles.map { it.target } }
        }
        val extapps = targets.filter { it.startsWith("extapp:") }
        assertTrue("expected at least the Comms + IDE extapp tiles", extapps.size >= 4)

        for (t in extapps) {
            val payload = t.removePrefix("extapp:")
            val parts = payload.split("/", limit = 2)
            val app = Sections.externalApp(parts[0])
            assertNotNull("extapp target '$t' references unknown app '${parts[0]}'", app)
            val forkKey = parts.getOrNull(1)
            if (!forkKey.isNullOrBlank()) {
                assertTrue(
                    "extapp target '$t' references unknown fork key '$forkKey' " +
                        "(known: ${app!!.forks.keys})",
                    app.forks.containsKey(forkKey),
                )
            }
        }
    }

    /** The three Comms tiles the user named map to the three comms forks. */
    @Test fun commsSection_tilesPointAtForks() {
        val comms = Sections.byId("communication")
        assertNotNull("communication section must exist", comms)
        val byTarget = comms!!.tilesShared.associateBy { it.target }
        assertTrue(byTarget.containsKey("extapp:cloud-comms/mail"))
        assertTrue(byTarget.containsKey("extapp:cloud-comms/chat"))
        assertTrue(byTarget.containsKey("extapp:cloud-comms/matrix"))
    }
}
