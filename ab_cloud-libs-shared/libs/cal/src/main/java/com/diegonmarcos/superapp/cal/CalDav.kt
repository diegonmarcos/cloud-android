package com.diegonmarcos.superapp.cal

import android.content.Context
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.StringReader
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Credentials + server URL for one CalDAV account. Never persisted by
 *  this object — see CalBridge.kt's EncryptedSharedPreferences-backed
 *  store, which decrypts these fresh for each call and hands them in. */
data class CalDavConfig(val baseUrl: String, val username: String, val password: String)

/** Thrown for CalDAV-specific failures (bad scheme, unexpected
 *  multistatus shape, HTTP error). Message text only — never include
 *  the Authorization header or password in it, since it can end up in
 *  [TodoSyncReport.messages] shown to the user. */
class CalDavException(message: String) : Exception(message)

/** Result of one [CalDav.syncAll] pass — same shape/spirit as
 *  [SyncReport] from CalSync, minus `skipped` (there's no
 *  refresh-interval throttling for tasks; every sync call fetches
 *  every collection fresh). */
data class TodoSyncReport(val ok: Int, val failed: Int, val messages: List<String>)

/**
 * Minimal CalDAV client for VTODO collections (RFC 4791 / RFC 5545).
 * BLOCKING, synchronous — does real network I/O on whatever thread
 * calls it, same contract as [CalSync]: callers must run this off the
 * main thread.
 *
 * Uses [HttpURLConnection] throughout, matching [CalSync]'s HTTP
 * approach/timeouts. PROPFIND and REPORT aren't in the JDK's built-in
 * request-method whitelist (WebDAV/CalDAV extension verbs), so
 * [forceMethod] reaches past that whitelist via reflection on the
 * `method` field — that field is declared directly on the stable,
 * documented `java.net.HttpURLConnection` base class itself, not on
 * some vendor's implementation subclass, which is what makes this
 * portable rather than a fragile OEM-specific hack. This avoids
 * pulling in a WebDAV/HTTP client dependency for two verbs.
 */
object CalDav {
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    // ---- public API -----------------------------------------------------

    /** PROPFIND current-user-principal -> PROPFIND calendar-home-set ->
     *  PROPFIND Depth:1 on the home set, keeping only collections whose
     *  supported-calendar-component-set includes VTODO. */
    fun discoverCollections(cfg: CalDavConfig): List<CalDavCollection> {
        requireHttps(cfg.baseUrl)
        val base = URL(cfg.baseUrl)

        val principalHref = propfindPrincipal(cfg, cfg.baseUrl) ?: cfg.baseUrl
        val principalUrl = resolveHref(base, principalHref)

        val homeSetHref = propfindHomeSet(cfg, principalUrl) ?: principalHref
        val homeSetUrl = resolveHref(base, homeSetHref)

        return propfindTaskCollections(cfg, homeSetUrl, base)
    }

    /** REPORT calendar-query with a VTODO comp-filter, Depth:1. */
    fun fetchTodos(cfg: CalDavConfig, collection: CalDavCollection): List<CalTodo> {
        requireHttps(collection.href)
        val res = request(
            cfg, "REPORT", collection.href,
            depth = "1",
            body = TODO_QUERY_BODY,
            contentType = "application/xml; charset=utf-8",
        )
        expectMultistatus(res, "REPORT calendar-query")
        val base = URL(collection.href)
        return parseTodoResponses(res.body).mapNotNull { r ->
            if (r.calendarData.isBlank()) return@mapNotNull null
            TodoIcs.parse(r.calendarData, collection.href, resolveHref(base, r.href), r.etag)
        }
    }

    /** PUT an .ics to `<collection>/<uid>.ics`. `If-None-Match: *` when
     *  [isCreate], `If-Match: <etag>` on update. Returns the task with
     *  its (possibly new) href/etag filled in. */
    fun putTodo(cfg: CalDavConfig, collectionHref: String, todo: CalTodo, isCreate: Boolean): CalTodo {
        requireHttps(collectionHref)
        val uid = todo.uid.ifBlank { UUID.randomUUID().toString() }
        val targetUrl = todo.href.ifBlank {
            val base = if (collectionHref.endsWith("/")) collectionHref else "$collectionHref/"
            resolveHref(URL(base), "$uid.ics")
        }
        val toWrite = todo.copy(uid = uid)
        val ics = TodoIcs.serialize(toWrite)

        val res = request(
            cfg, "PUT", targetUrl,
            body = ics,
            contentType = "text/calendar; charset=utf-8",
            ifMatch = if (!isCreate && todo.etag.isNotBlank()) todo.etag else null,
            ifNoneMatch = if (isCreate) "*" else null,
        )
        if (res.status !in 200..299) {
            throw CalDavException("PUT VTODO failed: HTTP ${res.status}")
        }
        val newEtag = res.header("ETag") ?: ""
        return toWrite.copy(href = targetUrl, etag = newEtag)
    }

    /** DELETE, with If-Match when an ETag is known. 404 counts as
     *  success (already gone). */
    fun deleteTodo(cfg: CalDavConfig, todo: CalTodo) {
        if (todo.href.isBlank()) throw CalDavException("cannot delete a task with no href")
        requireHttps(todo.href)
        val res = request(cfg, "DELETE", todo.href, ifMatch = todo.etag.takeIf { it.isNotBlank() })
        if (res.status !in 200..299 && res.status != 404) {
            throw CalDavException("DELETE VTODO failed: HTTP ${res.status}")
        }
    }

    /** Discover collections, fetch every one's VTODOs, and write the
     *  result into [TodoStore]. Every collection is isolated — one
     *  failing must not blank out the rest, mirroring [CalSync.syncAll]. */
    fun syncAll(ctx: Context, cfg: CalDavConfig): TodoSyncReport {
        val collections = try {
            discoverCollections(cfg)
        } catch (e: Exception) {
            return TodoSyncReport(0, 1, listOf("discovery: ${e.message ?: e.javaClass.simpleName}"))
        }
        TodoStore.replaceCollections(ctx, collections)

        var ok = 0
        var failed = 0
        val messages = mutableListOf<String>()
        for (col in collections) {
            runCatching { fetchTodos(cfg, col) }
                .onSuccess { todos ->
                    TodoStore.replaceTodos(ctx, col.href, todos)
                    ok++
                }
                .onFailure { e ->
                    failed++
                    messages.add("${col.displayName}: ${e.message ?: e.javaClass.simpleName}")
                }
        }
        TodoStore.setLastSync(ctx, System.currentTimeMillis())
        return TodoSyncReport(ok, failed, messages)
    }

    // ---- discovery: XML request bodies -----------------------------------

    private const val PRINCIPAL_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:current-user-principal/>
  </D:prop>
</D:propfind>"""

    private const val HOME_SET_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <C:calendar-home-set/>
  </D:prop>
</D:propfind>"""

    private const val COLLECTIONS_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav" xmlns:CS="http://apple.com/ns/ical/">
  <D:prop>
    <D:resourcetype/>
    <D:displayname/>
    <C:supported-calendar-component-set/>
    <CS:calendar-color/>
  </D:prop>
</D:propfind>"""

    private const val TODO_QUERY_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
  <D:prop>
    <D:getetag/>
    <C:calendar-data/>
  </D:prop>
  <C:filter>
    <C:comp-filter name="VCALENDAR">
      <C:comp-filter name="VTODO"/>
    </C:comp-filter>
  </C:filter>
</C:calendar-query>"""

    private fun propfindPrincipal(cfg: CalDavConfig, url: String): String? {
        val res = request(cfg, "PROPFIND", url, depth = "0", body = PRINCIPAL_BODY, contentType = "application/xml; charset=utf-8")
        expectMultistatus(res, "PROPFIND current-user-principal")
        return firstHrefUnder(res.body, "current-user-principal")
    }

    private fun propfindHomeSet(cfg: CalDavConfig, url: String): String? {
        val res = request(cfg, "PROPFIND", url, depth = "0", body = HOME_SET_BODY, contentType = "application/xml; charset=utf-8")
        expectMultistatus(res, "PROPFIND calendar-home-set")
        return firstHrefUnder(res.body, "calendar-home-set")
    }

    private fun propfindTaskCollections(cfg: CalDavConfig, homeSetUrl: String, base: URL): List<CalDavCollection> {
        val res = request(cfg, "PROPFIND", homeSetUrl, depth = "1", body = COLLECTIONS_BODY, contentType = "application/xml; charset=utf-8")
        expectMultistatus(res, "PROPFIND collections")
        return parseCollectionResponses(res.body)
            .filter { it.isCalendar && it.href.isNotEmpty() && "VTODO" in it.comps }
            .map { raw ->
                CalDavCollection(
                    href = resolveHref(base, raw.href),
                    displayName = raw.displayName.ifBlank { raw.href.trimEnd('/').substringAfterLast('/') },
                    color = raw.color.ifBlank { "#4a86e8" },
                )
            }
    }

    private fun expectMultistatus(res: HttpResult, what: String) {
        // 207 Multi-Status is the normal PROPFIND/REPORT success code;
        // some servers reply 200 for a single-result Depth:0 request.
        if (res.status !in 200..299 && res.status != 207) {
            throw CalDavException("$what failed: HTTP ${res.status}")
        }
    }

    // ---- XML parsing (android.util.Xml / org.xmlpull — no new dependency) ----

    private fun firstHrefUnder(xml: String, containerLocalName: String): String? {
        val parser = newParser(xml)
        var inContainer = false
        var containerDepth = -1
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == containerLocalName) {
                        inContainer = true
                        containerDepth = parser.depth
                    } else if (inContainer && parser.name == "href") {
                        val text = runCatching { parser.nextText() }.getOrDefault("").trim()
                        if (text.isNotEmpty()) return text
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inContainer && parser.name == containerLocalName && parser.depth == containerDepth) {
                        inContainer = false
                    }
                }
            }
            event = parser.next()
        }
        return null
    }

    private data class RawCollection(
        val href: String,
        val isCalendar: Boolean,
        val displayName: String,
        val comps: Set<String>,
        val color: String,
    )

    private fun parseCollectionResponses(xml: String): List<RawCollection> {
        val parser = newParser(xml)
        val out = mutableListOf<RawCollection>()
        var href = ""
        var isCalendar = false
        var displayName = ""
        var color = ""
        val comps = mutableSetOf<String>()
        var inResponse = false
        var responseDepth = -1
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> {
                        inResponse = true; responseDepth = parser.depth
                        href = ""; isCalendar = false; displayName = ""; color = ""; comps.clear()
                    }
                    "href" -> if (inResponse && href.isEmpty()) {
                        href = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                    "calendar" -> if (inResponse) isCalendar = true
                    "displayname" -> if (inResponse) {
                        displayName = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                    "comp" -> if (inResponse) {
                        parser.getAttributeValue(null, "name")?.let { comps.add(it.uppercase()) }
                    }
                    "calendar-color" -> if (inResponse) {
                        color = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inResponse && parser.name == "response" && parser.depth == responseDepth) {
                        out.add(RawCollection(href, isCalendar, displayName, comps.toSet(), color))
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private data class RawTodoResponse(val href: String, val etag: String, val calendarData: String)

    private fun parseTodoResponses(xml: String): List<RawTodoResponse> {
        val parser = newParser(xml)
        val out = mutableListOf<RawTodoResponse>()
        var href = ""
        var etag = ""
        var data = ""
        var inResponse = false
        var responseDepth = -1
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "response" -> { inResponse = true; responseDepth = parser.depth; href = ""; etag = ""; data = "" }
                    "href" -> if (inResponse && href.isEmpty()) {
                        href = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                    "getetag" -> if (inResponse) {
                        etag = runCatching { parser.nextText() }.getOrDefault("").trim()
                    }
                    "calendar-data" -> if (inResponse) {
                        data = runCatching { parser.nextText() }.getOrDefault("")
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (inResponse && parser.name == "response" && parser.depth == responseDepth) {
                        out.add(RawTodoResponse(href, etag, data))
                        inResponse = false
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return parser
    }

    private fun resolveHref(base: URL, href: String): String =
        if (href.startsWith("http://", ignoreCase = true) || href.startsWith("https://", ignoreCase = true)) {
            href
        } else {
            URL(base, href).toString()
        }

    // ---- HTTP plumbing ----------------------------------------------------

    // Keys are declared nullable here (unlike the Kotlin-inferred platform
    // type from HttpURLConnection.headerFields) because that map always
    // has one null-key entry for the HTTP status line itself.
    private data class HttpResult(val status: Int, val headers: Map<String?, List<String>>, val body: String) {
        fun header(name: String): String? =
            headers.entries.firstOrNull { it.key?.equals(name, ignoreCase = true) == true }?.value?.firstOrNull()
    }

    /** Refuses anything but https:// — CalDAV auth here is HTTP Basic,
     *  which must never go out over plaintext. */
    private fun requireHttps(url: String) {
        if (!url.startsWith("https://", ignoreCase = true)) {
            throw CalDavException("refusing to send CalDAV credentials over a non-HTTPS URL")
        }
    }

    private fun basicAuth(cfg: CalDavConfig): String {
        val raw = "${cfg.username}:${cfg.password}".toByteArray(StandardCharsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    private fun request(
        cfg: CalDavConfig,
        method: String,
        urlStr: String,
        depth: String? = null,
        body: String? = null,
        ifMatch: String? = null,
        ifNoneMatch: String? = null,
        contentType: String? = null,
    ): HttpResult {
        requireHttps(urlStr)
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // Redirects are handled manually (disabled) rather than via
            // instanceFollowRedirects: an automatic redirect could in
            // principle point at a plain http:// URL, and Basic auth
            // credentials must never be at risk of following it there.
            instanceFollowRedirects = false
            forceMethod(method)
            setRequestProperty("Authorization", basicAuth(cfg))
            setRequestProperty("Accept", "application/xml, text/xml, text/calendar, */*")
            depth?.let { setRequestProperty("Depth", it) }
            ifMatch?.let { setRequestProperty("If-Match", it) }
            ifNoneMatch?.let { setRequestProperty("If-None-Match", it) }
            contentType?.let { setRequestProperty("Content-Type", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Length", body.toByteArray(StandardCharsets.UTF_8).size.toString())
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
            val status = conn.responseCode
            if (status in 300..399) {
                throw CalDavException("unexpected redirect (HTTP $status) — not followed")
            }
            val stream = if (status in 200..299 || status == 207) conn.inputStream else conn.errorStream
            val text = stream?.use { s ->
                BufferedReader(InputStreamReader(s, StandardCharsets.UTF_8)).readText()
            } ?: ""
            return HttpResult(status, conn.headerFields, text)
        } finally {
            conn.disconnect()
        }
    }

    /** [HttpURLConnection.setRequestMethod] only allows a fixed verb
     *  whitelist (GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE/PATCH) and
     *  throws [ProtocolException] for WebDAV/CalDAV verbs like PROPFIND
     *  and REPORT. Those aren't going to be added to the JDK, so this
     *  reaches past the check via reflection on `method` — a field
     *  declared right on `java.net.HttpURLConnection` itself, i.e. part
     *  of the class's stable layout on every JVM/Android version, not
     *  an implementation-specific internal. */
    private fun HttpURLConnection.forceMethod(method: String) {
        try {
            requestMethod = method
        } catch (e: ProtocolException) {
            val field = HttpURLConnection::class.java.getDeclaredField("method")
            field.isAccessible = true
            field.set(this, method)
            // Read back rather than trust the write: if some Android
            // networking stack ever shadows this field instead of
            // reading the inherited one, we must fail loudly here
            // rather than silently send the wrong HTTP verb (e.g. a
            // PROPFIND that's actually transmitted as GET).
            if (requestMethod != method) {
                throw CalDavException("could not set HTTP method $method on this platform's HttpURLConnection")
            }
        }
    }
}
