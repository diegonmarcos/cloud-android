package com.diegonmarcos.superapp.devtools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Starts AppDebugServer and AppCrashLogger with ZERO code in the host app.
 *
 * A ContentProvider declared in a library manifest merges into every app that
 * links the library, and Android calls its onCreate BEFORE
 * Application.onCreate. That is what makes this constellation-wide rather
 * than "wired into whichever apps someone remembered": adding
 * `api project(':libs:devtools')` to libs:core is the entire integration.
 * (Same trick androidx.startup uses.)
 *
 * Running before Application.onCreate is also the useful order — if the app's
 * own startup throws, the debug server is already up and you can still pull
 * the logcat that explains why.
 *
 * The authority uses ${applicationId} so fifteen apps carrying the same
 * provider do not collide; a duplicate authority makes Android refuse to
 * install the second app.
 *
 * This is not a real provider — every data method is a no-op. It exists
 * purely for the onCreate callback, and it is not exported.
 */
class DebugInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // Never let a debug facility take down the host app.
        runCatching { AppCrashLogger.install(ctx) }
        runCatching { AppDebugServer.start(ctx) }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
