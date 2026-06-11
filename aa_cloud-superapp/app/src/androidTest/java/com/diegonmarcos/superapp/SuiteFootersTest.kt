package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the Suite "More" footers
 * (build.json::sections[suite].section_footer / phone_footer →
 * Sections.Section.sectionFooter / phoneFooter).
 *
 * Proves the footers parse and point at the Home-Apps drawer actions the
 * dispatch handlers (MainActivity.dispatchHomeAction) know about — so the
 * centered "More" tile on Suite/Cloud (below Tools Dashboards) and Suite/Phone
 * (below Configs) actually navigate instead of dead-tapping.
 *
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class SuiteFootersTest {

    @Test fun suiteCloudFooter_opensHomeAppsCloud() {
        val footer = Sections.byId("suite")?.sectionFooter
        assertNotNull("suite.section_footer must be declared", footer)
        assertEquals("More", footer!!.label)
        // Cloud tab is the drawer default → plain open_home_apps.
        assertEquals("action:open_home_apps", footer.target)
    }

    @Test fun suitePhoneFooter_opensHomeAppsPhone() {
        val footer = Sections.byId("suite")?.phoneFooter
        assertNotNull("suite.phone_footer must be declared", footer)
        assertEquals("More", footer!!.label)
        // Phone tab needs the dedicated action handled by dispatchHomeAction.
        assertEquals("action:open_home_apps_phone", footer.target)
    }
}
