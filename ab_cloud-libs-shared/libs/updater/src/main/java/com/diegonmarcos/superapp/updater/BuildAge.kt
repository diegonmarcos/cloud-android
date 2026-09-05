package com.diegonmarcos.superapp.updater

/**
 * How OLD a build is, read straight out of its versionCode.
 *
 * The ship engine encodes wall-clock time into every versionCode:
 *
 *     versionCode = 3_000_000 + (minutes since 2026-01-01T00:00:00Z)
 *
 * which means a device can answer "how far behind am I?" with NO network and
 * NO extra metadata — the number it already has is a timestamp. That is the
 * exact arithmetic the 2026-09 stranded-fleet incident was diagnosed with from
 * the outside, and there is no reason the phone cannot do it for itself.
 *
 * Pure and side-effect free on purpose: the staleness banner, the advisory
 * notification and the recovery screen all render the same sentence, and a
 * second hand-written copy of this formula is one edit away from two surfaces
 * disagreeing about how stale the device is.
 */
object BuildAge {

    /** 2026-01-01T00:00:00Z, the epoch the ship engine counts minutes from. */
    const val EPOCH_MS = 1_767_225_600_000L

    /** The floor the scheme starts at. Below it the code is from the old
     *  per-workflow `version_code + GITHUB_RUN_NUMBER` scheme and carries no
     *  time at all — which must read as "unknown", never as "1970". */
    const val BASE = 3_000_000L

    /** Sanity ceiling: BASE plus ~20 years of minutes. A code above this is
     *  not a wall-clock code either, and guessing would print a date in the
     *  next century as though it were a fact. */
    private const val CEILING = BASE + 20L * 365L * 24L * 60L

    /** When this build was cut, or null when [code] is not a wall-clock code. */
    fun builtAtMs(code: Long): Long? =
        if (code in BASE..CEILING) EPOCH_MS + (code - BASE) * 60_000L else null

    /** Milliseconds between two builds, or null when either is undecodable. */
    fun gapMs(olderCode: Long, newerCode: Long): Long? {
        val a = builtAtMs(olderCode) ?: return null
        val b = builtAtMs(newerCode) ?: return null
        return b - a
    }

    /** "3 days", "5 hours", "12 minutes" — the largest honest unit. Never
     *  rounds a real gap down to "0": anything under a minute reads as
     *  "less than a minute" so a positive gap never renders as no gap. */
    fun humanDuration(ms: Long): String {
        val m = ms / 60_000L
        return when {
            m < 1L -> "less than a minute"
            m < 60L -> "$m minute" + plural(m)
            m < 24L * 60L -> (m / 60L).let { "$it hour" + plural(it) }
            else -> (m / (24L * 60L)).let { "$it day" + plural(it) }
        }
    }

    private fun plural(n: Long) = if (n == 1L) "" else "s"

    /**
     * One sentence for the UI, or null when there is nothing to say — either
     * the codes are not wall-clock codes, or the installed build is not older.
     *
     * Returning null rather than "up to date" is deliberate: the caller must
     * not be able to render a staleness warning out of an absent answer.
     */
    fun behindBy(installedCode: Long, availableCode: Long): String? {
        val gap = gapMs(installedCode, availableCode) ?: return null
        if (gap <= 0L) return null
        return "your build is ${humanDuration(gap)} behind"
    }

    /** ISO-ish local timestamp for a build, or "unknown build date". */
    fun describe(code: Long): String {
        val at = builtAtMs(code) ?: return "unknown build date (versionCode $code)"
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
            .format(java.util.Date(at))
    }
}
