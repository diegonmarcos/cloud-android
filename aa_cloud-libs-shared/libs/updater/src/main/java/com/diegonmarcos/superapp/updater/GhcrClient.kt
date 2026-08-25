package com.diegonmarcos.superapp.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OCI registry client for GHCR. Mirrors what build.sh oras-pull does:
 *   1. anonymous bearer token from /token
 *   2. manifest GET (vnd.oci.image.manifest.v1+json)
 *   3. blob GET by digest
 *
 * All registry/namespace/image come from BuildConfig (which gets them from
 * build.json::release.ghcr at gradle eval time). Zero hardcoded strings.
 */
internal class GhcrClient(
    private val registry: String = BuildConfig.GHCR_REGISTRY,
    private val namespace: String = BuildConfig.GHCR_NAMESPACE,
    private val image: String = BuildConfig.GHCR_IMAGE,
) {
    private val repo = "$namespace/$image"
    private val json = Json { ignoreUnknownKeys = true }

    /** Non-2xx GHCR response, carrying the status code so callers can tell a
     *  genuinely-absent tag (404 — e.g. an ABI variant not published yet)
     *  from a transient/auth failure. */
    class HttpException(val code: Int, val target: String, body: String?) :
        java.io.IOException("HTTP $code for $target: $body")

    /** Anonymous bearer token for pull. Public packages: this just works. */
    fun token(): String {
        val url = URL("https://$registry/token?service=$registry&scope=repository:$repo:pull")
        return openGet(url, mapOf()).use { stream ->
            val body = stream.bufferedReader().readText()
            json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
        }
    }

    data class ManifestLayer(val digest: String, val size: Long, val title: String, val revision: String?)

    /** Returns the first layer (the APK blob) + the manifest-level
     *  `org.opencontainers.image.revision` (short git sha) for code-identity
     *  update checks. */
    fun manifest(tag: String, token: String): ManifestLayer {
        val url = URL("https://$registry/v2/$repo/manifests/$tag")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.oci.image.manifest.v1+json",
        )
        return openGet(url, headers).use { stream ->
            val body = stream.bufferedReader().readText()
            val root = json.parseToJsonElement(body).jsonObject
            val layer = root["layers"]!!.jsonArray[0].jsonObject
            ManifestLayer(
                digest = layer["digest"]!!.jsonPrimitive.content,
                size = layer["size"]!!.jsonPrimitive.content.toLong(),
                title = layer["annotations"]?.jsonObject?.get("org.opencontainers.image.title")
                    ?.jsonPrimitive?.content ?: "$image.apk",
                revision = root["annotations"]?.jsonObject?.get("org.opencontainers.image.revision")
                    ?.jsonPrimitive?.content,
            )
        }
    }

    /** Streams the blob into [target]. Caller verifies sha256 against [digest].
     *  [onProgress] is called periodically with (bytesRead, totalBytes);
     *  totalBytes may be -1 if the server didn't send Content-Length.
     *  [shouldCancel] is polled every 64KiB chunk so a WorkManager cancel
     *  (Cancel button) actually aborts the blocking read loop — throwing
     *  CancellationException. A wedged socket still bounds at readTimeout. */
    fun blob(
        digest: String, token: String, target: File,
        shouldCancel: () -> Boolean = { false },
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        // Content-addressed cache HIT. Both callers name `target` after the
        // digest, so a file already sitting there with that exact content IS
        // this blob - re-downloading 35MB to produce bytes we already have is
        // pure waste, and worse, the old code opened `target` for writing
        // FIRST, so a failed or cancelled retry truncated the good copy it was
        // about to replace. That is why a failed install "lost" the download.
        if (target.isFile && runCatching { "sha256:" + sha256(target) == digest }.getOrDefault(false)) {
            onProgress?.invoke(target.length(), target.length())
            return
        }
        val url = URL("https://$registry/v2/$repo/blobs/$digest")
        val headers = mapOf("Authorization" to "Bearer $token")
        val conn = openConn(url, headers)
        if (conn.responseCode !in 200..299) {
            val msg = conn.errorStream?.bufferedReader()?.readText()
            throw java.io.IOException("HTTP ${conn.responseCode} for $url: $msg")
        }
        val total = conn.contentLengthLong
        // Download to a sibling and rename on success, so `target` is either
        // absent or complete-and-verified - never a half-written APK that the
        // cache check above would then have to distrust.
        val part = File(target.parentFile, target.name + ".part")
        try {
        conn.inputStream.use { input ->
            part.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var soFar = 0L
                var lastTick = 0L
                while (true) {
                    if (shouldCancel()) throw java.util.concurrent.CancellationException("download cancelled")
                    read = input.read(buf)
                    if (read < 0) break
                    output.write(buf, 0, read)
                    soFar += read
                    // Throttle callbacks — at most one per 80ms.
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 80) {
                        onProgress?.invoke(soFar, total)
                        lastTick = now
                    }
                }
                onProgress?.invoke(soFar, total)
            }
        }
        if (!part.renameTo(target)) {
            // Rename can only fail across filesystems; both live in cacheDir,
            // but copy rather than fail the whole update if it ever does.
            part.copyTo(target, overwrite = true)
        }
        } finally {
            part.delete()
        }
    }

    /** Drop older cache entries for the same app - [prefix] is per-app and the
     *  rest of the name is the digest, so anything matching but not [keep] is a
     *  superseded version. Without this the cache grows one APK per release
     *  until Android evicts the whole directory, taking the current one too. */
    fun pruneCache(prefix: String, keep: File) {
        keep.parentFile?.listFiles { f: File -> f.name.startsWith(prefix) && f != keep }
            ?.forEach { it.delete() }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun openConn(url: URL, headers: Map<String, String>): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }

    private fun openGet(url: URL, headers: Map<String, String>) =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (responseCode !in 200..299) {
                val msg = errorStream?.bufferedReader()?.readText()
                throw HttpException(responseCode, url.toString(), msg)
            }
        }.inputStream
}
