package com.diegonmarcos.superapp

import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.floatingnav.FloatingNavConfig
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the 2-line Floating Top Nav menu
 * (build.json::ui.floating_nav → UI_FLOATING_NAV_B64 → FloatingNavConfig →
 * FloatingNavService).
 *
 * Line 1 = the three hub parents (constant everywhere). Line 2 = the matched
 * context's children — Cloud-IDE → Acode·Amaze; Cloud-Comms → Mail·Chat·
 * Messenger·Dialer; otherwise the default = Cloud-SuperApp's first-level pages.
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class FloatingNavConfigTest {

    private val cfg = FloatingNavConfig.get()

    @Test fun enabledWithLayout() {
        assertTrue("floating_nav must be enabled", cfg.enabled)
        assertTrue("menu sits below the top edge", cfg.topOffsetDp > 0)
        assertTrue("menu width is a sane % of screen", cfg.widthPct in 40..100)
    }

    @Test fun line1IsTheThreeHubs() {
        val byLabel = cfg.parents.associateBy { it.label }
        assertEquals("self", byLabel["Cloud SuperApp"]?.target)
        assertEquals("app:com.diegonmarcos.ide", byLabel["Cloud IDE"]?.target)
        assertEquals("cloud-ide", byLabel["Cloud IDE"]?.installApp)
        assertEquals("app:com.diegonmarcos.comms", byLabel["Cloud Comms"]?.target)
        assertEquals("cloud-comms", byLabel["Cloud Comms"]?.installApp)
    }

    @Test fun commsForegroundResolvesToCommsChildren() {
        val c = cfg.contextFor("com.diegonmarcos.comms.mail")!!
        assertEquals("comms", c.id)
        assertEquals(listOf("Mail", "Chat", "Messenger", "Dialer"), c.children.map { it.label })
        assertTrue("comms children launch packages",
            c.children.all { it.target.startsWith("app:com.diegonmarcos.comms.") })
    }

    @Test fun ideForegroundResolvesToIdeChildren() {
        val c = cfg.contextFor("com.diegonmarcos.ide.editor")!!
        assertEquals("ide", c.id)
        assertEquals(listOf("Acode", "Amaze File Man"), c.children.map { it.label })
    }

    @Test fun unknownForegroundFallsBackToSuperAppPages() {
        val c = cfg.contextFor("com.whatsapp")!!
        assertEquals("default", c.id)
        val byLabel = c.children.associateBy { it.label }
        assertEquals(listOf("Comms", "Infos", "Home-Apps", "Suite", "Labs", "Configs"),
            c.children.map { it.label })
        // SuperApp pages route through the shortcut_action dispatch, not app launch.
        assertEquals("section:communication", byLabel["Comms"]?.target)
        assertEquals("section:suite", byLabel["Suite"]?.target)
        assertEquals("section:config", byLabel["Configs"]?.target)
        assertEquals("action:open_home_apps", byLabel["Home-Apps"]?.target)
    }

    @Test fun parentInstallAppsResolveInExternalApps() {
        for (p in cfg.parents.filter { it.installApp.isNotBlank() }) {
            assertNotNull("parent '${p.label}' references unknown external app '${p.installApp}'",
                Sections.externalApp(p.installApp))
        }
    }

    @Test fun actionsLine_lightScreensaverCalcThenDndSaver() {
        val labels = cfg.actions.map { it.label }
        assertEquals(listOf("Light", "Screensaver", "Calc", "DND", "Saver"), labels)
        // Collapsed notification shows the first 3; expanded shows all 5.
        assertEquals(3, cfg.compactActionCount)
        assertTrue("expanded view shows more than the compact count",
            cfg.actions.size > cfg.compactActionCount)
        val byLabel = cfg.actions.associateBy { it.label }
        assertEquals("torch", byLabel["Light"]?.target)
        assertEquals("dnd", byLabel["DND"]?.target)
        assertEquals("powersave", byLabel["Saver"]?.target)
    }

    @Test fun toggleContract_startsOnlyWhenOverlayGranted() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        if (!Settings.canDrawOverlays(ctx)) {
            assertFalse(FloatingNavService.startIfPermitted(ctx))
            assertFalse(FloatingNavService.isRunning)
        }
    }
}
