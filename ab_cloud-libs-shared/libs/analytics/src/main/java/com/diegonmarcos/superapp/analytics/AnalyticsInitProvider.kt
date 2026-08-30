package com.diegonmarcos.superapp.analytics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Self-initialization. A ContentProvider is created by the framework before
 * Application.onCreate(), so merging this into every consumer's manifest means
 * no app has to remember to call Analytics.init() — the module would otherwise
 * be wired into the build and never invoked, i.e. dead code.
 *
 * This only wires up SharedPreferences and reads the stored consent flag; it
 * sends nothing. Nothing leaves the device until setConsent(true).
 *
 * The authority is suffixed with the app id so two apps in the same profile
 * can't collide — duplicate provider authorities fail INSTALL_FAILED_CONFLICTING_PROVIDER.
 */
class AnalyticsInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let {
            Analytics.init(it)
            // Emit the launch here, not in each app. Without this the library is
            // wired into every APK and still reports nothing, because no app has
            // a call site - which is exactly the state it shipped in.
            Analytics.screen("app_open")
        }
        return true
    }

    override fun query(u: Uri, p: Array<String>?, s: String?, a: Array<String>?, o: String?): Cursor? = null
    override fun getType(u: Uri): String? = null
    override fun insert(u: Uri, v: ContentValues?): Uri? = null
    override fun delete(u: Uri, s: String?, a: Array<String>?): Int = 0
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<String>?): Int = 0
}
