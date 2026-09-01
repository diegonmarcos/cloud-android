package org.fossify.phone.helpers

import android.content.Context
import org.fossify.phone.spam.ScreeningLog
import java.io.File

/**
 * Cloud Dialer (patch 0013): persistence for the screened-call history.
 * Thin Android wrapper over the pure ScreeningLog model — one private JSON
 * file, synchronized so the CallScreeningService and the UI never interleave
 * writes. All operations are best-effort: a corrupt/missing file reads as an
 * empty history, never crashes call screening.
 */
object ScreeningLogStore {

    private const val FILE_NAME = "screening_log.json"
    private val lock = Any()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun read(context: Context): List<ScreeningLog.Entry> = synchronized(lock) {
        runCatching { ScreeningLog.parse(file(context).readText()) }.getOrDefault(emptyList())
    }

    fun record(context: Context, number: String, reason: String) = synchronized(lock) {
        val entries = runCatching { ScreeningLog.parse(file(context).readText()) }.getOrDefault(emptyList())
        val updated = ScreeningLog.add(entries, ScreeningLog.Entry(number, System.currentTimeMillis(), reason))
        runCatching { file(context).writeText(ScreeningLog.serialize(updated)) }
    }

    fun clear(context: Context) = synchronized(lock) {
        runCatching { file(context).delete() }
    }
}
