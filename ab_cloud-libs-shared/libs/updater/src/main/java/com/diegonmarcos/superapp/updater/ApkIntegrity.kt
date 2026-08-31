package com.diegonmarcos.superapp.updater

import java.io.File
import java.security.MessageDigest

/**
 * The one place this module hashes or sanity-checks an APK.
 *
 * There were three copies of sha256 (Fleet, GhcrClient, UpdateChecker), the
 * same 64 kB loop written three slightly different ways — one of them broke on
 * `n < 0` where the others used `n <= 0`. Three copies is not a tidiness
 * problem: it is three places to fix when one of them is wrong, and no way to
 * tell from a call site which behaviour you got.
 */
internal object ApkIntegrity {

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Weakest useful test: does this parse as a zip with at least one entry.
     *
     * A truncated download is a valid PREFIX of a valid APK — it opens, it
     * reads, and it only fails when something looks for the central directory
     * at the end. That is why a 383 kB fragment of a 29 MB APK reached
     * PackageInstaller and came back as "Failed to load asset path" instead of
     * "your download stopped early".
     */
    fun looksLikeApk(file: File): Boolean = runCatching {
        java.util.zip.ZipFile(file).use { it.size() > 0 }
    }.getOrDefault(false)
}
