/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.core.decoder

import android.content.Context
import android.os.Build
import android.util.Log
import com.diegonmarcos.superapp.core.Telemetry
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The single loader for this app's four JNI libraries, and the reason a dead
 * one can no longer hide.
 *
 * Every call site used to be `runCatching { System.loadLibrary(x); nativeSelfTest() }`,
 * which collapses "this feature no longer exists" into one Log.w line. The
 * 2026-08-30 package rename off com.dot.gallery (b771ad243) moved the Kotlin
 * but not the JNI symbols in app/src/main/cpp/, so all four libraries loaded
 * and then failed to bind a single method: HEIF tiling, RAW decode, HEIF
 * encode and JPEG/PNG stream encode were dead for days and nothing above WARN
 * ever said so.
 *
 * [load] keeps the no-crash contract — an ABI genuinely without prebuilt
 * codecs must still run — but tells the three outcomes apart, because they are
 * not the same news:
 *
 *  - loadLibrary throws     the .so is not in the APK for this ABI.
 *                           Log.e plus telemetry kind="probe".
 *  - the self-test throws   the .so loaded but its symbols do not bind. That
 *                           is ALWAYS a build defect (it is exactly this bug).
 *                           Log.e plus telemetry kind="crash", the kind
 *                           c3-infra-api fans out to ntfy — the same channel
 *                           that finally diagnosed the launch crash 4fcadfadf
 *                           fixed.
 *  - the self-test is false the .so is the deliberate stub compiled for an ABI
 *                           without prebuilt codecs (the `#else` half of each
 *                           *_jni.cpp). Expected and designed for, so Log.w
 *                           and no telemetry.
 */
object NativeLibraries {

    private const val TAG = "NativeLibraries"

    @Volatile
    private var appContext: Context? = null

    /**
     * Reports raised before [attach] — a `by lazy` loader can win the race with
     * Application.onCreate — so the first and most interesting failure is not
     * the one that gets dropped.
     */
    private val pending = ConcurrentLinkedQueue<Report>()

    private data class Report(val kind: String, val title: String, val message: String)

    /** Wired once from GalleryApp.onCreate, before any decoder can be touched. */
    fun attach(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        while (true) {
            val queued = pending.poll() ?: return
            send(ctx, queued)
        }
    }

    /**
     * Loads `lib[library].so` and runs its [selfTest], returning whether the
     * library is genuinely usable. Never throws: callers fall back to their
     * pure-Kotlin path on false.
     */
    fun load(library: String, selfTest: () -> Boolean): Boolean {
        runCatching { System.loadLibrary(library) }.onFailure {
            val detail = "${it.javaClass.simpleName}: ${it.message}"
            Log.e(TAG, "lib$library.so is not loadable on this ABI: $detail")
            report(library, "probe", "native library missing: $library", detail)
            return false
        }
        val passed = runCatching { selfTest() }.getOrElse {
            val detail = "${it.javaClass.simpleName}: ${it.message}"
            Log.e(TAG, "lib$library.so loaded but its JNI symbols do not bind: $detail")
            report(library, "crash", "native symbols unbound: $library", detail)
            return false
        }
        if (!passed) {
            Log.w(TAG, "lib$library.so is a stub build; its feature is disabled on this ABI")
        }
        return passed
    }

    private fun report(library: String, kind: String, title: String, message: String) {
        val queued = Report(kind, title, "$message (abi=${Build.SUPPORTED_ABIS.firstOrNull()})")
        val ctx = appContext
        if (ctx == null) pending.add(queued) else send(ctx, queued)
    }

    private fun send(context: Context, report: Report) {
        // Telemetry.post is already fire-and-forget and swallows its own
        // failures, so a dead network cannot turn a disabled feature into a
        // crash.
        Telemetry.post(
            context = context,
            kind = report.kind,
            title = report.title,
            message = report.message,
        )
    }
}
