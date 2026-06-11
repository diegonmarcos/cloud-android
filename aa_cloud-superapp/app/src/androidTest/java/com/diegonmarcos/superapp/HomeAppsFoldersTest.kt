package com.diegonmarcos.superapp

import android.content.ComponentName
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home Apps / Phone tab: "No Categorie" sink + "New Apps" smart folder.
 * Run via the engine: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class HomeAppsFoldersTest {

    @Test fun noCategorieIsTheSink() {
        val folders = PhoneFolders.loadFromBuildConfig()
        val sinkId = PhoneFolders.sinkFolderId(folders)
        val sink = folders.firstOrNull { it.id == sinkId }
        assertNotNull("a sink folder must exist", sink)
        assertEquals("No Categorie", sink!!.label)
        assertTrue("sink folder must be flagged sink:true", sink.sink)
    }

    /** sink:true wins over declaration order even when an earlier folder also
     *  has empty keywords — the determinism the flag guarantees. */
    @Test fun sinkFlagBeatsOrder() {
        val folders = listOf(
            PhoneFolders.Folder("early", "00", "_Early", emptyList()),
            PhoneFolders.Folder("cat", "99", "No Categorie", emptyList(), pinned = true, sink = true),
        )
        assertEquals("cat", PhoneFolders.sinkFolderId(folders))
    }

    @Test fun newAppsSmartFolderIsDeclared() {
        val sf = PhoneSmartFolders.loadFromBuildConfig().firstOrNull { it.id == "new_apps" }
        assertNotNull("New Apps smart folder must be declared", sf)
        assertEquals("recently_installed", sf!!.rule.type)
        assertEquals(12, sf.rule.limit)
    }

    @Test fun newAppsSelectsNewestFirstCappedAtLimit() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val sf = PhoneSmartFolders.SmartFolder(
            "new_apps", "New Apps",
            PhoneSmartFolders.Rule("recently_installed", emptyList(), limit = 2),
        )
        val me = Process.myUserHandle()
        fun app(p: String, t: Long) = PhoneApp(p, ComponentName(p, "$p.Main"), p, null, me, t)
        val apps = listOf(app("a", 100), app("b", 300), app("c", 200))
        val picked = sf.select(ctx, apps).map { it.packageName }
        assertEquals("newest two, newest first", listOf("b", "c"), picked)
    }
}
