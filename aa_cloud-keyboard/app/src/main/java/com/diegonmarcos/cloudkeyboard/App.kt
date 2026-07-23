package com.diegonmarcos.cloudkeyboard

import android.app.Application
import androidx.work.Configuration as WorkManagerConfiguration
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.translate.TranslateEngines
import helium314.keyboard.latin.utils.prefs as heliboardPrefs

/**
 * Application entry point for Cloud Keyboard.
 *
 * HeliBoard (libs:keyboard) ships android:name="helium314.keyboard.latin.App" in its
 * manifest; our manifest wins via tools:replace="android:name". Since HeliBoard's own
 * App.onCreate never fires, we replicate its synchronous keyboard init here so
 * LatinIME / Settings don't crash with "parameter prefs is null".
 */
class App : Application(), WorkManagerConfiguration.Provider {

    override val workManagerConfiguration: WorkManagerConfiguration
        get() = WorkManagerConfiguration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Crash takeout — on any uncaught exception, drop the stack into a
        // single, predictably-named SHARED file: Download/cloud-keyboard-log-error.log
        // (MediaStore, no permission on Android 10+). Readable from Termux
        // (~/storage/Download) or any file manager — no adb/logcat needed.
        // Mirrors the superapp's cloud-superapp-log-error.log.
        CrashTakeout.install(this, "cloud-keyboard-log-error.log")

        // Mirror helium314.keyboard.latin.App.onCreate's synchronous init.
        runCatching { helium314.keyboard.latin.define.DebugFlags.init(this) }
        runCatching { helium314.keyboard.latin.utils.FoldableUtils.init(this) }
        runCatching { helium314.keyboard.latin.settings.Settings.init(this) }
        runCatching { helium314.keyboard.latin.utils.SubtypeSettings.init(this) }
        runCatching { helium314.keyboard.latin.RichInputMethodManager.init(this) }
        runCatching { helium314.keyboard.latin.settings.Defaults.initDynamicDefaults(this) }
        runCatching { helium314.keyboard.latin.utils.upgradeToolbarPrefs(heliboardPrefs()) }
        runCatching { MediaRuntime.configure(BuildConfig.MEDIA_CONFIG_B64, null, BuildConfig.GIPHY_API_KEY) }
        // Register AIDL client so libs:translate's Translator / TranslateBarView
        // delegate to the companion cloud-keyboard-libs service (ML Kit not bundled here).
        runCatching { TranslateEngines.client = AidlTranslateEngineClient(this) }

        // Extract keyboard dicts from companion cloud-keyboard-libs (which bundles
        // the .dict files since this APK omits them to save ~39 MB). Must run
        // before seedDefaultLocales/prewarmEmojiDict so the cache is populated
        // before HeliBoard's lazy-extraction path is hit. No-op if companion is
        // absent or dicts are already cached.
        runCatching { CompanionDictExtractor.extractIfNeeded(this) }

        // Seed the default enabled languages once (data-driven from
        // build.json::default_locales → BuildConfig.DEFAULT_LOCALES). Runs only on
        // a fresh install (no subtype yet + never seeded), so it never fights a
        // user who later curates their own languages. Uses the exact API the
        // Settings → Languages screen uses (getResourceSubtypesForLocale +
        // addEnabledSubtype), with createDefaultSubtype as fallback for locales
        // without an exact resource subtype.
        runCatching { seedDefaultLocales() }
    }

    private fun seedDefaultLocales() {
        val prefs = heliboardPrefs()
        if (prefs.getBoolean(PREF_DEFAULT_LOCALES_SEEDED, false)) return
        if (helium314.keyboard.latin.utils.SubtypeSettings.getEnabledSubtypes(false).isNotEmpty()) {
            prefs.edit().putBoolean(PREF_DEFAULT_LOCALES_SEEDED, true).apply()
            return
        }
        BuildConfig.DEFAULT_LOCALES.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tag ->
            runCatching {
                val locale = helium314.keyboard.latin.common.LocaleUtils.run { tag.constructLocale() }
                val subtype = helium314.keyboard.latin.utils.SubtypeSettings
                    .getResourceSubtypesForLocale(locale).firstOrNull()
                    ?: helium314.keyboard.latin.utils.SubtypeUtilsAdditional.createDefaultSubtype(locale)
                helium314.keyboard.latin.utils.SubtypeSettings.addEnabledSubtype(prefs, subtype)
                prewarmEmojiDict(locale)
            }
        }
        prefs.edit().putBoolean(PREF_DEFAULT_LOCALES_SEEDED, true).apply()
    }

    /**
     * Extract the bundled emoji dictionary (assets/dicts/emoji_<locale>.dict, shipped
     * by libs:keyboard) into this locale's per-locale cache on first install.
     *
     * PREF_SUGGEST_EMOJIS already defaults true, but emoji suggestions and inline
     * emoji search gate on getLocalesWithEmojiDicts(), which reads the *cache* dir —
     * and normal extraction is lazy (only on first dictionary load). On a fresh
     * install that leaves the "An emoji dictionary is required for this feature"
     * dialog showing until the keyboard has been opened once. Pre-extracting here
     * (a cheap asset→file copy, no binary load) makes emoji suggestions work from
     * first boot. Emoji dicts only — main dicts stay lazy to keep first launch fast.
     */
    private fun prewarmEmojiDict(locale: java.util.Locale) {
        val (_, nonExtracted) = helium314.keyboard.latin.dictionary.DictionaryFactory
            .getAvailableDictsForLocale(locale, this, true)
        nonExtracted
            .filter { it.substringBefore("_") == helium314.keyboard.latin.dictionary.Dictionary.TYPE_EMOJI }
            .forEach { filename ->
                helium314.keyboard.latin.utils.DictionaryInfoUtils
                    .extractAssetsDictionary(filename, locale, this)
            }
    }

    private companion object {
        const val PREF_DEFAULT_LOCALES_SEEDED = "cloudkb_default_locales_seeded"
    }
}
