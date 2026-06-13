package com.diegonmarcos.superapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.floatingnav.MediaProxy
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test for the media-session proxy. Without notification-listener access
 * (the instrumentation default), MediaSessionManager.getActiveSessions throws
 * SecurityException — MediaProxy must swallow it (no active controller, no
 * crash) so the rest of the floating-nav service keeps running.
 *
 * Run via the engine: ./build.sh instrument
 */
@RunWith(AndroidJUnit4::class)
class MediaProxyTest {

    private val proxy = MediaProxy(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test fun noControllerWithoutNotificationAccess() {
        assertNull(proxy.activeController())
    }

    @Test fun refreshAndTransportNeverThrow() {
        proxy.refresh()                 // no active session → cancels, no crash
        proxy.transport("media:next")   // no controller → no-op
        proxy.cancel()
    }
}
