package com.diegonmarcos.cloudkeyboardlibs

import android.app.Application
import com.diegonmarcos.superapp.media.MediaRuntime
import com.diegonmarcos.superapp.translate.LocalTranslateEngineClient
import com.diegonmarcos.superapp.translate.TranslateEngines

/**
 * Application entry point for Cloud Keyboard Libs.
 *
 * Initialises libs:media so GIF/sticker support is ready when the
 * companion keyboard APK delegates to this package's content providers.
 * Also registers the in-process ML Kit engine so TranslateEngineService
 * can call it when the cloud-keyboard AIDL client binds.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            MediaRuntime.configure(BuildConfig.MEDIA_CONFIG_B64, null, BuildConfig.GIPHY_API_KEY)
        }
        runCatching {
            TranslateEngines.client = LocalTranslateEngineClient(this)
        }
    }
}
