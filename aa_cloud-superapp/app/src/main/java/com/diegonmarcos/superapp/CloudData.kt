package com.diegonmarcos.superapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live source-of-truth for many app surfaces — fetched from the public
 * `cloud-data` GitHub repo's `_cloud-data-consolidated.json`. This single
 * JSON has the full picture of the stack: every service's DNS, proxy
 * domain, VM, ports, deps, etc. The app reuses it for the C3/Health
 * probe table and (later) wg tunnels, c3 stack list, etc.
 *
 * Network strategy:
 *  1. Try a fresh HTTP GET from the GitHub raw URL (5 s connect timeout).
 *  2. On success: write to cacheDir and return.
 *  3. On failure: fall back to the last cached copy if any.
 *  4. In-memory single-flight cache for the lifetime of the process.
 */
object CloudData {

    private const val RAW_URL =
        "https://raw.githubusercontent.com/diegonmarcos/cloud-data/main/_cloud-data-consolidated.json"
    private const val CACHE_FILE = "cloud_data_consolidated.json"

    @Volatile private var memCache: JSONObject? = null

    /** Load (and cache) the consolidated.json. `force=true` bypasses the
     *  in-memory cache and re-fetches over HTTP. */
    suspend fun load(ctx: Context, force: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        if (!force) memCache?.let { return@withContext it }

        val cacheFile = File(ctx.cacheDir, CACHE_FILE)

        val text = try {
            fetchFresh().also { cacheFile.writeText(it) }
        } catch (t: Throwable) {
            if (cacheFile.exists()) cacheFile.readText()
            else throw t
        }

        val obj = JSONObject(text)
        memCache = obj
        obj
    }

    private fun fetchFresh(): String {
        val conn = (URL(RAW_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "Cloud-SuperApp/1.0")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("cloud-data HTTP $code: ${body.take(200)}")
        return body
    }

    /** Services map: name → service object. */
    fun services(root: JSONObject): JSONObject =
        root.optJSONObject("services") ?: JSONObject()
}
