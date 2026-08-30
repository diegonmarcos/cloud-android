package com.diegonmarcos.superapp.contacts

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * The contacts BACK END: device + social merge, the channel registry, and the
 * per-person switchboard detail, all as JSON.
 *
 * Everything here used to live in the app's ContactsBridge. It is data work,
 * so it belongs behind the library boundary; what stays in the app is the part
 * that genuinely needs an Activity - the runtime permission request, the file
 * picker, launching a channel - plus build identity.
 *
 * One boundary decision worth stating: "is this channel's app installed" is
 * NOT answered here. Package visibility on Android 11+ is a property of the
 * querying package's manifest <queries>, so answering it in the engine would
 * mean duplicating the app's channel list into a second manifest and keeping
 * the two in step. The engine returns the registry; the caller badges it.
 */
class ContactsEngine(context: Context) {

    private val ctx = context.applicationContext
    private val store = SocialStore(ctx)

    /** build.json::channels.registry, baked into THIS module's BuildConfig. */
    private val registry: List<ChannelConfig> by lazy {
        Channels.parse(String(Base64.decode(BuildConfig.CHANNELS_B64, Base64.DEFAULT), Charsets.UTF_8))
            .sortedBy { it.order }
    }

    @Volatile private var peopleCache: List<Person>? = null

    @Synchronized fun invalidate() { peopleCache = null }

    /** [readDevice] is false when the caller has no READ_CONTACTS grant - the
     *  permission belongs to the app, so the app tells us rather than us
     *  guessing from a different package's grants. */
    private fun people(readDevice: Boolean): List<Person> =
        peopleCache ?: synchronized(this) {
            // MergeEngine takes ONE list of raw contacts and unions them by
            // shared phone/email keys, so device and social go in together.
            peopleCache ?: MergeEngine.merge(
                (if (readDevice) DeviceContacts.read(ctx) else emptyList()) + store.all()
            ).also { peopleCache = it }
        }

    fun sources(readDevice: Boolean): JSONObject = JSONObject().apply {
        if (readDevice) {
            DeviceContacts.read(ctx).groupingBy { it.source }.eachCount()
                .forEach { (source, count) -> put(source, count) }
        }
        store.counts().forEach { (source, count) -> put(source, count) }
    }

    /** Registry WITHOUT `installed` - see the class note. */
    fun channels(): JSONArray = JSONArray().apply {
        registry.forEach { c ->
            put(JSONObject()
                .put("id", c.id).put("label", c.label).put("color", c.color)
                .put("glyph", c.glyph).put("order", c.order)
                .put("pkg", c.pkg))
        }
    }

    fun peopleRows(readDevice: Boolean): JSONArray = JSONArray().apply {
        people(readDevice).sortedBy { it.name.lowercase() }.forEach { p ->
            put(JSONObject()
                .put("id", p.id).put("name", p.name)
                .put("initials", initials(p.name))
                .put("org", p.org).put("title", p.title)
                .put("sources", JSONArray(p.sources))
                .put("badges", JSONArray(Channels.forPerson(p, registry).map { it.config.id })))
        }
    }

    fun person(id: String, readDevice: Boolean): JSONObject {
        val p = people(readDevice).firstOrNull { it.id == id }
            ?: return JSONObject().put("error", "not_found")
        val channelsArr = JSONArray()
        for (pc in Channels.forPerson(p, registry)) {
            val actions = JSONArray()
            pc.actions.forEach { a ->
                actions.put(JSONObject().put("id", a.id).put("label", a.label).put("uri", a.uri))
            }
            channelsArr.put(JSONObject()
                .put("id", pc.config.id).put("label", pc.config.label)
                .put("color", pc.config.color).put("glyph", pc.config.glyph)
                .put("value", pc.value).put("pkg", pc.config.pkg)
                .put("actions", actions))
        }
        return JSONObject()
            .put("id", p.id).put("name", p.name).put("org", p.org).put("title", p.title)
            .put("sources", JSONArray(p.sources))
            .put("phones", JSONArray(p.phones))
            .put("emails", JSONArray(p.emails))
            .put("urls", JSONArray(p.urls))
            .put("channels", channelsArr)
    }

    /**
     * One-time handoff of the app's existing social imports.
     *
     * SocialStore writes to PRIVATE app storage, so moving this engine into
     * Cloud-Lib-Contacts.apk also moves the data directory: without this the
     * engine would start empty and everything the user had imported would be
     * orphaned - present on disk, invisible to the app. The app seeds us once
     * with what it already had, guarded by its own pref so it happens exactly
     * once.
     *
     * Returns how many sources were taken, so the app only marks the seed done
     * when it actually landed.
     */
    fun seed(rawJson: String): JSONObject {
        val arr = runCatching { JSONArray(rawJson) }.getOrNull()
            ?: return JSONObject().put("ok", false).put("error", "bad payload")
        var sources = 0
        // Group by source: replaceSource is per-source and wholesale.
        val bySource = LinkedHashMap<String, MutableList<RawContact>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // RawContact has no JSON codec of its own - SocialStore's are
            // private - so the shape is spelled out here, matching the app's
            // export side exactly.
            val c = RawContact(
                source  = o.optString("source").ifBlank { "imported" },
                name    = o.optString("name"),
                phones  = o.optJSONArray("phones").toStringList(),
                emails  = o.optJSONArray("emails").toStringList(),
                org     = o.optString("org"),
                title   = o.optString("title"),
                urls    = o.optJSONArray("urls").toStringList(),
                handles = o.optJSONObject("handles").toStringMap(),
            )
            if (c.name.isBlank() && c.phones.isEmpty() && c.emails.isEmpty()) continue
            bySource.getOrPut(c.source) { mutableListOf() }.add(c)
        }
        bySource.forEach { (source, contacts) ->
            store.replaceSource(source, contacts); sources++
        }
        invalidate()
        return JSONObject().put("ok", true).put("sources", sources)
    }

    /** Whether this engine already holds data - lets the app skip the seed. */
    fun hasData(): Boolean = store.all().isNotEmpty()

    fun removeSource(source: String): JSONObject {
        store.removeSource(source)
        invalidate()
        return JSONObject().put("ok", true)
    }

    fun count(readDevice: Boolean): Int = people(readDevice).size

    private fun org.json.JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }

    private fun JSONObject?.toStringMap(): Map<String, String> {
        if (this == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        keys().forEach { k -> optString(k).takeIf { it.isNotBlank() }?.let { out[k] = it } }
        return out
    }

    private fun initials(name: String): String =
        name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            .take(2).joinToString("") { it.first().uppercase() }
}
