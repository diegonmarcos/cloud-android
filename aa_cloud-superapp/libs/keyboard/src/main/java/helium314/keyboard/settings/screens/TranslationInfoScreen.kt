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
        val liveLangs = client?.supportedLanguages().orEmpty()
        val defaultLangs = listOf("en", "pt", "es", "de")
        val installedLangs = liveLangs.ifEmpty { defaultLangs }
        Column(Modifier.padding(16.dp)) {
            Text(
                "Tap the translate icon on the keyboard toolbar to open a translate bar above " +
                    "the keys. Pick a source (or Auto-detect) and target language from the two " +
                    "chips at the top. What you type is translated live and the translation " +
                    "(not your original text) is committed to the app.",
                style = MaterialTheme.typography.bodyMedium
            )
            PreferenceCategory("Engine")
            Text(
                "Type: ${client?.javaClass?.simpleName ?: "not connected"}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (client == null) {
                Text(
                    "No translate engine registered in this app. On the standalone Cloud " +
                        "Keyboard app, translation runs via the cloud-keyboard-libs companion " +
                        "app over AIDL — install it for translation to actually run (the " +
                        "language picker below still works either way).",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            PreferenceCategory("Installed / default languages")
            Text(
                installedLangs.joinToString(", ") { code ->
                    java.util.Locale(code).displayLanguage + " ($code)"
                } + if (liveLangs.isEmpty()) " — defaults, engine not reporting yet" else " — from engine",
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
