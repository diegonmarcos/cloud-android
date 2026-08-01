// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.translate.TranslateEngines
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory

// SuperApp addition — informational screen for the Translate bar feature.
// No functional toggles exist yet (translate is always available from the
// toolbar), so this just surfaces the current engine status and language
// coverage instead of pretending to configure prefs that don't exist.
@Composable
fun TranslationInfoScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_translation),
        settings = emptyList(),
    ) {
        val client = TranslateEngines.client
        val langs = client?.supportedLanguages() ?: emptyList()
        Column(Modifier.padding(16.dp)) {
            Text(
                "Tap the translate icon on the keyboard toolbar to open a translate bar above " +
                    "the keys. Pick a source (or Auto-detect) and target language from the two " +
                    "chips at the top — every language the engine supports is listed there, " +
                    "including German (de). What you type is translated live and the translation " +
                    "(not your original text) is committed to the app.",
                style = MaterialTheme.typography.bodyMedium
            )
            PreferenceCategory("Engine status")
            Text(
                if (client == null) "No translate engine connected."
                else "Connected — ${langs.size} languages available" +
                    (if ("de" in langs) " (German included)." else " (German not reported by engine)."),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            TranslationInfoScreen {}
        }
    }
}
