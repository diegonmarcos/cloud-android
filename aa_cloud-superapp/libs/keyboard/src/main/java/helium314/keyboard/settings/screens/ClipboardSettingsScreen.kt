// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import helium314.keyboard.latin.R
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Defaults
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Theme
import helium314.keyboard.latin.utils.previewDark
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsWithoutKey
import helium314.keyboard.settings.filePicker
import helium314.keyboard.settings.initPreview
import helium314.keyboard.settings.preferences.Preference
import helium314.keyboard.settings.preferences.SliderPreference
import helium314.keyboard.settings.preferences.SwitchPreference

// SuperApp addition — clipboard settings, promoted out of the general
// Preferences screen to its own top-level menu entry (was previously a
// buried category inside Preferences). All Setting() definitions moved
// here verbatim from PreferencesScreen.kt's createPreferencesSettings.

@Composable
fun ClipboardSettingsScreen(onClickBack: () -> Unit) {
    val prefs = LocalContext.current.prefs()
    val clipboardHistoryEnabled = prefs.getBoolean(Settings.PREF_ENABLE_CLIPBOARD_HISTORY, Defaults.PREF_ENABLE_CLIPBOARD_HISTORY)
    val items = listOf(
        Settings.PREF_ENABLE_CLIPBOARD_HISTORY,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_HISTORY_PINNED_FIRST else null,
        if (clipboardHistoryEnabled) Settings.PREF_CLIPBOARD_USE_FILES else null,
        if (clipboardHistoryEnabled && prefs.getBoolean(Settings.PREF_CLIPBOARD_USE_FILES, Defaults.PREF_CLIPBOARD_USE_FILES))
            Settings.PREF_CLIPBOARD_FILES_SIZE_LIMIT else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_EXPORT_JSON else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_IMPORT_JSON else null,
        if (clipboardHistoryEnabled) SettingsWithoutKey.CLIPBOARD_RENAME_LIST else null,
    )
    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = stringResource(R.string.settings_screen_clipboard),
        settings = items
    )
}

fun createClipboardSettings(context: Context) = listOf(
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
    // Rename a pin list from Settings (the in-panel version can't work: while the
    // clipboard is open the keyboard shows the clipboard, so there's nothing to type into).
    Setting(context, SettingsWithoutKey.CLIPBOARD_RENAME_LIST, R.string.clipboard_rename_list) {
        val ctx = LocalContext.current
        Preference(name = stringResource(R.string.clipboard_rename_list), onClick = {
            val dao = ClipboardDao.getInstance(ctx)
            val names = dao?.getListNames().orEmpty()
            if (dao != null && names.isNotEmpty()) {
                android.app.AlertDialog.Builder(ctx)
                    .setTitle(R.string.clipboard_rename_list)
                    .setItems(names.toTypedArray()) { _, which ->
                        val old = names[which]
                        val input = android.widget.EditText(ctx).apply {
                            setText(old)
                            hint = ctx.getString(R.string.clipboard_rename_list_hint)
                            setSingleLine()
                            selectAll()
                        }
                        android.app.AlertDialog.Builder(ctx)
                            .setTitle(R.string.clipboard_rename_list)
                            .setView(input)
                            .setPositiveButton(android.R.string.ok) { _, _ ->
                                val newName = input.text?.toString()?.trim().orEmpty()
                                if (newName.isNotEmpty()) dao.renameList(old, newName)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                    .show()
            }
        })
    },
)

@Preview
@Composable
private fun PreferencePreview() {
    initPreview(LocalContext.current)
    Theme(previewDark) {
        Surface {
            ClipboardSettingsScreen {}
        }
    }
}
