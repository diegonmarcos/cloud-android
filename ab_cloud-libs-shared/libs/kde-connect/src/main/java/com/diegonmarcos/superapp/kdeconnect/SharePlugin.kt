package com.diegonmarcos.superapp.kdeconnect

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * kdeconnect.share.request — text, URL, AND real file transfer.
 *
 *   • text  → copy to clipboard + notify.
 *   • url   → open in the desktop browser (outgoing) / open here (incoming).
 *   • file  → KDE payload transfer: the SENDER opens a one-shot TLS server on
 *             an ephemeral port and advertises it in payloadTransferInfo; the
 *             RECEIVER connects to the sender's address:port (TLS, same device
 *             cert as the control link) and streams payloadSize bytes.
 *
 * Upload (phone → desktop): [sendFile] — we're the payload SERVER.
 * Download (desktop → phone): [onPacket] sees payloadSize+port — we're the
 *             payload CLIENT, pulling to Downloads via MediaStore.
 */
object SharePlugin : KdePlugin {
    private const val TAG = "KdeConnect/Share"

    override val id = "share"
    override val incoming = setOf(NetworkPacket.TYPE_SHARE)
    override val outgoing = setOf(NetworkPacket.TYPE_SHARE)

    override fun onPacket(ctx: Context, link: KdeLink, packet: NetworkPacket): Boolean {
        // Incoming file: payload side-channel advertised by the desktop.
        val port = packet.payloadPort()
        if (packet.has("filename") && packet.payloadSize > 0L && port != null) {
            receiveFile(ctx, link, packet, port)
            return true
        }
        when {
            packet.getString("url").isNotEmpty() -> runCatching {
                ctx.startActivity(android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    Uri.parse(packet.getString("url")),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            packet.getString("text").isNotEmpty() -> {
                val text = packet.getString("text")
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
                    ?.setPrimaryClip(ClipData.newPlainText("KDE Connect", text))
                KdeNotifications.post(ctx, "Shared text · ${link.peerName}",
                    text.take(120) + if (text.length > 120) "…" else "")
            }
            packet.has("filename") -> KdeNotifications.post(ctx, "Incoming file · ${link.peerName}",
                "${packet.getString("filename")} (no payload channel offered)")
        }
        return true
    }

    /** Sender builders — push text / a link to the desktop. */
    fun text(value: String) = NetworkPacket.of(NetworkPacket.TYPE_SHARE) { put("text", value) }
    fun url(value: String) = NetworkPacket.of(NetworkPacket.TYPE_SHARE) { put("url", value) }

    // ── Upload (phone → desktop): we are the payload server ──────────────

    /**
     * Stream [uri] to the connected [link]. Opens a one-shot TLS server on an
     * ephemeral port, advertises it in the share.request packet, accepts ONE
     * connection (the desktop) and copies the file. All socket work on a
     * daemon thread; returns true once the request packet is queued.
     */
    fun sendFile(ctx: Context, link: KdeLink, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        var name = "file"; var size = -1L
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getColumnIndex(OpenableColumns.DISPLAY_NAME).let { if (it >= 0) name = c.getString(it) ?: name }
                    c.getColumnIndex(OpenableColumns.SIZE).let { if (it >= 0 && !c.isNull(it)) size = c.getLong(it) }
                }
            }
        }
        if (size < 0L) { KdeNotifications.post(ctx, "Send file", "Can't read file size"); return false }

        val ssl = runCatching { KdeCrypto.sslContext(ctx) }.getOrNull()
            ?: return false.also { KdeNotifications.post(ctx, "Send file", "TLS init failed") }
        val server = runCatching {
            (ssl.serverSocketFactory.createServerSocket(0) as SSLServerSocket).apply {
                useClientMode = false          // we are the TLS server
                needClientAuth = false         // app-layer trust; desktop is already paired
                soTimeout = 60_000             // give the desktop a minute to connect
            }
        }.getOrNull() ?: return false.also { KdeNotifications.post(ctx, "Send file", "Couldn't open payload port") }

        val port = server.localPort
        val packet = NetworkPacket.withPayload(NetworkPacket.TYPE_SHARE, size, port) {
            put("filename", name)
            put("open", false)
            put("lastModified", System.currentTimeMillis())
            put("numberOfFiles", 1)
            put("totalPayloadSize", size)
        }
        if (!link.send(packet)) { runCatching { server.close() }; return false }

        Thread({
            runCatching {
                server.use { srv ->
                    (srv.accept() as SSLSocket).use { sock ->
                        runCatching { sock.startHandshake() }
                        cr.openInputStream(uri)?.use { input ->
                            input.copyTo(sock.outputStream, 64 * 1024)
                            sock.outputStream.flush()
                        }
                    }
                }
                KdeNotifications.post(ctx, "File sent · ${link.peerName}", name)
            }.onFailure {
                Log.w(TAG, "sendFile failed: ${it.message}")
                KdeNotifications.post(ctx, "Send file failed · ${link.peerName}", it.message ?: "error")
            }
        }, "kde-share-upload").apply { isDaemon = true; start() }
        return true
    }

    // ── Download (desktop → phone): we are the payload client ────────────

    private fun receiveFile(ctx: Context, link: KdeLink, packet: NetworkPacket, port: Int) {
        val name = packet.getString("filename").ifBlank { "kde-${System.currentTimeMillis()}" }
        val size = packet.payloadSize
        val addr = link.peerAddress
        if (addr == null) { KdeNotifications.post(ctx, "Incoming file", "no peer address"); return }
        val ssl = runCatching { KdeCrypto.sslContext(ctx) }.getOrNull() ?: return

        Thread({
            runCatching {
                (ssl.socketFactory.createSocket(addr, port) as SSLSocket).use { sock ->
                    sock.soTimeout = 60_000
                    runCatching { sock.startHandshake() }
                    saveToDownloads(ctx, name, size, sock.inputStream.buffered())
                }
                KdeNotifications.post(ctx, "File received · ${link.peerName}", "$name → Downloads")
            }.onFailure {
                Log.w(TAG, "receiveFile failed: ${it.message}")
                KdeNotifications.post(ctx, "Receive failed · ${link.peerName}", it.message ?: "error")
            }
        }, "kde-share-download").apply { isDaemon = true; start() }
    }

    /** Write [size] bytes (or until EOF) from [src] into Downloads via
     *  MediaStore (scoped-storage safe, API 29+). */
    private fun saveToDownloads(ctx: Context, name: String, size: Long, src: java.io.InputStream) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val item = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("MediaStore insert failed")
            resolver.openOutputStream(item)?.use { out -> copyExact(src, out, size) }
                ?: throw java.io.IOException("openOutputStream failed")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(item, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            java.io.File(dir, name).outputStream().use { out -> copyExact(src, out, size) }
        }
    }

    /** Copy up to [size] bytes (KDE payload streams exactly payloadSize, then
     *  the sender closes — so we stop at size OR EOF, whichever first). */
    private fun copyExact(src: java.io.InputStream, out: java.io.OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var remaining = if (size > 0) size else Long.MAX_VALUE
        while (remaining > 0) {
            val want = minOf(buf.size.toLong(), remaining).toInt()
            val n = src.read(buf, 0, want)
            if (n < 0) break
            out.write(buf, 0, n)
            remaining -= n
        }
        out.flush()
    }
}
