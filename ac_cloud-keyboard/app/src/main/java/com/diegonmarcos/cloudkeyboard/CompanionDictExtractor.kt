package com.diegonmarcos.cloudkeyboard

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import helium314.keyboard.latin.common.FileUtils
import helium314.keyboard.latin.utils.DictionaryInfoUtils
import java.io.File
import java.util.Locale

/**
 * Extracts keyboard dictionaries from the companion cloud-keyboard-libs APK
 * into this app's per-locale dict cache.
 *
 * cloud-keyboard omits the bundled dicts (~39 MB) from its own APK.
 * cloud-keyboard-libs bundles them at its assets root (files from
 * libs/keyboard/src/main/assets/dicts/ land at assets/ root in the companion
 * because its build.gradle sets assets.srcDirs pointing at the dicts/ folder).
 *
 * Extraction mirrors DictionaryInfoUtils.extractAssetsDictionary exactly:
 *   - source: companion.assets.open("<type>_<locale>.dict")
 *   - target: DictionaryInfoUtils cache dir for that locale, as "<type>.dict"
 *
 * If the companion is not installed, or any IO error occurs, this is a no-op.
 * HeliBoard's normal no-dict behavior applies (typing still works, just no
 * suggestions / emoji-search for that locale until the companion is installed).
 */
object CompanionDictExtractor {

    private const val TAG = "CompanionDictExtractor"
    const val COMPANION_PACKAGE = "com.diegonmarcos.cloudkeyboardlibs"

    /**
     * Extract all .dict files from the companion into this app's cache.
     * Safe to call on every launch — DictionaryInfoUtils already short-circuits
     * extraction if the cached file is present (cache is in filesDir, not
     * cleared on app update, and HeliBoard evicts on version upgrades).
     * Only extracts files not already cached; cheap on subsequent launches.
     */
    fun extractIfNeeded(context: Context) {
        val companionCtx = openCompanionContext(context) ?: return
        val dicts = runCatching { companionCtx.assets.list("") ?: emptyArray() }.getOrElse {
            Log.w(TAG, "cannot list companion assets", it); return
        }
        dicts.filter { it.endsWith(".dict") }.forEach { filename ->
            extractOne(filename, companionCtx, context)
        }
    }

    private fun extractOne(filename: String, companionCtx: Context, appCtx: Context) {
        // filename: "<type>_<locale_tag>.dict"  e.g. "main_en-US.dict", "emoji_de.dict"
        if (!filename.contains('_')) return
        val localeTag = filename.substringAfter("_").substringBefore(".dict")
        val type = filename.substringBefore("_")
        val locale = runCatching { Locale.forLanguageTag(localeTag) }.getOrNull()
            ?: return

        val cacheDir = DictionaryInfoUtils.getCacheDirectoryForLocale(locale, appCtx)
            ?: return
        val targetFile = File(cacheDir, "$type.dict")
        // Skip if already extracted (same as DictionaryInfoUtils behavior)
        if (targetFile.exists()) return

        runCatching {
            FileUtils.copyStreamToNewFile(
                companionCtx.assets.open(filename),
                targetFile
            )
            Log.d(TAG, "extracted $filename → ${targetFile.path}")
        }.onFailure {
            Log.w(TAG, "failed to extract $filename", it)
            targetFile.delete()
        }
    }

    private fun openCompanionContext(context: Context): Context? {
        return try {
            context.createPackageContext(COMPANION_PACKAGE, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            Log.d(TAG, "companion not installed — dicts unavailable")
            null
        } catch (e: Exception) {
            Log.w(TAG, "cannot open companion context", e)
            null
        }
    }
}
