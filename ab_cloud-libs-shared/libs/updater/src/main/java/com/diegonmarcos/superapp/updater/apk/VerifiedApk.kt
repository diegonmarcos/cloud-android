package com.diegonmarcos.superapp.updater.apk

import com.diegonmarcos.superapp.updater.Updater
import android.util.Log
import java.io.File

/**
 * An APK that has been checked, carrying the evidence with it.
 *
 * ## Why this is a type and not a function
 * Every download used to return `File` and every installer used to take
 * `File`, so "verify before installing" was a convention each path
 * re-implemented — four downloads, three install entry points, four different
 * rules. One of them (the GitHub release path) guarded its only check with
 * `if (total > 0 …)`, so when the CDN omitted Content-Length the check turned
 * itself off and an unverified file went straight to the installer. Nothing
 * could notice, because a `File` looks exactly like every other `File`.
 *
 * The constructor is private and every factory ends at [ApkIntegrity]. An
 * installer takes a [VerifiedApk], so installing something unchecked is not a
 * bug you can write — it is a program that does not compile.
 *
 * [evidence] is one line naming what was actually proven, so a log or the
 * Diagnose screen can say WHICH guarantee this APK has rather than implying
 * they are all equal. They are not: a digest match means these are the exact
 * published bytes; a size match means only that nothing was lost in transit.
 */
class VerifiedApk private constructor(
    val file: File,
    val evidence: String,
) {
    val length: Long get() = file.length()

    override fun toString() = "${file.name} (${file.length()} B, $evidence)"

    companion object {
        private const val TAG = "Updater/Verify"

        /** Strongest: the bytes hash to the digest the registry published. */
        fun byDigest(file: File, expectedSha256: String): VerifiedApk? {
            if (!ApkIntegrity.looksLikeApk(file)) return reject(file, "not a zip")
            val actual = "sha256:" + ApkIntegrity.sha256(file)
            val want = if (expectedSha256.startsWith("sha256:")) expectedSha256 else "sha256:$expectedSha256"
            if (actual != want) return reject(file, "digest $actual != $want")
            return VerifiedApk(file, "digest ${actual.take(19)}…")
        }

        /**
         * Weaker: the byte count matches what the server said it would send.
         * For the GitHub release asset, which has no digest to offer. A size
         * of zero or less is NOT a pass — it means the server declined to say,
         * and "unknown" must never read as "fine".
         */
        fun bySize(file: File, expectedBytes: Long): VerifiedApk? {
            if (expectedBytes <= 0L) return reject(file, "no declared length — nothing to check against")
            if (file.length() != expectedBytes) return reject(file, "short read: ${file.length()} of $expectedBytes")
            if (!ApkIntegrity.looksLikeApk(file)) return reject(file, "right length but not a zip")
            return VerifiedApk(file, "size $expectedBytes B")
        }

        /**
         * Last resort: it parses as a zip and is not empty. For a caller that
         * has no digest and no length — it proves the file is structurally an
         * APK and nothing more. Enough to stop a truncation or an error page;
         * not enough to prove provenance. Prefer either of the above.
         */
        fun structural(file: File): VerifiedApk? {
            if (!ApkIntegrity.looksLikeApk(file)) return reject(file, "not a zip")
            if (file.length() <= 0L) return reject(file, "empty")
            return VerifiedApk(file, "structure only (no digest, no length)")
        }

        private fun reject(file: File, why: String): VerifiedApk? {
            Log.w(TAG, "rejected ${file.name}: $why")
            return null
        }
    }
}
