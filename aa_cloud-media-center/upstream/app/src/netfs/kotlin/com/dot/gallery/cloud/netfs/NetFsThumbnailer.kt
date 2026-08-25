/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.netfs

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import com.dot.gallery.cloud.core.CloudTrace
import com.dot.gallery.cloud.core.ThumbnailSize
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Generates JPEG thumbnails/previews on-device, since network filesystems expose no
 * server-side preview endpoint. Images are downscaled from a buffered read; videos use
 * [MediaMetadataRetriever] against the loopback original URL.
 */
internal object NetFsThumbnailer {

    private const val MAX_IMAGE_BYTES = 64L * 1024 * 1024 // skip decoding very large originals

    // Read network streams in large chunks: smbj issues one SMB2 READ per buffer fill (capped at the
    // negotiated max read size, typically ~1 MB), so the stdlib 8 KB default produced hundreds of
    // round-trips per file — slow enough that OkHttp timed out and thumbnails came back blank.
    private const val READ_BUFFER_BYTES = 1024 * 1024

    fun targetDimension(size: ThumbnailSize): Int =
        if (size == ThumbnailSize.THUMBNAIL) 256 else 1024

    fun fromImage(open: () -> InputStream, declaredSize: Long, size: ThumbnailSize): ByteArray? {
        val target = targetDimension(size)
        // Fast path (grid thumbnails only): an embedded EXIF thumbnail (present in most camera
        // JPEGs) is decoded from just the file header, so we avoid streaming the entire multi-MB
        // original over the network for every grid cell — the read that was timing out and leaving
        // thumbnails blank. Skipped for PREVIEW since EXIF thumbnails (~160px) are always far below
        // its target and would only waste an extra network file-open.
        if (size == ThumbnailSize.THUMBNAIL) {
            runCatching {
                open().use { input ->
                    val exif = ExifInterface(input)
                    val thumb = if (exif.hasThumbnail()) exif.thumbnailBitmap else null
                    if (thumb != null && maxOf(thumb.width, thumb.height) >= target / 2) {
                        CloudTrace.d("NetFsThumbnailer EXIF fast-path hit (${thumb.width}x${thumb.height})")
                        return encode(scaleBitmap(thumb, target))
                    }
                }
            }
        }
        // Fallback: decode + downscale the full original (bounded to avoid OOM on huge files).
        if (declaredSize in 1..MAX_IMAGE_BYTES || declaredSize <= 0L) {
            CloudTrace.d("NetFsThumbnailer full-read fallback for $size (declared ${CloudTrace.bytes(declaredSize)})")
            val bytes = CloudTrace.time("NetFsThumbnailer read+decode $size") {
                val raw = open().use { readAll(it) }
                CloudTrace.d("NetFsThumbnailer read ${CloudTrace.bytes(raw.size.toLong())} from network")
                downscale(raw, target)
            }
            return bytes
        }
        return null
    }

    /** Reads a stream fully using a large buffer to minimise SMB/NFS round-trips (see [READ_BUFFER_BYTES]). */
    private fun readAll(input: InputStream): ByteArray =
        ByteArrayOutputStream().use { out ->
            input.copyTo(out, READ_BUFFER_BYTES)
            out.toByteArray()
        }

    fun fromVideoUrl(url: String, size: ThumbnailSize): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(url, emptyMap())
            val frame = retriever.getFrameAtTime(-1) ?: return null
            val scaled = scaleBitmap(frame, targetDimension(size))
            encode(scaled)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun downscale(bytes: ByteArray, target: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        val decoded = BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, opts) ?: return null
        val scaled = scaleBitmap(decoded, target)
        return encode(scaled)
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (w / 2 >= target && h / 2 >= target) {
            w /= 2; h /= 2; sample *= 2
        }
        return sample
    }

    private fun scaleBitmap(bitmap: Bitmap, target: Int): Bitmap {
        val max = maxOf(bitmap.width, bitmap.height)
        if (max <= target) return bitmap
        val scale = target.toFloat() / max
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }

    private fun encode(bitmap: Bitmap): ByteArray =
        ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
}
