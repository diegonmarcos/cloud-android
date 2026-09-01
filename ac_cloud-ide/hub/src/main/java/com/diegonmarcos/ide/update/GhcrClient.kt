package com.diegonmarcos.ide.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal OCI registry client for GHCR. Mirrors what build.sh oras-pull does:
 *   1. anonymous bearer token from /token
 *   2. manifest GET (vnd.oci.image.manifest.v1+json)
 *   3. blob GET by digest
 *
 * registry/namespace come from BuildConfig (baked from build.json::release.ghcr
 * at gradle eval time). `image` is a constructor arg so the same client can pull
 * the hub APK OR any fork's image — zero hardcoded strings. Uses built-in
 * org.json (no serialization dep) to keep the thin hub light.
 */
internal class GhcrClient(
    private val image: String,
    private val registry: String = com.diegonmarcos.ide.BuildConfig.GHCR_REGISTRY,
    private val namespace: String = com.diegonmarcos.ide.BuildConfig.GHCR_NAMESPACE,
) {
    private val repo = "$namespace/$image"

    /** Anonymous bearer token for pull. Public packages: this just works. */
    fun token(): String {
        val url = URL("https://$registry/token?service=$registry&scope=repository:$repo:pull")
        val body = openGet(url, emptyMap()).bufferedReader().use { it.readText() }
        return JSONObject(body).getString("token")
    }

    data class ManifestLayer(val digest: String, val size: Long, val title: String)

    /** Returns the first layer (the APK blob — there's only one per artifact). */
    fun manifest(tag: String, token: String): ManifestLayer {
        val url = URL("https://$registry/v2/$repo/manifests/$tag")
        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.oci.image.manifest.v1+json",
        )
        val body = openGet(url, headers).bufferedReader().use { it.readText() }
        val layer = JSONObject(body).getJSONArray("layers").getJSONObject(0)
        val title = layer.optJSONObject("annotations")
            ?.optString("org.opencontainers.image.title")
            ?.takeIf { it.isNotEmpty() } ?: "$image.apk"
        return ManifestLayer(layer.getString("digest"), layer.getLong("size"), title)
    }

    /** Streams the blob into [target]. Caller verifies sha256 against the digest.
     *  [onProgress] is called (throttled) with (bytesRead, totalBytes). */
    fun blob(
        digest: String, token: String, target: File,
        onProgress: ((bytesRead: Long, totalBytes: Long) -> Unit)? = null,
    ) {
        val url = URL("https://$registry/v2/$repo/blobs/$digest")
        val conn = openConn(url, mapOf("Authorization" to "Bearer $token"))
        if (conn.responseCode !in 200..299) {
            val msg = conn.errorStream?.bufferedReader()?.readText()
            throw IOException("HTTP ${conn.responseCode} for $url: $msg")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            target.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var soFar = 0L
                var lastTick = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read < 0) break
                    output.write(buf, 0, read)
                    soFar += read
                    val now = System.currentTimeMillis()
                    if (now - lastTick >= 80) { onProgress?.invoke(soFar, total); lastTick = now }
                }
                onProgress?.invoke(soFar, total)
            }
        }
    }

    private fun openConn(url: URL, headers: Map<String, String>): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun openGet(url: URL, headers: Map<String, String>) =
        openConn(url, headers).run {
            if (responseCode !in 200..299) {
                val msg = errorStream?.bufferedReader()?.readText()
                throw IOException("HTTP $responseCode for $url: $msg")
            }
            inputStream
        }
}
