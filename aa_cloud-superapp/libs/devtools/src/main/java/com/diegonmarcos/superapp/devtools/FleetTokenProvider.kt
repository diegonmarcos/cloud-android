package com.diegonmarcos.superapp.devtools

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Hands this app's fleet token to sibling apps — the mesh handshake.
 *
 * The whole access decision lives in the manifest: `android:permission` is
 * [FleetToken.PERMISSION], declared `signature`, so the platform rejects any
 * caller not signed with the Cloud key before a line of this class runs. There
 * is deliberately no identity check in code — `Binder.getCallingUid` plus a
 * signature comparison would only re-implement, worse, what the package manager
 * already enforces.
 *
 * Every member ships one so the library needs no per-app manifest work, but in
 * practice only [FleetToken.AUTHORITY_PKG]'s copy is ever read.
 */
class FleetTokenProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(COLUMN))
        return MatrixCursor(arrayOf(COLUMN)).apply {
            addRow(arrayOf(DevControlPrefs(ctx).token))
        }
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/fleet-token"

    // Read-only by construction: a member adopts the authority's token, it never
    // pushes one back.
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val COLUMN = "token"
    }
}
