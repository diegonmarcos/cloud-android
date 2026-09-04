package com.diegonmarcos.superapp.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Installs [Telemetry.installCrashHandler] with ZERO code in the host app, so
 * "an app crashed" always reaches c3-infra-api instead of dying on the device.
 *
 * [Telemetry]'s own KDoc has promised since it was written that "every app that
 * links libs:core gets this for free, with zero per-app wiring" — but nothing
 * ever called installCrashHandler, in any app, so the crash path had never run
 * once. This provider is the missing half of that promise.
 *
 * WHY A PROVIDER, NOT Application.onCreate: a ContentProvider declared in a
 * library manifest merges into every app that links the library, and Android
 * calls its onCreate BEFORE Application.onCreate. That is what makes this
 * constellation-wide rather than "wired into whichever apps someone
 * remembered" — the failure mode that left this dead in the first place. It is
 * also the only ordering that reports a crash IN Application.onCreate, which
 * is exactly the crash a user experiences as "the app does not open" and the
 * one no other mechanism here can currently see. (Same trick androidx.startup
 * and the sibling DebugInitProvider use.)
 *
 * The authority uses ${applicationId} so the apps carrying this provider do not
 * collide; a duplicate authority makes Android refuse to install the second app.
 *
 * COEXISTS WITH devtools' AppCrashLogger: both wrap the default handler and
 * both chain to the handler they replaced, so whichever installs second wraps
 * the first and BOTH still run — the local crash file and the upload are not
 * exclusive, and neither suppresses the system crash dialog.
 *
 * This is not a real provider — every data method is a no-op. It exists purely
 * for the onCreate callback, and it is not exported.
 */
class CoreInitProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // Telemetry must never be the reason an app fails to start: a throw
        // out of a provider's onCreate takes the whole process down before
        // Application.onCreate ever runs.
        runCatching { Telemetry.installCrashHandler(ctx) }
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
