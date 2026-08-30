package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the Configs "KDE" page is declared and sits immediately AFTER
 * "Update" (build.json::sections[config].pages → Sections), and that it
 * dispatches to the libs:kde-connect fragment via SectionPages.
 *
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class KdeConfigsPageTest {

    @Test fun kdePageFollowsUpdate() {
        val pages = Sections.byId("config")?.pages?.map { it.id } ?: emptyList()
        val update = pages.indexOf("update")
        val kde = pages.indexOf("kde")
        assertTrue("Configs must declare an 'update' page", update >= 0)
        assertTrue("Configs must declare a 'kde' page", kde >= 0)
        assertEquals("KDE must sit immediately after Update", update + 1, kde)
    }

    @Test fun kdePageDispatchesToFragment() {
        // Resolve the page factory the same way MainActivity does.
        val frag = SectionPages.pagesFor("config")
            .firstOrNull { it.id == "kde" }?.factory?.invoke()
        assertTrue(
            "config/kde must map to KdeConnectFragment",
            frag is com.diegonmarcos.superapp.kdeconnect.KdeConnectFragment,
        )
    }
}
