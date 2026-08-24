package com.diegonmarcos.superapp.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import java.io.IOException

/**
 * ContentProvider that serves bundled sticker packs from
 * `assets/stickers/index.json` + `assets/stickers/<pack_id>/<file>.webp`.
 *
 * Authority = `${applicationId}.stickercontentprovider` (declared in app
 * manifest with a placeholder). StickerRepo.installedStickerAuthorities()
 * picks this up automatically — no changes needed there.
 *
 * WhatsApp sticker contract:
 *   /metadata                           → pack rows (identifier, name)
 *   /stickers/<pack_id>                 → sticker rows (sticker_file_name)
 *   /stickers_asset/<pack_id>/<file>    → file descriptor for the .webp
 *
 * index.json shape:  [{"id":"flork_1","name":"Flork Stickers"}, …]
 */
class BundledStickerProvider : ContentProvider() {

    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<String>?, selection: String?,
        selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val ctx = context ?: return null
        val segs = uri.pathSegments
        return when {
            segs.firstOrNull() == "metadata" -> {
                val c = MatrixCursor(arrayOf("sticker_pack_identifier", "sticker_pack_name"))
                packs(ctx).forEach { c.addRow(arrayOf(it.first, it.second)) }
                c
            }
            segs.size >= 2 && segs[0] == "stickers" -> {
                val packId = segs[1]
                val c = MatrixCursor(arrayOf("sticker_file_name"))
                runCatching {
                    ctx.assets.list("stickers/$packId")
                        ?.filter { it.endsWith(".webp") || it.endsWith(".png") }
                        ?.forEach { c.addRow(arrayOf(it)) }
                }
                c
            }
            else -> null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        // /stickers_asset/<pack_id>/<file>
        val segs = uri.pathSegments
        if (segs.size < 3 || segs[0] != "stickers_asset") return null
        val packId = segs[1]
        val file   = segs[2]
        val ctx = context ?: return null
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val stream = ctx.assets.open("stickers/$packId/$file")
            Thread {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        stream.copyTo(out)
                    }
                } catch (_: IOException) { }
            }.apply { isDaemon = true }.start()
            pipe[0]
        } catch (_: IOException) { null }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun packs(ctx: android.content.Context): List<Pair<String, String>> {
        val json = runCatching {
            ctx.assets.open("stickers/index.json").bufferedReader().readText()
        }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val id   = obj.optString("id");   if (id.isBlank())   return@mapNotNull null
            val name = obj.optString("name"); if (name.isBlank()) return@mapNotNull null
            id to name
        }
    }

    // ── unused contract methods ─────────────────────────────────────────────

    override fun getType(uri: Uri): String {
        val path = uri.lastPathSegment ?: return "image/webp"
        return if (path.endsWith(".png")) "image/png" else "image/webp"
    }
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?) = 0
}
