package com.diegonmarcos.superapp

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for Suite/Phone → Communications (build.json::sections[suite]
 * .phone_app_groups → UI_SUITE_PHONE_GROUPS_B64). Proves the requested change:
 * "Samsung Phone" (com.samsung.android.dialer) is replaced by Fossy
 * (org.fossify.phone), and LinkedIn is added to the Others folder.
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class SuitePhoneCommsTest {

    private fun comms(): org.json.JSONObject {
        val raw = String(Base64.decode(BuildConfig.UI_SUITE_PHONE_GROUPS_B64, Base64.DEFAULT))
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val g = arr.getJSONObject(i)
            if (g.optString("title") == "Communications") return g
        }
        throw AssertionError("Communications group not found in suite phone_app_groups")
    }

    private fun JSONArray.toList(): List<String> = (0 until length()).map { getString(it) }

    @Test fun fossyReplacesSamsungPhone() {
        val pkgs = comms().getJSONArray("packages").toList()
        assertTrue("Fossy (org.fossify.phone) must be in Communications", pkgs.contains("org.fossify.phone"))
        assertFalse("Samsung Phone must be removed", pkgs.contains("com.samsung.android.dialer"))
    }

    @Test fun linkedinInOthersFolder() {
        val folders = comms().getJSONArray("folders")
        var others: List<String>? = null
        for (i in 0 until folders.length()) {
            val f = folders.getJSONObject(i)
            if (f.optString("label") == "Others") others = f.getJSONArray("packages").toList()
        }
        assertNotNull("Others folder must exist", others)
        assertTrue("LinkedIn must be in the Others folder", others!!.contains("com.linkedin.android"))
    }
}
