package com.diegonmarcos.superapp.profile

import android.content.Context
import android.util.Log
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.ConfigSyncClient
import com.diegonmarcos.superapp.network.WireGuardPrefs
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.wireguard.config.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.StringReader

/**
 * Applies a fetched cloud config artifact onto the app's local stores.
 *
 * Artifact shape (contract with the cloud side):
 * ```
 * { "_meta": { "description", "format_version", "user" },
 *   "_generated": "<iso8601>",
 *   "profile":   {...},   "wireguard": {...},
 *   "mesh":      {...},   "services":  {...} }
 * ```
 *
 * TRANSACTIONAL-ISH, on purpose. [apply] runs in two passes:
 *   1. PLAN  — parse and validate every section, staging each write as a
 *              closure. Anything that fails validation is recorded as a
 *              skip and nothing has been written yet.
 *   2. COMMIT — run the staged closures.
 * A malformed WireGuard block therefore cannot leave you with a new profile
 * and half a tunnel. The returned [Report] names every section that was
 * applied AND every section that was not, with the reason — there is no
 * silent partial apply and no silent drop.
 *
 * Diagnose with:  adb logcat -s ConfigSync
 */
object ConfigAutoImport {

    private const val TAG = ConfigSyncClient.TAG

    /** Marker glyphs used in the report, so the UI stays dumb. */
    private const val OK = "✓"     // ✓ applied
    private const val SKIP = "–"   // – received, deliberately not applied
    private const val BAD = "✗"    // ✗ present but unusable

    data class Report(val ok: Boolean, val lines: List<String>) {
        fun text(): String = lines.joinToString("\n")
    }

    /**
     * @param root the parsed artifact from [ConfigSyncClient.Outcome.Ok].
     */
    fun apply(context: Context, root: JSONObject): Report {
        val lines = mutableListOf<String>()
        val commits = mutableListOf<() -> Unit>()

        // ── format_version gate FIRST. Refuse the whole artifact rather than
        //    guessing at a shape we do not know.
        val meta = root.optJSONObject("_meta")
        val expected = BuildConfig.UI_CONFIG_SOURCE_FORMAT_VERSION
        val declared = if (meta == null) null else majorVersion(meta.opt("format_version"))
        if (meta == null || declared == null) {
            Log.w(TAG, "artifact has no _meta.format_version")
            return Report(false, listOf("$BAD schema mismatch — artifact has no _meta.format_version (expected $expected). Nothing applied."))
        }
        if (declared != expected) {
            Log.w(TAG, "format_version $declared != expected $expected")
            return Report(false, listOf("$BAD schema mismatch — artifact format_version $declared, this build understands $expected. Nothing applied."))
        }
        val who = meta.optString("user", "")
        lines += "artifact v$declared${if (who.isNotBlank()) " for \"$who\"" else ""}" +
            root.optString("_generated", "").let { if (it.isBlank()) "" else ", generated $it" }

        // ── plan each section ────────────────────────────────────────────
        lines += planProfile(context, root.optJSONObject("profile"), commits)
        lines += planWireGuard(context, root.optJSONObject("wireguard"), commits)
        lines += planBaked("mesh", root.opt("mesh"), "data/mesh.json")
        lines += planBaked("services", root.opt("services"), "data/services_*.json")
        lines += planConfigsBlob(context, root.optJSONObject("configs"), commits)

        // Anything the contract does not cover is REPORTED, never dropped.
        val known = setOf("_meta", "_generated", "profile", "wireguard", "mesh", "services", "configs")
        val unknown = root.keys().asSequence().filterNot { it in known }.toList()
        if (unknown.isNotEmpty()) lines += "$SKIP unknown sections ignored: ${unknown.joinToString(", ")}"

        if (commits.isEmpty()) {
            return Report(false, lines + "$BAD nothing applicable in this artifact — no store was written.")
        }

        // ── commit ───────────────────────────────────────────────────────
        return try {
            commits.forEach { it() }
            Log.i(TAG, "applied ${commits.size} section(s)")
            Report(true, lines)
        } catch (t: Throwable) {
            Log.e(TAG, "commit failed after validation: ${t.javaClass.simpleName}", t)
            Report(false, lines + "$BAD write failed at commit time: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ── profile ──────────────────────────────────────────────────────────

    private fun planProfile(context: Context, o: JSONObject?, commits: MutableList<() -> Unit>): String {
        if (o == null) return "$SKIP profile — not in artifact"
        // Only keys actually present are written; absent keys keep their
        // current local value rather than being blanked.
        val fields = linkedMapOf<String, String>()
        for (key in listOf("name", "email", "initials", "titles", "company", "location", "website")) {
            if (!o.has(key)) continue
            // Titles are " | "-separated in ProfilePrefs, so an array of
            // titles must join on that rather than the generic comma.
            fields[key] = flatten(o.opt(key), if (key == "titles") " | " else ", ")
        }

        if (fields.isEmpty()) return "$BAD profile — present but no recognised fields"
        commits += {
            val prefs = ProfilePrefs(context)
            for ((key, value) in fields) when (key) {
                "name"     -> prefs.name = value
                "email"    -> prefs.email = value
                "initials" -> prefs.initials = value
                "titles"   -> prefs.titles = value
                "company"  -> prefs.company = value
                "location" -> prefs.location = value
                "website"  -> prefs.website = value
            }
        }
        // picture/banner are LOCAL file paths written by the gallery picker;
        // a server-side value would point at a path that does not exist here.
        val images = listOf("picture_uri", "banner_uri").filter { o.has(it) }
        val tail = if (images.isEmpty()) "" else " ($SKIP ${images.joinToString(", ")}: local file paths, pick those on-device)"
        return "$OK profile — ${fields.size} fields: ${fields.keys.joinToString(", ")}$tail"
    }

    // ── wireguard ────────────────────────────────────────────────────────

    /**
     * Three artifact shapes, ONE parser.
     *
     *  A. `{"conf": "<wg-quick text with PrivateKey>"}`  — parsed by
     *     `Config.parse`, identical to picking the .conf file by hand.
     *  B. same, but with NO `PrivateKey` line (the cloud side refuses to
     *     ship key material from a public repo). The locally stored private
     *     key is spliced into the `[Interface]` block and the result goes
     *     through the SAME `Config.parse`. With no local key yet, the whole
     *     section is skipped with that stated plainly.
     *  C. `{"interface": {...}, "peers": [...]}` — structured fields, mapped
     *     straight onto [WireGuardPrefs] the way the on-screen form does.
     *     `private_key` is optional; absent keeps the stored one.
     *
     * Multiple tunnels may arrive under `tunnels: [{name, conf}]`.
     * [WireGuardPrefs] stores exactly ONE tunnel, so the first is applied
     * and the rest are reported as skipped — never silently dropped.
     */
    private fun planWireGuard(context: Context, o: JSONObject?, commits: MutableList<() -> Unit>): String {
        if (o == null) return "$SKIP wireguard — not in artifact"
        val prefs = WireGuardPrefs(context)
        val storedKey = prefs.interfacePrivateKey

        // Normalise every shape into an ordered list of (name, conf-text).
        val tunnels = mutableListOf<Pair<String, String>>()
        o.optJSONArray("tunnels")?.let { arr ->
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val conf = t.optString("conf", "")
                if (conf.isNotBlank()) tunnels += t.optString("name", "tunnel-${i + 1}") to conf
            }
        }
        if (tunnels.isEmpty() && o.optString("conf", "").isNotBlank()) {
            tunnels += o.optString("name", prefs.tunnelName) to o.optString("conf")
        }

        if (tunnels.isNotEmpty()) {
            val (name, rawConf) = tunnels.first()
            val hasKey = rawConf.lineSequence().any { it.trim().startsWith("PrivateKey", ignoreCase = true) }
            if (!hasKey && storedKey.isBlank()) {
                return "$SKIP wireguard — tunnel \"$name\" carries no PrivateKey and none is stored locally. " +
                    "Key material must still come from a file import: Configs › WireGuard › Import .conf."
            }
            val confText = if (hasKey) rawConf else spliceKey(rawConf, storedKey)
            val cfg = try {
                Config.parse(BufferedReader(StringReader(confText)))
            } catch (t: Throwable) {
                Log.w(TAG, "wireguard conf rejected by Config.parse: ${t.message}")
                return "$BAD wireguard — tunnel \"$name\" rejected by the WireGuard parser: ${t.message}"
            }
            val peerCount = cfg.peers.size
            commits += {
                prefs.tunnelName = name
                prefs.hydrateFromConfig(cfg)
            }
            val keyNote = if (hasKey) "private key from artifact" else "kept the private key already on this device"
            val extra = if (tunnels.size > 1) {
                " $SKIP ${tunnels.size - 1} further tunnel(s) skipped (${tunnels.drop(1).joinToString(", ") { it.first }}): this app stores one tunnel."
            } else ""
            return "$OK wireguard — tunnel \"$name\", $peerCount peers ($keyNote).$extra"
        }

        // Shape C — structured.
        val iface = o.optJSONObject("interface")
        val peersArr = o.optJSONArray("peers")
        if (iface == null && peersArr == null) return "$BAD wireguard — present but has no conf/tunnels/interface/peers"

        val newKey = iface?.optString("private_key", "")?.takeIf { it.isNotBlank() }
        if (newKey == null && storedKey.isBlank() && iface != null) {
            // Not fatal: address/DNS/peers are still worth applying, and the
            // Connect button will tell the user the key is missing. Say so.
            Log.i(TAG, "structured wireguard section has no private_key and none stored")
        }
        val name = o.optString("name", "").takeIf { it.isNotBlank() }
        val peers = peersArr?.let { parsePeers(it) } ?: emptyList()
        commits += {
            name?.let { prefs.tunnelName = it }
            iface?.let { i ->
                newKey?.let { prefs.interfacePrivateKey = it }
                if (i.has("address"))     prefs.interfaceAddress = flatten(i.opt("address"))
                if (i.has("dns"))         prefs.interfaceDns = flatten(i.opt("dns"))
                if (i.has("listen_port")) prefs.interfaceListenPort = flatten(i.opt("listen_port"))
                if (i.has("mtu"))         prefs.interfaceMtu = flatten(i.opt("mtu"))
            }
            if (peers.isNotEmpty()) prefs.savePeers(peers)
        }
        val keyNote = when {
            newKey != null -> "private key from artifact"
            storedKey.isNotBlank() -> "kept the private key already on this device"
            else -> "NO private key — import your .conf in Configs › WireGuard before connecting"
        }
        return "$OK wireguard — structured, ${peers.size} peers ($keyNote)."
    }

    private fun parsePeers(arr: JSONArray): List<WireGuardPrefs.PeerData> =
        (0 until arr.length()).mapNotNull { i ->
            val p = arr.optJSONObject(i) ?: return@mapNotNull null
            WireGuardPrefs.PeerData(
                name                = p.optString("name", "peer-${i + 1}"),
                publicKey           = p.optString("public_key", ""),
                presharedKey        = p.optString("preshared_key", ""),
                endpoint            = p.optString("endpoint", ""),
                allowedIps          = flatten(p.opt("allowed_ips")),
                persistentKeepalive = p.optString("persistent_keepalive", ""),
            )
        }

    /** Insert `PrivateKey = …` right after `[Interface]` so a key-less
     *  artifact can still go through the upstream parser unchanged. */
    private fun spliceKey(conf: String, privateKey: String): String {
        val out = StringBuilder()
        var inserted = false
        conf.lineSequence().forEach { line ->
            out.append(line).append('\n')
            if (!inserted && line.trim().equals("[Interface]", ignoreCase = true)) {
                out.append("PrivateKey = ").append(privateKey).append('\n')
                inserted = true
            }
        }
        return if (inserted) out.toString() else "[Interface]\nPrivateKey = $privateKey\n$conf"
    }

    // ── build-time-baked sections ────────────────────────────────────────

    /**
     * `mesh` and `services` are BAKED at build time (app/build.gradle reads
     * the data/ snapshots into BuildConfig.MESH_JSON_B64 and the
     * SERVICES_PUBLIC/PRIVATE_B64 fields, and
     * Sections caches the parsed result process-wide). There is no runtime
     * store to write, so we say that rather than pretending to apply it.
     */
    private fun planBaked(section: String, value: Any?, source: String): String {
        if (value == null || value == JSONObject.NULL) return "$SKIP $section — not in artifact"
        val count = when (value) {
            is JSONArray -> value.length()
            is JSONObject -> value.optJSONArray("nodes")?.length()
                ?: value.optJSONArray("items")?.length()
                ?: value.length()
            else -> 0
        }
        return "$SKIP $section — $count entries received, NOT applied: this app bakes $section at build time ($source). Ship a new APK to change it."
    }

    // ── optional encrypted blob (the manual Import store) ─────────────────

    /**
     * Optional `configs` section carrying the same shape as
     * build.json::ui.import_schema. Writing it here is what makes the
     * auto-import a real replacement for pasting into the Import screen —
     * it lands in the same EncryptedSharedPreferences store.
     */
    private fun planConfigsBlob(context: Context, o: JSONObject?, commits: MutableList<() -> Unit>): String {
        if (o == null) return "$SKIP configs — not in artifact"
        val raw = o.toString()
        commits += { ConfigsPrefs(context).json = raw }
        return "$OK configs — ${o.length()} groups (${o.keys().asSequence().joinToString(", ")}) into encrypted prefs"
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** JSON scalars, and arrays of scalars, both render as the comma string
     *  the prefs layer expects (`"10.0.0.9/32, fd00::9/128"`). */
    private fun flatten(value: Any?, separator: String = ", "): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).joinToString(separator) { value.opt(it)?.toString().orEmpty() }
        else -> value.toString()
    }

    /** `1`, `"1"`, `"1.2"` all read as major version 1; anything else null. */
    private fun majorVersion(value: Any?): Int? = when (value) {
        null, JSONObject.NULL -> null
        is Number -> value.toInt()
        else -> value.toString().substringBefore('.').trim().toIntOrNull()
    }
}
