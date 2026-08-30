package com.diegonmarcos.cloudcontacts

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.diegonmarcos.superapp.contacts.Channels
import com.diegonmarcos.superapp.contacts.ChannelConfig
import com.diegonmarcos.superapp.contacts.DeviceContacts
import com.diegonmarcos.superapp.contacts.MergeEngine
import com.diegonmarcos.superapp.contacts.Person
import com.diegonmarcos.superapp.contacts.RawContact
import com.diegonmarcos.superapp.contacts.SocialImport
import com.diegonmarcos.superapp.contacts.SocialStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * JS bridge exposed to contacts.html as `window.Native` (see MainActivity —
 * that name, not "ContactsBridge", is what addJavascriptInterface registers).
 *
 * SECURITY: same note as NewsBridge/CalBridge — this bridge is only safe to
 * attach because the WebView it lives on loads a LOCAL asset we control
 * (file:///android_asset/contacts.html). Never attach a JavascriptInterface
 * to a WebView that can navigate to remote content.
 *
 * Every method returns a JSON STRING (never a raw object), built with
 * org.json, same convention as the sibling apps' bridges.
 *
 * The merged [Person] list is the one piece of real state here: it's
 * expensive-ish to recompute (a ContactsContract query + the social-import
 * store + MergeEngine's merge pass) so it's cached and only rebuilt when
 * something that could change it happens — an import, a source removal, or
 * a permission grant. @JavascriptInterface methods already run on a
 * background binder thread (never the WebView's JS thread), so there's no
 * UI-thread-blocking concern here — @Synchronized is just cache-consistency
 * guarding against two JS calls landing on the binder thread pool at once.
 */
class ContactsBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView,
    private val requestContactsPermission: () -> Unit,
    private val launchImportPicker: () -> Unit,
) {

    /** applicationContext, not the activity itself — this bridge outlives
     *  any single Activity instance recreation and must never leak one. */
    private val ctx: Context = activity.applicationContext

    private val store = SocialStore(ctx)

    /** THE multi-channel registry (build.json::channels.registry, baked as
     *  BuildConfig.CHANNELS_B64 by app/build.gradle) — parsed once and
     *  reused; the registry itself never changes at runtime. */
    private val registry: List<ChannelConfig> by lazy {
        Channels.parse(String(Base64.decode(BuildConfig.CHANNELS_B64, Base64.DEFAULT), Charsets.UTF_8))
            .sortedBy { it.order }
    }

    @Volatile private var peopleCache: List<Person>? = null

    // ---- merged people (device + social, cached) -----------------------------

    private fun hasContactsPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Drops the cached merge — called after anything that could change it
     *  (import lands, a source is removed, permission is newly granted). */
    @Synchronized
    private fun invalidate() {
        peopleCache = null
    }

    /** The merged [Person] list, computed once and cached until [invalidate]
     *  is called. Device contacts are only read if READ_CONTACTS is
     *  currently granted — a revoked permission degrades to social-only
     *  rather than throwing a SecurityException. */
    @Synchronized
    private fun people(): List<Person> {
        peopleCache?.let { return it }
        val raw = mutableListOf<RawContact>()
        if (hasContactsPermission()) raw += DeviceContacts.read(ctx)
        raw += store.all()
        val merged = MergeEngine.merge(raw)
        peopleCache = merged
        return merged
    }

    private fun isInstalled(pkg: String?): Boolean {
        if (pkg.isNullOrEmpty()) return true
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** First letters of the first two whitespace-separated words,
     *  uppercased — "" (never throws) for a blank name. */
    private fun initials(name: String): String {
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.take(2).mapNotNull { it.firstOrNull()?.uppercaseChar() }.joinToString("")
    }

    // ---- state ----------------------------------------------------------------

    /** Snapshot the UI needs before it can render anything: permission
     *  state, per-source contact counts (device contacts grouped by their
     *  own source string — e.g. "local", "gmail:me@x" — plus the social
     *  import store's counts), the channel registry with live per-channel
     *  install status, and build/version info for the About page. */
    @JavascriptInterface
    fun getState(): String {
        val granted = hasContactsPermission()
        val sourcesObj = JSONObject()
        if (granted) {
            val deviceCounts = DeviceContacts.read(ctx).groupingBy { it.source }.eachCount()
            for ((source, count) in deviceCounts) sourcesObj.put(source, count)
        }
        for ((source, count) in store.counts()) sourcesObj.put(source, count)

        val channelsArr = JSONArray()
        for (c in registry) {
            channelsArr.put(JSONObject().apply {
                put("id", c.id)
                put("label", c.label)
                put("color", c.color)
                put("glyph", c.glyph)
                put("order", c.order)
                put("installed", isInstalled(c.pkg))
            })
        }

        return JSONObject().apply {
            put("permission", if (granted) "granted" else "denied")
            put("sources", sourcesObj)
            put("channels", channelsArr)
            put("version", BuildConfig.VERSION_NAME)
            put("sha", BuildConfig.GIT_SHORT_SHA)
            put("buildTime", BuildConfig.BUILD_TIMESTAMP)
            put("peopleCount", people().size)
        }.toString()
    }

    // ---- people -----------------------------------------------------------------

    /** The People-view row list: just enough per person to render a row —
     *  name/org/title, source provenance, and the ordered channel-dot
     *  badges — sorted by name. Full per-person detail (phones, emails,
     *  channel actions, ...) is [getPerson], fetched on demand when a row
     *  is tapped, not baked into every row here. */
    @JavascriptInterface
    fun getPeople(): String {
        val arr = JSONArray()
        for (p in people().sortedBy { it.name.lowercase() }) {
            val badges = Channels.forPerson(p, registry).map { it.config.id }
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("initials", initials(p.name))
                put("org", p.org)
                put("title", p.title)
                put("sources", JSONArray(p.sources))
                put("badges", JSONArray(badges))
            })
        }
        return arr.toString()
    }

    /** The switchboard's full detail for one person: raw contact fields
     *  plus, per available channel, everything the UI needs to render a
     *  tile and fire its action(s) — label/color/glyph/value/pkg/installed
     *  and the channel's action list (id/label/uri) as-is from [Channels],
     *  since a tile's tap target is Native.open(action.uri, pkg). */
    @JavascriptInterface
    fun getPerson(id: String): String {
        val p = people().firstOrNull { it.id == id }
            ?: return JSONObject().put("error", "not_found").toString()

        val channelsArr = JSONArray()
        for (pc in Channels.forPerson(p, registry)) {
            val actionsArr = JSONArray()
            for (a in pc.actions) {
                actionsArr.put(JSONObject().apply {
                    put("id", a.id)
                    put("label", a.label)
                    put("uri", a.uri)
                })
            }
            channelsArr.put(JSONObject().apply {
                put("id", pc.config.id)
                put("label", pc.config.label)
                put("color", pc.config.color)
                put("glyph", pc.config.glyph)
                put("value", pc.value)
                put("pkg", pc.config.pkg ?: "")
                put("installed", isInstalled(pc.config.pkg))
                put("actions", actionsArr)
            })
        }

        val handlesObj = JSONObject()
        for ((k, v) in p.handles) handlesObj.put(k, v)

        return JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("initials", initials(p.name))
            put("org", p.org)
            put("title", p.title)
            put("sources", JSONArray(p.sources))
            put("phones", JSONArray(p.phones))
            put("emails", JSONArray(p.emails))
            put("urls", JSONArray(p.urls))
            put("handles", handlesObj)
            put("channels", channelsArr)
        }.toString()
    }

    // ---- permission / import launchers -------------------------------------------

    /** MainActivity owns the actual ActivityResultLauncher — this just
     *  hops to the UI thread to fire it (JS interface calls land on a
     *  background binder thread, and launch() must run on the UI thread). */
    @JavascriptInterface
    fun requestContacts() {
        activity.runOnUiThread { requestContactsPermission() }
    }

    @JavascriptInterface
    fun pickImport() {
        activity.runOnUiThread { launchImportPicker() }
    }

    /** Drops one social source (e.g. "linkedin", "instagram") and hands
     *  back the fresh [getState] so the UI can re-render off one call
     *  instead of removeSource+getState round-tripping separately. */
    @JavascriptInterface
    fun removeSource(source: String): String {
        store.removeSource(source)
        invalidate()
        return getState()
    }

    // ---- external links -----------------------------------------------------------

    /** Fires an ACTION_VIEW intent for [uri], preferring [pkg] (a
     *  constellation/social app hint from the channel registry) when it's
     *  both non-empty and actually installed; falls back to the system
     *  chooser on an ActivityNotFoundException (a stale/incorrect pkg
     *  hint) or when no pkg was given at all. Runs on the UI thread since
     *  startActivity must; the JS interface call itself is on a binder
     *  thread with no synchronous way to wait for that post, so this is
     *  best-effort — it returns true once the attempt is queued, not once
     *  it's confirmed to have succeeded. */
    @JavascriptInterface
    fun open(uri: String, pkg: String): Boolean {
        activity.runOnUiThread {
            try {
                val target = Uri.parse(uri)
                val intent = Intent(Intent.ACTION_VIEW, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pkg.isNotEmpty() && isInstalled(pkg)) intent.setPackage(pkg)
                try {
                    activity.startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    intent.setPackage(null)
                    activity.startActivity(intent)
                }
            } catch (e: Exception) {
                // best-effort — nothing left to report back once we're this
                // deep into an async UI-thread post
            }
        }
        return true
    }

    // ---- about ------------------------------------------------------------------

    /** Version/build info + the stack-scan fields baked by app/build.gradle
     *  — passed through as-is (still base64/JSON-encoded) for the About
     *  page to decode itself, same contract as the sibling apps. */
    @JavascriptInterface
    fun getAbout(): String = JSONObject().apply {
        put("version", BuildConfig.VERSION_NAME)
        put("sha", BuildConfig.GIT_SHORT_SHA)
        put("buildTime", BuildConfig.BUILD_TIMESTAMP)
        put("logSinkStream", BuildConfig.LOG_SINK_STREAM)
        put("stackLanguages", BuildConfig.UI_STACK_LANGUAGES_JSON_B64)
        put("stackFrameworks", BuildConfig.UI_STACK_FRAMEWORKS_JSON_B64)
        put("stackFolderTree", BuildConfig.UI_STACK_FOLDER_TREE_B64)
        put("buildAvgSecs", BuildConfig.STACK_BUILD_AVG_SECS)
        put("buildLastSecs", BuildConfig.STACK_BUILD_LAST_SECS)
        put("buildSample", BuildConfig.STACK_BUILD_SAMPLE)
    }.toString()

    // ---- callbacks INTO JS (Kotlin -> JS, called by MainActivity) ----------------

    /** Posted from MainActivity's RequestPermission callback. Always
     *  invalidates the people cache first — a grant makes device contacts
     *  newly readable, a denial is a no-op for the cache but cheap either
     *  way. */
    fun onPermissionResult(granted: Boolean) {
        invalidate()
        postToJs("permission", JSONObject().put("granted", granted))
    }

    /** Posted from MainActivity's GetContent callback. null [uri] means the
     *  user backed out of the picker. Text read + SocialImport.parse both
     *  run on a plain background Thread — never the UI thread, and never
     *  the binder thread this was invoked from either, since a large
     *  export file's parse shouldn't hold either of those up. */
    fun onFilePicked(uri: Uri?) {
        if (uri == null) {
            postToJs("import", JSONObject().put("cancelled", true))
            return
        }
        Thread {
            try {
                val text = readCappedText(uri)
                val result = SocialImport.parse(text)
                if (result == null) {
                    postToJs("import", JSONObject().put("error", "unrecognized"))
                } else {
                    store.replaceSource(result.source, result.contacts)
                    invalidate()
                    postToJs("import", JSONObject().apply {
                        put("source", result.source)
                        put("count", result.contacts.size)
                    })
                }
            } catch (e: Exception) {
                postToJs("import", JSONObject().put("error", "unrecognized"))
            }
        }.start()
    }

    /** Reads [uri] as UTF-8 text, capped at ~20MB — a runaway/corrupt
     *  export file gets truncated rather than OOMing the process. */
    private fun readCappedText(uri: Uri): String {
        val cap = 20 * 1024 * 1024
        val input = ctx.contentResolver.openInputStream(uri) ?: return ""
        input.use { stream ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                total += n
                if (total > cap) break
                buffer.write(chunk, 0, n)
            }
            return buffer.toString("UTF-8")
        }
    }

    /** JSONObject.toString() already produces syntactically valid JSON,
     *  which is also a valid JS object-literal expression — so the
     *  evaluated string is `window.onNative && window.onNative('type', {..})`,
     *  no extra string-escaping needed on top. Always posted to the
     *  WebView's own thread (webView.post), never called directly from a
     *  background thread. */
    private fun postToJs(type: String, payload: JSONObject) {
        webView.post {
            webView.evaluateJavascript(
                "window.onNative && window.onNative('" + type + "', " + payload.toString() + ")",
                null,
            )
        }
    }
}
