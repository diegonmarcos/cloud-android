package com.diegonmarcos.superapp.adbdebug

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runs the DATA-DRIVEN diagnostic bundles declared in
 * build.json::shizuku_diagnostics.bundles[] (baked into
 * [BuildConfig.ADB_DIAG_BUNDLES_B64] by this module's build.gradle).
 *
 * Each bundle is a named list of `sh -c` commands; [runBundle] executes
 * them through [ShizukuAdb] (shell uid 2000) and returns a JSON document
 * the loopback DevControlServer serves verbatim. Adding a diagnostic is
 * a one-line build.json edit — no code change here (Pillar: DATA-DRIVEN).
 */
object AdbDiagnostics {

    /** Parsed catalog: ordered list of (id, label, commands[(id,cmd)]). */
    private val catalog: List<Bundle> by lazy { decodeCatalog() }

    data class Cmd(val id: String, val cmd: String)
    data class Bundle(val id: String, val label: String, val commands: List<Cmd>)

    fun bundleIds(): List<String> = catalog.map { it.id }

    private fun decodeCatalog(): List<Bundle> = runCatching {
        val json = String(Base64.decode(BuildConfig.ADB_DIAG_BUNDLES_B64, Base64.DEFAULT))
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val cmds = o.optJSONArray("commands") ?: JSONArray()
            Bundle(
                id = o.getString("id"),
                label = o.optString("label", o.getString("id")),
                commands = (0 until cmds.length()).map { j ->
                    val c = cmds.getJSONObject(j)
                    Cmd(c.getString("id"), c.getString("cmd"))
                },
            )
        }
    }.getOrDefault(emptyList())

    /**
     * Execute the named bundle through the channel ladder ([ShellChannels]
     * — self-contained local-server first, Shizuku fallback) and return:
     *   {"bundle":"charger","label":"…","channel":"local-server",
     *    "ok":true,"results":[{"id":"dumpsys_battery","cmd":"…","out":"…"}]}
     * If no channel is ready, ok=false and each result's out explains why
     * (server not started / Shizuku not running).
     */
    fun runBundle(ctx: Context, bundleId: String): String {
        val bundle = catalog.firstOrNull { it.id == bundleId }
            ?: return JSONObject()
                .put("error", "unknown bundle '$bundleId'")
                .put("available", JSONArray(bundleIds()))
                .toString()

        val channel = ShellChannels.active(ctx)
        val results = JSONArray()
        for (c in bundle.commands) {
            val out = if (channel != null)
                (channel.exec(ctx, c.cmd) ?: "ERR: ${channel.name()} exec returned null")
            else
                "SKIPPED: no shell channel ready — " + LocalShellChannel.status(ctx)
            results.put(
                JSONObject()
                    .put("id", c.id)
                    .put("cmd", c.cmd)
                    .put("out", out)
            )
        }
        return JSONObject()
            .put("bundle", bundle.id)
            .put("label", bundle.label)
            .put("channel", channel?.name() ?: "none")
            .put("ok", channel != null)
            .put("results", results)
            .toString()
    }
}
