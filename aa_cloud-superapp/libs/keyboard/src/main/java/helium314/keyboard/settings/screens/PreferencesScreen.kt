// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.media.AudioManager
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.keyboard.KeyboardLayoutSet
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import android.content.Intent
import android.widget.Toast
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.SubtypeSettings
import helium314.keyboard.latin.utils.getActivity
import helium314.keyboard.latin.utils.locale
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.preferences.ListPreference
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.preferences.ReorderSwitchPreference
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.SettingsActivity
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.SwitchPreferenceWithEmojiDictWarning
import helium314.keyboard.latin.utils.previewDark

@Composable
fun PreferencesScreen(
    onClickBack: () -> Unit,
) {
    val prefs = LocalContext.current.prefs()
    val b = (LocalContext.current.getActivity() as? SettingsActivity)?.prefChanged?.collectAsState()
    if ((b?.value ?: 0) < 0)
        Log.v("irrelevant", "stupid way to trigger recomposition on preference change")
    val clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val items = listOf(
        R.string.settings_category_input,
        Settings.PREF_SHOW_HINTS,
        if (prefs.getBoolean(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS))
            Settings.PREF_POPUP_KEYS_HINT_ORDER else null,
        Settings.PREF_POPUP_KEYS_ORDER,
        Settings.PREF_SHOW_POPUP_HINTS,
        Settings.PREF_SHOW_TLD_POPUP_KEYS,
        Settings.PREF_POPUP_ON,
        if (AudioAndHapticFeedbackManager.getInstance().hasVibrator())
            Settings.PREF_VIBRATE_ON else null,
        if (prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            Settings.PREF_VIBRATION_DURATION_SETTINGS else null,
        if (prefs.getBoolean(Settings.PREF_VIBRATE_ON, Defaults.PREF_VIBRATE_ON))
            Settings.PREF_VIBRATE_IN_DND_MODE else null,
        Settings.PREF_SOUND_ON,
        if (prefs.getBoolean(Settings.PREF_SOUND_ON, Defaults.PREF_SOUND_ON))
            Settings.PREF_KEYPRESS_SOUND_VOLUME else null,
        Settings.PREF_SAVE_SUBTYPE_PER_APP,
        Settings.PREF_SHOW_EMOJI_DESCRIPTIONS,
        R.string.settings_category_additional_keys,
        Settings.PREF_SHOW_NUMBER_ROW,
        if (SubtypeSettings.getEnabledSubtypes(true).any { it.locale().language in localesWithLocalizedNumberRow })
            Settings.PREF_LOCALIZED_NUMBER_ROW else null,
        if (prefs.getBoolean(Settings.PREF_SHOW_HINTS, Defaults.PREF_SHOW_HINTS)
            && prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW))
            Settings.PREF_SHOW_NUMBER_ROW_HINTS else null,
        if (!prefs.getBoolean(Settings.PREF_SHOW_NUMBER_ROW, Defaults.PREF_SHOW_NUMBER_ROW))
            Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS else null,
        Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY,
        Settings.PREF_LANGUAGE_SWITCH_KEY,
        Settings.PREF_SHOW_EMOJI_KEY,
        Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        R.string.settings_category_clipboard_history,
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_USE_FILES else null,
        if (clipboardHistoryEnabled && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES))
            Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT else null,
        // clipboard export / import (always shown when clipboard is enabled)
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_EXPORT_JSON else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_IMPORT_JSON else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_preferences),
        settings = items
    )
}

fun createPreferencesSettings(context: Context) = listOf(
    Setting(context, Settings.PREF_SAVE_SUBTYPE_PER_APP, R.string.save_subtype_per_app) {
        SwitchPreference(it, Defaults.PREF_SAVE_SUBTYPE_PER_APP)
    },
    Setting(context, Settings.PREF_SHOW_HINTS, R.string.show_hints, R.string.show_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_HINTS) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_POPUP_KEYS_HINT_ORDER, R.string.hint_source) {
        ReorderSwitchPreference(it, Defaults.PREF_POPUP_KEYS_HINT_ORDER)
    },
    Setting(context, Settings.PREF_POPUP_KEYS_ORDER, R.string.popup_order) {
        ReorderSwitchPreference(it, Defaults.PREF_POPUP_KEYS_ORDER)
    },
    Setting(
        context, Settings.PREF_SHOW_TLD_POPUP_KEYS, R.string.show_tld_popup_keys,
        R.string.show_tld_popup_keys_summary
    ) {
        SwitchPreference(it, Defaults.PREF_SHOW_TLD_POPUP_KEYS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_POPUP_HINTS, R.string.show_popup_hints, R.string.show_popup_hints_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_POPUP_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_POPUP_ON, R.string.popup_on_keypress) {
        SwitchPreference(it, Defaults.PREF_POPUP_ON) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_VIBRATE_ON, R.string.vibrate_on_keypress) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_ON)
    },
    Setting(context, Settings.PREF_VIBRATE_IN_DND_MODE, R.string.vibrate_in_dnd_mode) {
        SwitchPreference(it, Defaults.PREF_VIBRATE_IN_DND_MODE)
    },
    Setting(context, Settings.PREF_SOUND_ON, R.string.sound_on_keypress) {
        SwitchPreference(it, Defaults.PREF_SOUND_ON)
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_DESCRIPTIONS, R.string.show_emoji_descriptions) {
        SwitchPreferenceWithEmojiDictWarning(it, Defaults.PREF_SHOW_EMOJI_DESCRIPTIONS)
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW, R.string.number_row, R.string.number_row_summary) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS, R.string.number_row_in_symbols) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_IN_SYMBOLS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_LOCALIZED_NUMBER_ROW, R.string.localized_number_row, R.string.localized_number_row_summary) {
        SwitchPreference(it, Defaults.PREF_LOCALIZED_NUMBER_ROW) {
            KeyboardLayoutSet.onSystemLocaleChanged()
            KeyboardSwitcher.getInstance().reloadKeyboard()
        }
    },
    Setting(context, Settings.PREF_SHOW_NUMBER_ROW_HINTS, R.string.number_row_hints) {
        SwitchPreference(it, Defaults.PREF_SHOW_NUMBER_ROW_HINTS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_LANGUAGE_SWITCH_KEY, R.string.show_language_switch_key) {
        SwitchPreference(it, Defaults.PREF_SHOW_LANGUAGE_SWITCH_KEY) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_LANGUAGE_SWITCH_KEY, R.string.language_switch_key_behavior) {
        ListPreference(
            it,
            listOf(
                stringResource(R.string.switch_language) to "internal",
                stringResource(R.string.language_switch_key_switch_input_method) to "input_method",
                stringResource(R.string.language_switch_key_switch_both) to "both"
            ),
            Defaults.PREF_LANGUAGE_SWITCH_KEY
        ) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_SHOW_EMOJI_KEY, R.string.show_emoji_key) {
        SwitchPreference(it, Defaults.PREF_SHOW_EMOJI_KEY) { KeyboardSwitcher.getInstance().reloadKeyboard() }
    },
    Setting(context, Settings.PREF_REMOVE_REDUNDANT_POPUPS,
        R.string.remove_redundant_popups, R.string.remove_redundant_popups_summary)
    {
        SwitchPreference(it, Defaults.PREF_REMOVE_REDUNDANT_POPUPS) { KeyboardSwitcher.getInstance().setThemeNeedsReload() }
    },
    Setting(context, Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        R.string.enable_clipboard_history, R.string.enable_clipboard_history_summary)
    {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY) { ClipboardDao.getInstance(ctx)?.clearNonPinned() }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, R.string.clipboard_history_retention_time) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_HISTORY_RETENTION_TIME,
            description = {
                if (it > 120) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_minutes, it.toString())
            },
            range = 1f..121f,
        ) { ClipboardDao.getInstance(ctx)?.clearOldClips(true) }
    },
    Setting(context, Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST, R.string.clipboard_history_pinned_first) {
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_HISTORY_PINNED_FIRST)
    },
    Setting(context, Settings.PREF_CLIPBOARD_USE_FILES, R.string.clipboard_history_files) {
        val ctx = LocalContext.current
        SwitchPreference(it, Defaults.PREF_CLIPBOARD_USE_FILES) {
            ClipboardDao.getInstance(ctx)?.cleanupFiles(ctx.prefs())
        }
    },
    Setting(context, Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT, R.string.clipboard_history_max_file_size) { setting ->
        val ctx = LocalContext.current
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_CLIPBOARD_FILES_SIZE_LIMIT,
            description = {
                if (it > 1000) stringResource(R.string.settings_no_limit)
                else stringResource(R.string.abbreviation_unit_mb, it.toString())
            },
            range = 1f..1001f,
        ) { ClipboardDao.getInstance(ctx)?.cleanupFiles(ctx.prefs()) }
    },
    Setting(context, SettingsWithoutKey.CLIPBOARD_EXPORT_JSON, R.string.clipboard_export_json, R.string.clipboard_export_json_summary) {
        val ctx = LocalContext.current
        // Launch SAF CREATE_DOCUMENT, then write exportToJson output to the chosen URI.
        val exportLauncher = filePicker { uri ->
            val dao = ClipboardDao.getInstance(ctx) ?: return@filePicker
            runCatching {
                val (json, count) = dao.exportToJsonWithCount(ctx)
                ctx.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(ctx, ctx.getString(R.string.clipboard_export_success, count), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(ctx, R.string.clipboard_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
        Preference(name = stringResource(R.string.clipboard_export_json), onClick = {
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_TITLE, "clipboard-export.json")
            exportLauncher.launch(intent)
        })
    },
    Setting(context, SettingsWithoutKey.CLIPBOARD_IMPORT_JSON, R.string.clipboard_import_json, R.string.clipboard_import_json_summary) {
        val ctx = LocalContext.current
        // Launch SAF OPEN_DOCUMENT, read the chosen JSON file, and call importFromJson.
        val importLauncher = filePicker { uri ->
            val dao = ClipboardDao.getInstance(ctx) ?: return@filePicker
            runCatching {
                val json = ctx.contentResolver.openInputStream(uri)?.use { it.reader().readText() }
                    ?: return@filePicker
                val count = dao.importFromJson(ctx, json)
                Toast.makeText(ctx, ctx.getString(R.string.clipboard_import_success, count), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(ctx, R.string.clipboard_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
        Preference(name = stringResource(R.string.clipboard_import_json), onClick = {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
            importLauncher.launch(intent)
        })
    },
    Setting(context, Settings.PREF_VIBRATION_DURATION_SETTINGS, R.string.prefs_keypress_vibration_duration_settings) { setting ->
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_VIBRATION_DURATION_SETTINGS,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else stringResource(R.string.abbreviation_unit_milliseconds, it.toString())
            },
            range = -1f..100f,
            onValueChanged = { it?.let { AudioAndHapticFeedbackManager.getInstance().vibrate(it.toLong()) } }
        )
    },
    Setting(context, Settings.PREF_KEYPRESS_SOUND_VOLUME, R.string.prefs_keypress_sound_volume_settings) { setting ->
        val audioManager = LocalContext.current.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        SliderPreference(
            name = setting.title,
            key = setting.key,
            default = Defaults.PREF_KEYPRESS_SOUND_VOLUME,
            description = {
                if (it < 0) stringResource(R.string.settings_system_default)
                else (it * 100).toInt().toString()
            },
            range = -0.01f..1f,
            onValueChanged = { it?.let { audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, it) } }
        )
    },
)

private val localesWithLocalizedNumberRow = listOf("bn", "ckb", "gu", "hi", "kn", "mr", "ne", "th", "ur", "ar", "fa")

@Preview
@Composable
private fun Preview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            PreferencesScreen { }
        }
    }
}
