package org.fossify.phone.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud Dialer: tester for the screened-call history model (patch 0013).
 * Pure JVM — proves serialize/parse roundtrip, the newest-first cap, and
 * fail-open parsing of malformed input.
 */
class ScreeningLogTest {

    @Test
    fun roundtripPreservesEntries() {
        val entries = listOf(
            ScreeningLog.Entry("+19001234567", 1750000000000L, "spam:premium-rate-us-900"),
            ScreeningLog.Entry("", 1750000001000L, "hidden_number"),
        )
        assertEquals(entries, ScreeningLog.parse(ScreeningLog.serialize(entries)))
    }

    @Test
    fun addPrependsAndCaps() {
        var entries = emptyList<ScreeningLog.Entry>()
        repeat(ScreeningLog.MAX_ENTRIES + 5) { i ->
            entries = ScreeningLog.add(entries, ScreeningLog.Entry("+3460000$i", i.toLong(), "not_in_contacts"))
        }
        assertEquals(ScreeningLog.MAX_ENTRIES, entries.size)
        assertEquals((ScreeningLog.MAX_ENTRIES + 4).toLong(), entries.first().timestamp)
    }

    @Test
    fun malformedInputParsesEmpty() {
        assertTrue(ScreeningLog.parse("not json").isEmpty())
        assertTrue(ScreeningLog.parse("{}").isEmpty())
        assertTrue(ScreeningLog.parse("""{"entries":[{"number":"+1"}]}""").isEmpty())
    }
}
