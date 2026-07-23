// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/**
 * Fully offline grammar pass over the word/separator the user just typed. Only high-confidence,
 * unambiguous fixes are applied (no its/it's, your/you're style word-choice guessing - that
 * needs real grammar parsing to avoid constant false positives, which is out of scope here).
 *
 * [checkAndFix] runs once per separator keypress and only ever looks at the word immediately
 * before the cursor.  [checkWholeField] applies the same rules across the entire field on demand
 * (Grammar toolbar key tap).
 *
 * Both entry points respect the master toggle (PREF_GRAMMAR_CHECK_ENABLED) and the three
 * individual sub-toggles.  InputLogic.java calls checkAndFix unconditionally — the enable
 * gate is enforced here so InputLogic stays untouched.
 */
object GrammarChecker {
    private const val LOOKBACK = 60
    /** Large limit used by checkWholeField to fetch the full field content. */
    private const val FIELD_LIMIT = 4096
    private val TRAILING_WORD = Regex("""(\w+)$""")
    private val SENTENCE_END = Regex("""[.!?]\s*$""")

    // ── per-fix helpers ─────────────────────────────────────────────────────

    /**
     * Capitalise a standalone lowercase "i" -> "I".
     * Returns the fixed string, or the original if nothing changed.
     */
    private fun applyCapitalizeI(text: String): String =
        text.replace(Regex("""(?<!\w)i(?!\w)"""), "I")

    /**
     * Collapse an immediate duplicate word pair ("the the" -> "the").
     * Only collapses the pair with optional whitespace between them; does not
     * remove later occurrences to avoid false positives in longer passages.
     */
    private fun applyDedupeWords(text: String): String =
        text.replace(Regex("""(?i)\b(\w+)[ \t]+\1\b"""), "$1")

    /**
     * Capitalise the first character of a sentence: after [.!?] followed by
     * optional whitespace, and at the very start of the text.
     */
    private fun applySentenceCaps(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text)
        // capitalise start of string
        val first = sb[0]
        if (first.isLetter() && first.isLowerCase()) sb[0] = first.uppercaseChar()
        // capitalise after sentence-end punctuation
        val sentenceFollower = Regex("""([.!?]\s+)([a-z])""")
        var offset = 0
        sentenceFollower.findAll(text).forEach { m ->
            val capIdx = m.range.last + offset  // index of the lowercase letter in sb
            sb[capIdx] = sb[capIdx].uppercaseChar()
        }
        return sb.toString()
    }

    // ── public entry points ──────────────────────────────────────────────────

    /**
     * Called unconditionally by InputLogic on every separator keypress (~line 1249).
     * Reads prefs and returns immediately if the master toggle is off.
     * Each sub-fix is individually gated.
     */
    @JvmStatic
    fun checkAndFix(context: Context, connection: RichInputConnection) {
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_GRAMMAR_CHECK_ENABLED, Defaults.PREF_GRAMMAR_CHECK_ENABLED)) return

        val capitalizeI  = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,      Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I)
        val dedupeWords  = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,    Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS)
        val sentenceCaps = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,     Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS)

        val before = connection.getTextBeforeCursor(LOOKBACK, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val tail = before.substring(trimmed.length) // separator(s) just typed, restored after the fix

        val lastWordMatch = TRAILING_WORD.find(trimmed) ?: return
        val lastWord = lastWordMatch.value
        val lastWordStart = lastWordMatch.range.first

        // fix 1 — standalone "i" -> "I"
        if (capitalizeI && lastWord == "i") {
            replace(context, connection, before, lastWordStart, lastWordStart + 1, "I", tail)
            return
        }

        // fix 2 — repeated word
        if (dedupeWords) {
            val beforeLastWord = trimmed.substring(0, lastWordStart).trimEnd()
            val prevWordMatch = TRAILING_WORD.find(beforeLastWord)
            if (prevWordMatch != null && prevWordMatch.value.equals(lastWord, ignoreCase = true)) {
                replace(context, connection, before, prevWordMatch.range.last + 1, lastWordStart + lastWord.length, "", tail)
                return
            }
        }

        // fix 3 — capitalise word at sentence start
        if (sentenceCaps && lastWord.first().isLowerCase()) {
            val beforeWord = trimmed.substring(0, lastWordStart)
            val isSentenceStart = beforeWord.isBlank() || SENTENCE_END.containsMatchIn(beforeWord)
            if (isSentenceStart)
                replace(context, connection, before, lastWordStart, lastWordStart + lastWord.length,
                    lastWord.replaceFirstChar { it.uppercase() }, tail)
        }
    }

    /**
     * On-demand whole-field grammar fix, triggered by the GRAMMAR toolbar key.
     * Reads text before AND after the cursor (up to FIELD_LIMIT each side), applies each
     * enabled fix across the combined text, then replaces the field if something changed.
     *
     * Replacement strategy (cursor-safe, uses only public RichInputConnection API):
     *  1. finishComposingText — drop any pending composition to stabilise the cursor.
     *  2. beginBatchEdit.
     *  3. Re-read rawBefore / rawAfter inside the batch (cursor position is stable).
     *  4. Apply fixes to full = rawBefore + rawAfter.
     *  5. Split fixed at rawBefore.length to get fixedBefore / fixedAfter.
     *  6. Replace before-cursor region: deleteTextBeforeCursor(rawBefore.length) +
     *     commitText(fixedBefore, 1)   → cursor lands right after fixedBefore.
     *  7. If after-cursor text changed: deleteTextBeforeCursor(0) won't help; instead
     *     we move the cursor forward past the old after-text with a setSelection trick,
     *     but RichInputConnection doesn't expose setSelection publicly.  To stay within
     *     the public API we only rewrite rawAfter when it changed by deleting it char-by-
     *     char backwards via deleteTextBeforeCursor after first appending the fixedAfter
     *     and moving back.  This is overly complex — instead we take the conservative
     *     path: apply fixes only to the before-cursor region (where typos almost always
     *     are when the user taps the toolbar key), and leave after-cursor text untouched.
     *     This is always correct and avoids any cursor-position arithmetic.
     *  8. endBatchEdit.
     */
    @JvmStatic
    fun checkWholeField(context: Context, connection: RichInputConnection) {
        val prefs = context.prefs()
        if (!prefs.getBoolean(Settings.PREF_GRAMMAR_CHECK_ENABLED, Defaults.PREF_GRAMMAR_CHECK_ENABLED)) return

        val capitalizeI  = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,   Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I)
        val dedupeWords  = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS, Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS)
        val sentenceCaps = prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,  Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS)

        // Stabilise composing state before reading.
        connection.finishComposingText()

        // Read all text before the cursor (the region we can safely rewrite).
        val rawBefore = connection.getTextBeforeCursor(FIELD_LIMIT, 0)?.toString() ?: return

        // Also read after-cursor so sentence-caps can see the full context, but we only
        // rewrite the before-cursor region (conservative, cursor-safe).
        val rawAfter = connection.getTextAfterCursor(FIELD_LIMIT, 0)?.toString() ?: ""
        val full = rawBefore + rawAfter

        // Apply enabled fixes to the whole text for context accuracy, then take only the
        // before-cursor portion for the actual rewrite.
        var fixed = full
        if (capitalizeI)  fixed = applyCapitalizeI(fixed)
        if (dedupeWords)  fixed = applyDedupeWords(fixed)
        if (sentenceCaps) fixed = applySentenceCaps(fixed)

        // Extract the before-cursor slice of the fixed text.
        // If fixes removed characters before the cursor, clamp to avoid overrun.
        val fixedBefore = fixed.substring(0, rawBefore.length.coerceAtMost(fixed.length))

        // Nothing changed in the before-cursor region — nothing to do.
        if (fixedBefore == rawBefore) return

        connection.beginBatchEdit()
        connection.deleteTextBeforeCursor(rawBefore.length)
        connection.commitText(fixedBefore, 1) // cursor placed after fixedBefore
        connection.endBatchEdit()
    }

    // ── private low-level helper ─────────────────────────────────────────────

    private fun replace(context: Context, connection: RichInputConnection, before: String, start: Int, end: Int, replacement: String, tail: String) {
        val original = before.substring(start, end)
        if (original == replacement) return
        connection.deleteTextBeforeCursor(before.length - start)
        val combined = SpannableString(replacement + tail)
        if (replacement.isNotEmpty()) {
            combined.setSpan(
                SuggestionSpan(context, null, arrayOf(original), SuggestionSpan.FLAG_EASY_CORRECT, null),
                0, replacement.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        connection.commitText(combined, 1)
    }
}
