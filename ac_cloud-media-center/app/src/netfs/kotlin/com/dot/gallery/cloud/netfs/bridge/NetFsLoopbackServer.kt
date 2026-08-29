/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.dot.gallery.cloud.netfs.bridge

import android.util.Base64
import com.dot.gallery.cloud.core.CloudTrace
import com.dot.gallery.cloud.core.ProviderType
import com.dot.gallery.cloud.core.ThumbnailSize
import com.dot.gallery.cloud.image.CloudFetcherRegistryHolder
import com.dot.gallery.feature_node.presentation.util.printError
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.SecureRandom

/**
 * Stream source the loopback server reads from. Implemented by `NetworkFileSystemProvider`
 * so the server can stay protocol-agnostic and resolve the right provider per request.
 */
interface NetFsLoopbackSource {
    fun loopbackSize(path: String): Long
    fun loopbackOpen(path: String, offset: Long): InputStream
    fun loopbackMime(path: String): String
    fun loopbackThumbnail(path: String, size: ThumbnailSize): ByteArray?
}

/**
 * On-device HTTP bridge that exposes SMB/NFS streams as `http://127.0.0.1` URLs so the
 * existing cloud media pipeline (Glide / Sketch / ZoomImage / ExoPlayer) can consume them
 * unchanged, including `Range` seeking for video.
 *
 * URL shape: `http://127.0.0.1:{port}/{token}/{PROVIDER}/{kind}/{size}/{base64url(path)}`
 *  - `kind`  = `original` | `thumb`
 *  - `size`  = `orig` | `preview` | `thumbnail`
 * The random per-process [token] prevents other local apps from reading the port.
 */
internal class NetFsLoopbackServer : NanoHTTPD(LOOPBACK_HOST, 0) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: return notFound()
        // /{token}/{provider}/{kind}/{size}/{b64}
        val parts = uri.trimStart('/').split('/', limit = 5)
        if (parts.size < 5) return notFound()
        val (token, providerName, kind, sizeName) = parts
        val b64 = parts[4]

        if (token != NetFsLoopback.token) {
            return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Forbidden")
        }

        val providerType = runCatching { ProviderType.valueOf(providerName) }.getOrNull() ?: return notFound()
        val source = CloudFetcherRegistryHolder.registry?.get(providerType) as? NetFsLoopbackSource
            ?: return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No provider")

        val path = runCatching {
            String(Base64.decode(b64, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
        }.getOrNull() ?: return notFound()

        return try {
            if (kind == KIND_THUMB) {
                val size = if (sizeName == "thumbnail") ThumbnailSize.THUMBNAIL else ThumbnailSize.PREVIEW
                val bytes = CloudTrace.time("Loopback[$providerName] thumb/$sizeName generate '$path'") {
                    source.loopbackThumbnail(path, size)
                } ?: return notFound()
                CloudTrace.d("Loopback[$providerName] thumb/$sizeName '$path' -> ${CloudTrace.bytes(bytes.size.toLong())}")
                newFixedLengthResponse(
                    Response.Status.OK, "image/jpeg",
                    ByteArrayInputStream(bytes), bytes.size.toLong()
                )
            } else {
                serveOriginal(session, source, path)
            }
        } catch (e: Exception) {
            // Surface the real cause: Sketch/Glide only log the bare "HTTP 500" status line, so
            // without this the underlying SMB/NFS read failure is invisible.
            printError("NetFsLoopback: $providerName $kind/$sizeName failed for '$path': ${e.javaClass.simpleName}: ${e.message}")
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun serveOriginal(session: IHTTPSession, source: NetFsLoopbackSource, path: String): Response {
        val total = source.loopbackSize(path)
        val mime = source.loopbackMime(path)
        val rangeHeader = session.headers["range"]

        if (rangeHeader != null && total > 0) {
            val range = rangeHeader.removePrefix("bytes=").trim()
            val bits = range.split("-")
            val start = bits.getOrNull(0)?.toLongOrNull() ?: 0L
            val end = bits.getOrNull(1)?.takeIf { it.isNotBlank() }?.toLongOrNull() ?: (total - 1)
            val length = (end - start + 1).coerceAtLeast(0L)
            CloudTrace.d("Loopback original '$path' range=$start-$end/$total (${CloudTrace.bytes(length)}, $mime)")
            val stream = TracingInputStream("Loopback original '$path' bytes=$start-$end", source.loopbackOpen(path, start))
            return newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, stream, length).apply {
                addHeader("Content-Range", "bytes $start-$end/$total")
                addHeader("Accept-Ranges", "bytes")
            }
        }

        CloudTrace.d("Loopback original '$path' full (${CloudTrace.bytes(total)}, $mime)")
        val stream = TracingInputStream("Loopback original '$path' full", source.loopbackOpen(path, 0L))
        return newFixedLengthResponse(Response.Status.OK, mime, stream, total).apply {
            addHeader("Accept-Ranges", "bytes")
        }
    }

    /**
     * Wraps the SMB/NFS stream to log how long the full transfer took and the achieved throughput,
     * so a slow "original" load can be attributed to network read time (vs. decode in the client).
     */
    private class TracingInputStream(
        private val label: String,
        private val delegate: InputStream
    ) : InputStream() {
        private val startNs = System.nanoTime()
        private var transferred = 0L
        private var logged = false

        override fun read(): Int = delegate.read().also { if (it >= 0) transferred++ else logDone() }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) transferred += it else if (it < 0) logDone() }

        override fun available(): Int = delegate.available()

        override fun close() {
            logDone()
            delegate.close()
        }

        private fun logDone() {
            if (logged) return
            logged = true
            val ms = (System.nanoTime() - startNs) / 1_000_000
            val mbps = if (ms > 0) transferred * 1000.0 / (1024.0 * 1024.0) / ms else 0.0
            CloudTrace.d("$label streamed ${CloudTrace.bytes(transferred)} in ${ms}ms (%.2f MB/s)".format(mbps))
        }
    }

    private fun notFound(): Response =
        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val KIND_ORIGINAL = "original"
        const val KIND_THUMB = "thumb"
    }
}

/**
 * Lazily-started singleton wrapper around [NetFsLoopbackServer]. Providers call
 * [originalUrl] / [thumbnailUrl] to obtain loopback URLs for the media pipeline.
 */
object NetFsLoopback {

    @Volatile
    private var server: NetFsLoopbackServer? = null

    @Volatile
    var token: String = randomToken()
        private set

    @Synchronized
    private fun ensureStarted(): Int {
        var s = server
        if (s == null) {
            // NanoHTTPD logs every client-side disconnect ("Broken pipe" / "Connection reset") at
            // SEVERE with a full stack trace. These are expected whenever the image/video pipeline
            // cancels a request (scrolling, seeking, switching items), so silence them to avoid
            // drowning the log — real serve() failures are returned as HTTP error responses instead.
            java.util.logging.Logger.getLogger(NanoHTTPD::class.java.name).level =
                java.util.logging.Level.OFF
            s = NetFsLoopbackServer()
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = s
        }
        return s.listeningPort
    }

    private fun base(): String = "http://${NetFsLoopbackServer.LOOPBACK_HOST}:${ensureStarted()}/$token"

    private fun encode(path: String): String =
        Base64.encodeToString(path.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun originalUrl(type: ProviderType, path: String): String =
        "${base()}/${type.name}/${NetFsLoopbackServer.KIND_ORIGINAL}/orig/${encode(path)}"

    fun thumbnailUrl(type: ProviderType, path: String, size: ThumbnailSize): String {
        val sizeName = if (size == ThumbnailSize.THUMBNAIL) "thumbnail" else "preview"
        return "${base()}/${type.name}/${NetFsLoopbackServer.KIND_THUMB}/$sizeName/${encode(path)}"
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        token = randomToken()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
