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

    data class ManifestLayer(val digest: String, val size: Long, val title: String)

    /** Returns the first layer (the APK blob — there's only one per artifact). */
    fun manifest(tag: String, token: String): ManifestLayer {
        val url = URL("https://$registry/v2/$repo/manifests/$tag")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.oci.image.manifest.v1+json",
        )
        return openGet(url, headers).use { stream ->
            val body = stream.bufferedReader().readText()
            val layer = json.parseToJsonElement(body).jsonObject["layers"]!!.jsonArray[0].jsonObject
            ManifestLayer(
                digest = layer["digest"]!!.jsonPrimitive.content,
                size = layer["size"]!!.jsonPrimitive.content.toLong(),
                title = layer["annotations"]?.jsonObject?.get("org.opencontainers.image.title")
                    ?.jsonPrimitive?.content ?: "$image.apk",
            )
        }
    }

    /** Streams the blob into [target]. Caller verifies sha256 against [digest].
     *  [onProgress] is called periodically with (bytesRead, totalBytes);
     *  totalBytes may be -1 if the server didn't send Content-Length. */
    fun blob(
        digest: String, token: String, target: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val url = URL("https://$registry/v2/$repo/blobs/$digest")
        val headers = mapOf("Authorization" to "Bearer $token")
        val conn = openConn(url, headers)
        if (conn.responseCode !in 200..299) {
            val msg = conn.errorStream?.bufferedReader()?.readText()
            throw java.io.IOException("HTTP ${conn.responseCode} for $url: $msg")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var read: Int
                var soFar = 0L
                var lastTick = 0L
                while (true) {
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
