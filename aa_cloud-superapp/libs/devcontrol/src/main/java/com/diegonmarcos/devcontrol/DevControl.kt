package com.diegonmarcos.devcontrol

import android.content.Context

/**
 * App-supplied configuration for the devcontrol lib. Everything app-specific
 * (identity, build metadata, notification sink) is passed in here so the lib
 * itself reads NO build.json and NO app BuildConfig — that's what lets the
 * whole module be symlinked verbatim into every constellation app.
 */
data class DevControlConfig(
    /** Filesystem-safe app id, e.g. "cloud-superapp". Names 0_logs/<appId>/ etc. */
    val appId: String,
    val versionName: String,
    val versionCode: Int,
    val gitSha: String,
    val buildTimestamp: String = "",
    /** App wires this to its own notification feed; null = no in-app notice. */
    val onCrash: ((title: String, body: String) -> Unit)? = null,

    // ── DevControlServer identity (was app BuildConfig.*) ──────────────────
    /** Package/application id, e.g. "com.diegonmarcos.superapp". Used by the
     *  /system/about + /diagnostics/* endpoints and the download filename. */
    val applicationId: String = appId,
    /** debug / release. */
    val buildType: String = "",

    // ── Cloud diagnostics sink (was BuildConfig.LOG_SINK_*) ────────────────
    /** OpenObserve `_json` ingest URL; blank disables /diagnostics/push. */
    val logSinkUrl: String = "",
    /** Stream name substituted into {stream} in [logSinkUrl]. */
    val logSinkStream: String = "cloud-app",

    // ── /system/about "stack" + "trees" payload (was BuildConfig.UI_*/STACK_*)
    //    App supplies these already-decoded (the lib does not read BuildConfig,
    //    so the app decodes its own base64 blobs before constructing config).
    //    null/blank → the About endpoint emits sane empty defaults.
    val aboutStack: AboutStack? = null,
)

/** Pre-decoded data for GET /system/about's "stack" + "trees" sections. */
data class AboutStack(
    /** {language: {files, loc}} JSON object. */
    val languagesJson: String = "{}",
    /** [dep, …] JSON array. */
    val frameworksJson: String = "[]",
    val buildAvgSecs: Long = 0,
    val buildLastSecs: Long = 0,
    val gradleConfigMs: Long = 0,
    val folderTree: String = "",
    val sitemapTree: String = "",
    val astTree: String = "",
)

/**
 * Entry point. The app calls [install] once from Application.onCreate with its
 * [DevControlConfig]; the engines (Trace, CrashLogger, LogExport) then read
 * their app-specific values from [config].
 */
object DevControl {
    @Volatile private var _config: DevControlConfig? = null
    val config: DevControlConfig
        get() = _config ?: error("DevControl.install() not called before use")

    /**
     * The foreground UI host the loopback server drives (nav/action/haptic/state).
     * Set by the app's Activity in onCreate/onResume, cleared (== this) in
     * onDestroy/onPause. Null while no Activity is foreground → server calls no-op.
     */
    @Volatile var host: DevControlHost? = null

    /**
     * App-registered HTTP endpoints for subsystems the lib knows nothing about
     * (battery/phone/energy/sysfs/adb). The app installs one provider; the
     * server dispatches ops it doesn't itself handle to it.
     */
    @Volatile var endpoints: DevControlEndpoints? = null

    fun install(context: Context, config: DevControlConfig) {
        _config = config
        // Order: config first so every engine below sees it.
        runCatching { Trace.install(context) }
        runCatching { CrashLogger.install(context) }
        runCatching { LogExport.startLogcat(context) }
        // Loopback HTTP control surface — started here so even if downstream
        // app init crashes the shell can still curl /logcat /trace /crashes.
        runCatching { DevControlServer.start(context) }
    }
}
