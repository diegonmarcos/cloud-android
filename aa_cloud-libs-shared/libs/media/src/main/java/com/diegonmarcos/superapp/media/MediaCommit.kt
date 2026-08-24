package com.diegonmarcos.superapp.media

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Turns a picked [MediaItem] into an [InputContentInfoCompat] the keyboard can
 * hand to RichInputConnection.commitContent. Remote GIFs are downloaded and
 * cross-app sticker content:// URIs are copied into our OWN cache, then served
 * back through the app's FileProvider — you cannot re-grant another app's URI
 * to a third app, so we must own the file we commit.
 *
 * BLOCKING (network / IO): call off the main thread. Returns null on failure.
 *
 * FileProvider authority = "<applicationId>.mediaprovider", declared in
 * app/src/main/AndroidManifest.xml with res/xml/media_file_paths.xml.
 */
object MediaCommit {

    fun toContent(context: Context, item: MediaItem): InputContentInfoCompat? {
        val ext = when (item.mime) {
            "image/gif" -> "gif"; "image/webp" -> "webp"; "image/png" -> "png"; else -> "bin"
        }
        val dir = File(context.cacheDir, "keyboard_media").apply { mkdirs() }
        // Stable-ish name from the source URL hash → dedupes repeat picks.
        val file = File(dir, "${item.sourceUrl.hashCode().toUInt()}.$ext")
        if (!file.exists() || file.length() == 0L) {
            val ok = when (Uri.parse(item.sourceUrl).scheme) {
                "content" -> copyContent(context, item.sourceUrl, file)
                "http", "https" -> download(item.sourceUrl, file)
                else -> false
            }
            if (!ok) return null
        }
        val uri: Uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.mediaprovider", file)
        }.getOrNull() ?: return null
        return InputContentInfoCompat(
            uri,
            ClipDescription(item.description.ifBlank { "media" }, arrayOf(item.mime)),
            null
        )
    }

    private fun copyContent(context: Context, source: String, dest: File): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(source))?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } != null && dest.length() > 0
    }.getOrDefault(false)

    private fun download(source: String, dest: File): Boolean = runCatching {
        val c = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 15000; requestMethod = "GET"
        }
        try {
            if (c.responseCode != 200) return false
            c.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
            dest.length() > 0
        } finally { c.disconnect() }
    }.getOrDefault(false)
}
