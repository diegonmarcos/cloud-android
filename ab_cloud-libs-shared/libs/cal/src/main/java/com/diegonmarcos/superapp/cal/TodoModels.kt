package com.diegonmarcos.superapp.cal

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One CalDAV VTODO collection — i.e. one "project" (§4 of the task
 * brief: a Radicale task list IS a project, 1:1).
 */
data class CalDavCollection(
    /** Full absolute URL of the collection, used as the stable
     *  project id everywhere in this app (bridge `projectId`,
     *  [TodoStore] cache key). */
    val href: String,
    val displayName: String,
    val color: String,
)

/**
 * One parsed VTODO, plus [unknownLines] — every unfolded property (or
 * whole nested component such as VALARM) this app doesn't model,
 * preserved verbatim in original text form. On save these are
 * re-emitted as-is so round-tripping a task through this app never
 * destroys data another CalDAV client wrote onto it.
 */
data class CalTodo(
    val uid: String,
    /** [CalDavCollection.href] this task lives in — the bridge's `projectId`. */
    val collectionId: String,
    /** Full resource URL, e.g. `https://host/calendars/user/tasks/<uid>.ics`.
     *  Empty for a task that hasn't been PUT to the server yet. */
    val href: String,
    /** Last known ETag, used for `If-Match` on update. Empty if unknown. */
    val etag: String,
    val summary: String,
    val description: String,
    /** NEEDS-ACTION | COMPLETED | IN-PROCESS | CANCELLED (RFC 5545 §3.8.1.11). */
    val status: String,
    val dueUtcMillis: Long?,
    val dueIsDate: Boolean,
    /** 0 = no priority assigned (RFC 5545 §3.8.1.9), 1 (highest) .. 9 (lowest). */
    val priority: Int,
    val percentComplete: Int?,
    val createdUtcMillis: Long?,
    val lastModifiedUtcMillis: Long?,
    val completedUtcMillis: Long?,
    val unknownLines: List<String> = emptyList(),
) {
    /** Opaque bridge id for this task — see [encodeBridgeId]. Unlike
     *  [com.diegonmarcos.superapp.cal.CalEvent]'s plain `"<calendarId>:<uid>"`,
     *  a naive colon-join isn't safe here because [collectionId] is a
     *  full `https://...` URL that already contains colons. */
    val bridgeId: String get() = encodeBridgeId(collectionId, uid)

    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("collectionId", collectionId)
        put("href", href)
        put("etag", etag)
        put("summary", summary)
        put("description", description)
        put("status", status)
        put("dueUtcMillis", dueUtcMillis ?: -1L)
        put("dueIsDate", dueIsDate)
        put("priority", priority)
        put("percentComplete", percentComplete ?: -1)
        put("createdUtcMillis", createdUtcMillis ?: -1L)
        put("lastModifiedUtcMillis", lastModifiedUtcMillis ?: -1L)
        put("completedUtcMillis", completedUtcMillis ?: -1L)
        val arr = JSONArray()
        for (l in unknownLines) arr.put(l)
        put("unknownLines", arr)
    }

    companion object {
        /** Encodes ("<collectionId href>", uid) into one opaque string
         *  safe to hand across the JS bridge and later decode with
         *  [decodeBridgeId]. Base64-encoding the (colon-containing)
         *  collection URL keeps the first literal ':' in the result
         *  unambiguous as the collectionId/uid separator. */
        fun encodeBridgeId(collectionId: String, uid: String): String {
            val b64 = android.util.Base64.encodeToString(
                collectionId.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE,
            )
            return "$b64:$uid"
        }

        /** Reverses [encodeBridgeId]. Returns null for a malformed id. */
        fun decodeBridgeId(bridgeId: String): Pair<String, String>? {
            val sep = bridgeId.indexOf(':')
            if (sep < 0) return null
            val collectionId = runCatching {
                String(
                    android.util.Base64.decode(bridgeId.substring(0, sep), android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP),
                    Charsets.UTF_8,
                )
            }.getOrNull() ?: return null
            return collectionId to bridgeId.substring(sep + 1)
        }

        fun fromJson(o: JSONObject): CalTodo {
            val unknown = mutableListOf<String>()
            o.optJSONArray("unknownLines")?.let { arr ->
                for (i in 0 until arr.length()) unknown.add(arr.optString(i, ""))
            }
            fun longOrNull(key: String): Long? = o.optLong(key, -1L).let { if (it < 0) null else it }
            fun intOrNull(key: String): Int? = o.optInt(key, -1).let { if (it < 0) null else it }
            return CalTodo(
                uid                   = o.optString("uid", ""),
                collectionId          = o.optString("collectionId", ""),
                href                  = o.optString("href", ""),
                etag                  = o.optString("etag", ""),
                summary               = o.optString("summary", ""),
                description           = o.optString("description", ""),
                status                = o.optString("status", "NEEDS-ACTION"),
                dueUtcMillis          = longOrNull("dueUtcMillis"),
                dueIsDate             = o.optBoolean("dueIsDate", false),
                priority              = o.optInt("priority", 0),
                percentComplete       = intOrNull("percentComplete"),
                createdUtcMillis      = longOrNull("createdUtcMillis"),
                lastModifiedUtcMillis = longOrNull("lastModifiedUtcMillis"),
                completedUtcMillis    = longOrNull("completedUtcMillis"),
                unknownLines          = unknown,
            )
        }
    }
}

/**
 * Minimal RFC 5545 VTODO reader/writer. Reuses [IcsParser]'s line
 * unfolder and property splitter rather than re-implementing them —
 * see those functions' kdoc. Unlike [IcsParser] this object also
 * *writes* iCalendar text, since VTODOs round-trip through this app
 * (create/edit) instead of being read-only feed data.
 */
object TodoIcs {

    /** Properties this app understands and models explicitly on
     *  [CalTodo]. Everything else encountered inside a VTODO — plus
     *  any nested component such as VALARM — is preserved verbatim
     *  in [CalTodo.unknownLines] rather than dropped. DTSTAMP is
     *  deliberately NOT modeled: RFC 5545 requires it, and this app
     *  regenerates it fresh on every write (see [serialize]), which
     *  is standard CalDAV client behaviour ("when this representation
     *  of the object was created"). */
    private val KNOWN = setOf(
        "UID", "SUMMARY", "DESCRIPTION", "STATUS", "DUE", "PRIORITY",
        "PERCENT-COMPLETE", "CREATED", "LAST-MODIFIED", "COMPLETED",
    )

    /**
     * Parse one VTODO out of a full VCALENDAR document (CalDAV
     * `calendar-data` responses are always one standalone VCALENDAR
     * per resource). Returns null if the document has no VTODO —
     * never throws, matching [IcsParser.parse]'s "never blank the
     * whole sync over one bad block" philosophy.
     */
    fun parse(ics: String, collectionId: String, href: String, etag: String): CalTodo? =
        runCatching { parseOrThrow(ics, collectionId, href, etag) }.getOrNull()

    private fun parseOrThrow(ics: String, collectionId: String, href: String, etag: String): CalTodo? {
        val lines = IcsParser.unfold(ics)
        var inTodo = false
        var nestedDepth = 0
        val props = mutableMapOf<String, Pair<Map<String, String>, String>>()
        val unknown = mutableListOf<String>()
        var sawBeginVtodo = false

        for (raw in lines) {
            val line = raw.trim('\r')
            if (line.isEmpty()) continue

            if (nestedDepth > 0) {
                // Inside a nested component (VALARM, etc.) we don't
                // model — preserve every line verbatim, including the
                // BEGIN/END markers, so it survives a rewrite intact.
                unknown.add(line)
                if (line.startsWith("BEGIN:")) nestedDepth++
                else if (line.startsWith("END:")) nestedDepth--
                continue
            }

            when {
                line == "BEGIN:VTODO" -> { inTodo = true; sawBeginVtodo = true }
                line == "END:VTODO" -> inTodo = false
                !inTodo -> { /* VCALENDAR header / sibling components — ignore. */ }
                line.startsWith("BEGIN:") -> { unknown.add(line); nestedDepth = 1 }
                else -> {
                    val parsed = IcsParser.splitProperty(line)
                    if (parsed == null) { unknown.add(line); continue }
                    val (name, params, value) = parsed
                    if (name in KNOWN) props[name] = params to value else unknown.add(line)
                }
            }
        }

        if (!sawBeginVtodo) return null
        val uid = props["UID"]?.second?.let { IcsParser.unescapeText(it) } ?: return null

        val dueEntry = props["DUE"]
        val dueIsDate = dueEntry?.first?.get("VALUE")?.uppercase() == "DATE"
        val due = dueEntry?.let { (_, v) -> parseDate(v) }
        val statusRaw = props["STATUS"]?.second?.trim()?.uppercase()

        return CalTodo(
            uid = uid,
            collectionId = collectionId,
            href = href,
            etag = etag,
            summary = props["SUMMARY"]?.second?.let { IcsParser.unescapeText(it) } ?: "",
            description = props["DESCRIPTION"]?.second?.let { IcsParser.unescapeText(it) } ?: "",
            status = if (statusRaw.isNullOrEmpty()) "NEEDS-ACTION" else statusRaw,
            dueUtcMillis = due,
            dueIsDate = dueIsDate,
            priority = props["PRIORITY"]?.second?.trim()?.toIntOrNull() ?: 0,
            percentComplete = props["PERCENT-COMPLETE"]?.second?.trim()?.toIntOrNull(),
            createdUtcMillis = props["CREATED"]?.second?.let { parseDate(it) },
            lastModifiedUtcMillis = props["LAST-MODIFIED"]?.second?.let { parseDate(it) },
            completedUtcMillis = props["COMPLETED"]?.second?.let { parseDate(it) },
            unknownLines = unknown,
        )
    }

    /** Serialize [todo] back into a full VCALENDAR/VTODO document,
     *  folded at 75 octets per RFC 5545 §3.1 and CRLF-terminated.
     *  [unknownLines] (foreign properties + nested components from
     *  the original resource) are re-emitted verbatim so a save from
     *  this app never destroys data another client wrote. */
    fun serialize(todo: CalTodo): String {
        val now = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        lines += "BEGIN:VCALENDAR"
        lines += "VERSION:2.0"
        lines += "PRODID:-//diegonmarcos//CloudCalendar//EN"
        lines += "BEGIN:VTODO"
        lines += "UID:${escapeText(todo.uid)}"
        lines += "DTSTAMP:${formatDateTime(now)}"
        if (todo.summary.isNotEmpty()) lines += "SUMMARY:${escapeText(todo.summary)}"
        if (todo.description.isNotEmpty()) lines += "DESCRIPTION:${escapeText(todo.description)}"
        lines += "STATUS:${todo.status}"
        todo.dueUtcMillis?.let {
            lines += if (todo.dueIsDate) "DUE;VALUE=DATE:${formatDate(it)}" else "DUE:${formatDateTime(it)}"
        }
        if (todo.priority != 0) lines += "PRIORITY:${todo.priority}"
        todo.percentComplete?.let { lines += "PERCENT-COMPLETE:$it" }
        lines += "CREATED:${formatDateTime(todo.createdUtcMillis ?: now)}"
        lines += "LAST-MODIFIED:${formatDateTime(now)}"
        todo.completedUtcMillis?.let { lines += "COMPLETED:${formatDateTime(it)}" }
        for (u in todo.unknownLines) {
            // UID/DTSTAMP are always re-emitted above from the model —
            // never duplicate them from the preserved-verbatim bucket.
            if (u.startsWith("UID", ignoreCase = true) || u.startsWith("DTSTAMP", ignoreCase = true)) continue
            lines += u
        }
        lines += "END:VTODO"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    // ---- date helpers -----------------------------------------------

    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /** DATE ("20260101") -> midnight UTC. DATETIME, 'Z' or naive (both
     *  treated as UTC — see [IcsParser]'s identical simplification). */
    private fun parseDate(value: String): Long? = runCatching {
        val v = value.trim()
        if (v.length == 8 && !v.contains('T')) {
            LocalDate.parse(v, DATE_FMT).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            val zulu = v.endsWith("Z")
            val core = if (zulu) v.dropLast(1) else v
            LocalDateTime.parse(core, DATETIME_FMT).toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    }.getOrNull()

    private fun formatDate(millis: Long): String =
        DATE_FMT.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())

    private fun formatDateTime(millis: Long): String =
        DATETIME_FMT.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDateTime()) + "Z"

    // ---- text escaping / folding -------------------------------------

    /** RFC 5545 §3.3.11 TEXT escaping: \ ; , and newlines. Mirror image
     *  of [IcsParser]'s unescapeText. */
    private fun escapeText(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                ';' -> sb.append("\\;")
                ',' -> sb.append("\\,")
                '\n' -> sb.append("\\n")
                '\r' -> { /* dropped: \n alone carries the line break */ }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** RFC 5545 §3.1 line folding: no physical line exceeds 75 octets
     *  (UTF-8 bytes), continuations prefixed with a single space that
     *  itself counts toward that budget. Codepoint-aware so a
     *  multi-byte UTF-8 character is never split across the fold. */
    private fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line

        val out = StringBuilder()
        val seg = StringBuilder()
        var segBytes = 0
        var limit = 75
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chStr = String(Character.toChars(cp))
            val chBytes = chStr.toByteArray(Charsets.UTF_8).size
            if (segBytes + chBytes > limit) {
                out.append(seg).append("\r\n ")
                seg.setLength(0)
                segBytes = 0
                limit = 74 // continuation lines lose 1 octet to the leading space
            }
            seg.append(chStr)
            segBytes += chBytes
            i += Character.charCount(cp)
        }
        out.append(seg)
        return out.toString()
    }
}
