package com.diegonmarcos.superapp.updater

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * WorkManager job: download a plain-HTTPS APK (a direct asset URL — e.g. a
 * GitHub release attachment) and hand it to [UpdateInstaller] for a FOREIGN
 * package. This is how the launcher installs companion apps (Cloud-Comms /
 * Cloud-IDE hubs) on first tile tap.
 *
 * Distinct from [UpdateWorker]: that one talks to GHCR's OCI API to self-update
 * Cloud-SuperApp by digest. Here the URL + target package are passed in as
 * input data (sourced from build.json::ui.external_apps), there is no manifest
 * step, and the install targets another applicationId. Progress is published on
 * the shared [UpdateProgress] bus so the existing overlay/notification surface
 * reflects the download with no extra UI.
 */
class ApkInstallWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url   = inputData.getString(KEY_URL)
        val pkg   = inputData.getString(KEY_PKG)
        val label = inputData.getString(KEY_LABEL) ?: pkg ?: "app"
        if (url.isNullOrBlank() || pkg.isNullOrBlank()) {
            Log.w(TAG, "missing url/pkg input — url=$url pkg=$pkg")
            return@withContext Result.failure()
        }
        try {
            val apk = File(applicationContext.cacheDir, "companion-$pkg.apk")
            UpdateProgress.update(UpdateProgress.State.Downloading(0, 0, 0))
            download(url, apk) { bytes, total ->
                val pct = if (total > 0) ((bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                UpdateProgress.update(UpdateProgress.State.Downloading(pct, bytes, total))
            }
            Log.i(TAG, "downloaded $label (${apk.length()} bytes) → installing $pkg")
            // install() flips UpdateProgress to Installing and commits the
            // PackageInstaller session. Do NOT force Done here — that races the
            // async session and would overwrite the "Installing…" overlay before
            // the system installer even appears. The terminal state (Done /
            // Failed) is driven by PackageInstallerReceiver from the real
            // PackageInstaller callback, so the overlay tracks the actual
            // install lifecycle instead of flickering straight to "Done".
            UpdateInstaller(applicationContext).install(apk, pkg)
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "install of $pkg failed: ${t.message}", t)
            UpdateProgress.update(UpdateProgress.State.Failed(t.message ?: t.toString()))
            Result.failure()
        }
    }

    /**
     * Stream [url] to [target], manually following redirects. GitHub release
     * links 302 to objects.githubusercontent.com (and may cross protocol), so
     * we disable instanceFollowRedirects and chase Location ourselves to stay
     * correct across hosts. Progress callbacks are throttled to ~12/s.
     */
    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        var current = url
        var hops = 0
        while (true) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/octet-stream")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                    ?: throw IOException("HTTP $code with no Location for $current")
                conn.disconnect()
                if (++hops > MAX_REDIRECTS) throw IOException("too many redirects for $url")
                current = loc
                continue
            }
            if (code !in 200..299) {
                val msg = conn.errorStream?.bufferedReader()?.readText()
                throw IOException("HTTP $code for $current: $msg")
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
                        if (now - lastTick >= 80) { onProgress(soFar, total); lastTick = now }
                    }
                    onProgress(soFar, total)
                }
            }
            return
        }
    }

    companion object {
        private const val TAG = "Updater/ApkInstall"
        private const val MAX_REDIRECTS = 5
        const val KEY_URL = "url"
        const val KEY_PKG = "pkg"
        const val KEY_LABEL = "label"
    }
}
