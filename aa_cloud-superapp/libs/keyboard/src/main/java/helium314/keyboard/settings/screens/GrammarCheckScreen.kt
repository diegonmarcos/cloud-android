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
import helium314.keyboard.settings.preferences.SwitchPreference

// SuperApp addition (patch 0002) — Grammar check settings screen.
// Mirrors the shape of TextCorrectionScreen / createCorrectionSettings.

fun createGrammarSettings(context: Context): List<Setting> = listOf(
    Setting(
        context,
        Settings.PREF_GRAMMAR_CHECK_ENABLED,
        R.string.grammar_check_enabled,
        R.string.grammar_check_enabled_summary,
    ) {
        SwitchPreference(it, Defaults.PREF_GRAMMAR_CHECK_ENABLED)
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
)

@Composable
fun GrammarCheckScreen(onClickBack: () -> Unit) {
    val items = listOf(
        Settings.PREF_GRAMMAR_CHECK_ENABLED,
        R.string.grammar_category_fixes,
        Settings.PREF_GRAMMAR_FIX_CAPITALIZE_I,
        Settings.PREF_GRAMMAR_FIX_SENTENCE_CAPS,
        Settings.PREF_GRAMMAR_FIX_REPEATED_WORDS,
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
