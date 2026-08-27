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
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.media.StickerRepo
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.PreferenceCategory

// SuperApp addition — informational screen covering the three tabs the emoji
// panel offers: Emoji, Sticker, GIF. No toggle prefs exist for these yet.
@Composable
fun EmojiInfoScreen(onClickBack: () -> Unit) {
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_emoji),
        settings = emptyList(),
    ) {
        val context = LocalContext.current
        Column(Modifier.padding(16.dp)) {
            PreferenceCategory("Emoji")
            Text(
                "Tap the search icon in the emoji keyboard's category strip to search emoji, " +
                    "stickers and GIFs together in one bar drawn above the keys.",
                style = MaterialTheme.typography.bodyMedium
            )
            val emojiLocales = runCatching { DictionaryInfoUtils.getLocalesWithEmojiDicts(context) }.getOrDefault(emptyList())
            Text(
                if (emojiLocales.isEmpty()) "No on-device emoji-name dictionary installed for the enabled languages."
                else "Emoji-name search dictionary installed for: ${emojiLocales.joinToString(", ") { it.displayLanguage }}.",
                style = MaterialTheme.typography.bodyMedium
            )
            PreferenceCategory("Stickers")
            val packs = runCatching { StickerRepo.packs(context) }.getOrDefault(emptyList())
            Text(
                "Source: WhatsApp-compatible sticker content providers (any installed app " +
                    "exposing one is picked up automatically, no per-app configuration).",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                if (packs.isEmpty()) "No sticker packs installed."
                else "${packs.size} sticker pack(s) installed: ${packs.joinToString(", ") { it.name }}.",
                style = MaterialTheme.typography.bodyMedium
            )
            PreferenceCategory("GIFs")
            val gif = MediaRuntime.gif
            Text(
                when {
                    gif == null -> "GIFs are disabled (no provider configured in build.json)."
                    !MediaRuntime.gifEnabled -> "Source: ${gif.provider} — configured but no API key set."
                    else -> "Source: ${gif.provider} — API key set, GIF search is active."
                },
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
            EmojiInfoScreen {}
        }
    }
}
