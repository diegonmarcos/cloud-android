package com.diegonmarcos.cloudkeyboardlibs

import android.app.Application
import com.diegonmarcos.superapp.media.MediaRuntime

/**
 * Application entry point for Cloud Keyboard Libs.
 *
 * Initialises libs:media so GIF/sticker support is ready when the
 * companion keyboard APK delegates to this package's content providers.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        runCatching {
            MediaRuntime.configure(BuildConfig.MEDIA_CONFIG_B64, null, BuildConfig.GIPHY_API_KEY)
        }
    }
}
