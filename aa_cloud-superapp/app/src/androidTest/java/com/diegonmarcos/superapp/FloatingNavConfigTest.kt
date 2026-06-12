package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diegonmarcos.superapp.floatingnav.FloatingNavConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the Floating Top Nav Bar (build.json::ui.floating_nav →
 * UI_FLOATING_NAV_B64 → BuildConfig → FloatingNavConfig → FloatingNavService).
 *
 * Proves the data path AND the context-resolution rule that FloatingNavService
 * relies on: a foreground package inside Cloud-Comms (hub or any fork) resolves
 * to the comms nav; inside Cloud-IDE to the ide nav; anything else (or nothing)
 * to the default Cloud-Comms | Cloud-IDE nav. A typo in match_prefixes or a
 * dropped link would otherwise render the wrong bar at runtime with no signal.
 *
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class FloatingNavConfigTest {

    private val cfg = FloatingNavConfig.get()

    @Test fun threeContextsDeclared() {
        assertTrue("floating_nav must be enabled", cfg.enabled)
        val ids = cfg.contexts.map { it.id }.toSet()
        assertTrue("expected default/comms/ide contexts, got $ids",
            ids.containsAll(listOf("default", "comms", "ide")))
    }

    @Test fun commsForegroundResolvesToCommsNav() {
        // A fork package (prefix match) must still land on the comms context.
        val c = cfg.contextFor("com.diegonmarcos.comms.mail")
        assertNotNull(c)
        assertEquals("comms", c!!.id)
        assertEquals("Cloud-Comms", c.homeLabel)
        val labels = c.links.map { it.label }
        assertTrue("comms nav must list Fossy | Element | Mattermost | FairMail, got $labels",
            labels.containsAll(listOf("Fossy", "Element", "Mattermost", "FairMail")))
    }

    @Test fun ideForegroundResolvesToIdeNav() {
        val c = cfg.contextFor("com.diegonmarcos.ide.editor")
        assertNotNull(c)
        assertEquals("ide", c!!.id)
        val labels = c.links.map { it.label }
        assertTrue("ide nav must list Acode + Amaze File, got $labels",
            labels.containsAll(listOf("Acode", "Amaze File")))
    }

    @Test fun unknownForegroundFallsBackToDefaultNav() {
        val c = cfg.contextFor("com.whatsapp")
        assertNotNull(c)
        assertEquals("default", c!!.id)
        assertEquals("Cloud-SuperApp", c.homeLabel)
        // Default links point at the installable hubs.
        val byLabel = c.links.associateBy { it.label }
        assertEquals("com.diegonmarcos.comms", byLabel["Cloud-Comms"]?.pkg)
        assertEquals("cloud-comms", byLabel["Cloud-Comms"]?.installApp)
        assertEquals("com.diegonmarcos.ide", byLabel["Cloud-IDE"]?.pkg)
    }

    @Test fun everyDefaultInstallAppResolvesInExternalApps() {
        // The default nav's install_app ids must be real ui.external_apps entries
        // (so a not-installed hub triggers the companion-APK install flow).
        val def = cfg.contextFor("com.example.unknown")!!
        for (link in def.links.filter { it.installApp.isNotBlank() }) {
            assertNotNull(
                "floating_nav default link '${link.label}' references unknown external app '${link.installApp}'",
                Sections.externalApp(link.installApp),
            )
        }
    }
}
