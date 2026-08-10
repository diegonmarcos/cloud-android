package com.diegonmarcos.superapp.news

import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.URI
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal reader for the public feed formats data/sources.json's rss
 * entries actually use: RSS 2.0, RSS 1.0/RDF (dw-en — items are
 * siblings of `<channel>` under `<rdf:RDF>`, not nested inside it), and
 * (defensively, though none of the seeded sources are Atom) Atom.
 * Produces the same [GdeltArticle] shape the GDELT endpoints do, always
 * with `tone = null` — see that field's kdoc in NewsModels.kt for why
 * that's never 0.0.
 *
 * Uses `org.xmlpull.v1.XmlPullParser` (the interface Android's own
 * `android.util.Xml.newPullParser()` implements) rather than any new
 * dependency, same instruction as IcsParser's "no new dependency" for
 * ICS. [parse] is the only entry point that touches `android.util.Xml`
 * — the actual walk lives in [parseInternal], which is written purely
 * against the `XmlPullParser` interface so any implementation of it
 * (Android's on-device parser, or a JVM-side one like kxml2 in a
 * desktop test harness) can drive the exact same code path.
 *
 * Defensive exactly like IcsParser: one malformed `<item>`/`<entry>` —
 * missing link, unparseable date, whatever — is skipped and the rest
 * of the document survives; a totally unparseable document degrades to
 * an empty list, never throws.
 *
 * Field mapping: title -> title (HTML-stripped, entities decoded, see
 * [cleanText]); link -> url; pubDate|dc:date|updated|published ->
 * seendate, reformatted into GDELT's zero-padded
 * `yyyyMMdd'T'HHmmss'Z'` so a plain descending string sort stays
 * chronological across GDELT- and rss-sourced articles alike (see
 * NewsStore's kdoc on why that format matters); the item's
 * `<source>`/domain -> domain, preferring the `<source url="...">`
 * attribute's host (matches GDELT's own hostname-style domain field)
 * over its text content, over the article link's own host when no
 * `<source>` element is present at all. description/content is parsed
 * but never stored — nothing user-facing consumes it yet.
 *
 * Google News' `<title>` arrives as "Headline - Publisher": when no
 * `<source>` element is present (defensive fallback only — every
 * seeded Google News flavor DOES carry `<source>`) and the article's
 * link host is news.google.com, the trailing " - Publisher" is split
 * off into `domain` instead of left glued onto the title, since that
 * is the only publisher signal that particular shape of feed gives.
 *
 * YouTube Atom (`https://www.youtube.com/feeds/videos.xml?channel_id=...`)
 * is handled by this SAME code path, not a second parser — it's just
 * Atom with two extra pieces of information data/media.json's UI wants:
 * `<yt:videoId>` (a DIRECT child of `<entry>`, itemDepth+1 — captured
 * via [GdeltArticle.videoId] the exact same way title/link/pubDate
 * are) and `<media:thumbnail url="...">` (nested INSIDE
 * `<media:group>`, i.e. itemDepth+2, one level deeper than every other
 * field this parser reads — captured via a depth-independent check
 * alongside the normal itemDepth+1 walk, first occurrence wins, into
 * [GdeltArticle.thumbnail]). Both are simply absent (null) on every
 * non-YouTube feed, which every existing source already is.
 */
object RssParser {

    /** Entry point: obtains an Android XmlPullParser and feeds [xml]
     *  through the shared [parseInternal] walk. */
    fun parse(xml: String): List<GdeltArticle> {
        if (xml.isBlank()) return emptyList()
        return runCatching {
            val parser = android.util.Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(xml))
            parseInternal(parser)
        }.getOrDefault(emptyList())
    }

    /**
     * The actual walk, factored out of [parse] so it can be driven by
     * ANY [XmlPullParser] implementation — [parse] always supplies
     * Android's, but nothing here calls `android.util.Xml` directly.
     * `internal` (not `private`) so a JVM-side test harness in the same
     * module can hand this a desktop XmlPullParser implementation
     * (e.g. kxml2) and exercise this exact code path outside the
     * Android runtime, instead of only being able to verify by
     * compiling against android.jar's stub implementations.
     *
     * Tracks element depth relative to each `<item>`/`<entry>` so only
     * that element's DIRECT children are read as fields — a same-named
     * descendant nested deeper (e.g. a hypothetical `<media:title>`)
     * never clobbers the real title. Text is accumulated across nested
     * sub-elements of a field without being cleared (only cleared when
     * a NEW direct-child field starts), so real nested markup inside a
     * field (rare) still concatenates sensibly; HTML embedded as
     * escaped entities or CDATA (common — see hnrss's `<description>`)
     * comes through as plain characters either way and is stripped by
     * [cleanText].
     */
    internal fun parseInternal(parser: XmlPullParser): List<GdeltArticle> {
        val out = mutableListOf<GdeltArticle>()

        var depth = 0
        var itemDepth = -1
        var itemTag = ""
        var inItem = false

        var title = StringBuilder()
        var link = ""
        var pubDate = ""
        var sourceUrlAttr = ""
        var sourceText = ""
        var videoId = ""
        var thumbnail = ""
        var textBuf = StringBuilder()

        fun resetItemState() {
            title = StringBuilder()
            link = ""
            pubDate = ""
            sourceUrlAttr = ""
            sourceText = ""
            videoId = ""
            thumbnail = ""
        }

        var eventType = runCatching { parser.eventType }.getOrDefault(XmlPullParser.END_DOCUMENT)
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    val name = localName(runCatching { parser.name }.getOrDefault(""))
                    if (!inItem && (name == "item" || name == "entry")) {
                        inItem = true
                        itemDepth = depth
                        itemTag = name
                        resetItemState()
                    } else if (inItem && depth == itemDepth + 1) {
                        textBuf = StringBuilder()
                        when (name) {
                            "link" -> {
                                // Atom: <link href="..." rel="alternate"/>, no text child.
                                val href = runCatching { parser.getAttributeValue(null, "href") }.getOrNull()
                                if (!href.isNullOrBlank()) {
                                    val rel = runCatching { parser.getAttributeValue(null, "rel") }.getOrNull()
                                    if (link.isEmpty() || rel == null || rel == "alternate") link = href
                                }
                            }
                            "source" -> {
                                sourceUrlAttr = runCatching { parser.getAttributeValue(null, "url") }.getOrNull().orEmpty()
                            }
                        }
                    }
                    // media:thumbnail lives INSIDE media:group, i.e. one
                    // level deeper than every other captured field (see
                    // class kdoc) — checked independently of the
                    // itemDepth+1 branch above so it's found regardless
                    // of nesting depth within the item. First occurrence
                    // wins (a video normally carries just one).
                    if (inItem && name == "thumbnail" && thumbnail.isEmpty()) {
                        thumbnail = runCatching { parser.getAttributeValue(null, "url") }.getOrNull().orEmpty()
                    }
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                    if (inItem) textBuf.append(runCatching { parser.text }.getOrDefault(""))
                }
                XmlPullParser.END_TAG -> {
                    val name = localName(runCatching { parser.name }.getOrDefault(""))
                    if (inItem && depth == itemDepth + 1) {
                        val text = textBuf.toString()
                        when (name) {
                            "title" -> title.append(text)
                            "link" -> if (link.isEmpty()) link = text.trim()
                            "pubDate", "published", "updated", "date" -> if (pubDate.isEmpty()) pubDate = text.trim()
                            "source" -> if (sourceText.isEmpty()) sourceText = text.trim()
                            "videoId" -> if (videoId.isEmpty()) videoId = text.trim()
                        }
                    }
                    if (inItem && name == itemTag && depth == itemDepth) {
                        runCatching { finishItem(title.toString(), link, pubDate, sourceUrlAttr, sourceText, videoId, thumbnail) }
                            .getOrNull()
                            ?.let { out.add(it) }
                        inItem = false
                        itemDepth = -1
                    }
                    textBuf = StringBuilder()
                    depth--
                }
            }
            eventType = runCatching { parser.next() }.getOrDefault(XmlPullParser.END_DOCUMENT)
        }
        return out
    }

    /** Builds one [GdeltArticle] from one item's raw fields, or returns
     *  null (caller skips it) for a link-less or title-less item —
     *  same "drop the one bad record, keep the rest" contract as
     *  IcsParser's malformed VEVENT handling. */
    private fun finishItem(
        rawTitle: String,
        rawLink: String,
        rawPubDate: String,
        sourceUrlAttr: String,
        sourceText: String,
        rawVideoId: String = "",
        rawThumbnail: String = "",
    ): GdeltArticle? {
        val link = rawLink.trim()
        if (link.isEmpty()) return null
        var title = cleanText(rawTitle)
        if (title.isEmpty()) return null

        val linkHost = hostOf(link)
        var domain = hostOf(sourceUrlAttr).orEmpty().ifEmpty { cleanText(sourceText) }

        // Google News-only fallback: only when no <source> element gave
        // us a domain AND the link is actually a news.google.com
        // redirect — see class kdoc.
        if (domain.isEmpty() && linkHost == "news.google.com") {
            val idx = title.lastIndexOf(" - ")
            if (idx > 0 && idx < title.length - 3) {
                val publisher = title.substring(idx + 3).trim()
                if (publisher.isNotEmpty()) {
                    title = title.substring(0, idx).trim()
                    domain = publisher
                }
            }
        }
        if (domain.isEmpty()) domain = linkHost.orEmpty()
        if (title.isEmpty()) return null

        return GdeltArticle(
            url = link,
            title = title,
            seendate = parseDate(rawPubDate),
            socialimage = "",
            domain = domain,
            language = "",
            sourcecountry = "",
            tone = null,
            videoId = rawVideoId.trim().takeIf { it.isNotEmpty() },
            thumbnail = rawThumbnail.trim().takeIf { it.isNotEmpty() },
        )
    }

    // ---- small helpers ----------------------------------------------------

    private fun localName(name: String): String {
        val idx = name.indexOf(':')
        return if (idx >= 0) name.substring(idx + 1) else name
    }

    private fun hostOf(url: String): String? {
        if (url.isBlank()) return null
        return runCatching { URI(url.trim()).host }.getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }

    private val TAG_RE = Regex("<[^>]*>")
    private val WS_RE = Regex("\\s+")

    /** Strips HTML tags and decodes HTML entities — both routinely
     *  embedded in these feeds' titles (Google News wraps its
     *  `<description>` in escaped HTML; hnrss wraps its `<description>`
     *  in raw HTML inside CDATA; titles are usually clean but this is
     *  applied defensively to every title regardless). Collapses
     *  whitespace and trims. */
    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        val noTags = TAG_RE.replace(raw, " ")
        val decoded = decodeEntities(noTags)
        return WS_RE.replace(decoded, " ").trim()
    }

    private fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i + 1)
                if (semi in (i + 1)..(i + 10)) {
                    val replacement = namedOrNumericEntity(s.substring(i + 1, semi))
                    if (replacement != null) {
                        sb.append(replacement)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun namedOrNumericEntity(entity: String): String? = when {
        entity == "amp" -> "&"
        entity == "lt" -> "<"
        entity == "gt" -> ">"
        entity == "quot" -> "\""
        entity == "apos" -> "'"
        entity == "nbsp" -> " "
        entity.startsWith("#x", ignoreCase = true) ->
            entity.substring(2).toIntOrNull(16)?.let { runCatching { it.toChar().toString() }.getOrNull() }
        entity.startsWith("#") ->
            entity.substring(1).toIntOrNull()?.let { runCatching { it.toChar().toString() }.getOrNull() }
        else -> null
    }

    private val GDELT_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

    /** Reformats an RFC-822 (`Mon, 10 Aug 2026 13:19:39 GMT`, used by
     *  RSS 2.0's pubDate) or ISO-8601 (`2026-08-10T13:07:00Z`, used by
     *  RDF's dc:date and Atom's updated/published) date into GDELT's
     *  own `yyyyMMdd'T'HHmmss'Z'`, always normalized to UTC, so
     *  [NewsStore]'s "sort by seendate as a plain string" trick stays
     *  correct across sources. Returns "" (never throws) for anything
     *  that doesn't parse as one of the above — callers still keep the
     *  article, just without a sortable date; NewsBridge's descending
     *  string sort puts empty-seendate articles last. */
    private fun parseDate(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return ""

        runCatching {
            java.time.ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME)
        }.getOrNull()?.let { return it.withZoneSameInstant(ZoneOffset.UTC).format(GDELT_FMT) }

        runCatching { OffsetDateTime.parse(t) }.getOrNull()
            ?.let { return it.withOffsetSameInstant(ZoneOffset.UTC).format(GDELT_FMT) }

        runCatching { LocalDateTime.parse(t) }.getOrNull()
            ?.let { return it.atZone(ZoneOffset.UTC).format(GDELT_FMT) }

        runCatching { LocalDate.parse(t) }.getOrNull()
            ?.let { return it.atStartOfDay(ZoneOffset.UTC).format(GDELT_FMT) }

        return ""
    }
}
