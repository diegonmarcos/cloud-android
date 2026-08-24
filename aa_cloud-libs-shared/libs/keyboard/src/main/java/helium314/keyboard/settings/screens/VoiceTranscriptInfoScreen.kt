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
import com.diegonmarcos.superapp.voice.VoiceEngines
import com.diegonmarcos.superapp.voice.VoiceModelManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory

// SuperApp addition — informational screen for the voice-transcript (mic) key.
@Composable
fun VoiceTranscriptInfoScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_voice_transcript),
        settings = emptyList(),
    ) {
        val context = LocalContext.current
        val client = VoiceEngines.client
        Column(Modifier.padding(16.dp)) {
            Text(
                "Tap the mic icon on the keyboard toolbar to start dictation. Speech is " +
                    "transcribed fully offline using a downloaded Vosk model for the current " +
                    "input language, and the transcript is typed as you speak.",
                style = MaterialTheme.typography.bodyMedium
            )
            PreferenceCategory("Engine")
            Text(
                "Type: ${client?.javaClass?.simpleName ?: "none registered"}",
                style = MaterialTheme.typography.bodyMedium
            )
            // client != null only means the object exists — isConnected() reflects
            // whether the AIDL bind (for the standalone app) actually succeeded.
            Text(
                when {
                    client == null -> "Not connected — no engine registered."
                    client.isConnected() -> "Connected — dictation is functional."
                    else -> "NOT connected — client exists but the underlying service " +
                        "isn't bound. On the standalone Cloud Keyboard app this means the " +
                        "cloud-keyboard-libs companion app is either not installed, or its " +
                        "service failed to bind (see logcat tag AidlVoiceEngine)."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            PreferenceCategory("Languages (from build.json::voice.models)")
            val langs = runCatching { VoiceModelManager.registeredLanguages(context) }.getOrDefault(emptyList())
            if (langs.isEmpty()) {
                Text("No voice models declared.", style = MaterialTheme.typography.bodyMedium)
            } else {
                langs.forEach { (key, downloaded) ->
                    Text(
                        "$key — ${if (downloaded) "downloaded on this device" else "not downloaded yet (fetches on first use)"}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            VoiceTranscriptInfoScreen {}
        }
    }
}
