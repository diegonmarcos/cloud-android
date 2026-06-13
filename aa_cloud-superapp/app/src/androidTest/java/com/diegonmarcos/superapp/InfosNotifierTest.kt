package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.floatingnav.InfosNotifier
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Contract test for the grouped Infos notification
 * (build.json::ui.notification_infos → UI_NOTIFICATION_INFOS_B64 →
 * InfosNotifier). Proves the sample groups + messages decode, and that posting
 * never throws (degrades to no-op without POST_NOTIFICATIONS).
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class InfosNotifierTest {

    private val notifier = InfosNotifier(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun groupsAndMessagesParse() {
        val groups = notifier.parse()
        assertTrue("expected at least the sample groups", groups.size >= 2)
        assertTrue("each group must carry messages", groups.all { it.title.isNotBlank() && it.messages.isNotEmpty() })
    }

    @Test fun refreshAndCancelNeverThrow() {
        notifier.refresh()
        notifier.cancel()
    }
}
