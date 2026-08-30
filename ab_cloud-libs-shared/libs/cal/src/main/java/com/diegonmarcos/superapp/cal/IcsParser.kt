package com.diegonmarcos.superapp.cal

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID

/**
 * Minimal RFC 5545 (iCalendar) reader — turns a public ICS feed into
 * [CalEvent]s. Still intentionally not a general-purpose calendar
 * library, but wide enough to cover the feeds currently subscribed:
 *
 * TZID resolution: DATETIME values are resolved through
 * [java.time.ZoneId] using the tzdb name in the TZID parameter (e.g.
 * `Europe/Berlin`), so wall-clock times convert to the correct UTC
 * instant across DST. A trailing 'Z' always wins over any TZID
 * (that's already an absolute instant). A naive DATETIME with no
 * TZID and no 'Z' is still treated as UTC — unchanged legacy
 * behaviour, deliberately NOT switched to device-local, since that
 * would silently move every event already synced this way.
 * VALUE=DATE stays midnight UTC even when a TZID parameter is
 * present, since all-day events are date-only. An unrecognised or
 * invalid TZID (not in the platform tzdb) falls back to UTC instead
 * of dropping the event — a slightly-wrong time beats a missing one.
 *
 * RRULE expansion: supported for FREQ=DAILY|WEEKLY|MONTHLY|YEARLY
 * with INTERVAL, COUNT, UNTIL, and BYDAY as a plain weekday list
 * (e.g. `BYDAY=MO,WE,FR`), for WEEKLY only. EXDATE is honoured —
 * excluded occurrences are dropped from the output rather than shown
 * as cancelled-but-present. A VEVENT carrying RECURRENCE-ID is
 * skipped outright: that is a modified single instance of another
 * series, and expanding it as a series of its own would duplicate
 * the occurrence it overrides. Merging RECURRENCE-ID overrides into
 * their base series is NOT implemented.
 *
 * NOT supported, left as a later slice if a feed needs it: RRULE
 * using BYMONTHDAY, BYSETPOS, BYMONTH, BYYEARDAY, BYWEEKNO,
 * BYHOUR/BYMINUTE/BYSECOND, ordinal BYDAY (e.g. `BYDAY=2WE`,
 * `BYDAY=-1MO`), or BYDAY combined with MONTHLY/YEARLY; a custom
 * WKST (week start is always assumed Monday); VTIMEZONE blocks with
 * custom (non-tzdb) zone definitions — VTIMEZONE is still skipped
 * rather than parsed, TZID resolution relies entirely on the
 * platform tzdb; and no VALARM. Any RRULE that needs one of the
 * unsupported pieces degrades to emitting just the VEVENT's base
 * occurrence — never thrown away, never expanded incorrectly.
 *
 * Expansion is bounded on purpose: only occurrences between 1 year
 * before now and 2 years after now are generated, and each VEVENT is
 * capped at [MAX_OCCURRENCES_PER_EVENT] occurrences — a malformed
 * RRULE (or one with neither COUNT nor UNTIL) must never expand
 * unbounded. Generated occurrence ids are suffixed with the
 * occurrence's start instant (`"$calendarId:$uid:$startMillis"`) to
 * stay distinct across a series; `uid` itself is left unchanged since
 * it is the iCalendar identity of the whole series, not of one
 * occurrence. Non-recurring VEVENTs keep the original
 * `"$calendarId:$uid"` id, unchanged from before RRULE support.
 */
object IcsParser {

    /** Per-VEVENT ceiling on generated occurrences — a malformed RRULE
     *  (or an open-ended one with no COUNT/UNTIL) must still terminate. */
    private const val MAX_OCCURRENCES_PER_EVENT = 750

    /** Absolute ceiling on stepping iterations while expanding a single
     *  RRULE, independent of the occurrence cap above — belt-and-braces
     *  against any FREQ/INTERVAL combination that would otherwise creep
     *  forward in time without ever tripping the window/cap checks. */
    private const val HARD_ITERATION_CAP = 50_000

    /**
     * Parse a raw ICS document into events tagged with [calendarId].
     * Never throws: a malformed VEVENT is skipped and parsing
     * continues with the rest of the document, because a single bad
     * block in a 400-event feed must not blank the whole calendar.
     */
    fun parse(ics: String, calendarId: String): List<CalEvent> {
        val lines = unfold(ics)
        val events = mutableListOf<CalEvent>()

        val now = Instant.now()
        val windowStart = now.atZone(ZoneOffset.UTC).minusYears(1).toInstant()
        val windowEnd = now.atZone(ZoneOffset.UTC).plusYears(2).toInstant()

        var inEvent = false
        // Blocks we must recognise and skip the *contents* of without
        // trying to interpret their properties as VEVENT properties
        // (VALARM nests INSIDE VEVENT; VTIMEZONE is a sibling of VEVENT).
        var skipDepth = 0
        var props: MutableMap<String, Pair<Map<String, String>, String>>? = null
        // EXDATE can repeat (one line per excluded date, or comma-
        // separated dates on one line) — a plain Map would only keep
        // the last one, silently un-excluding earlier cancellations.
        var exdates: MutableList<Pair<Map<String, String>, String>>? = null

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
                    exdates = mutableListOf()
                }
                line == "END:VEVENT" -> {
                    inEvent = false
                    val p = props
                    val ex = exdates
                    props = null
                    exdates = null
                    if (p != null) {
                        runCatching {
                            buildOccurrences(p, ex ?: emptyList(), calendarId, windowStart, windowEnd)
                        }
                            .getOrNull()
                            ?.let { events.addAll(it) }
                        // Malformed VEVENT (bad date, missing DTSTART, etc.)
                        // -> runCatching swallows it, that one block is
                        // dropped, and we keep going. RRULE-specific
                        // failures are already handled inside
                        // buildOccurrences and never reach this catch.
                    }
                }
                line.startsWith("BEGIN:VALARM") || line.startsWith("BEGIN:VTIMEZONE") -> {
                    skipDepth = 1
                }
                inEvent -> {
                    val (name, params, value) = splitProperty(line) ?: continue
                    if (name == "EXDATE") {
                        exdates?.add(params to value)
                    } else {
                        props?.put(name, params to value)
                    }
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

    /** True for a DATE-only value: an explicit VALUE=DATE param, or a
     *  bare 8-digit value with no 'T' (belt-and-braces for feeds that
     *  omit the param but still emit a date-only value). */
    private fun isAllDayValue(value: String, params: Map<String, String>): Boolean {
        val v = value.trim()
        return params["VALUE"]?.uppercase() == "DATE" || (v.length == 8 && !v.contains('T'))
    }

    /** Resolves a TZID parameter to a [ZoneId] via the platform tzdb.
     *  Returns null (never throws) for a missing/blank/unrecognised
     *  id, so callers can fall back to UTC and keep the event instead
     *  of dropping it. */
    private fun resolveZone(tzid: String?): ZoneId? {
        if (tzid.isNullOrBlank()) return null
        return runCatching { ZoneId.of(tzid) }.getOrNull()
    }

    /** DATE ("20260101") -> midnight UTC, regardless of any TZID
     *  present (all-day events are date-only). DATETIME with a
     *  trailing 'Z' ("20260315T130000Z") -> that instant, TZID
     *  ignored — 'Z' already names an absolute instant. DATETIME with
     *  a TZID param ("...;TZID=Europe/Berlin:20260810T190000") -> that
     *  wall-clock time resolved in the named zone (DST-aware) via
     *  [ZoneId]/[java.time.ZonedDateTime]; an unresolvable TZID falls
     *  back to UTC. Naive DATETIME with neither -> UTC, unchanged
     *  legacy approximation. */
    private fun parseIcsDate(value: String, params: Map<String, String>): Pair<Long, Boolean> {
        val v = value.trim()
        if (isAllDayValue(v, params)) {
            val d = LocalDate.parse(v.take(8), DATE_FMT)
            return d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() to true
        }
        val zulu = v.endsWith("Z")
        val core = if (zulu) v.dropLast(1) else v
        val dt = LocalDateTime.parse(core, DATETIME_FMT)
        val zone: ZoneId = if (zulu) ZoneOffset.UTC else (resolveZone(params["TZID"]) ?: ZoneOffset.UTC)
        return dt.atZone(zone).toInstant().toEpochMilli() to false
    }

    /** The zone RRULE expansion should step in, so a recurring wall
     *  clock time (e.g. Berlin 19:00 weekly) stays at that wall-clock
     *  time across DST transitions instead of drifting by an hour.
     *  Mirrors the zone [parseIcsDate] resolves DTSTART's own instant
     *  in. */
    private fun zoneForStepping(value: String, params: Map<String, String>): ZoneId {
        val v = value.trim()
        if (isAllDayValue(v, params)) return ZoneOffset.UTC
        if (v.endsWith("Z")) return ZoneOffset.UTC
        return resolveZone(params["TZID"]) ?: ZoneOffset.UTC
    }

    private val WEEKDAY_CODES: Map<String, DayOfWeek> = mapOf(
        "MO" to DayOfWeek.MONDAY,
        "TU" to DayOfWeek.TUESDAY,
        "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY,
        "FR" to DayOfWeek.FRIDAY,
        "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )

    /** Parses a plain BYDAY weekday list ("MO,WE,FR"). Returns null
     *  (unsupported) for anything with an ordinal prefix ("2WE",
     *  "-1MO") or an unrecognised code — those need "Nth weekday of
     *  the period" semantics this expander doesn't implement. */
    private fun parseByDay(raw: String): List<DayOfWeek>? {
        val result = mutableListOf<DayOfWeek>()
        for (code in raw.split(',')) {
            val day = WEEKDAY_CODES[code.trim().uppercase()] ?: return null
            result.add(day)
        }
        return result.ifEmpty { null }
    }

    /** UNTIL per RFC 5545 must share DTSTART's value type, but real
     *  feeds are inconsistent (bare DATE, naive DATE-TIME, or a 'Z'
     *  instant all show up in the c-base feed) — accept all three.
     *  A bare DATE is treated as inclusive through the end of that UTC
     *  day. Returns null (no bound from UNTIL) rather than throwing if
     *  the value doesn't parse — the window/cap still bound expansion. */
    private fun parseUntilMillis(value: String): Long? = runCatching {
        val v = value.trim()
        if (v.length == 8 && !v.contains('T')) {
            LocalDate.parse(v, DATE_FMT).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() - 1
        } else {
            val zulu = v.endsWith("Z")
            val core = if (zulu) v.dropLast(1) else v
            LocalDateTime.parse(core, DATETIME_FMT).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
    }.getOrNull()

    private fun parseRruleParts(rrule: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        for (chunk in rrule.split(';')) {
            val kv = chunk.split('=', limit = 2)
            if (kv.size == 2) out[kv[0].trim().uppercase()] = kv[1].trim()
        }
        return out
    }

    /**
     * Expands an RRULE into a bounded list of occurrence start instants
     * (UTC epoch millis), including the DTSTART occurrence itself.
     * Returns null when the rule is unparseable or uses a modifier this
     * minimal expander doesn't implement (see class kdoc) — callers
     * treat null as "fall back to the single base occurrence". Returns
     * a (possibly empty) list when the rule IS understood: empty means
     * the series legitimately has no occurrences inside the expansion
     * window, which is different from "couldn't expand" and must NOT
     * fall back to re-adding the base occurrence.
     */
    private fun expandRrule(
        rrule: String,
        dtStartMillis: Long,
        zone: ZoneId,
        windowStart: Instant,
        windowEnd: Instant,
    ): List<Long>? {
        val parts = parseRruleParts(rrule)
        val freq = parts["FREQ"]?.uppercase() ?: return null
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return null

        // Modifiers this expander doesn't implement — bail out to "just
        // the base occurrence" instead of silently computing wrong dates.
        val unsupportedKeys = listOf(
            "BYMONTHDAY", "BYSETPOS", "BYMONTH", "BYYEARDAY", "BYWEEKNO",
            "BYHOUR", "BYMINUTE", "BYSECOND",
        )
        if (unsupportedKeys.any { parts.containsKey(it) }) return null

        val byDay: List<DayOfWeek>? = parts["BYDAY"]?.let { parseByDay(it) ?: return null }
        if (byDay != null && freq != "WEEKLY") return null // ordinal-free BYDAY only handled for WEEKLY

        val interval = parts["INTERVAL"]?.toIntOrNull()?.takeIf { it > 0 } ?: 1
        val count = parts["COUNT"]?.toIntOrNull()?.takeIf { it > 0 }
        val until = parts["UNTIL"]?.let { parseUntilMillis(it) }

        val anchor: ZonedDateTime = Instant.ofEpochMilli(dtStartMillis).atZone(zone)
        val windowStartMillis = windowStart.toEpochMilli()
        val windowEndMillis = windowEnd.toEpochMilli()

        val results = LinkedHashSet<Long>()
        var seriesIndex = 0
        var iterations = 0

        // Returns false once the whole expansion should stop: past
        // COUNT, past UNTIL, or past the window end — occurrences are
        // generated in non-decreasing time order in every branch below,
        // so once one is past the window end all later ones are too.
        fun consider(zdt: ZonedDateTime): Boolean {
            seriesIndex++
            if (count != null && seriesIndex > count) return false
            val millis = zdt.toInstant().toEpochMilli()
            if (until != null && millis > until) return false
            if (millis > windowEndMillis) return false
            if (millis >= windowStartMillis) {
                results.add(millis)
                if (results.size >= MAX_OCCURRENCES_PER_EVENT) return false
            }
            return true
        }

        when (freq) {
            "DAILY" -> {
                var current = anchor
                while (true) {
                    if (!consider(current)) break
                    if (++iterations > HARD_ITERATION_CAP) break
                    current = current.plusDays(interval.toLong())
                }
            }
            "WEEKLY" -> {
                if (byDay == null) {
                    var current = anchor
                    while (true) {
                        if (!consider(current)) break
                        if (++iterations > HARD_ITERATION_CAP) break
                        current = current.plusWeeks(interval.toLong())
                    }
                } else {
                    val sortedDays = byDay.distinct().sortedBy { it.value }
                    var weekMonday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
                    outer@ while (true) {
                        for (dow in sortedDays) {
                            val candidate = weekMonday.plusDays((dow.value - 1).toLong())
                            if (candidate.toInstant().toEpochMilli() < dtStartMillis) continue
                            if (!consider(candidate)) break@outer
                            if (++iterations > HARD_ITERATION_CAP) break@outer
                        }
                        weekMonday = weekMonday.plusWeeks(interval.toLong())
                        if (++iterations > HARD_ITERATION_CAP) break
                    }
                }
            }
            "MONTHLY" -> {
                var current = anchor
                while (true) {
                    if (!consider(current)) break
                    if (++iterations > HARD_ITERATION_CAP) break
                    current = current.plusMonths(interval.toLong())
                }
            }
            "YEARLY" -> {
                var current = anchor
                while (true) {
                    if (!consider(current)) break
                    if (++iterations > HARD_ITERATION_CAP) break
                    current = current.plusYears(interval.toLong())
                }
            }
        }

        return results.toList()
    }

    private fun buildOccurrences(
        props: Map<String, Pair<Map<String, String>, String>>,
        exdateEntries: List<Pair<Map<String, String>, String>>,
        calendarId: String,
        windowStart: Instant,
        windowEnd: Instant,
    ): List<CalEvent> {
        // A RECURRENCE-ID VEVENT is a modified single instance of
        // another series (identified by the same UID) — expanding it
        // as a series of its own would duplicate the occurrence it
        // overrides. Merging it into the base series isn't implemented
        // (see class kdoc), so it's dropped rather than emitted wrong.
        if (props.containsKey("RECURRENCE-ID")) return emptyList()

        val uid = props["UID"]?.second?.let { unescapeText(it) } ?: UUID.randomUUID().toString()
        val summary = props["SUMMARY"]?.second?.let { unescapeText(it) } ?: ""
        val description = props["DESCRIPTION"]?.second?.let { unescapeText(it) } ?: ""
        val location = props["LOCATION"]?.second?.let { unescapeText(it) } ?: ""

        val dtStartEntry = props["DTSTART"] ?: error("VEVENT missing DTSTART")
        val (start, startAllDay) = parseIcsDate(dtStartEntry.second, dtStartEntry.first)

        val dtEndEntry = props["DTEND"]
        val end: Long
        val allDay: Boolean
        if (dtEndEntry != null) {
            val (e, eAllDay) = parseIcsDate(dtEndEntry.second, dtEndEntry.first)
            end = e
            allDay = startAllDay || eAllDay
        } else {
            allDay = startAllDay
            // No DTEND: DATE events default to 1 day, DATETIME events
            // default to a zero-length instant (spec allows DURATION
            // instead of DTEND too, but the seeded feeds don't use it).
            end = if (allDay) start + 24L * 60 * 60 * 1000 else start
        }
        val durationMillis = end - start

        fun mkEvent(id: String, startMillis: Long) = CalEvent(
            id = id,
            calendarId = calendarId,
            uid = uid,
            title = summary,
            description = description,
            location = location,
            startUtcMillis = startMillis,
            endUtcMillis = startMillis + durationMillis,
            allDay = allDay,
        )

        val exdateMillis: Set<Long> = exdateEntries.mapNotNull { (p, v) ->
            runCatching { parseIcsDate(v, p).first }.getOrNull()
        }.toSet()

        val rrule = props["RRULE"]?.second
        if (rrule == null) {
            if (start in exdateMillis) return emptyList()
            return listOf(mkEvent("$calendarId:$uid", start))
        }

        val zone = zoneForStepping(dtStartEntry.second, dtStartEntry.first)
        // getOrNull() also catches any exception during expansion and
        // folds it into "unsupported" (null), same as an unparseable rule.
        val occurrenceStarts = runCatching {
            expandRrule(rrule, start, zone, windowStart, windowEnd)
        }.getOrNull()

        if (occurrenceStarts == null) {
            // Unparseable/unsupported RRULE (or it threw) -> degrade to
            // the single base occurrence rather than lose the event.
            if (start in exdateMillis) return emptyList()
            return listOf(mkEvent("$calendarId:$uid", start))
        }

        return occurrenceStarts
            .filterNot { it in exdateMillis }
            .map { s -> mkEvent("$calendarId:$uid:$s", s) }
    }

    private val DATE_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
    private val DATETIME_FMT = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
}
