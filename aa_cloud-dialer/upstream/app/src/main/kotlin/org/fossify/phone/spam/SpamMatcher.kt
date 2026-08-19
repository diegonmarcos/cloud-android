package org.fossify.phone.spam

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Cloud Dialer self-contained spam matcher (patch 0007). Pure logic — no Android
 * deps — so it unit-tests on the JVM. Grounded in the FOSS call-blocker rule
 * model (SpamBlocker, Yet Another Call Blocker): a number is spam if it matches
 * any rule by exact value, dialling prefix, or regex. Rules come from the bundled
 * offline asset assets/spam_patterns.json (updatable via the in-app updater).
 * Uses kotlinx.serialization (already a dep) so it works in JVM unit tests —
 * org.json is only an android.jar stub off-device.
 */
object SpamMatcher {

    data class Rule(val type: String, val value: String, val category: String)

    private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse the spam_patterns.json payload into rules. Malformed rules are skipped. */
    fun parse(json: String): List<Rule> {
        val out = ArrayList<Rule>()
        val arr = runCatching { lenient.parseToJsonElement(json).jsonObject["rules"]?.jsonArray }
            .getOrNull() ?: return out
        for (el in arr) {
            val o = runCatching { el.jsonObject }.getOrNull() ?: continue
            val type = o["type"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val value = o["value"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (type.isEmpty() || value.isEmpty()) continue
            out.add(Rule(type, value, o["category"]?.jsonPrimitive?.contentOrNull ?: ""))
        }
        return out
    }

    /** Reduce a raw number to comparable form: keep digits and a leading '+'. */
    fun normalize(number: String): String =
        number.filter { it.isDigit() || it == '+' }

    /** The first rule the number matches, or null — exposes the spam category to callers. */
    fun match(number: String?, rules: List<Rule>): Rule? {
        if (number.isNullOrBlank()) return null
        val n = normalize(number)
        if (n.isEmpty()) return null
        return rules.firstOrNull { rule ->
            when (rule.type) {
                "exact" -> n == normalize(rule.value)
                "prefix" -> n.startsWith(normalize(rule.value))
                "regex" -> runCatching { Regex(rule.value).matches(n) }.getOrDefault(false)
                else -> false
            }
        }
    }

    fun isSpam(number: String?, rules: List<Rule>): Boolean = match(number, rules) != null
}
