package org.fossify.phone.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cloud Dialer: tester for the self-contained offline spam matcher (patch 0007).
 * Pure JVM (uses org.json, available on the test classpath via Android's stubs /
 * json dependency) — proves exact/prefix/regex hits, hidden/empty handling, and
 * that a clean normal number is NOT flagged.
 */
class SpamMatcherTest {

    private val rules = listOf(
        SpamMatcher.Rule("exact", "+18005551234", "scam"),
        SpamMatcher.Rule("prefix", "+8809", "premium-rate"),
        SpamMatcher.Rule("regex", "^\\+?1?900\\d{7}$", "premium-rate-us-900"),
    )

    @Test
    fun exactRuleMatches_ignoringFormatting() {
        assertTrue(SpamMatcher.isSpam("+1 (800) 555-1234", rules))
    }

    @Test
    fun prefixRuleMatches() {
        assertTrue(SpamMatcher.isSpam("+8809123456", rules))
    }

    @Test
    fun regexRuleMatches() {
        assertTrue(SpamMatcher.isSpam("19001234567", rules))
    }

    @Test
    fun cleanNumberIsNotSpam() {
        assertFalse(SpamMatcher.isSpam("+34600123456", rules))
    }

    @Test
    fun hiddenOrEmptyNumberIsNotSpam() {
        assertFalse(SpamMatcher.isSpam(null, rules))
        assertFalse(SpamMatcher.isSpam("", rules))
    }

    @Test
    fun matchExposesCategory() {
        assertEquals("premium-rate", SpamMatcher.match("+8809123456", rules)?.category)
        assertEquals(null, SpamMatcher.match("+34600123456", rules))
    }

    @Test
    fun parseDropsMalformedRules() {
        val json = """
            { "version": 1, "rules": [
              { "type": "prefix", "value": "+8809", "category": "x" },
              { "type": "", "value": "+1", "category": "y" },
              { "type": "exact", "value": "" },
              { "value": "+2" }
            ] }
        """.trimIndent()
        val parsed = SpamMatcher.parse(json)
        assertEquals(1, parsed.size)
        assertEquals("+8809", parsed[0].value)
    }
}
