package com.diegonmarcos.ide

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * The unified read surface Cloud-SuperApp queries. It does NOT own any data: for
 * each contract table it fans the query out to every installed fork's exporter
 * provider (`com.diegonmarcos.ide.<domain>.provider`) and UNIONs the rows into a
 * single cursor carrying the contract column order. Forks that aren't installed,
 * are blocked, don't own that table, or throw are skipped — the cursor is always
 * well-formed (the hub never returns null for a known table), so SuperApp can
 * render "nothing yet" without special-casing.
 *
 * Not every fork owns every table (editor → workspaces/recent_files/git_repos;
 * files → recent_files/transfers; utils → storage_summary). A fork that doesn't
 * implement a queried table simply returns no rows.
 *
 * Exported with the signature IPC permission (see AndroidManifest). A caller
 * signed with a different key can't query it.
 */
class IdeProvider : ContentProvider() {

    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        IdeContract.Tables.ALL.forEachIndexed { i, table ->
            addURI(IdeContract.AUTHORITY, table, i)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val code = matcher.match(uri)
        if (code == UriMatcher.NO_MATCH) {
            throw IllegalArgumentException("Unknown URI: $uri")
        }
        val table = IdeContract.Tables.ALL[code]
        val columns = IdeContract.Columns.forTable(table)
        val out = MatrixCursor(columns)

        val ctx = context ?: return out
        val cr = ctx.contentResolver

        for (fork in ForkRegistry.forks) {
            if (fork.blockedOn != null) continue
            if (!fork.isInstalled(ctx)) continue
            val forkUri = IdeContract.forkUri(fork.domain, table)
            try {
                cr.query(forkUri, columns, selection, selectionArgs, sortOrder)?.use { c ->
                    val idx = columns.map { c.getColumnIndex(it) }
                    while (c.moveToNext()) {
                        val row = arrayOfNulls<Any?>(columns.size)
                        for (i in columns.indices) {
                            val ci = idx[i]
                            row[i] = if (ci < 0) null else when (c.getType(ci)) {
                                Cursor.FIELD_TYPE_INTEGER -> c.getLong(ci)
                                Cursor.FIELD_TYPE_FLOAT -> c.getDouble(ci)
                                Cursor.FIELD_TYPE_NULL -> null
                                else -> c.getString(ci)
                            }
                        }
                        out.addRow(row)
                    }
                }
            } catch (t: Throwable) {
                // A fork provider that's present but uncooperative (or doesn't
                // own this table) must not take down the whole union — log+skip.
                Log.w(TAG, "fork ${fork.domain} query failed for $table: ${t.message}")
            }
        }
        out.setNotificationUri(cr, IdeContract.hubUri(table))
        return out
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        UriMatcher.NO_MATCH -> null
        else -> "vnd.android.cursor.dir/vnd.${IdeContract.AUTHORITY}"
    }

    // The hub is a read-only broker — writes/actions go through IIdeService (AIDL).
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Cloud-IDE is read-only via provider; use IIdeService for actions")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use IIdeService")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("use IIdeService")

    companion object {
        private const val TAG = "IdeProvider"
    }
}
