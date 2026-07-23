// SPDX-License-Identifier: GPL-3.0-only

package helium314.keyboard.latin

import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.SuggestionSpan
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Offline + optional remote grammar pass over the word/separator the user just typed.
 *
 * Mode (PREF_GRAMMAR_MODE):
 *   "off"    — all grammar checks disabled.
 *   "local"  — the three built-in rules (capitalize-I, sentence-caps, repeated-words).
 *   "remote" — whole-field check POSTs to a LanguageTool-compatible server; falls back
 *              to the local rules on any network failure.
 *
 * Back-compat: if PREF_GRAMMAR_MODE is absent the old PREF_GRAMMAR_CHECK_ENABLED boolean
 * is consulted (true → "local", false → "off").
 *
 * [checkAndFix] runs once per separator keypress — always local, never remote (too slow).
 * [checkWholeField] is triggered by the GRAMMAR toolbar key and uses the selected mode.
 */
object GrammarChecker {
    private const val LOOKBACK = 60
    /** Large limit used by checkWholeField to fetch the full field content. */
    private const val FIELD_LIMIT = 4096
    private val TRAILING_WORD = Regex("""(\w+)$""")
    private val SENTENCE_END = Regex("""[.!?]\s*$""")

    private val remoteExecutor = Executors.newSingleThreadExecutor()

    // ── mode helper ──────────────────────────────────────────────────────────

    private fun resolveMode(context: Context): String {
        val prefs = context.prefs()
        // New pref takes precedence.
        if (prefs.contains(Settings.PREF_GRAMMAR_MODE)) {
            return prefs.getString(Settings.PREF_GRAMMAR_MODE, Defaults.PREF_GRAMMAR_MODE)
                ?: Defaults.PREF_GRAMMAR_MODE
        }
        // Back-compat: derive from old boolean.
        return if (prefs.getBoolean(Settings.PREF_GRAMMAR_CHECK_ENABLED, Defaults.PREF_GRAMMAR_CHECK_ENABLED)) "local" else "off"
    }

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

    // ── local 3-rule pass ────────────────────────────────────────────────────

    private fun applyLocalRules(context: Context, text: String): String {
        val prefs = context.prefs()
        var fixed = text
        if (prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I, Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I))
            fixed = applyCapitalizeI(fixed)
        if (prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS, Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS))
            fixed = applyDedupeWords(fixed)
        if (prefs.getBoolean(Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS, Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS))
            fixed = applySentenceCaps(fixed)
        return fixed
    }

    // ── remote LanguageTool call ─────────────────────────────────────────────

    /**
     * POST [text] to the LanguageTool-compatible endpoint and return the fixed text,
     * applying all safe single-replacement matches from highest offset to lowest so
     * earlier offsets stay valid.  Returns null on any failure (caller falls back to local).
     *
     * "Safe" = issueType in {typographical, grammar} AND at least one replacement available.
     * Only single replacements are applied (multi-replacement matches are skipped to avoid
     * ambiguity).  Matches are applied in descending offset order.
     *
     * Must NOT be called on the IME main thread.
     */
    private fun callRemote(context: Context, text: String): String? {
        return try {
            val prefs = context.prefs()
            val urlStr = prefs.getString(Settings.PREF_GRAMMAR_REMOTE_URL, Defaults.PREF_GRAMMAR_REMOTE_URL)
                ?: Defaults.PREF_GRAMMAR_REMOTE_URL
            val body = "text=${URLEncoder.encode(text, "UTF-8")}&language=auto&level=default"
            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 1500
                readTimeout = 1500
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != 200) return null
            val responseJson = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            // Parse matches array.
            val root = JSONObject(responseJson)
            val matches = root.optJSONArray("matches") ?: return text

            // Collect safe single-replacement matches, sort highest offset first.
            data class Fix(val offset: Int, val length: Int, val replacement: String)
            val fixes = mutableListOf<Fix>()
            for (i in 0 until matches.length()) {
                val m = matches.getJSONObject(i)
                val issueType = m.optJSONObject("rule")?.optString("issueType") ?: continue
                if (issueType != "typographical" && issueType != "grammar") continue
                val reps = m.optJSONArray("replacements") ?: continue
                if (reps.length() == 0) continue
                val replacement = reps.getJSONObject(0).optString("value").takeIf { it.isNotEmpty() } ?: continue
                val offset = m.optInt("offset", -1).takeIf { it >= 0 } ?: continue
                val length = m.optInt("length", 0).takeIf { it > 0 } ?: continue
                fixes.add(Fix(offset, length, replacement))
            }

            // Apply highest-offset-first so earlier offsets remain valid.
            fixes.sortByDescending { it.offset }
            val sb = StringBuilder(text)
            for (fix in fixes) {
                val end = fix.offset + fix.length
                if (end > sb.length) continue // guard against stale offsets
                sb.replace(fix.offset, end, fix.replacement)
            }
            sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ── public entry points ──────────────────────────────────────────────────

    /**
     * Called unconditionally by InputLogic on every separator keypress (~line 1249).
     * Always uses local rules only (remote is too slow per-keystroke).
     * Returns immediately if mode is "off".
     */
    @JvmStatic
    fun checkAndFix(context: Context, connection: RichInputConnection) {
        if (resolveMode(context) == "off") return
        // Always local: remote only from checkWholeField.
        val prefs = context.prefs()
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
     * In remote mode: fires async network call; on success applies LanguageTool matches;
     * on failure falls back to local 3-rule pass.  In local mode: applies local rules
     * synchronously.  In off mode: no-op.
     */
    @JvmStatic
    fun checkWholeField(context: Context, connection: RichInputConnection) {
        when (resolveMode(context)) {
            "off" -> return
            "remote" -> checkWholeFieldRemote(context, connection)
            else -> checkWholeFieldLocal(context, connection)
        }
    }

    private fun checkWholeFieldLocal(context: Context, connection: RichInputConnection) {
        connection.finishComposingText()
        val rawBefore = connection.getTextBeforeCursor(FIELD_LIMIT, 0)?.toString() ?: return
        val rawAfter  = connection.getTextAfterCursor(FIELD_LIMIT, 0)?.toString() ?: ""
        val full = rawBefore + rawAfter

        val fixed = applyLocalRules(context, full)
        val fixedBefore = fixed.substring(0, rawBefore.length.coerceAtMost(fixed.length))
        if (fixedBefore == rawBefore) return

        connection.beginBatchEdit()
        connection.deleteTextBeforeCursor(rawBefore.length)
        connection.commitText(fixedBefore, 1)
        connection.endBatchEdit()
    }

    private fun checkWholeFieldRemote(context: Context, connection: RichInputConnection) {
        connection.finishComposingText()
        val rawBefore = connection.getTextBeforeCursor(FIELD_LIMIT, 0)?.toString() ?: return
        val rawAfter  = connection.getTextAfterCursor(FIELD_LIMIT, 0)?.toString() ?: ""
        val full = rawBefore + rawAfter
        if (full.isBlank()) return

        // Capture connection reference for the background thread callback.
        remoteExecutor.execute {
            val fixedFull = callRemote(context, full) ?: applyLocalRules(context, full)
            val fixedBefore = fixedFull.substring(0, rawBefore.length.coerceAtMost(fixedFull.length))
            if (fixedBefore == rawBefore) return@execute

            // Post the edit back; RichInputConnection methods are thread-safe (they use
            // Handler.post internally through InputConnection.sendKeyEvent routing).
            // beginBatchEdit/endBatchEdit pair keeps it atomic for undo history.
            connection.beginBatchEdit()
            connection.deleteTextBeforeCursor(rawBefore.length)
            connection.commitText(fixedBefore, 1)
            connection.endBatchEdit()
        }
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
