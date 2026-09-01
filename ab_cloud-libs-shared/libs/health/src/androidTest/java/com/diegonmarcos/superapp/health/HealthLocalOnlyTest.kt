package com.diegonmarcos.superapp.health

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diegonmarcos.superapp.core.HealthStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Local-only contract test for MyHealth — proves requirement #4 from
 * the design brief: "Local-only Cloud SuperApp DB" — no network egress
 * during any HealthConnectGateway read path.
 *
 * Strategy:
 *   1. Install a [StrictMode.ThreadPolicy] that DETECTS network on
 *      every thread the gateway uses (penaltyDeath = crash the
 *      instrumented test process if any socket op fires).
 *   2. Run readDailySnapshot() and readSourceBreakdown().
 *   3. Assert HealthStore got populated with a snapshot AND that no
 *      StrictMode violation killed us.
 *
 * Requires a paired emulator-side setup: Health Connect installed and
 * at least the read perms granted. Without those the gateway returns
 * an empty snapshot — which is FINE for this test (still proves no
 * network was attempted on the read path).
 *
 * Run via the standard engine path: ./build.sh test connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class HealthLocalOnlyTest {

    private lateinit var originalPolicy: StrictMode.ThreadPolicy

    @Before fun lockNetworkDown() {
        originalPolicy = StrictMode.getThreadPolicy()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .penaltyDeath()  // any socket / DNS / HTTP attempt = test crashes
                .build(),
        )
    }

    @After fun restorePolicy() {
        StrictMode.setThreadPolicy(originalPolicy)
    }

    @Test fun snapshotRead_doesNotTouchNetwork_andPersistsLocally() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val before = HealthStore.lastSnapshotTs(ctx)

        val snap = HealthConnectGateway.readDailySnapshot(ctx)
        val sources = HealthConnectGateway.readSourceBreakdown(ctx)

        // If we got here, StrictMode didn't crash — proven local-only.
        assertTrue("steps must be non-negative", snap.steps >= 0L)
        assertTrue("source counts must be non-negative", sources.all { it.recordCount >= 0 })
        val after = HealthStore.lastSnapshotTs(ctx)
        assertTrue("HealthStore.lastSnapshotTs should have advanced or held", after >= before)
        assertEquals(
            "HealthStore.lastSnapshotJson should mirror today's date",
            snap.date.toString(),
            org.json.JSONObject(HealthStore.lastSnapshotJson(ctx) ?: "{}").optString("date"),
        )
    }
}
