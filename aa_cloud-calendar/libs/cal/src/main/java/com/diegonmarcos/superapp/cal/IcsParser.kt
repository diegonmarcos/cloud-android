package com.diegonmarcos.superapp.cal

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Minimal RFC 5545 (iCalendar) reader — just enough to turn a public
 * holiday/astro ICS feed into [CalEvent]s. This is intentionally NOT
 * a general-purpose calendar library: no RRULE expansion, no
 * VTIMEZONE offset resolution beyond "Z or naive", no VALARM.
 *
 * RRULE is explicitly out of scope for this slice. The feeds this
 * engine currently subscribes to (Google holiday calendars,
 * calendarlabs moon phases) all emit one explicit VEVENT per
 * occurrence rather than a single recurring VEVENT + RRULE, so
 * skipping RRULE loses nothing today. Recurrence expansion is a
 * later slice if a feed that actually needs it gets added.
 */
object IcsParser {

    /**
     * Parse a raw ICS document into events tagged with [calendarId].
     * Never throws: a malformed VEVENT is skipped and parsing
     * continues with the rest of the document, because a single bad
     * block in a 400-event feed must not blank the whole calendar.
     */
    fun parse(ics: String, calendarId: String): List<CalEvent> {
        val lines = unfold(ics)
        val events = mutableListOf<CalEvent>()

        var inEvent = false
        // Blocks we must recognise and skip the *contents* of without
        // trying to interpret their properties as VEVENT properties
        // (VALARM nests INSIDE VEVENT; VTIMEZONE is a sibling of VEVENT).
        var skipDepth = 0
        var props: MutableMap<String, Pair<Map<String, String>, String>>? = null

        for (raw in lines) {
            val line = raw.trim('\r')
            if (line.isEmpty()) continue

            if (skipDepth > 0) {
                if (line.startsWith("BEGIN:")) skipDepth++
                else if (line.startsWith("END:")) skipDepth--
                continue
            }

            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true
                    props = mutableMapOf()
                }
                line == "END:VEVENT" -> {
                    inEvent = false
                    val p = props
                    props = null
                    if (p != null) {
                        runCatching { buildEvent(p, calendarId) }
                            .getOrNull()
                            ?.let { events.add(it) }
                        // Malformed VEVENT (bad date, missing DTSTART, etc.)
                        // -> runCatching swallows it, that one block is
                        // dropped, and we keep going.
                    }
                }
                line.startsWith("BEGIN:VALARM") || line.startsWith("BEGIN:VTIMEZONE") -> {
                    skipDepth = 1
                }
                inEvent -> {
                    val (name, params, value) = splitProperty(line) ?: continue
                    // RRULE deliberately ignored — see class kdoc.
                    props?.put(name, params to value)
                }
                else -> {
                    // Outside VEVENT/VALARM/VTIMEZONE (VCALENDAR header,
                    // other component types we don't understand yet) —
                    // ignore silently, per spec we must tolerate unknown
                    // properties/components.
                }
            }
        }

        return events
    }

    /** RFC 5545 §3.1 line unfolding: a line starting with a single
     *  space or horizontal tab is a continuation of the previous
     *  line, with that leading whitespace character removed. Feeds
     *  wrap long SUMMARY/DESCRIPTION text this way, so getting this
     *  wrong corrupts titles rather than merely losing formatting.
     *  `internal` (not `private`) so [TodoIcs] can reuse the exact
     *  same unfolder for VTODO documents instead of writing a second
     *  one — same module, same package, no public API surface added. */
    internal fun unfold(ics: String): List<String> {
        val rawLines = ics.split("\r\n", "\n")
        val out = mutableListOf<String>()
        for (l in rawLines) {
            if ((l.startsWith(" ") || l.startsWith("\t")) && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + l.substring(1)
            } else {
                out.add(l)
            }
        }
        return out
    }

    /** Splits `NAME;PARAM=VAL;PARAM2=VAL2:value` into (name, params, value).
     *  Returns null for lines with no ':' — not valid property syntax.
     *  `internal` so [TodoIcs] can reuse it — see [unfold]. */
    internal fun splitProperty(line: String): Triple<String, Map<String, String>, String>? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = head.split(';')
        val name = parts[0].uppercase()
        val params = mutableMapOf<String, String>()
        for (i in 1 until parts.size) {
            val kv = parts[i].split('=', limit = 2)
            if (kv.size == 2) params[kv[0].uppercase()] = kv[1]
        }
        return Triple(name, params, value)
    }

    /** Reverses RFC 5545 §3.3.11 TEXT escaping: \\ \; \, \N/\n.
     *  `internal` so [TodoIcs] can reuse it — see [unfold]. */
    internal fun unescapeText(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> { sb.append('\n'); i += 2 }
                    ';' -> { sb.append(';'); i += 2 }
                    ',' -> { sb.append(','); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(c); i += 1 }
                }
            } else {
                sb.append(c); i += 1
            }
        }
        return sb.toString()
    }

    /** DATE ("20260101") -> midnight UTC. DATETIME with trailing 'Z'
     *  ("20260315T130000Z") -> that instant. Naive DATETIME (no 'Z',
     *  no VTIMEZONE resolution attempted) is treated as UTC — an
     *  approximation, but VTIMEZONE offset math is out of scope here
     *  and the feeds we consume today emit 'Z' or DATE values only. */
    private fun parseIcsDate(value: String, isDateParam: Boolean): Pair<Long, Boolean> {
        val v = value.trim()
        val allDay = isDateParam || (v.length == 8 && !v.contains('T'))
        if (allDay) {
            val d = LocalDate.parse(v.take(8), DATE_FMT)
            return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        }
        val zulu = v.endsWith("Z")
        val core = if (zulu) v.dropLast(1) else v
        val dt = LocalDateTime.parse(core, DATETIME_FMT)
        return dt.toInstant(ZoneOffset.UTC).toEpochMilli() to false
    }

    private fun buildEvent(
        props: Map<String, Pair<Map<String, String>, String>>,
        calendarId: String,
    ): CalEvent {
        val uid = props["UID"]?.second?.let { unescapeText(it) } ?: UUID.randomUUID().toString()
        val summary = props["SUMMARY"]?.second?.let { unescapeText(it) } ?: ""
        val description = props["DESCRIPTION"]?.second?.let { unescapeText(it) } ?: ""
        val location = props["LOCATION"]?.second?.let { unescapeText(it) } ?: ""

        val dtStartEntry = props["DTSTART"] ?: error("VEVENT missing DTSTART")
        val startIsDate = dtStartEntry.first["VALUE"]?.uppercase() == "DATE"
        val (start, startAllDay) = parseIcsDate(dtStartEntry.second, startIsDate)

        val dtEndEntry = props["DTEND"]
        val end: Long
        val allDay: Boolean
        if (dtEndEntry != null) {
            val endIsDate = dtEndEntry.first["VALUE"]?.uppercase() == "DATE"
            val (e, eAllDay) = parseIcsDate(dtEndEntry.second, endIsDate)
            end = e
            allDay = startAllDay || eAllDay
        } else {
            allDay = startAllDay
            // No DTEND: DATE events default to 1 day, DATETIME events
            // default to a zero-length instant (spec allows DURATION
            // instead of DTEND too, but the seeded feeds don't use it).
            end = if (allDay) start + 24L * 60 * 60 * 1000 else start
        }

        return CalEvent(
            id = "$calendarId:$uid",
            calendarId = calendarId,
            uid = uid,
            title = summary,
            description = description,
            location = location,
            startUtcMillis = start,
            endUtcMillis = end,
            allDay = allDay,
        )
    }

    private val DATE_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
}
