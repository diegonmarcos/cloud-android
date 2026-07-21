// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan

/**
 * Fully offline grammar pass over the word/separator the user just typed. Only high-confidence,
 * unambiguous fixes are applied (no its/it's, your/you're style word-choice guessing - that
 * needs real grammar parsing to avoid constant false positives, which is out of scope here).
 *
 * Runs once per separator keypress and only ever looks at the word immediately before the
 * cursor, so it never reaches back into text the user has already moved past.
 */
object GrammarChecker {
    private const val LOOKBACK = 60
    private val TRAILING_WORD = Regex("""(\w+)$""")
    private val SENTENCE_END = Regex("""[.!?]\s*$""")

    fun checkAndFix(context: Context, connection: RichInputConnection) {
        val before = connection.getTextBeforeCursor(LOOKBACK, 0)?.toString() ?: return
        val trimmed = before.trimEnd()
        val tail = before.substring(trimmed.length) // separator(s) just typed, restored after the fix

        val lastWordMatch = TRAILING_WORD.find(trimmed) ?: return
        val lastWord = lastWordMatch.value
        val lastWordStart = lastWordMatch.range.first

        if (lastWord == "i") {
            replace(context, connection, before, lastWordStart, lastWordStart + 1, "I", tail)
            return
        }

        val beforeLastWord = trimmed.substring(0, lastWordStart).trimEnd()
        val prevWordMatch = TRAILING_WORD.find(beforeLastWord)
        if (prevWordMatch != null && prevWordMatch.value.equals(lastWord, ignoreCase = true)) {
            // repeated word, e.g. "the the" -> drop the duplicate
            replace(context, connection, before, prevWordMatch.range.last + 1, lastWordStart + lastWord.length, "", tail)
            return
        }

        if (lastWord.first().isLowerCase()) {
            val beforeWord = trimmed.substring(0, lastWordStart)
            val isSentenceStart = beforeWord.isBlank() || SENTENCE_END.containsMatchIn(beforeWord)
            if (isSentenceStart)
                replace(context, connection, before, lastWordStart, lastWordStart + lastWord.length,
                    lastWord.replaceFirstChar { it.uppercase() }, tail)
        }
    }

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
