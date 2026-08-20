package com.diegonmarcos.superapp.devtools

import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM test for the OpenObserve record builder (no Android deps). */
class DiagnosticsPushTest {

    @Test
    fun buildRecord_isJsonArray_withEscapedFields() {
        val rec = DiagnosticsPush.buildRecord(
            appId = "com.diegonmarcos.superapp",
            versionName = "1.0", versionCode = "1", gitSha = "abc1234",
            device = "Pixel 8", androidRelease = "15", sdkInt = 35,
            tsIso = "2026-07-01T00:00:00Z",
            logcat = "line1\nline2 \"quoted\"",
            trace = "t\ttab", crashes = "none",
        )
        assertTrue(rec.startsWith("[{") && rec.endsWith("}]"))
        assertTrue(rec.contains("\"app\":\"com.diegonmarcos.superapp\""))
        assertTrue(rec.contains("\"sdk_int\":\"35\""))
        // newlines + quotes + tabs must be escaped so the payload stays valid JSON
        assertTrue(rec.contains("line1\\nline2 \\\"quoted\\\""))
        assertTrue(rec.contains("t\\ttab"))
    }

    @Test
    fun buildRecord_includesPrefixedExtras() {
        val rec = DiagnosticsPush.buildRecord(
            appId = "a", versionName = "1", versionCode = "1", gitSha = "s",
            device = "d", androidRelease = "15", sdkInt = 35, tsIso = "t",
            logcat = "", trace = "", crashes = "",
            extra = mapOf("role_dialer" to "false"),
        )
        assertTrue(rec.contains("\"x_role_dialer\":\"false\""))
    }
}
