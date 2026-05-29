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

    /** Streams the blob into [target]. Caller verifies sha256 against [digest]. */
    fun blob(digest: String, token: String, target: File) {
        val url = URL("https://$registry/v2/$repo/blobs/$digest")
        val headers = mapOf("Authorization" to "Bearer $token")
        openGet(url, headers).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
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
                throw java.io.IOException("HTTP $responseCode for $url: $msg")
            }
        }.inputStream
}
