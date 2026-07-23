// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.settings.preferences.TextInputPreference

// SuperApp addition (patch 0002) — Grammar check settings screen.
// Mirrors the shape of TextCorrectionScreen / createCorrectionSettings.

fun createGrammarSettings(context: Context): List<Setting> = listOf(
    Setting(
        context,
        Settings.PREF_GRAMMAR_MODE,
        R.string.grammar_mode_title,
    ) { setting ->
        val items = listOf(
            context.getString(R.string.grammar_mode_off) to "off",
            context.getString(R.string.grammar_mode_local) to "local",
            context.getString(R.string.grammar_mode_remote) to "remote",
        )
        ListPreference(setting, items, Defaults.PREF_GRAMMAR_MODE)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,
        R.string.grammar_fix_capitalize_i,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_FIX_CAPITALIZE_I)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,
        R.string.grammar_fix_sentence_caps,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_FIX_SENTENCE_CAPS)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
        R.string.grammar_fix_repeated_words,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_FIX_REPEATED_WORDS)
    },
    Setting(
        context,
        Settings.PREF_GRAMMAR_REMOTE_URL,
        R.string.grammar_remote_url_title,
        R.string.grammar_remote_url_summary,
    ) { setting ->
        TextInputPreference(setting, Defaults.PREF_GRAMMAR_REMOTE_URL)
    },
    // Placeholder for future n-gram confusion-pair API (not yet wired to anything).
    Setting(
        context,
        Settings.PREF_GRAMMAR_NGRAM_URL,
        R.string.grammar_ngram_url_title,
        R.string.grammar_ngram_url_summary,
    ) { setting ->
        TextInputPreference(setting, Defaults.PREF_GRAMMAR_NGRAM_URL)
    },
)

@Composable
fun GrammarCheckScreen(onClickBack: () -> Unit) {
    val items = listOf(
        Settings.PREF_GRAMMAR_MODE,
        R.string.grammar_category_fixes,
        Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,
        Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,
        Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
        Settings.PREF_GRAMMAR_REMOTE_URL,
        Settings.PREF_GRAMMAR_NGRAM_URL,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_grammar),
        settings = items,
    )
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            GrammarCheckScreen {}
        }
    }
}
